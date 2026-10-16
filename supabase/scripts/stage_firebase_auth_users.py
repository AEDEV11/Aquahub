#!/usr/bin/env python3
"""Dry-run-first staging plan for Firebase Auth users in Supabase Auth.

This tool never imports password hashes, assigns roles, sets firebase_uid, or
deletes Firebase users. Apply mode only creates explicitly READY_TO_CREATE
email users through the Supabase Auth admin API, without a password or custom
metadata; those accounts require a separately approved activation flow.

Required for --apply:
  SUPABASE_URL
  SUPABASE_SERVICE_ROLE_KEY
  --confirm-production-user-staging
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

KNOWN_ROLES = {"OWNER", "MANAGER", "STAFF", "RIDER", "CUSTOMER"}
PHONE_ALIAS_DOMAIN = "auth.aquahub.app"


def normalize_phone(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    digits = "".join(character for character in value if character.isdigit())
    if len(digits) < 10:
        return None
    if digits.startswith("0"):
        return "63" + digits[1:]
    return digits if digits.startswith("63") else digits


def phone_alias(value: object) -> str | None:
    normalized = normalize_phone(value)
    return f"phone-{normalized}@{PHONE_ALIAS_DOMAIN}" if normalized else None


def normalized_email(value: object) -> str | None:
    return value.strip().lower() if isinstance(value, str) and value.strip() else None


def provider_summary(firebase: dict) -> tuple[str, list[str]]:
    providers = sorted({item.get("providerId") for item in firebase.get("providerUserInfo", []) if item.get("providerId")})
    has_password_hash = bool(firebase.get("passwordHash"))
    has_phone = bool(firebase.get("phoneNumber")) or "phone" in providers
    if "google.com" in providers and (has_password_hash or len(providers) > 1):
        return "MULTI_PROVIDER", providers
    if "google.com" in providers:
        return "GOOGLE", providers
    if has_password_hash and normalized_email(firebase.get("email")) and normalized_email(firebase.get("email")).startswith("phone-"):
        return "PHONE_ALIAS_PASSWORD", providers or ["password"]
    if has_phone:
        return "PHONE", providers or ["phone"]
    if has_password_hash:
        return "EMAIL_PASSWORD", providers or ["password"]
    return "OTHER", providers


def role_resolution(firebase_uid: str, role_export: dict[str, object] | None) -> tuple[str | None, str, str]:
    if role_export is None:
        return None, "MANUAL_REVIEW", f"Firestore /users/{firebase_uid}.role requires an authorized read-only role export"
    raw = role_export.get(firebase_uid)
    if raw is None:
        return None, "MANUAL_REVIEW", "legacy role not present in supplied role export"
    role = str(raw).upper()
    if role not in KNOWN_ROLES:
        return None, "MANUAL_REVIEW", f"legacy role {role!r} is not in the approved role allowlist"
    return role, "PROPOSED", "role proposed from supplied authoritative legacy export; not applied by this tool"


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
            raise RuntimeError(f"Supabase API {method} {path} failed: HTTP {error.code}: {detail[:300]}") from error

    def list_users(self) -> list[dict]:
        result = self.request("GET", "/auth/v1/admin/users?page=1&per_page=1000")
        return result.get("users", []) if isinstance(result, dict) else []

    def create_email_shell(self, email: str, email_confirmed: bool) -> dict:
        # No password, metadata, role, or Firebase UID is supplied. The auth
        # trigger creates one default-CUSTOMER profile for this new UUID.
        result = self.request(
            "POST",
            "/auth/v1/admin/users",
            {"email": email, "email_confirm": email_confirmed},
        )
        return result if isinstance(result, dict) else {}


def load_role_export(path: Path | None) -> dict[str, object] | None:
    if path is None:
        return None
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, dict) and isinstance(payload.get("users"), list):
        payload = payload["users"]
    if isinstance(payload, list):
        return {
            str(row.get("localId") or row.get("uid")): row.get("role")
            for row in payload
            if isinstance(row, dict) and (row.get("localId") or row.get("uid"))
        }
    if isinstance(payload, dict):
        return payload
    raise ValueError("role export must be an object, list, or object containing users")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--firebase-export", required=True, type=Path)
    parser.add_argument("--legacy-role-export", type=Path)
    parser.add_argument("--dry-run", action="store_true", help="Explicitly select read-only mode")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-production-user-staging", action="store_true")
    parser.add_argument("--report", type=Path, help="Write a mode-600 JSON audit report")
    args = parser.parse_args()
    if args.apply and not args.confirm_production_user_staging:
        raise SystemExit("--confirm-production-user-staging is required with --apply; no write was performed")

    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if args.apply and (not url or not key):
        raise SystemExit("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required for --apply")

    export = json.loads(args.firebase_export.read_text(encoding="utf-8"))
    firebase_users = export.get("users", export) if isinstance(export, dict) else export
    if not isinstance(firebase_users, list):
        raise SystemExit("Firebase export must be a list or an object containing users")
    role_export = load_role_export(args.legacy_role_export)

    admin = SupabaseAdmin(url, key) if url and key else None
    supabase_users = admin.list_users() if admin else []
    by_email: dict[str, set[str]] = {}
    by_phone: dict[str, set[str]] = {}
    for user in supabase_users:
        user_id = user.get("id", "")
        email = normalized_email(user.get("email"))
        phone = normalize_phone(user.get("phone"))
        if email:
            by_email.setdefault(email, set()).add(user_id)
        if phone:
            by_phone.setdefault(phone, set()).add(user_id)

    email_counts = Counter(normalized_email(user.get("email")) for user in firebase_users if normalized_email(user.get("email")))
    phone_counts = Counter(normalize_phone(user.get("phoneNumber")) for user in firebase_users if normalize_phone(user.get("phoneNumber")))
    report: list[dict[str, object]] = []

    for firebase in firebase_users:
        uid = firebase.get("localId") or firebase.get("uid")
        if not isinstance(uid, str) or not uid:
            report.append({"firebase_uid": "<missing>", "classification": "FAILED", "detail": "missing Firebase UID"})
            continue
        migration_type, providers = provider_summary(firebase)
        email = normalized_email(firebase.get("email"))
        phone = normalize_phone(firebase.get("phoneNumber"))
        role, role_status, role_detail = role_resolution(uid, role_export)
        row: dict[str, object] = {
            "firebase_uid": uid,
            "email": email,
            "phone": phone,
            "provider_ids": providers,
            "migration_type": migration_type,
            "email_verified": bool(firebase.get("emailVerified")),
            "disabled": bool(firebase.get("disabled", False)),
            "created_at": firebase.get("createdAt"),
            "last_sign_in_at": firebase.get("lastLoginAt"),
            "provider_identifier_present": [
                sorted(item.keys())
                for item in firebase.get("providerUserInfo", [])
                if isinstance(item, dict) and item.get("providerId")
            ],
            "role_status": role_status,
            "proposed_role": role,
            "role_detail": role_detail,
        }
        classification = "READY_TO_CREATE"
        detail = "staging creates an email shell with no password, role, metadata, or firebase_uid"
        existing_ids = set()
        if email:
            existing_ids.update(by_email.get(email, set()))
        if phone:
            existing_ids.update(by_phone.get(phone, set()))
        if bool(firebase.get("disabled", False)):
            classification, detail = "DISABLED", "disabled Firebase user is not staged"
        elif migration_type == "GOOGLE":
            classification, detail = "MANUAL_REVIEW", "do not forge Google identity; allow first Google sign-in to create/link through an authenticated flow"
        elif migration_type in {"MULTI_PROVIDER", "OTHER"}:
            classification, detail = "UNSUPPORTED_PROVIDER", "provider set requires explicit migration review"
        elif email_counts.get(email, 0) > 1:
            classification, detail = "EMAIL_CONFLICT", "duplicate Firebase email"
        elif phone and phone_counts.get(phone, 0) > 1:
            classification, detail = "PHONE_CONFLICT", "duplicate normalized Firebase phone"
        elif len(existing_ids) > 1:
            classification, detail = "EMAIL_CONFLICT", "one Firebase identity matches multiple Supabase users"
        elif existing_ids:
            classification, detail = "ALREADY_EXISTS_MATCHED", "existing Supabase user matches by exact normalized email/phone"
            row["supabase_uid"] = next(iter(existing_ids))
        elif migration_type == "PHONE_ALIAS_PASSWORD":
            classification, detail = "MANUAL_REVIEW", "phone aliases have no mailbox; current Android claim flow must prove Firebase password before creating the Supabase account"
        elif not email:
            classification, detail = "MANUAL_REVIEW", "no email or safe phone staging path"
        row["classification"] = classification
        row["detail"] = detail
        if args.apply and classification == "READY_TO_CREATE":
            created = admin.create_email_shell(email, bool(firebase.get("emailVerified")))
            row["supabase_uid"] = created.get("id")
            row["detail"] = "created email shell; activation and role resolution remain separate"
        report.append(row)

    rendered = json.dumps(report, indent=2, sort_keys=True)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")
        os.chmod(args.report, 0o600)
    print(rendered)
    unsafe = {"EMAIL_CONFLICT", "PHONE_CONFLICT", "PROVIDER_CONFLICT", "UNSUPPORTED_PROVIDER", "DISABLED", "MANUAL_REVIEW", "FAILED"}
    return 0 if all(row.get("classification") not in unsafe for row in report) else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(f"FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
