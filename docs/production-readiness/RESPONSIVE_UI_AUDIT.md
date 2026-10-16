# AquaHub Owner responsive UI and tablet audit

Snapshot: 2026-10-09, updated after the approved adaptive-shell change. This is source/architecture review, not rendered visual QA. Every production route and identified shared UI area below is inventoried. Since no device/emulator was connected and no screenshot tests exist, runtime behavior is not marked PASS. Main navigation now uses `NavigationSuiteScaffold`, but feature routes still lack dedicated tablet workspaces and list-detail/POS layouts, so the overall tablet release gate remains FAIL. This does **not** assert that every current phone screen is visually broken; actual screenshots remain NOT TESTED.

Group 4 adds the refund-obligation card inside the existing Reports screen and corresponding ViewModel/repository/RPC contract. It does not introduce a tablet-specific report pane, navigation/layout breakpoint, or screenshot test. The report UI compiles locally; visual behavior and hosted data/error states remain unverified. This leaves the responsive audit matrix and tablet release gate unchanged (FAIL / NOT TESTED).

Group 2 added phone-oriented payment/refund history and confirmation controls in Owner Order Details, and masked refund status in Customer tracking. The requested dedicated tablet layouts and runtime visual checks remain outstanding; the backend/API status integration has not been validated against hosted PostgREST.

## Current implementation and design-system evidence

- 27 `Screen` declarations in `core/navigation/Screen.kt` (including `More`, which is an alias, not a separate feature screen); 26 feature `*Screen.kt` composables, plus exported `PasswordRecoveryActivity`. The matrix also inventories the `MainContainer` shell and the non-route `QuickWalkInSaleSheet` task flow.
- 23 `@Preview` functions were found. No UI screenshot comparison test suite. Six Android instrumentation source files exist, only one is a Compose UI test; instrumentation was not run.
- `MainContainerScreen` now wraps navigation in Material `NavigationSuiteScaffold`, backed by the Material3 adaptive navigation suite artifact selected through the Compose BOM. This preserves Navigation Compose and delegates compact/wide navigation selection to the adaptive suite. No route-specific `WindowSizeClass`/adaptive-content policy, `ListDetailPaneScaffold`, dedicated tablet POS, or general content max-width system exists. A `BoxWithConstraints` appears only in `CustomerOrderingScreen` and does not establish feature-wide adaptivity.
- Common components in `core/designsystem/components`: AquaHubGlassCard/MetricCard/StatusChip/buttons/top bar/text/search/password/phone/currency fields/form and confirmation dialogs/loading/skeleton/empty state/section headers; subscription gate/banner/chips/plan comparison/usage; date/time pickers; charts; onboarding stepper; logo and StorageAssetImage. Task UI also includes `QuickWalkInSaleSheet`, order/payment dialogs, product/staff dialogs, report export dialog and dropdown menus. Their tablet presentation was not tested.
- No Android Compose/adaptive skill was available in the session catalog. Official Android adaptive guidance was reviewed. `android` CLI does not provide `skills add`; the remote skill was not provisioned. A project-specific skill now exists at `.agents/skills/aquahub-adaptive-ui/SKILL.md` with `design-system.md`, `tablet-patterns.md`, `component-guidelines.md`, and `ui-testing-checklist.md`. Do not force Navigation 3 solely to adopt examples from the current official skill page.

## Responsive policy proposed for implementation

