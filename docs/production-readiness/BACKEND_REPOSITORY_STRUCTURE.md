# Backend and release-source repository proposal

Status: proposal prepared; backend `.gitignore` and inert `RELEASE_MANIFEST.example.json` are now present locally. No repository was initialized, created remotely, or pushed. The workspace parent contains an empty `.git` directory but is not a Git repository; `AquaHub/` and `AquaHubCustomer/` remain independent Git roots. Do not initialize a monorepo around them implicitly.

## Recommendation

Create a dedicated private backend/infrastructure repository (suggested name `aquahub-backend`) for Supabase migrations/functions/tests and the canonical production-readiness records. This preserves both Android histories, gives database/function releases an independently reviewable revision, and avoids copying Android builds, release bundles, app signing files, Firebase client configuration, or secrets into a new parent repository.

Proposed structure:

```text
aquahub-backend/
  supabase/
    config.toml
    migrations/
    functions/
    tests/
    scripts/                 # non-secret validation/deployment helpers
  docs/production-readiness/
  .github/workflows/         # local/static checks; deployment gated separately
  .gitignore
  README.md
  RELEASE_MANIFEST.example.json
```

`RELEASE_MANIFEST.example.json` should record backend Git SHA, migration IDs, Edge Function source SHA, Owner/Customer app version codes and commit SHAs, environment label, and verification links. It must not store project secrets, JWTs, service-role values, signing keys, database dumps, or customer data. Actual release records belong in the authorized release system.

## Ignore and example-config rules

Exclude `.env`, `.env.*` (except explicitly safe example templates), Supabase access tokens, service-account JSON/key material, local DB volumes, `.supabase/`, dumps/backups, test exports, logs, Android AAB/APK files, keystores, signing properties, and generated coverage/build directories. Safe templates must use conspicuously fake placeholders and be documented as non-deployable until populated through a secret manager. Never copy the workspace `.env.validation.local` or `local.properties`.

## Migration and client provenance

- Preserve every migration filename and SQL checksum; record the exact commit and migration versions in each backend release.
- Deployments must reference a reviewed commit, not a developer working tree.
- Record separately which Owner and Customer app commit/version was tested against each nonproduction backend release.
- Keep existing Android Git roots intact. Link exact tested app/backend SHAs in release metadata; do not imply that a shared folder is versioned when it is not.
- CI may run SQL lint, clean isolated migration replay, pgTAP, Deno tests/typecheck, and static secret scanning. Production deploy credentials and jobs must be separately protected and manually gated.

## Current limitation

The existing `supabase/` directory and `docs/production-readiness/` are outside both Android Git roots. Therefore their current worktree changes have no verifiable commit ancestry in those repositories. This proposal does not solve provenance until an owner approves and creates/configures the dedicated private repository. No remote creation, push, history rewrite, or repository initialization was performed here.
