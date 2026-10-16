# Owner UI/UX Audit

## Phase 3 device and UI evidence — 2026-10-12

The current connected QA run used the `Medium Phone API 35` emulator and `Pixel_Tablet` AVD, both API 35 with measured `PAGE_SIZE=4096`. The QA APK is isolated at `com.aesprt.aquahub.qa`; no production app or user data was removed. The directly invoked instrumentation suite passed **30/30 on each device**. The tablet cold launch first timed out while ART verified DEX under >90% emulator CPU pressure; after warmup, the focused UI suite passed 3/3 and full suite passed 30/30. The opt-in OFF-03/expense scenarios were invoked separately on the phone emulator.

Real OFF-03 transaction execution exercised the repository/worker with Android connectivity disabled and verified a durable receipt, recovery after force-stop, sync progress to COMMITTED and report confirmation. It did not automate taps through the full POS UI or capture a phone/tablet screenshot matrix. Expense hydration and a remote server edit were reflected correctly in Room and aggregate totals through the real Compose app dependency graph.

Samsung SM-A366B / Android 16 was not attached during Phase 3; previous 10/11 Samsung results remain historical. Pixel Tablet API 35 was run in this pass after ART warmup. A 16 KB runtime, older supported Android version, landscape/tablet full POS, rotation/process recreation UI, TalkBack, large font, dark mode, accessibility labels/contrast and reduced motion remain NOT VERIFIED. No screenshot evidence was captured.

### Screen disposition

| Screen / component | Phase 3 evidence | Remaining concern |
|---|---|---|
| Walk-in POS | Real repository cash sale succeeded offline; connected component suite passed. Pending receipt and confirmed revenue state stayed distinct. | No end-to-end UI tap sequence, rapid duplicate taps, adaptive tablet layout, or screenshot trace. |
| Offline recovery / sync state | Process-force-stop recovery and committed state verified in Room; existing UI test suite passed on emulator. | No reboot, 429/503 status presentation, lease-expired screen or full conflict-resolution interaction. |
| Dashboard / reports | Report repository confirmed the synchronized sale exactly once; pending cash returned to baseline. | Full production data comparison and date-range matrix not run. |
| Expenses | Hydration and remote-change convergence observed through Android repository/Room state. | Same device only; no new install/device or local pending-edit conflict. |
| Phone/tablet adaptation | Existing adaptive navigation source and prior focused receipt tests. | Tablet full instrumentation suite passed 30/30 after ART warmup; full tablet POS flow, landscape, accessibility and screenshot matrix remain open. |

The source findings in the prior screen inventory remain active unless specifically verified above; absence of a new visual failure is not a pass.


## OFF-03 UI verification update — 2026-10-11

Walk-in UI now reports network/offline status and presents saved offline cash receipts with immutable item, price, quantity, tender, timestamp and operation ID. Retry is offered only when bounded automatic attempts ended in `RETRY_LIMIT_REACHED`; permanent catalog, authorization and stock conflicts show owner-review guidance and do not invite an unsafe replay. Pending cash is visibly distinct from confirmed revenue.

The full QA instrumentation suite passed 29/29 on Samsung SM-A366B / Android 16. The focused Compose receipt/reconciliation suite passed on the same phone and Pixel Tablet / API 35. This verifies the recovery component on two form factors, not every POS screen, process recreation, layout size, TalkBack, offline network transition or full offline sale.

## Coverage and verdict

Navigation source contains splash, intro, login, subscription, registration, station setup, and main container routes. The main container adapts among Dashboard, Orders, Inventory, Customers, and More destinations using `NavigationSuiteScaffold`; `AdaptiveLayouts.kt` and width-class tests show adaptive intent. Feature source includes order/customer/product/rider/report/expense/station/customer-ordering/payment/delivery/staff/subscription screens. The current connected suite passed 30/30 on both API 35 phone and Pixel Tablet; it is not a full visual or accessibility audit.

The audit did not render every route, capture a screenshot matrix, or test TalkBack/font scaling. Consequently visual and accessibility conformity is **NOT VERIFIED**, not PASS. The QA connected suite passed 28 tests on Samsung; the focused recovery component also passed on Pixel Tablet. These are targeted tests, not a full visual audit.

## Screen-level findings

