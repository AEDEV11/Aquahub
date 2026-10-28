#!/usr/bin/env python3
"""Read-only Reach authentication check; never print or persist the entered token."""

import argparse
import getpass
import hashlib
import hmac
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request


def check_endpoint(token, url):
    """Return only status and safe diagnostic identifiers from a read-only GET."""
    report = {}
    request = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json",
                 "User-Agent": "AquaHub-Reach-Diagnostic/1.0"},
    )
    try:
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            report["hostinger_http_status"] = response.status
            body = response.read(65536)
        try:
            data = json.loads(body)
        except (ValueError, UnicodeError):
            data = {}
        waf_block = isinstance(data, dict) and data.get("cloudflare_error") is True
        report["result"] = (
            "cloudflare_access_block" if waf_block else
            "provider_authenticated" if response.status == 200 else
            "provider_rejected_authentication" if response.status == 401 else
            "provider_denied_access" if response.status == 403 else
            "provider_rate_limited" if response.status == 429 else
            "unexpected_provider_response"
        )
        if waf_block and isinstance(data.get("error_code"), int):
            report["cloudflare_error_code"] = data["error_code"]
        if isinstance(data, dict):
            for name in ("correlation_id", "ray_id"):
                value = data.get(name)
                if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9-]{1,80}", value):
                    report[name] = value
    except (OSError, ValueError):
        report["result"] = "network_or_request_error"
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-ref", default="pfkxrggexueltytxtqil")
    parser.add_argument("--metadata-only", action="store_true")
    parser.add_argument(
        "--check-reach-token", action="store_true",
        help="Also test the same token against Reach's WordPress integration API (read-only).",
    )
    args = parser.parse_args()
    try:
        result = subprocess.run(
            ["supabase", "secrets", "list", "--project-ref", args.project_ref,
             "--output", "json"],
            capture_output=True, text=True, timeout=30, check=True,
        )
        payload = json.loads(result.stdout)
        rows = payload if isinstance(payload, list) else payload.get("secrets", [])
        secrets = {row["name"]: row for row in rows}
    except (OSError, subprocess.SubprocessError, ValueError, KeyError):
        print("Unable to read secret metadata. Check Supabase CLI login/project access.")
        return 1

    secret = secrets.get("HOSTINGER_REACH_API_TOKEN")
    report = {"project_ref": args.project_ref, "reach_secret_present": secret is not None}
    if not secret:
        print(json.dumps(report, indent=2))
        return 1
    report["secret_updated_at_utc"] = secret.get("updated_at")
    # Validate the digest algorithm against a public, non-secret project URL.
    public_url = f"https://{args.project_ref}.supabase.co"
    public_digest = secrets.get("SUPABASE_URL", {}).get("value", "")
    algorithm_verified = hmac.compare_digest(
        hashlib.sha256(public_url.encode()).hexdigest(), public_digest.lower(),
    )
    report["sha256_digest_format_verified"] = algorithm_verified
    if args.metadata_only:
        print(json.dumps(report, indent=2))
        return 0
    if not sys.stdin.isatty():
        print("Run this command directly in your terminal for a hidden token prompt.")
        return 1
    token = getpass.getpass("Paste the original Hostinger API token (hidden): ")
    if not token or token != token.strip() or token.lower().startswith("bearer "):
        print("Enter only the raw token, without a Bearer prefix or surrounding whitespace.")
        return 1

    if algorithm_verified:
        stored_digest = str(secret.get("value", "")).lower()
        variants = {
            "raw_token": token,
            "bearer_prefix": f"Bearer {token}",
            "double_quotes": f'"{token}"',
            "single_quotes": f"'{token}'",
            "trailing_newline": token + "\n",
            "trailing_space": token + " ",
        }
        report["stored_secret_matches"] = next(
            (name for name, value in variants.items() if hmac.compare_digest(
                hashlib.sha256(value.encode()).hexdigest(), stored_digest)),
            "none_of_checked_variants",
        )
    else:
        report["stored_secret_matches"] = "not_verified_digest_format"

    report.update(check_endpoint(
        token,
        "https://developers.hostinger.com/api/reach/v1/profiles",
    ))
    if args.check_reach_token:
        # Hostinger's published WordPress plugin uses this GET to validate
        # Reach profile integration tokens, rather than hPanel API tokens.
        report["reach_profile_token_api"] = check_endpoint(
            token, "https://reach.hostinger.com/api/public/v1/connection/status",
        )
        if (report.get("hostinger_http_status") == 401 and
                report["reach_profile_token_api"].get("hostinger_http_status") == 200):
            report["diagnosis"] = "reach_integration_token_accepted_but_hpanel_api_rejects_it"
    # Never print request headers, raw response bodies, tokens, or digests.
    print(json.dumps(report, indent=2))
    return 0 if report.get("result") == "provider_authenticated" else 1


if __name__ == "__main__":
    raise SystemExit(main())
