#!/usr/bin/env python3
"""Dry-run-first Firebase Auth -> Supabase profile identity backfill.

Required environment variables for --apply:
  SUPABASE_URL
  SUPABASE_SERVICE_ROLE_KEY

The service-role key is read only from the environment and is never printed.
The Firebase export is an authorized Admin SDK export and is never modified.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

KNOWN_ROLES = {"OWNER", "MANAGER", "STAFF", "RIDER", "CUSTOMER"}
PHONE_ALIAS_DOMAIN = "auth.aquahub.app"


def normalize_phone(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    digits = re.sub(r"\D", "", value)
    if len(digits) < 10:
        return None
    if digits.startswith("0"):
        return "63" + digits[1:]
    if digits.startswith("63"):
        return digits
    return digits


def phone_alias(value: object) -> str | None:
    normalized = normalize_phone(value)
    return f"phone-{normalized}@{PHONE_ALIAS_DOMAIN}" if normalized else None


def normalized_email(value: object) -> str | None:
    return value.strip().lower() if isinstance(value, str) and value.strip() else None


def firebase_role(record: dict) -> tuple[str | None, str]:
    raw = record.get("customAttributes") or record.get("custom_claims") or "{}"
    try:
        claims = json.loads(raw) if isinstance(raw, str) else raw
    except json.JSONDecodeError:
        return None, "INVALID_CLAIMS_JSON"
    if not isinstance(claims, dict):
        return None, "INVALID_CLAIMS"
    role = claims.get("role")
    if role is None and isinstance(claims.get("claims"), dict):
        role = claims["claims"].get("role")
    if role is None:
        return None, "NOT_PROVIDED"
    role = str(role).upper()
    return (role, "KNOWN") if role in KNOWN_ROLES else (None, "UNKNOWN_ROLE")


def local_property(name: str) -> str | None:
    path = Path(__file__).resolve().parents[2] / "AquaHub" / "local.properties"
    if not path.exists():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    return None


class SupabaseAdmin:
    def __init__(self, url: str, key: str):
        self.base = url.rstrip("/")
        self.key = key

    def request(self, method: str, path: str, body: object | None = None) -> object:
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("apikey", self.key)
        request.add_header("Authorization", f"Bearer {self.key}")
        request.add_header("Content-Type", "application/json")
        request.add_header("Prefer", "return=representation")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = response.read()
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Supabase API {method} {path} failed: HTTP {error.code}: {detail}") from error

    def list_users(self) -> list[dict]:
        users: list[dict] = []
        page = 1
        while True:
            result = self.request("GET", f"/auth/v1/admin/users?page={page}&per_page=1000")
            batch = result.get("users", []) if isinstance(result, dict) else []
            users.extend(batch)
            if len(batch) < 1000:
                return users
            page += 1

    def profiles(self) -> list[dict]:
        result = self.request("GET", "/rest/v1/profiles?select=id,firebase_uid,role")
        return result if isinstance(result, list) else []

    def update_profile(self, profile_id: str, firebase_uid: str, role: str | None) -> None:
        body: dict[str, str] = {"firebase_uid": firebase_uid}
        if role is not None:
            body["role"] = role
        self.request("PATCH", f"/rest/v1/profiles?id=eq.{urllib.parse.quote(profile_id)}", body)


@dataclass
class Result:
    firebase_uid: str
    supabase_uid: str | None
    status: str
    role_status: str
    detail: str


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--firebase-export", required=True, type=Path)
    parser.add_argument("--apply", action="store_true", help="Write only unambiguous, safe mappings")
    parser.add_argument("--confirm-production-backfill", action="store_true", help="Required with --apply")
    parser.add_argument("--report", type=Path, help="Also write the JSON report to this mode-600 file")
    args = parser.parse_args()

    url = os.environ.get("SUPABASE_URL") or local_property("SUPABASE_URL")
    key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not url:
        raise SystemExit("SUPABASE_URL is required via environment or AquaHub/local.properties")
    if args.apply and not key:
        raise SystemExit("SUPABASE_SERVICE_ROLE_KEY is required for --apply and must not be placed in Android config")
    if args.apply and not args.confirm_production_backfill:
        raise SystemExit("--confirm-production-backfill is required with --apply; no write was performed")
    if not key:
        print("DRY RUN: no service-role key supplied; no writes are possible")
        return inspect_export(args.firebase_export)

    export = json.loads(args.firebase_export.read_text(encoding="utf-8"))
    firebase_users = export.get("users", export) if isinstance(export, dict) else export
    if not isinstance(firebase_users, list):
        raise SystemExit("Firebase export must be a list or an object containing users")

    admin = SupabaseAdmin(url, key)
    supabase_users = admin.list_users()
    profiles = admin.profiles()
    profile_by_id = {row.get("id"): row for row in profiles}
    mapped_uids: dict[str, set[str]] = {}
    for row in profiles:
        firebase_uid = row.get("firebase_uid")
        if firebase_uid:
            mapped_uids.setdefault(firebase_uid, set()).add(row.get("id", ""))

    candidates: dict[str, set[str]] = {}
    for user in supabase_users:
        keys = {normalized_email(user.get("email")), phone_alias(user.get("email"))}
        keys.discard(None)
        for key_value in keys:
            candidates.setdefault(key_value, set()).add(user.get("id", ""))

    export_uid_counts: dict[str, int] = {}
    export_key_counts: dict[str, int] = {}
    for firebase in firebase_users:
        firebase_uid = firebase.get("localId") or firebase.get("uid")
        if isinstance(firebase_uid, str) and firebase_uid:
            export_uid_counts[firebase_uid] = export_uid_counts.get(firebase_uid, 0) + 1
        for key_value in (normalized_email(firebase.get("email")), phone_alias(firebase.get("phoneNumber"))):
            if key_value:
                export_key_counts[key_value] = export_key_counts.get(key_value, 0) + 1

    results: list[Result] = []
    claimed_supabase_ids: set[str] = set()
    for firebase in firebase_users:
        firebase_uid = firebase.get("localId") or firebase.get("uid")
        if not isinstance(firebase_uid, str) or not firebase_uid:
            results.append(Result("<missing>", None, "FAILED", "INVALID", "missing Firebase UID"))
            continue
        email = normalized_email(firebase.get("email"))
        alias = phone_alias(firebase.get("phoneNumber"))
        if export_uid_counts.get(firebase_uid, 0) > 1:
            results.append(Result(firebase_uid, None, "UID_CONFLICT", "INVALID", "duplicate Firebase UID in export"))
            continue
        if any(export_key_counts.get(key_value, 0) > 1 for key_value in (email, alias) if key_value):
            results.append(Result(firebase_uid, None, "ALIAS_CONFLICT", "INVALID", "duplicate email or phone alias in export"))
            continue
        candidate_ids = set()
        for key_value in (email, alias):
            if key_value:
                candidate_ids.update(candidates.get(key_value, set()))
        role, role_status = firebase_role(firebase)
        if firebase_uid in mapped_uids:
            existing_profiles = mapped_uids[firebase_uid]
            if len(existing_profiles) > 1:
                results.append(Result(firebase_uid, None, "UID_CONFLICT", role_status, "duplicate Firebase UID already present in Supabase profiles"))
            else:
                existing_profile = next(iter(existing_profiles))
                claimed_supabase_ids.add(existing_profile)
                results.append(Result(firebase_uid, existing_profile, "ALREADY_MAPPED", role_status, "mapping already exists"))
            continue
        if len(candidate_ids) == 0:
            results.append(Result(firebase_uid, None, "MISSING_SUPABASE_USER", role_status, "no unique Supabase Auth match"))
            continue
        if len(candidate_ids) > 1:
            claimed_supabase_ids.update(candidate_ids)
            results.append(Result(firebase_uid, None, "AMBIGUOUS", role_status, "multiple Supabase Auth candidates"))
            continue
        profile_id = next(iter(candidate_ids))
        claimed_supabase_ids.add(profile_id)
        profile = profile_by_id.get(profile_id)
        if profile is None:
            results.append(Result(firebase_uid, profile_id, "MISSING_PROFILE", role_status, "Supabase profile row missing"))
            continue
        existing_uid = profile.get("firebase_uid")
        if existing_uid and existing_uid != firebase_uid:
            results.append(Result(firebase_uid, profile_id, "UID_CONFLICT", role_status, "profile has a different Firebase UID"))
            continue
        if role_status == "UNKNOWN_ROLE":
            results.append(Result(firebase_uid, profile_id, "UNKNOWN_ROLE", role_status, "unknown legacy role; mapping withheld"))
            continue
        if args.apply:
            admin.update_profile(profile_id, firebase_uid, role)
        results.append(Result(firebase_uid, profile_id, "READY", role_status, "applied" if args.apply else "would apply"))

    for user in supabase_users:
        user_id = user.get("id")
        if user_id and user_id not in claimed_supabase_ids:
            results.append(Result("<missing-firebase>", user_id, "MISSING_FIREBASE_USER", "NOT_APPLICABLE", "no Firebase export match"))

    report = json.dumps([result.__dict__ for result in results], indent=2, sort_keys=True)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report + "\n", encoding="utf-8")
        os.chmod(args.report, 0o600)
    print(report)
    return 0 if all(result.status in {"READY", "ALREADY_MAPPED", "MISSING_SUPABASE_USER", "MISSING_FIREBASE_USER"} for result in results) else 2


def inspect_export(path: Path) -> int:
    export = json.loads(path.read_text(encoding="utf-8"))
    users = export.get("users", export) if isinstance(export, dict) else export
    if not isinstance(users, list):
        raise SystemExit("Firebase export must be a list or an object containing users")
    seen: set[str] = set()
    duplicates = 0
    for user in users:
        uid = user.get("localId") or user.get("uid")
        if uid in seen:
            duplicates += 1
        if isinstance(uid, str):
            seen.add(uid)
    print(json.dumps({"firebase_users": len(users), "duplicate_firebase_uids": duplicates, "status": "INPUT_VALIDATED_ONLY"}, indent=2))
    return 0 if duplicates == 0 else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(f"FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
