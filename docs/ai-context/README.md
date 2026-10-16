# AI Context Index

This directory is the progressive entry point for AquaHub workspace work. Facts were derived from source and configuration in both projects; source remains authoritative.

## Choose the smallest useful context

| Task | Read first | Then |
|---|---|---|
| Workspace, build, or project-boundary change | `WORKSPACE_OVERVIEW.md` | Both project overviews |
| Shared model, Firebase path, enum, money, order, or notification | `SHARED_DOMAIN_AND_CONTRACTS.md` | Both `DATA_AND_BACKEND.md` files and Functions |
| Kotlin/Compose/testing/security/performance work | `ENGINEERING_GUIDE.md` | Nearest project architecture |
| Bug triage or roadmap assessment | `KNOWN_RISKS_AND_STATUS.md` | Relevant feature/source |
| Owner/station feature | `AquaHub/AGENTS.md` | Owner feature/architecture document |
| Customer or callable-backend feature | `AquaHubCustomer/AGENTS.md` | Customer feature/data document |

## Authority order

1. Current executable source, Gradle configuration, manifest, rules, and tests.
2. These AI context documents.
3. Existing implementation plans and README claims.

When these disagree, verify the code, preserve the disagreement in status/risk documentation if still relevant, and do not infer deployment state from local source.

## Maintenance

Update these files only for durable changes to architecture, navigation, persistence, contracts, business rules, feature status, or operational risk. Avoid class inventories and line numbers. Keep source paths stable and label unverified external state as `UNKNOWN`.

