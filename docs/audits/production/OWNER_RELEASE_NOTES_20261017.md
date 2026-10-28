# AquaHub Owner Release Candidate Notes — 2026-10-17

**Release status:** Signed production AAB built locally; Play Console upload compatibility and release gates remain unverified. No upload or publication was performed.
**Local version:** `aq-1.0.7` (`versionCode` 12). Confirm version availability in Play Console before upload.

## Prepared changes

- Corrected the QA manifest Lint error and several low-risk static-analysis findings.
- Added preflight validation so production packaging requires the configured AquaHub Supabase production project, valid P-256 offline POS verifier public key, and existing upload keystore.
- Fixed the Owner account-deletion reauthentication flow and clarified deletion, retained business records, marketing preferences, and subscription cancellation copy.
- Prepared aligned account-deletion and privacy wording for the public website.
- Added a rollback switch to stop issuing new offline POS grants while preserving reconciliation for previously authorized offline sales.
- Refreshes incoming orders when the Orders screen resumes to recover missed notifications.
- Moved the app logo to a non-density drawable resource and removed unused generated launcher drawable resources.

## Validation

- QA unit tests: 190 passed.
- Deno function tests: 87 passed; all 14 function entrypoints typechecked.
- Website static tests: 14 passed.
- Final QA and production Android Lint runs passed with zero errors (87 and 85 warnings respectively); advisory warnings remain.
- `:app:bundleProductionRelease` passed and generated the signed AAB at `AquaHub/app/build/outputs/bundle/productionRelease/app-production-release.aab`. Its signer matches the configured local keystore alias; compare with the Play Console upload certificate before upload.
- No production deployment or Google Play upload/publication was performed.

## Do not publish yet

The offline POS function is deployed but signing-secret pairing and end-to-end reconciliation remain unverified; database and tenant security integration tests are incomplete; account deletion changes are local only; Play Billing and Play Console declarations are unverified; and device tests are blocked. Release verdict is **NO-GO**.
