# AquaHub adaptive and tablet patterns

Choose patterns based on the available app window. Width classes are a baseline; content-level breakpoints may refine a layout where useful. Recheck height and posture for short landscape windows, split-screen, and foldables.

| Workflow | Compact window | Medium window | Expanded and larger windows |
|---|---|---|---|
| App navigation | Bottom navigation when the current top-level destinations fit | Rail or suite-selected navigation when usable | Persistent rail or drawer; keep labels and selected state clear |
| Dashboard | Single-column KPI cards and concise activity | Two-column KPI groups where cards remain readable | KPI grid, useful chart/activity panes, deliberate max-width and whitespace |
| Orders/deliveries | Searchable queue; focused detail route or sheet for short inspection | Queue with selected detail when both panes remain readable | List-detail queue with contextual actions and visible fulfillment progression |
| POS | Focused catalog → cart/checkout flow | Catalog and compact cart if touch targets remain usable | Catalog/grid beside a persistent cart, totals, payment, and checkout controls |
| Inventory/products | Search/filter and cards or rows | Wider rows with key stock/price fields | Table/grid with useful columns, filters, and selected-item detail/edit area |
| Customers | Searchable list and focused profile route | List plus detail if minimum widths permit | Customer list-detail, with history and account information grouped in detail |
| Staff/settings | Categorized single-column flows | Navigation plus focused content | Directory/category pane plus permission/settings detail pane |
| Reports/expenses | Focused cards and filters | Charts or summaries beside filters when useful | Wider comparison charts and tables with persistent filters, never stretched text |

## Implementation rules

- Obtain window class from a maintained window-aware API; do not branch on model name, `smallestScreenWidthDp`, deprecated display metrics, or orientation alone.
- Use `NavigationSuiteScaffold` where it preserves the app's existing navigation semantics. Preserve the current `NavController`/back stack; do not replace routes with a local enum-only navigator.
- Use list-detail/supporting-pane APIs only after checking the project's dependency versions and state/navigation integration. A custom `Row`/`BoxWithConstraints` can be safer for an existing flow, but must still preserve list selection and back behavior.
- Keep pane minimum widths and max content widths explicit. When the window falls below the two-pane threshold, render one pane and preserve the selected item so resize-back is lossless.
- Use hinges/folding features only when supported by the current WindowManager dependency and actual workflow value. Do not add a dependency solely for speculative posture handling.
- Keep tablet actions reachable with touch, keyboard, mouse, trackpad, and stylus. Avoid hover-only affordances.

## Workflow-specific acceptance

- POS: product selection and cart stay visible together on expanded windows; quantities, customer, payment, total, and commit state remain obvious. Checkout must still use the same idempotent business operation.
- Order queue: selection remains stable as the queue refreshes; status actions are contextual, visibly pending during persistence, and reflect only committed state.
- Inventory: stock, unit, price, and movement are distinguishable; destructive/adjustment actions retain confirmation and audit detail.
- Customer: account balance and payment history are not hidden behind a decorative summary; preserve exact money formatting and permissions.