Select by current usable app window bounds and posture, not device marketing name: compact <600dp; medium 600–839dp; expanded 840–1199dp; large 1200–1599dp; extra-large ≥1600dp. Recompute during resize/split-screen/fold; consider height and insets as well as width. Keep a single business/domain state source and adapt presentation. Use compact bottom navigation, medium rail if it fits, and expanded persistent rail/drawer; preserve route/back-stack/selected item. Bound form/chart widths; use real master-detail/workspace composition on tablet instead of stretched cards. Phone sheets may become a pane or route on tablet; short confirmations may remain dialogs. See [Android window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes) and [list-detail guidance](https://developer.android.com/develop/adaptive-apps/guides/list-detail).

## Screen coverage matrix

Status terms: NOT TESTED = no suitable render/device/workflow evidence. FAIL = known absence of a required intentional adaptive presentation from source. The FAIL status is about meeting the tablet acceptance criterion, not a claim of a particular pixel clipping defect. “Existing phone UI” describes source composition only; all runtime columns need screenshots.

| Screen / route | Existing phone source | Phone | Medium | Tablet | Landscape / resize | Functional | Finding and proposed layout/components/navigation/tests |
|---|---|---|---|---|---|---|---|
| Splash | Brand/loading entry | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Preserve centered bounded brand mark; adapt safe insets; test route transition/relaunch/large font. |
| Intro onboarding | Guided setup pages | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Center a constrained illustration/text/form column or intentional split; preserve step/form state through resize/back. |
| Login | Google/email auth form | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Bounded centered card or brand/form split; keyboard/IME/credential chooser/deep-link tests. |
| Subscription (registration step) | Plan/annual chooser | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Phone cards; tablet side-by-side comparison with fetched localized Play price/terms; no clipped plan features; licensed flow remains server authoritative. |
| Register | Account form | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Bounded form, tablet split only if content benefits; preserve validation/input through rotation and IME. |
| Station setup | Station provisioning form | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Bounded form/map/address pane; test permission denial, map resize and duplicate-submit protection. |
| MainContainer / app shell | Adaptive NavigationSuiteScaffold + FAB/sync banner | NOT TESTED | NOT TESTED | NOT TESTED (source-adaptive navigation only) | NOT TESTED | NOT TESTED | Shell now selects an adaptive Material navigation presentation; no render confirms actual bar/rail breakpoint or insets. Add viewport tests, route/back-stack restoration and keep FAB/sync states usable; all feature routes still need their own tablet content layouts. |
| Dashboard | KPI/quick actions/recent activity | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet KPI grid, bounded charts and adjacent activity; test stale/loading/offline/status refresh and chart descriptions. |
| Orders queue | Search/filter/list/cards | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet master-detail list plus order pane/table; persistent filters/status actions; test status persistence, refresh, cancel and notification. |
| Order detail | Progress, customer, items, payment and delivery controls | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Dedicated tablet pane/page; keep fulfillment/payment actions visible and correctly scoped; test rider assignment, out-for-delivery, paid proof, cancellation reason. |
| QuickWalkInSaleSheet (non-route task UI) | Full modal bottom sheet | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet POS catalog/product grid with persistent cart/payment summary; phone sheet/full task flow; test double tap, timeout retry, centavos, inventory and unpaid delivery. |
| Inventory | Stock list/movement shortcuts | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet stock table with search/filter and movement detail; test concurrent adjustments, sync badge and no-negative stock. |
| Products | Product list/cards and edit dialogs | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet table/grid + side edit detail; standard dropdown and validation; test price change/availability/stock. |
| Customers list | Searchable customer surface | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet directory plus customer detail pane; keep search/filter; test duplicate phone and sync/error states. |
| Customer detail | Balance/history/payment | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet profile with separate history/balance/activity panels; test balance arithmetic, paid receipt only, statement/refresh. |
| Riders | Rider directory/status/assignment | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet roster beside assignment/active order pane; keyboard-safe role/action menus; test availability and order assignment sync. |
| Reports | Filters, KPI and charts | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet multi-panel charts/tables with persistent date/station filters; test revenue excludes unpaid/cancelled and respects timezone. |
| Expenses | Expense entries/forms | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet ledger table, summary and bounded edit pane; test totals, date/receipt input and authorization. |
| Settings | Category rows and dialogs | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet category nav + detail pane; confirmation dialogs remain compact; test logout/account deletion and unsaved changes. |
| Station profile | Business/station identity form | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Bounded tablet form with preview/detail if useful; test active-station context and form restoration. |
| Customer ordering settings | Ordering configuration/action tiles | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet grouped settings, preview/help pane; test actual server state and offline/error feedback. |
| Payment methods | Toggle/configuration cards | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet grouped configuration with explanatory detail; ensure no UI claims imply payment collection; verify sync/error. |
| Delivery settings | Delivery fee/window/form | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet settings + map/preview side panel if present; date/time picker and keyboard stress tests. |
| Manage stations | Station list and plan limits | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet station list/detail with explicit plan usage; verify server quota, account isolation and switching. |
| Staff management | Staff list, role/create dialog | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet directory/detail/role pane; form instead of oversized dialog; test server role denial, plan cap and grace state. |
| Subscription billing | Current plan, status, purchase management | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet current entitlement beside plan/billing history; exact provider state; test restore/cancel/grace/expired across resize. |
| Plan comparison | Feature/limit matrix | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Tablet comparison table with readable horizontal/row semantics; phone stacked cards; verify accessibility, currency and offer terms. |
| PasswordRecoveryActivity | Exported deep-link recovery flow | NOT TESTED | NOT TESTED | FAIL | NOT TESTED | NOT TESTED | Bounded centered recovery form; test expired/forged/replayed PKCE links, IME, rotation and back. |

## Shared component audit coverage

| Component family | Source/evidence | Current status | Proposed adaptive behavior and verification |
|---|---|---|---|
| Navigation/app bars/FAB/sync banner | `MainContainerScreen.kt`, shared `AquaHubTopBar`, `NavigationSuiteScaffold` | Source shell adapts; tablet rendering/interaction NOT TESTED | Verify compact bar and wide rail rendering, brand/surface contrast, insets, badge, FAB placement, route selection and back-stack on resized windows. Route title/actions and content width still need screen-level policy. |
| Cards, KPI and status chips | `AquaHubComponents.kt`, dashboard/order screens | Tablet FAIL (unbounded stretch risk); NOT TESTED visually | Semantic tokens, max-width/grouping, consistent status meaning; screenshot/font-scale/contrast/TalkBack tests. |
| Text/search/password/phone/currency fields | `AquaHubComponents.kt` and per-feature forms | NOT TESTED | Field errors, IME, label/semantics, focus order; preserve input on resize and test large text. |
| Form/confirm dialogs and feature-specific dialogs | shared dialog components; orders/customers/products/staff/settings/billing | Tablet FAIL for complex editing (same dialog pattern reused); NOT TESTED | Phone sheet/dialog where task-sized; tablet pane/page for complex edit; block accidental dismissal with unsaved data; loading/retry/double-submit. |
| Quick sale sheet / payment settlement | `QuickWalkInSaleSheet.kt`, `OrderDetailScreen.kt`, `CustomerDetailScreen.kt` | Tablet FAIL; NOT TESTED | Promote to persistent POS/detail pane on tablet; maintain exact pending/paid state and clear error, proof and retry paths. |
| Dropdowns/filter/date/time pickers | inventory, products, riders, dashboard and shared date/time components | NOT TESTED | Correct anchor, scroll/keyboard/focus, popup placement near window edge; landscape/IME/split-screen tests. |
| Loading/error/empty/skeleton/snackbar | shared `AquaHubComponents.kt` and feature screens | NOT TESTED | Consistent inline refresh/error/retry/offline states without fake success; semantics and screenshot tests. |
| Charts/report export | `AquaHubCharts.kt`, `ReportsScreen.kt`, `ExportReportDialog.kt` | Tablet FAIL for deliberate multi-panel layout; NOT TESTED | Responsive axes/legends, accessible summaries and full-screen/export task layout; large font, landscape, save/cancel tests. |
| Subscription gates/plan comparison | `SubscriptionComponents.kt`, onboarding and billing | Tablet FAIL; contract defect confirmed separately | Adaptive feature comparison plus server state; verify all states and no optimistic entitlement. |
| Images/storage/logo/onboarding stepper | shared `StorageAssetImage.kt`, `AquaHubLogo.kt`, `OnboardingStepperHeader.kt` | NOT TESTED | Stable aspect/placeholder/loading/error and scalable step progress; offline/rotation/accessibility tests. |

### Task dialogs, sheets and pickers inventoried

These are not separate navigation routes, but are included in the UI scope and must receive the same compact/expanded and interaction treatment: `QuickWalkInSaleSheet`; `AquaHubFormDialog` and `AquaHubConfirmDialog`; order-detail confirmation/cancellation and `PaymentSettlementDialog`; customer-detail `RecordClientPaymentDialog`; product add/edit/delete dialogs and exposed dropdowns; staff `CreateStaffDialog`/role chooser; Settings logout and account-deletion dialogs; subscription billing confirmation; `ExportReportDialog`; `AquaHubDatePickerField`, `AquaHubDateRangePickerDialog`, `AquaHubTimePickerField` and `AquaHubTimePickerDialog`; inventory size dropdown and rider/dashboard action menus. Source declarations were inventoried; every item remains runtime NOT TESTED. Complex task flows should become tablet panes/routes; brief destructive confirmations may remain compact centered dialogs. All need keyboard/IME, scroll, dismissal/unsaved state, validation, loading, duplicate submit and accessibility checks.

## Viewport and workflow acceptance matrix (required after implementation)

| Environment | Required screens/flows | Status now |
|---|---|---|
| Compact phone portrait <600dp | Auth, dashboard, orders/detail, POS, products/inventory, customer, rider, settings | NOT TESTED (source only) |
| Compact phone landscape and short height | Login, POS, order payment/detail, product/customer forms, date/time | NOT TESTED |
| Medium 600–839dp | Main navigation, queue/detail, POS, inventory, settings | NOT TESTED; no specific adaptation found |
| Expanded 840–1199dp | Dashboard, POS split, master-detail, staff/rider, reports | FAIL (no deliberate adaptive architecture); screenshots NOT TESTED |
| Large 1200–1599dp and XL ≥1600dp | Content max widths, dashboard/report panels, settings detail | FAIL / NOT TESTED |
| Split-screen/resizable/fold posture | All open route/form and list selection state | NOT TESTED |
| Font scale/TalkBack/touch targets/contrast | Every route and modal | NOT TESTED |

## Acceptance criteria for closure

No screen is complete based on compilation alone. For each matrix row, implement and render compact plus medium/expanded behavior; record screenshots on representative phone/tablet; test landscape, resizing, keyboard and state restoration; verify every action still reaches the same authenticated authoritative data path; and run core business regression after UI changes. Every widget must expose appropriate semantics/touch target and loading/empty/offline/error behavior. No route may lose controls on tablet, and no business state may be presented as successful before server commit.

## Group 3 subscription UI source update — 2026-10-09

The plan comparison screen now uses an adaptive Compose grid for the available width and displays only Google Play-returned eligible prices/offer terms; plan purchase is unavailable when the eligible offer is missing rather than showing an invented fallback. This is a source-level improvement for the subscription route only. No compact/medium/expanded screenshot, tablet rendering, resize, font-scale, keyboard, TalkBack, or Play purchase interaction test was performed. This does not change the matrix's NOT TESTED / tablet FAIL statuses, and does not satisfy the all-screen adaptive release gate.

## Group 5 implementation update — 2026-10-10

The source now includes five available-window width bands, reusable content/form/grid/two-pane primitives, a bounded main navigation workspace, a dashboard KPI grid, selectable order/customer summary panes, and a tablet POS cart workspace. Responsive grids and bounded content were also applied across products, inventory metrics, reports, staff, riders, expenses, station management, subscription selection/comparison, account and station setup, customer/order details, settings, and password recovery. Existing `NavigationSuiteScaffold` and Navigation Compose remain; Navigation 3 was not introduced.

`TABLET_UI_IMPLEMENTATION_MATRIX.md` contains the current route/source delta. `TABLET_UI_TEST_MATRIX.md` records production Kotlin compilation and JVM tests as passed. A clean Android 35 tablet AVD was configured in `/tmp` at 1280×800, 160 dpi, but the host has no KVM acceleration and software emulation remained offline to ADB during a prolonged first boot. The emulator was stopped without installing the app. The existing Samsung device was not launched or modified due possible stale outbox replay. Phone/tablet rendering and accessibility checks remain BLOCKED. Many routes still need dedicated detail panes and modal adaptations, so tablet UI remains NOT READY / NOT FULLY VERIFIED; overall Google Play production GO/NO-GO remains separate.

### Group 5 verification retry — 2026-10-10

The new `UnusedBoxWithConstraintsScope` lint issue was corrected to use a plain `Box`, and `:app:compileProductionDebugKotlin`, `:app:testProductionDebugUnitTest`, `:app:assembleProductionDebug`, and a bounded `:app:lintProductionDebug` retry all passed afterward. A clean Android 35 tablet AVD was configured at 1280×800, 160 dpi. Multiple software-emulation boots stayed offline to ADB; the host has no KVM acceleration. The emulator was stopped without installing the app. Tablet rendering and screenshot evidence remain BLOCKED on this host.