| Screen/component | Evidence | Finding / impact | Recommendation | Status |
|---|---|---|---|---|
| Splash/session router | `feature/auth/SplashViewModel.kt:32-44` | Local shell now requires a restored valid Supabase session and exact account binding; invalid credentials do not grant access. Offline process-death/session-renewal device matrix remains unverified. | Exercise offline restart, expired refresh and reboot without weakening session validation. | OFF-01 fixed locally; runtime gate remains |
| Login / phone OTP | `feature/auth/LoginViewModel.kt:86-93` | No client cooldown visible in source for resend; backend throttling/config not verified. | Show countdown and disable resend, retain accessible error state. | P2, AUTH-02 |
| Account deletion confirmation | `feature/settings/SettingsViewModel.kt:233-241` | Reauthenticated Google UID is checked against the original account before deletion and checked again at invocation. | Keep the destructive action disabled when identity is missing or mismatched; do not run a real-account deletion in QA. | SEC-01 fixed locally; provider execution unverified |
| POS offline recovery | `feature/orders/walkin/QuickWalkInSaleSheet.kt`; `WalkInSaleViewModel.kt` | Receipt and immutable line/tender details stay visible; permanent conflicts require review, retry-limit records can be manually requeued online. Focused component tests passed 3/3 on API 35 phone and Pixel Tablet. | Complete device layout/process-death/network/reconnect verification; do not retry permanent catalog/authorization conflicts automatically. | P1, OFF-03 gate |
| Orders queue freshness | `feature/orders/list/OrdersViewModel.kt:64,76-98`; `feature/orders/list/OrdersScreen.kt` | The list refreshes on initial flow setup/filter or station changes and manual pull only. There is no resume refresh or Realtime subscription; periodic background work lacks station context. A missed FCM can leave a new browser order absent from the queue while the ViewModel remains alive. | Add station-scoped recovery and run a missed-push disposable Android→Edge→Postgres regression. | P1, SYNC-04 |
| Reports | `data/repository/RoomReportRepository.kt:30-109` | Reports hydrate authoritative paid receipts and completed refunds before aggregating; confirmed offline sales are included once, while pending cash stays separate. Production ledger/date-range comparison remains. | Reconcile production reports read-only against known server fixtures before release. | FIN-01 fixed locally; production comparison open |
| Expenses | `data/repository/RoomExpenseRepository.kt:43-113`, `feature/expenses/ExpensesViewModel.kt:105-116` | Local save/outbox and server hydration are implemented; a freshness warning exists. Conflict resolution and cross-device/report consistency are not device-tested. | Add integration coverage for pending expense, remote merge, retry and second-device totals. | P2 verification, EXP-01 |
| Product/customer list | `RoomProductRepository.kt:308-315`, `RoomCustomerRepository.kt:438-446` | Refresh failure silently retains cache; the screen does not expose freshness or retry state. | Add “last updated” and retry/error affordance. | P2, SYNC-05 |
| Subscription screen | `feature/settings/subscription/` and Billing source | Device test class exists; actual Play offer eligibility, pending purchase, restore and state explanation not exercised. | Validate with Play license testers; distinguish pending, grace, held and expired states. | P1 gate |
| Tablet / landscape / large font | `AdaptiveLayouts.kt` and adaptive unit tests | Adaptive structures exist; no real tablet, landscape, 200% font, or split-screen review captured. | Screenshot/device matrix and overflow/keyboard tests. | NOT VERIFIED |
| System insets, dark theme, reduced motion | Compose theme/resources | Source has Material 3 conventions; no systematic rendered check. | Inspect edge-to-edge bars, contrast, touch target and motion settings on representative devices. | NOT VERIFIED |
| Dialogs, sheets, dropdowns, empty/error/loading states | Feature Composables | Inventory indicates broad reusable UI but no exhaustive interaction audit. | Exercise each destructive dialog, validation, retry, empty and loading state. | NOT VERIFIED |

## Usability and accessibility gate

No quantified contrast audit, TalkBack traversal, touch-target measurement, keyboard-only flow, font scale, animation/reduced-motion, or screen-reader announcement verification was performed. These are **NOT VERIFIED**. Required follow-up is a route-by-route checklist on compact phone, standard phone, tablet/landscape and large font, with screenshots and accessibility findings. Do not treat a Compose preview as a substitute for interaction testing.

## Test evidence

Current direct instrumentation passed 30/30 on the API 35 phone emulator and 30/30 on Pixel Tablet after warming the tablet emulator. Focused recovery UI coverage passed 3/3 on each. Samsung SM-A366B was not attached during this pass; 2026-10-11 Samsung results are historical. This does not establish broad UI quality, full POS tap flow or accessibility compliance.
