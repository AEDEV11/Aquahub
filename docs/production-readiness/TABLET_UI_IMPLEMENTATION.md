# AquaHub Owner Group 5 implementation

Status: **IN PROGRESS — tablet UI is not ready.** This is a local UI implementation record, not a release approval.

## Current inventory

- `Screen.kt`: 27 declarations. `More` is a compatibility alias rather than a distinct screen, leaving 26 routed destinations.
- Feature source: 26 `*Screen.kt` files, plus the exported `PasswordRecoveryActivity` flow.
- Additional task UI: the non-route `QuickWalkInSaleSheet` POS flow.
- Current total inventory: 27 production screen/route surfaces plus the POS task surface. Shared dialogs and components are tracked separately.
- Existing project navigation is Navigation Compose 2.10 with `NavigationSuiteScaffold`. It is preserved; no Navigation 3 migration or dependency change was made.

## Implemented in this slice

- Added shared width classes for compact, medium, expanded, large, and extra-large app windows; added content/form frames and a two-pane layout primitive.
- Added a JVM unit test for every configured width boundary; the production debug unit-test task passes.
- Dashboard content is bounded to 1440dp and moves its four real ViewModel KPI values from a 2×2 layout to one row when content width reaches 1100dp.
- Orders use a selectable paged queue/detail workspace at 840dp and wider. Selection is saveable across configuration/resizing; the full existing detail route retains all mutation controls.
- POS switches from the phone bottom sheet to a bounded tablet surface at 840dp. It shows a persistent cart with item quantity controls and ViewModel-derived subtotal, promotion savings, delivery fee, total, and loading/submit state.
- Products, customers, staff, riders, station management, expenses, and plan comparison use adaptive card grids. Customers and orders expose a selected-record summary pane on expanded windows.
- Inventory and report KPIs use responsive grids. Order/customer detail, expenses, settings, station profile, payment/delivery settings, subscription billing, onboarding, and recovery content have bounded reading widths.
- Registration plan selection uses a responsive grid and continues to display Google Play-provided offers. Main navigation remains `NavigationSuiteScaffold`; navigation content is bounded for large windows.

## Preserved behavior

The UI still calls the existing ViewModel and navigation callbacks. No order, pricing, subscription, inventory, or backend contract was changed. POS quantity changes and checkout reuse `WalkInSaleViewModel`; the tablet cart displays its current derived state rather than keeping a second copy of cart data.

## Remaining implementation and validation

Every route has been inventoried, but the implementation is partial. Remaining work includes purpose-built tablet workspaces for inventory movements, order detail, customer account/history, reports, settings categories, delivery/staff context, plus review of all dialogs/dropdowns. Reusable loading/error/empty states remain existing components. Route-level status and follow-up are recorded in `TABLET_UI_IMPLEMENTATION_MATRIX.md`.

## Validation status — 2026-10-10

- The first compile command `:app:compileDebugKotlin` was rejected because debug task matching is ambiguous across production and QA flavors.
- `:app:compileProductionDebugKotlin` — PASS.
- `:app:testProductionDebugUnitTest` — PASS, including adaptive width boundaries.
- `:app:assembleProductionDebug` — PASS; APK packaging completed.
- `:app:lintProductionDebug` — PASS on a bounded-worker, no-daemon retry: 0 errors and 91 warnings. The earlier lint finding for `UnusedBoxWithConstraintsScope` was fixed before this run.
- A clean Android 35 tablet AVD definition was created under `/tmp` at 1280×800, 160 dpi. The host has no KVM acceleration; software emulation remained offline to ADB during multiple prolonged first-boot attempts and was stopped. No app APK was installed and no screenshot or runtime interaction result is claimed.
- The existing Samsung SM-A366B was not launched, installed to, or modified because it may contain stale offline outbox work. Phone/tablet rendering, resizing, landscape interactions, TalkBack, keyboard, font-scale, and theme validation remain BLOCKED.
- Overall: **TABLET UI NOT READY / NOT FULLY VERIFIED**; Google Play production GO/NO-GO remains a separate decision.
