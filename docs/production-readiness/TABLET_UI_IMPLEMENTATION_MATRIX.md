# AquaHub Owner screen implementation matrix

Current-source inventory: 27 `Screen` declarations (including the `More` alias), 26 routed destinations, 26 feature `*Screen.kt` files, the exported password-recovery activity, and the non-route POS task surface. The route table below includes all 26 destinations, the recovery activity, and POS. Dialogs and shared components are covered after the route table.

**Source status** distinguishes an implementation change from a runtime PASS. The original inventory below is the pre-Group-5 baseline; the dated current-source delta immediately after it is authoritative for source changes. Visual and functional columns only become PASS after corresponding rendered or interaction evidence exists. No screen has device-rendered tablet evidence.

| Screen / workflow | Phone source/presentation | Tablet source/presentation | Visual test | Functional test | Status / remaining work |
|---|---|---|---|---|---|
| Splash | Existing brand/loading screen | Existing centered screen | NOT TESTED | NOT TESTED | FAIL — bounded/inset-aware presentation remains. |
| Intro onboarding | Existing guided flow | Existing single-flow content | NOT TESTED | NOT TESTED | FAIL — centered/split layout and resize-state verification remain. |
| Login | Existing auth screen | Existing auth screen | NOT TESTED | NOT TESTED | FAIL — bounded form and IME/large-window validation remain. |
| Registration subscription step | Existing plan selection | Existing plan selection | NOT TESTED | NOT TESTED | FAIL — fetched-price comparison layout remains. |
| Register | Existing form | Existing form | NOT TESTED | NOT TESTED | FAIL — form width and resize-state validation remain. |
| Station setup | Existing setup/map flow | Existing setup/map flow | NOT TESTED | NOT TESTED | FAIL — bounded form/map split remains. |
| MainContainer / navigation | Existing `NavigationSuiteScaffold` | Suite-selected navigation | NOT TESTED | NOT TESTED | NOT TESTED — no shell changes; actual suite, insets, route restoration need runtime verification. |
| Dashboard | Existing KPI, revenue, activity | New 1440dp content frame; two or four KPI columns by available width | NOT TESTED | NOT TESTED | NOT TESTED — chart/activity grouping and live-state interaction need render and functional verification. |
| Orders queue | Existing paged queue | New selectable queue plus order summary pane at 840dp; full action route retained | NOT TESTED | NOT TESTED | NOT TESTED — no paging, resize, or state-transition interaction run. |
| Order detail | Existing detail route | Existing page with no dedicated multi-column update | NOT TESTED | NOT TESTED | FAIL — detail action groups need tablet organization. |
| Quick walk-in POS | Existing modal bottom sheet/sticky footer | New full-height tablet surface and persistent cart panel | NOT TESTED | NOT TESTED | NOT TESTED — no payment/stock/idempotency run. |
| Inventory | Existing stock and movement list | Existing list presentation | NOT TESTED | NOT TESTED | FAIL — wide metrics, movement table, adjustment pane remain. |
| Products | Existing product list and forms | New adaptive grid, 420dp minimum cell | NOT TESTED | NOT TESTED | NOT TESTED — product/plan interactions and visual density need verification. |
| Customers list | Existing searchable cards | Existing single list | NOT TESTED | NOT TESTED | FAIL — directory/detail selection pane remains. |
| Customer detail | Existing account/detail flow | Existing page | NOT TESTED | NOT TESTED | FAIL — balances, history, and actions need intentional tablet grouping. |
| Riders | Existing roster/dialogs | Existing list | NOT TESTED | NOT TESTED | FAIL — roster plus assignment/context panel remains. |
| Reports | Existing filters, KPI, charts, refunds | Existing report flow | NOT TESTED | NOT TESTED | FAIL — persistent filters and chart/table workspace remain. |
| Expenses | Existing summary/list/forms | Existing list | NOT TESTED | NOT TESTED | FAIL — ledger table and bounded edit form remain. |
| Settings | Existing category/action list | Existing list | NOT TESTED | NOT TESTED | FAIL — category navigation/detail pane remains. |
| Station profile | Existing form | Existing form | NOT TESTED | NOT TESTED | FAIL — tablet form grouping/bounds remain. |
| Customer ordering settings | Existing configuration screen | Existing configuration screen | NOT TESTED | NOT TESTED | FAIL — grouped tablet settings/preview remain. |
| Payment methods | Existing configuration cards | Existing configuration cards | NOT TESTED | NOT TESTED | FAIL — tablet grouping and sync/error evidence remain. |
| Delivery settings | Existing fee/window/map settings | Existing settings flow | NOT TESTED | NOT TESTED | FAIL — responsive setting/map layout remains. |
| Manage stations | Existing station list | Existing list | NOT TESTED | NOT TESTED | FAIL — station list/detail and switch state remain. |
| Staff management | Existing staff list/dialogs | Existing list/dialogs | NOT TESTED | NOT TESTED | FAIL — directory and permission detail pane remain. |
| Subscription billing | Existing billing/status screen | Existing screen | NOT TESTED | NOT TESTED | FAIL — entitlement/status comparison remains. |
| Plan comparison | Existing plan cards | Existing plan cards | NOT TESTED | NOT TESTED | FAIL — tablet comparison table remains. |
| Password recovery activity | Existing recovery flow | Existing recovery flow | NOT TESTED | NOT TESTED | FAIL — bounded tablet form remains. |

