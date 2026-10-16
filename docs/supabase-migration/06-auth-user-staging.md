# Firebase Auth user staging

Status: `DRY RUN COMPLETE — no Supabase Auth users created`.

## Export inspection

Firebase project: `aquahub-506411`. The current Firebase Auth export contains 24 users:

| Migration type | Count | Staging decision |
|---|---:|---|
| `PHONE_ALIAS_PASSWORD` | 20 | Manual review; prove the Firebase password first, then use the existing claim flow |
| `GOOGLE` | 4 | Manual review; do not pre-create or forge a Google identity |
| Disabled | 0 | — |

All 24 have an email field. The 20 password users use the deterministic `phone-<canonical-63-digit-phone>@auth.aquahub.app` alias and have password hash/salt fields. The four Google users have `google.com` provider records and verified email status. No phone-number field or last-sign-in timestamp is present in this export format. Creation timestamps are present. Reports omit password hashes, salts, tokens, and secrets; the restricted detailed dry-run report is `/tmp/aquahub-auth-user-staging-dry-run.json`.

## Password import decision

Supabase documents a Firebase migration utility that can import Firebase password hashes when the Firebase SCRYPT configuration is supplied: algorithm, `base64_signer_key`, `base64_salt_separator`, `rounds`, and `mem_cost`. The current export contains `passwordHash` and `salt`, but the required project-level SCRYPT parameters are not in the export and have not been provided from Firebase Console. No hash import or conversion was attempted.

The existing AquaHub flow therefore remains the safe path: prove the Firebase password at first login, create/claim the deterministic Supabase alias, then establish the Firebase compatibility session. No known/default/temporary password is generated.

## Provider strategies

### Phone aliases

The 20 aliases are not human mailboxes. Pre-creating passwordless alias shells would make the current Android claim path unable to set a password safely. They remain `MANUAL_REVIEW` until the first-login proof/claim path or an approved server-side activation flow is ready. Alias normalization is the existing canonical-63-digit rule, and no alias is claimed without Firebase credential proof.

### Google

The four Google identities remain `MANUAL_REVIEW`. Supabase Google sign-in should create or resolve the account through the real Google ID token. Provider identities must not be forged from Firebase `rawId` values, and pre-creating an email-only user could create an email/provider collision. First Google sign-in should be matched by verified email and then mapped to the Firebase UID; duplicate-account handling must be validated on a device.

## Profile and role safety

The hosted `auth.users` trigger creates exactly one `public.profiles` row with the Supabase UUID as `profiles.id` and default role `CUSTOMER`. The staging tool supplies no role, Firebase UID, or user metadata, so `profiles.firebase_uid` remains null until the separately reviewed backfill.

The 24 Firebase Auth records have no role custom claims. The authoritative role source is the legacy Firestore `/users/{firebaseUid}.role` document (with business membership documents as a cross-check). No authorized Firestore role export was available to this dry run, so every role resolution is `MANUAL_REVIEW`; no role was proposed or applied.

## Staging tool safeguards

`supabase/scripts/stage_firebase_auth_users.py` is server-side/admin-only and uses the service-role key only from the environment. It supports `--dry-run`, `--apply`, `--report`, and requires `--confirm-production-user-staging` for writes. It is idempotent, never deletes Firebase users, never imports hashes, never writes roles or `firebase_uid`, never logs credentials, and skips email/phone/provider conflicts. Apply mode can only create explicitly `READY_TO_CREATE` email shells with no password or custom metadata; the current production dry run has zero such rows.

## Dry-run result

| Classification | Count |
|---|---:|
| `READY_TO_CREATE` | 0 |
| `ALREADY_EXISTS_MATCHED` | 0 |
| `EMAIL_CONFLICT` | 0 |
| `PHONE_CONFLICT` | 0 |
| `PROVIDER_CONFLICT` | 0 |
| `UNSUPPORTED_PROVIDER` | 0 |
| `DISABLED` | 0 |
| `MANUAL_REVIEW` | 24 |
| `FAILED` | 0 |

## Prepared command

The apply command is prepared but was not executed:

```text
SUPABASE_URL=https://pfkxrggexueltytxtqil.supabase.co \
SUPABASE_SERVICE_ROLE_KEY='(secret manager value)' \
python3 supabase/scripts/stage_firebase_auth_users.py \
  --firebase-export /secure/path/firebase-auth-export.json \
  --report /secure/path/aquahub-auth-user-staging-apply.json \
  --apply \
  --confirm-production-user-staging
```

The command is not safe to execute until the 24 manual-review items have approved activation/provider/role plans.

