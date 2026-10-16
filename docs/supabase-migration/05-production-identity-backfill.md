# Production Firebase identity backfill

Status: `BLOCKED — read-only dry run complete; Supabase identity population is empty`.

## Read-only production dry run

Source Firebase project: `aquahub-506411`.

Hosted Supabase project: `pfkxrggexueltytxtqil`.

The Firebase CLI exported 24 Auth accounts. The hosted Supabase Admin API returned 0 Auth users and 0 `public.profiles` rows. The complete report was written with mode `0600` to `/tmp/aquahub-production-backfill-dry-run.json`; it contains identity IDs and classifications only, never passwords, access tokens, or service keys.

| Classification | Count |
|---|---:|
| READY | 0 |
| ALREADY_MAPPED | 0 |
| MISSING_SUPABASE_USER | 24 |
| MISSING_FIREBASE_USER | 0 |
| AMBIGUOUS | 0 |
| UID_CONFLICT | 0 |
| ALIAS_CONFLICT | 0 |
| UNKNOWN_ROLE | 0 |
| MISSING_PROFILE | 0 |
| MANUAL_REVIEW / FAILED | 0 |

Exact matches and already-mapped users: none. All 24 Firebase users require the staged Supabase account-creation/claim process before a UID backfill can be considered. The export contained no role claims (`NOT_PROVIDED` for all 24), so no role value is eligible for automatic assignment.

## Safety review

The dry-run tool now enforces these invariants:

- duplicate Firebase export UIDs become `UID_CONFLICT`;
- duplicate normalized emails or deterministic phone aliases become `ALIAS_CONFLICT`;
- one existing Supabase `firebase_uid` cannot be reused across profiles;
- an existing different `firebase_uid` is never overwritten;
- missing profiles, unknown roles, and ambiguous candidates never apply;
- only `OWNER`, `MANAGER`, `STAFF`, `RIDER`, and `CUSTOMER` are accepted role values;
- apply runs are idempotent and only update rows classified `READY`.

No ambiguity or conflicting mapping was found in this run, but the empty Supabase identity population is a systemic blocker. It is not safe to apply a backfill now because there are no Supabase users or profiles to receive the mappings.

## Prepared apply procedure

The tool requires an environment-only service-role key, an explicit confirmation flag, and a Firebase export. It never deletes Firebase users. The command is prepared but was not executed:

```text
SUPABASE_URL=https://pfkxrggexueltytxtqil.supabase.co \
SUPABASE_SERVICE_ROLE_KEY='(supply through the secret manager)' \
python3 supabase/scripts/backfill_firebase_identities.py \
  --firebase-export /secure/path/firebase-auth-export.json \
  --report /secure/path/aquahub-backfill-apply-report.json \
  --apply \
  --confirm-production-backfill
```

Run the same command without `--apply` first. Review the report, confirm that every write is `READY`, and retain the report as the audit artifact. The current production dry run does not satisfy that condition.

Before this backfill can run, the Auth-user staging gate must complete. The staging dry run is recorded in `06-auth-user-staging.md`; it created no users and left all 24 Firebase identities for manual activation/provider and role review.