## Group 5 current-source delta — 2026-10-10

For every row below, visual status is **BLOCKED** because no safe runtime is available; functional status is **NOT TESTED** unless explicitly stated. These rows update the older source descriptions above and do not imply release readiness.

| Screen / workflow | Current phone source | Current tablet source | Visual | Functional | Status / remaining work |
|---|---|---|---|---|---|
| Splash | Centered brand/loading screen | Existing centered composition | BLOCKED | NOT TESTED | Insets/large-window render remains unverified. |
| Intro onboarding | Existing paged flow | Content bounded to 1440dp | BLOCKED | NOT TESTED | No split layout or resize-state run. |
| Login | Existing auth flow | Form/brand column bounded to 640dp | BLOCKED | NOT TESTED | IME, credential chooser, and large-font behavior unverified. |
| Registration subscription | Google Play-backed plan chooser | Adaptive 380dp minimum plan-card grid | BLOCKED | NOT TESTED | Price, checkout, and layout interactions unverified. |
| Register | Existing registration form | Form bounded to 760dp | BLOCKED | NOT TESTED | OTP and resize-state behavior unverified. |
| Station setup | Existing setup/map flow | Form bounded to 900dp; existing paired fields retained | BLOCKED | NOT TESTED | Tablet map and permission behavior unverified. |
| MainContainer/navigation | Existing `NavigationSuiteScaffold` | Suite retained; NavHost bounded to 1600dp | BLOCKED | NOT TESTED | Breakpoints, insets, route restore, and resize unverified. |
| Dashboard | Existing ViewModel-backed metrics/activity | 1440dp frame; two/four KPI columns by content width | BLOCKED | NOT TESTED | Chart/activity grouping and live state unverified. |
| Orders queue | Existing paged queue | Selectable queue + summary pane at 840dp; saveable selected ID | BLOCKED | NOT TESTED | Paging, resize, and status refresh unverified. |
| Order detail | Existing status/payment/delivery actions | Content bounded to 1320dp | BLOCKED | NOT TESTED | Tablet action grouping and transitions unverified. |
| Quick walk-in POS | Existing phone sheet/sticky footer | Full-height tablet surface with persistent cart and shared VM totals/actions | BLOCKED | NOT TESTED | Payment, stock, duplicate-submit, and rendering unverified. |
| Inventory | Existing stock/movement list | Responsive two/four-column KPI grid | BLOCKED | NOT TESTED | Tablet list density and stock adjustment unverified. |
| Products | Existing product management | Adaptive 420dp minimum card grid | BLOCKED | NOT TESTED | Editing/archive/availability unverified. |
| Customers list | Existing searchable list | Adaptive 420dp cards + selected customer pane at 840dp | BLOCKED | NOT TESTED | Search, selection, and navigation unverified. |
| Customer detail | Existing balances/history/actions | Detail content bounded to 1320dp | BLOCKED | NOT TESTED | Account/history grouping and actions unverified. |
| Riders | Existing roster/actions | Adaptive 420dp cards, bounded content | BLOCKED | NOT TESTED | Assignment/status workflow unverified. |
| Reports | Existing filters/charts/refunds | Responsive executive KPI grid | BLOCKED | NOT TESTED | Chart/table workspace and financial presentation unverified. |
| Expenses | Existing summary/filters/forms | Adaptive 480dp expense-card grid; summary and filters span rows | BLOCKED | NOT TESTED | Add/edit/delete and ledger density unverified. |
| Settings | Existing category/action navigation | Content bounded to 1280dp | BLOCKED | NOT TESTED | No category/detail pane. |
| Station profile | Existing form/map actions | Form bounded to 1100dp | BLOCKED | NOT TESTED | Save/map/edit state unverified. |
| Customer ordering settings | Existing width-aware configuration | Existing behavior retained, content bounded to 1320dp | BLOCKED | NOT TESTED | QR/action and resize behavior unverified. |
| Payment methods | Existing configuration/cards | Form bounded to 1180dp | BLOCKED | NOT TESTED | QR upload/save/error behavior unverified. |
| Delivery settings | Existing fee/window/map settings | Form bounded to 1100dp | BLOCKED | NOT TESTED | Save/map responsiveness unverified. |
| Manage stations | Existing branch list/switch actions | 440dp adaptive card grid, bounded to 1440dp | BLOCKED | NOT TESTED | Switching, limits, and resize unverified. |
| Staff management | Existing staff actions | 420dp adaptive card grid, bounded to 1440dp | BLOCKED | NOT TESTED | Role/permission assignment unverified. |
| Subscription billing | Existing ViewModel-backed status | Content bounded to 1320dp | BLOCKED | NOT TESTED | Play renewal/cancel/status layout unverified. |
| Plan comparison | Existing Google Play-backed comparison | Adaptive 340dp grid, bounded to 1480dp | BLOCKED | NOT TESTED | Eligible prices/terms and feature comparison unverified. |
| Password recovery | Existing deep-link recovery activity | Form bounded to 600dp | BLOCKED | NOT TESTED | Deep link, keyboard, and completion flow unverified. |

