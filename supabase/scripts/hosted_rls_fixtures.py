#!/usr/bin/env python3
"""Disposable hosted Supabase RLS fixture runner.

This creates temporary Auth users through the public signup endpoint (or, when
SUPABASE_SERVICE_ROLE_KEY is explicitly supplied, through the Auth admin
endpoint with email confirmation enabled), creates fixture business rows through
the linked database admin connection, exercises
the public REST API with each user's JWT, and removes only its own fixtures.
It refuses to run without SUPABASE_URL and SUPABASE_ANON_KEY. No production
credentials or data are printed.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def local_property(name: str) -> str | None:
    path = Path(__file__).resolve().parents[2] / "AquaHub" / "local.properties"
    if not path.exists():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    return None


def http(url: str, method: str, key: str, token: str | None = None, body: object | None = None) -> tuple[int, object]:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("apikey", key)
    request.add_header("Authorization", f"Bearer {token or key}")
    request.add_header("Content-Type", "application/json")
    request.add_header("Prefer", "return=representation")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")


def cli_sql(sql: str) -> object:
    cli = os.environ.get("SUPABASE_CLI", "supabase")
    result = subprocess.run([cli, "db", "query", "--linked", sql], capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout


def assert_status(name: str, actual: int, expected: set[int], failures: list[str]) -> None:
    if actual not in expected:
        failures.append(f"{name}: expected HTTP {sorted(expected)}, got {actual}")


def main() -> int:
    base = os.environ.get("SUPABASE_URL") or local_property("SUPABASE_URL")
    anon = os.environ.get("SUPABASE_ANON_KEY") or local_property("SUPABASE_ANON_KEY")
    service_role = os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not base or not anon:
        raise SystemExit("SUPABASE_URL and SUPABASE_ANON_KEY are required; values are never printed")
    base = base.rstrip("/")
    suffix = uuid.uuid4().hex[:12]
    password = "AqRlsFixture-9!" + suffix
    names = ["owner_a", "manager_a", "staff_a", "owner_b", "staff_b", "customer"]
    accounts: dict[str, dict] = {}
    failures: list[str] = []
    org_a = str(uuid.uuid4())
    org_b = str(uuid.uuid4())
    station_a1 = str(uuid.uuid4())
    station_a2 = str(uuid.uuid4())
    station_b1 = str(uuid.uuid4())

    try:
        for name in names:
            email = f"codex-rls-{suffix}-{name}@example.com"
            if service_role:
                status, payload = http(
                    base + "/auth/v1/admin/users",
                    "POST",
                    service_role,
                    body={"email": email, "password": password, "email_confirm": True},
                )
                if status not in {200, 201} or not isinstance(payload, dict) or not payload.get("id"):
                    detail = payload if isinstance(payload, str) else json.dumps(payload, sort_keys=True)
                    raise RuntimeError(f"Auth admin fixture creation failed (HTTP {status}): {detail[:300]}")
                status, payload = http(
                    base + "/auth/v1/token?grant_type=password",
                    "POST",
                    anon,
                    body={"email": email, "password": password},
                )
            if isinstance(payload, dict) and payload.get("user", {}).get("id"):
                accounts[name] = payload
            if status not in {200, 201} or not isinstance(payload, dict) or not payload.get("access_token"):
                detail = payload if isinstance(payload, str) else json.dumps(payload, sort_keys=True)
                raise RuntimeError(f"Auth signup unavailable for disposable fixtures (HTTP {status}): {detail[:300]}")
            accounts[name] = payload

        owner_a = accounts["owner_a"]["user"]["id"]
        manager_a = accounts["manager_a"]["user"]["id"]
        staff_a = accounts["staff_a"]["user"]["id"]
        owner_b = accounts["owner_b"]["user"]["id"]
        staff_b = accounts["staff_b"]["user"]["id"]
        customer = accounts["customer"]["user"]["id"]
        ids = [owner_a, manager_a, staff_a, owner_b, staff_b, customer]
        if not all(re.fullmatch(r"[0-9a-f-]{36}", value) for value in ids):
            raise RuntimeError("Auth returned an unexpected user identifier")

        sql = f"""
        insert into public.organizations (id, owner_profile_id, name) values
          ('{org_a}', '{owner_a}', 'Codex RLS Fixture A'), ('{org_b}', '{owner_b}', 'Codex RLS Fixture B');
        insert into public.organization_members (organization_id, profile_id, role) values
          ('{org_a}', '{owner_a}', 'OWNER'), ('{org_a}', '{manager_a}', 'MANAGER'), ('{org_a}', '{staff_a}', 'STAFF'),
          ('{org_b}', '{owner_b}', 'OWNER'), ('{org_b}', '{staff_b}', 'STAFF');
        insert into public.stations (id, organization_id, name, address) values
          ('{station_a1}', '{org_a}', 'Fixture A1', 'Fixture'), ('{station_a2}', '{org_a}', 'Fixture A2', 'Fixture'),
          ('{station_b1}', '{org_b}', 'Fixture B1', 'Fixture');
        insert into public.member_stations (member_id, station_id)
          select m.id, '{station_a1}' from public.organization_members m where m.organization_id='{org_a}' and m.profile_id='{staff_a}';
        insert into public.member_stations (member_id, station_id)
          select m.id, '{station_b1}' from public.organization_members m where m.organization_id='{org_b}' and m.profile_id='{staff_b}';
        """.strip().replace("\n", " ")
        cli_sql(sql)

        def get(name: str, table: str, query: str = "select=id") -> tuple[int, object]:
            return http(base + f"/rest/v1/{table}?{query}", "GET", anon, accounts[name]["access_token"])

        status, body = get("owner_a", "organizations")
        assert_status("owner A organization read", status, {200}, failures)
        if isinstance(body, list) and any(row.get("id") == org_b for row in body):
            failures.append("owner A can read organization B")
        status, body = get("owner_b", "organizations")
        assert_status("owner B organization read", status, {200}, failures)
        if isinstance(body, list) and any(row.get("id") == org_a for row in body):
            failures.append("owner B can read organization A")
        status, body = get("manager_a", "organizations")
        assert_status("manager A organization read", status, {200}, failures)
        status, body = get("customer", "organizations")
        assert_status("customer organization read", status, {200}, failures)
        if body not in ([], None):
            failures.append("customer can read protected organizations")

        status, body = get("staff_a", "stations")
        assert_status("staff A station read", status, {200}, failures)
        if isinstance(body, list):
            station_ids = {row.get("id") for row in body}
            if station_a1 not in station_ids or station_a2 in station_ids or station_b1 in station_ids:
                failures.append("staff A station isolation failed")

        def mutate(name: str, table: str, method: str, query: str, payload: object) -> int:
            return http(base + f"/rest/v1/{table}?{query}", method, anon, accounts[name]["access_token"], payload)[0]

        assert_status("customer membership insert denied", mutate("customer", "organization_members", "POST", "", {"organization_id": org_a, "profile_id": customer, "role": "OWNER"}), {401, 403}, failures)
        assert_status("customer role update denied", mutate("customer", "profiles", "PATCH", f"id=eq.{customer}", {"role": "OWNER"}), {401, 403}, failures)
        assert_status("manager ownership update denied", mutate("manager_a", "organizations", "PATCH", f"id=eq.{org_a}", {"owner_profile_id": manager_a}), {401, 403}, failures)
        assert_status("customer subscription insert denied", mutate("customer", "subscriptions", "POST", "", {"organization_id": org_a, "status": "FREE", "billing_cycle": "MONTHLY"}), {401, 403}, failures)

        print(json.dumps({"status": "PASS" if not failures else "FAIL", "fixture_accounts": len(accounts), "failures": failures}, indent=2))
        return 0 if not failures else 2
    finally:
        cleanup_ids = list(accounts.values())
        cleanup_user_ids = [item.get("user", {}).get("id") for item in cleanup_ids]
        safe_ids = [value for value in cleanup_user_ids if isinstance(value, str) and re.fullmatch(r"[0-9a-f-]{36}", value)]
        if safe_ids:
            quoted = ",".join(f"'{value}'" for value in safe_ids)
            cleanup_sql = f"delete from public.organizations where id in ('{org_a}','{org_b}'); delete from auth.users where id in ({quoted});"
            try:
                cli_sql(cleanup_sql)
            except Exception as error:  # cleanup failure must remain visible
                print(f"CLEANUP FAILED: {error}", file=sys.stderr)
                failures.append("fixture cleanup failed")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        raise SystemExit(1)
