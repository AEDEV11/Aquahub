# AquaHub Owner adaptive design system

Status: in progress. This document records shared layout rules and the components currently introduced for Group 5. Source remains authoritative.

## Window policy

Derive presentation from the width available to the current Compose window. Recalculate while resizing; do not use a device name, physical display size, or orientation as the layout switch.

| Width | Class | Baseline treatment |
|---|---|---|
| `<600dp` | Compact | One focused pane, bottom navigation where appropriate, compact app bars, short contextual sheets. |
| `600–839dp` | Medium | Wider single pane or compact two-column content where controls remain usable. |
| `840–1199dp` | Expanded | Navigation rail from `NavigationSuiteScaffold`; purposeful two-pane workspaces and adaptive grids. |
| `1200–1599dp` | Large | More useful columns and persistent context; constrain reading and form widths. |
| `≥1600dp` | Extra-large | Multi-pane work areas with explicit content bounds and no full-window prose/forms. |

Window height, system and navigation insets, IME, large font scale, and fold posture still require runtime validation. The current app does not yet consume fold features or declare posture-specific layouts.

## Shared tokens and components

- Preserve `AquaHubTheme`, semantic Material 3 color roles, `AquaHubSpacing`, `AquaHubShapes`, and established water-service blue/cyan identity.
- `AquaHubWindowWidthClass` and `aquaHubWindowWidthClass(width)` encode the five workspace-width bands and have a JVM unit test.
- `AdaptiveContentFrame` centers a screen body and bounds wide content. `AdaptiveFormFrame` offers a 640dp default reading width for forms. Both recompute from the current window constraints.
- `AdaptiveTwoPaneLayout` provides a measured-width two-pane primitive; screens keep their data/selection state outside the layout branch.
- `AdaptiveCardGrid` lays out related metric cards in a measured two/four-column grid. Dashboard and inventory KPI cards use it; report metrics use equivalent adaptive grouping.
- Product, customer, staff, rider, station, expense, plan, and subscription cards use adaptive grid cells where the content suits a card. Forms and detail routes use bounded widths instead of full-display text fields.
- Existing loading, empty, error, status, and form components remain the source for state presentation; the new layouts must not substitute preview data or optimistic success.

## Workflow patterns

- Dashboard: bounded workspace, compact 2×2 KPIs, four KPI cards in one row when there is room. Keep charts and activity groups readable.
- POS: phone uses the existing modal sheet and sticky summary. Expanded windows use a bounded full-height surface, product/order flow beside a persistent cart and committed checkout action. The existing `WalkInSaleViewModel` remains responsible for calculations and submission.
- Orders: phone taps continue to open the existing detail route. Expanded windows retain the selected local order beside the paged queue; full status, payment, and delivery actions stay in the detail route.
- Products: the catalog uses `GridCells.Adaptive(420.dp)` and keeps existing archive, availability, edit, pricing, promotion, and stock behavior.
- Expenses and station management: cards use adaptive grids while summary/filter or usage content spans the workspace; existing ViewModel actions remain wired.
- Forms and dialogs: bound widths and use a contextual side pane or destination for complex tablet workflows; retain short confirmation dialogs.

## Accessibility and interaction

- Give icon-only actions labels; preserve touch-size controls and visible selected/status state.
- Keep screen state in ViewModels or saveable state above adaptive branches so window resizing does not discard a selected record or active operation.
- Preserve font scaling, keyboard focus order, IME scrolling, mouse/trackpad, and TalkBack behavior.
- Keep motion brief and nonessential. Existing theme/component interaction motion remains; reduced-motion and device accessibility review are not yet verified.

## Still required

Complete dedicated tablet layouts for remaining detail/settings/report workflows, inspect all dialogs/dropdowns, verify light/dark and accessibility states, add representative Compose render tests, and validate compact, medium, expanded, landscape, and resized-window screenshots. The current JVM boundary test and production variant/unit suite pass, but runtime visuals are blocked. See `TABLET_UI_IMPLEMENTATION_MATRIX.md` and `TABLET_UI_TEST_MATRIX.md` for current coverage.