## Shared component coverage

| Component family | Current source | Phone | Tablet | Test | Status |
|---|---|---|---|---|---|
| Window classes/content frames | `core/designsystem/components/AdaptiveLayouts.kt` | Shared primitives | Shared primitives | Boundary JVM test passed | NOT TESTED — rendering remains unverified. |
| Navigation suite | `feature/main/MainContainerScreen.kt` | Adaptive suite | Adaptive suite | No runtime navigation test | NOT TESTED |
| KPI and metric cards | Shared AquaHub metric card | Existing | Dashboard can use four columns | No screenshot | NOT TESTED |
| Product cards | `ProductsScreen.kt` | One adaptive cell | Multiple adaptive cells | No screenshot/action test | NOT TESTED |
| Search/text/form fields | `AquaHubComponents.kt` and features | Existing | Existing patterns in most screens | No keyboard/font-scale test | NOT TESTED |
| Dialogs/bottom sheets | Shared and feature dialogs | Existing sheets/dialogs | POS changes to a tablet surface; most complex flows still use existing presentation | No modal render test | BLOCKED — family adaptation and modal tests remain. |
| Order/customer detail panes | Feature screens | Existing route flow | Orders/customer summary panes; detail routes remain bounded pages | No resize test | BLOCKED — resize/state preservation not tested. |
| Loading/error/empty/sync states | Shared and feature components | Existing | Reused in current adaptive branches | No screenshot/semantics suite | NOT TESTED |
| Charts/tables/dropdowns | Shared and feature components | Existing | Existing behavior in most screens | No popup/render tests | FAIL — adaptive treatment remains. |
