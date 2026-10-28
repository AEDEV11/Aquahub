# AquaHub Owner Deployment Manifest — 2026-10-17

**State:** PARTIALLY EXECUTED — the user-authorized `offline-pos` deployment is complete and verified. No database, website, or Play Console change was made. End-to-end readiness remains gated on signing-key/secret verification and remaining safety tests.

| Batch | Target | Exact planned changes | Current gate | Recovery |
|---|---|---|---|---|
| Database | Supabase project `pfkxrggexueltytxtqil` | None currently. Migration history is 69/69 and offline POS prerequisites are already applied. | No isolated migration/RPC/RLS suite; backup/restore readiness not evidenced. Do not apply. | No DB rollback plan required for current batch because no migration is pending. For any future schema change, prefer a forward-fix; restore only from verified recovery point under approved incident procedure. |
| Edge Functions | Supabase project `pfkxrggexueltytxtqil` | Fresh inventory shows `offline-pos` v2 active with `verify_jwt=true`; retrieved entrypoint exactly matches local source. `delete-account` v19 remains active; its separate local update was not included. | Deployment verified. Prior disposable tests cover core sale/replay, but Edge signing-secret presence/match cannot be inspected here; remaining fault cases/recovery evidence are incomplete. No production grant/sale request was sent. | Setting `OFFLINE_POS_ENABLED=false` can stop new grants while allowing valid previously authorized commits; this recheck did not change or verify that secret. Preserve pending receipts. Do not combine the separate deletion-handler update without its own failure/retry tests. |
| Website | Firebase Hosting default project `aquahub-506411`, custom host `aquahub.aesprt.com` | `aquahub-website/account-deletion.html`, `privacy.html`, and `index.html` local copy corrections. | No preview/staging channel identified; Firebase CLI unavailable; custom host returned 403 in current checker. Do not deploy. | Keep the prior hosting release available for exact rollback; verify it in Hosting console before approval. If no release rollback is available, deploy a tested forward correction. |
| Android | Google Play app `com.aesprt.aquahub` | Production AAB built at version code 12 / `aq-1.0.7` and signed with the configured local keystore alias. No Play upload planned by this task. | Play Console upload-certificate comparison and acceptance remain unverified. | Do not replace upload key. Owner may compare the certificate and upload through Play Console; no upload/publication/rollout was performed here. |
| Validation | App/backend/site/device | Run an approved non-destructive integration check against the deployed function; device and Play test track remain unavailable. | Incomplete. | Stop on first failure; do not continue dependent batches after migration or function failure. |

## Predeployment changes and affected services

1. **Database:** none planned from current migration diff; 69 applied versions match. Production data is untouched.
2. **Supabase Functions:** `offline-pos` v2 is live-verified. `delete-account` v19 remains deployed; a separate local update is pending. Required secrets must be checked by presence only in Supabase dashboard; never include values in logs or this manifest.
3. **Firebase Hosting:** three static files. Target is production because no staging alias was found. Preview/rollback must be established before deployment approval.
4. **Google Play:** signed release artifact exists locally. No upload/publish; compare the certificate with Play Console's upload key and let the owner handle upload acceptance.

## Approval package required before execution

The deployment scope was `offline-pos` only and is complete. The user's authorization did not cover the separate `delete-account` update, website, database, or Google Play changes. No production smoke request capable of registering a device or reserving stock was sent. Functional readiness remains gated on key-pair/secret checks, remaining safety tests, a safe smoke plan, and rollback readiness.
