#!/usr/bin/env python3
"""Validate a customer/product Firestore export before Supabase import.

The tool is intentionally dry-run by default. It accepts a JSON document with
`customers`, `customer_addresses`, and `products` arrays. Each row must carry
its legacy Firestore id and station id. Apply mode is deliberately explicit
and requires a service-role key; it is not run by this migration checkpoint.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from collections import Counter
from pathlib import Path


PRODUCT_TYPES = {"REFILL", "NEW_CONTAINER", "EMPTY_CONTAINER", "OTHER"}
PROMOTION_TYPES = {"PERCENTAGE", "FIXED_PRICE", "QUANTITY_BREAK"}
STATUSES = {"READY", "ALREADY_MIGRATED", "DUPLICATE", "INVALID_TENANT", "CONFLICT", "FAILED"}


def request(base: str, key: str, table: str, row: dict, apply: bool) -> tuple[int, str]:
    payload = json.dumps(row).encode("utf-8")
    req = urllib.request.Request(
        f"{base.rstrip('/')}/rest/v1/{table}",
        data=payload,
        method="POST",
        headers={
            "apikey": key,
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "Prefer": "resolution=merge-duplicates,return=minimal",
        },
    )
    if not apply:
        return 0, "dry-run"
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.status, "ok"
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")[:240]


def required(row: dict, keys: list[str]) -> bool:
    return all(isinstance(row.get(key), str) and row[key].strip() for key in keys)


def validate(kind: str, row: dict, known_stations: set[str], seen: set[str]) -> tuple[str, str]:
    legacy_id = row.get("id")
    if not isinstance(legacy_id, str) or not legacy_id.strip():
        return "INVALID_TENANT", "missing legacy id"
    if legacy_id in seen:
        return "DUPLICATE", "duplicate legacy id in export"
    seen.add(legacy_id)
    if not required(row, ["stationId"]):
        return "INVALID_TENANT", "missing stationId"
    if known_stations and row["stationId"] not in known_stations:
        return "INVALID_TENANT", "station is not in the supplied station set"
    if kind == "customer" and not required(row, ["name", "phone"]):
        return "FAILED", "customer requires name and phone"
    if kind == "product":
        if not required(row, ["name", "productType", "sizeLabel"]):
            return "FAILED", "product requires name, productType, and sizeLabel"
        if row["productType"] not in PRODUCT_TYPES:
            return "FAILED", "unknown productType"
        if not isinstance(row.get("priceCentavos"), int) or row["priceCentavos"] < 0:
            return "FAILED", "priceCentavos must be a non-negative integer"
        if row.get("promotionType") not in (None, *PROMOTION_TYPES):
            return "FAILED", "unknown promotionType"
    return "READY", "validated; legacy firebase_id will be preserved"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-production", action="store_true")
    args = parser.parse_args()
    if args.apply and not args.confirm_production:
        raise SystemExit("apply mode requires --confirm-production")
    if not args.input.exists():
        raise SystemExit(f"export not found: {args.input}")

    document = json.loads(args.input.read_text(encoding="utf-8"))
    known_stations = set(document.get("stations", []))
    results: list[dict] = []
    seen: dict[str, set[str]] = {"customer": set(), "product": set(), "address": set()}
    for kind, table, rows in (
        ("customer", "customers", document.get("customers", [])),
        ("address", "customer_addresses", document.get("customer_addresses", [])),
        ("product", "products", document.get("products", [])),
    ):
        for row in rows:
            status, reason = validate(kind, row, known_stations, seen[kind])
            result = {"kind": kind, "legacy_id": row.get("id"), "status": status, "reason": reason}
            if status == "READY":
                result["target_table"] = table
                result["legacy_firebase_id"] = row["id"]
                if args.apply:
                    status_code, detail = request(
                        os.environ["SUPABASE_URL"],
                        os.environ["SUPABASE_SERVICE_ROLE_KEY"],
                        table,
                        {**row, "firebase_id": row["id"]},
                        True,
                    )
                    if status_code >= 300:
                        result["status"] = "FAILED"
                        result["reason"] = f"Supabase HTTP {status_code}: {detail}"
            results.append(result)

    counts = Counter(item["status"] for item in results)
    print(json.dumps({"mode": "apply" if args.apply else "dry-run", "counts": counts, "records": results}, indent=2, default=dict))
    return 0 if not any(item["status"] in {"FAILED", "INVALID_TENANT", "CONFLICT"} for item in results) else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KeyError, OSError, ValueError, urllib.error.URLError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        raise SystemExit(1)
