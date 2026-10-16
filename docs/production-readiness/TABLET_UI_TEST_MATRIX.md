# AquaHub Owner tablet UI test matrix

Status values describe observed evidence only. **NOT TESTED** means no execution evidence; **BLOCKED** means a safe test environment is not currently available. Compilation is not visual or functional UI verification. The original route table is the pre-Group-5 baseline; the evidence delta below supersedes its stale source/status descriptions.

## Environment

| Environment | Status | Notes |
|---|---|---|
| Compact phone runtime | BLOCKED | Existing Samsung SM-A366B was not launched because it may replay stale outbox mutations. |
| Medium-width window | BLOCKED | No phone-sized emulator render was captured. |
| Expanded tablet portrait/landscape | BLOCKED | A clean Android 35 tablet AVD was configured at 1280×800, 160 dpi, but software emulation never came online to ADB during a prolonged first boot. It was stopped without installing the app. |
| Large/extra-large window | BLOCKED | No runtime render captured. |
| Runtime resizing/split-screen/fold posture | BLOCKED | No runtime session to capture interaction/state-preservation traces. |
| TalkBack/keyboard/large font/dark theme | BLOCKED | No runtime session for accessibility checks. |

## Route and workflow verification

| Screen/workflow | Source change | Phone visual | Tablet visual | Functional | Accessibility | Status |
|---|---|---|---|---|---|---|
| Splash | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — adaptive implementation remains. |
| Intro onboarding | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — adaptive implementation remains. |
| Login | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — adaptive implementation remains. |
| Registration subscription step | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — adaptive comparison remains. |
| Register | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — form bounds/runtime state remain. |
| Station setup | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — adaptive form/map remains. |
| MainContainer/navigation shell | Existing `NavigationSuiteScaffold` preserved | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED — source is adaptive; runtime navigation/insets not verified. |
| Dashboard | KPI grid and bounded content updated | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED — no rendered or interaction evidence. |
| Orders queue/detail | Selectable queue/detail pane updated | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED — status/paging refresh and pane behavior need execution. |
| Quick walk-in POS | Tablet surface and persistent cart updated | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED — no payment, stock, duplicate-submit, or screenshot run. |
| Inventory | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — adaptive table/workspace remains. |
| Products | Adaptive 420dp grid updated | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED — product edit/archive/availability unverified. |
| Customers list | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — directory/detail workspace remains. |
| Customer detail | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — multi-panel adaptation remains. |
| Riders/delivery management | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — queue/detail adaptation remains. |
| Reports | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — chart/table adaptation remains. |
| Expenses | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — ledger/form adaptation remains. |
| Settings | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — category/detail workspace remains. |
| Station profile | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — bounded tablet form remains. |
| Customer ordering settings | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — grouped tablet settings remain. |
| Payment methods | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — grouped tablet settings remain. |
| Delivery settings | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — responsive settings/map remains. |
| Manage stations | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — station list/detail remains. |
| Staff management | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — directory/role pane remains. |
| Subscription billing | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — plan/status panels remain. |
| Plan comparison | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — tablet comparison remains. |
| Password recovery activity | None in this slice | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | FAIL — bounded recovery form remains. |

## Group 5 evidence delta — 2026-10-10

The current implementation/source inventory is in [TABLET_UI_IMPLEMENTATION_MATRIX.md](TABLET_UI_IMPLEMENTATION_MATRIX.md). Across the inventoried screens, phone and tablet screenshot checks are **BLOCKED**, and UI functional/accessibility checks are **NOT TESTED**. A clean tablet AVD was attempted, but software emulation remained offline to ADB and no app was installed. No screenshot or device interaction is claimed. Source changes in this slice cover the shared adaptive primitives, navigation content frame, dashboard, POS, orders, inventory, products, customer list/detail, reports, staff, riders, expenses, settings, subscription/setup/authentication, and branch management.

## Automated checks

| Check | Result |
|---|---|
| Adaptive width boundary JVM test | PASS through `:app:testProductionDebugUnitTest`. |
| Production debug Kotlin compile | PASS through `:app:compileProductionDebugKotlin`. |
| `:app:testProductionDebugUnitTest` | PASS. |
| `:app:assembleProductionDebug` | PASS; APK packaging completed. |
| `:app:lintProductionDebug` | PASS on `--no-daemon --max-workers=2 --offline` (7m37s): 0 errors, 91 warnings. The first run identified `UnusedBoxWithConstraintsScope` in the new helper; that issue was fixed before this successful run. |
| Compose UI/instrumentation tests | NOT RUN. |
| Screenshot tests | No screenshot test harness or reference images added in this slice. |
