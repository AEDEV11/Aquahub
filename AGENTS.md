# AquaHub Workspace Agent Guide

This workspace contains two related but separately built Android applications:

- `AquaHub/`: owner and water-station operations app (`com.aesprt.aquahub`).
- `AquaHubCustomer/`: customer ordering app (`com.aesprt.aquahub_customer`) and the active Firebase Functions source.

Before editing, decide which application owns the requested behavior and read that project's nearest `AGENTS.md`. Start broader work at `docs/ai-context/README.md`.

## Mandatory change protocol

1. Read the nearest `AGENTS.md` and the context document for the affected feature.
2. Inspect the current source; context documents are maps, not substitutes for code.
3. Treat `Station`, `Product`, `Customer`, `Address`, `Order`, `OrderItem`, `OrderStatus`, delivery, payment, notification, and Firebase path changes as cross-project changes.
4. For a shared-contract change, inspect both apps, `AquaHubCustomer/functions/src/index.ts`, and both Firestore rules/index files before completion.
5. Preserve integer-centavo fields and exact enum/path compatibility at Firebase boundaries.
6. Make the smallest scoped change. Do not silently implement a planned requirement or preserve a documented bug.
7. Run the relevant Gradle/tests and backend/rules tests. Report anything not run.
8. Update context only when architecture, contracts, business rules, navigation, persistence, or major implementation status changes.

## Workspace rules

- The projects are independent Gradle builds; run each wrapper from its own root.
- The apps share a Firebase project and named Firestore database, but do not reference each other as Gradle modules.
- `AquaHubCustomer/functions/` is the active Cloud Functions package. `AquaHubCustomer/aquahub-functions/` is an unused scaffold unless Firebase configuration changes.
- The two `firestore.rules` files are synchronized copies of one canonical ruleset and must remain byte-identical. Emulator-test cross-app changes and deploy from `AquaHubCustomer/firebase.json`, which targets the named `aquahub` database.
- Do not expose values from `local.properties`, `google-services.json`, signing material, tokens, or other credentials in code, logs, or documentation.
- Preserve the existing AquaHub water-service visual identity and Compose/Material 3 conventions.
- Requirement plans describe intent and may be stale. Use status labels `IMPLEMENTED`, `PARTIAL`, `PLANNED`, `MOCK`, `DEPRECATED`, or `UNKNOWN` where the distinction matters.

## Context map

- `docs/ai-context/WORKSPACE_OVERVIEW.md`: project boundaries, stack, requirements, ADRs.
- `docs/ai-context/SHARED_DOMAIN_AND_CONTRACTS.md`: canonical vocabulary, backend schema, enums, impact matrix.
- `docs/ai-context/ENGINEERING_GUIDE.md`: coding, UI, security, performance, testing rules.
- `docs/ai-context/KNOWN_RISKS_AND_STATUS.md`: cross-workspace risks and requirement gaps.
- Each project has implementation-specific architecture, flow, navigation, data, and feature maps under `docs/ai-context/`.
