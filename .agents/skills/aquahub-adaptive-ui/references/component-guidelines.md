# Adaptive component guidelines

## Navigation and app bars

- Keep top-level destinations stable when changing between bottom bar, rail, and drawer.
- Expose the selected destination both visually and semantically; preserve the navigation back stack and deep-link behavior.
- Use app bars for page identity and high-value actions. Move overflow actions to menus without hiding frequent operational actions.

## Forms and menus

- Group fields by task and constrain form width on expanded windows. Use a purposeful two-column form only when field relationships and reading order remain clear.
- Use Material text fields with labels, supporting text, error text, and stable state holders. Preserve unsaved values across resize/rotation and do not clear fields on transient backend failure.
- Make exposed dropdown anchoring, selected value, menu scrolling, keyboard focus, and dismissal work at compact and expanded widths.
- Keep validation next to the field and summarize only cross-field or server errors separately. Focus the first invalid field when appropriate.
- Account for IME insets and scrolling; ensure primary actions remain reachable and keyboard Next/Done behavior follows the form order.

## Dialogs, sheets, and detail presentation

- Use a modal bottom sheet on compact windows for short contextual tasks such as filters, small edits, or quick details.
- Use a full-screen destination for long/multi-step work on phones. On expanded windows prefer a detail pane, side panel, or width-constrained dialog for complex editing.
- Keep short destructive confirmations in dialogs where their scope and consequence are explicit.
- Support scrolling, system insets, and keyboard appearance. Guard against accidental dismissal when edits are unsaved; provide explicit discard/save behavior.
- Do not show a mobile-height sheet centered or stretched on a tablet without re-evaluating its size and interaction pattern.

## Lists, grids, tables, and cards

- Use stable keys and preserve selected item and scroll position through refresh and resizing.
- Choose columns by task importance; let secondary fields move into detail rather than creating unreadably dense tables.
- Keep row/card actions discoverable and reachable; separate selection from destructive actions.
- Use maximum widths for prose/detail cards and multi-column layouts only where comparisons or scanning benefit.

## Feedback and motion

- Model initial load, refresh, empty, offline, retryable error, terminal error, and success distinctly.
- Show server-backed changes as pending until persistence succeeds. Retry actions must reuse the original idempotency identity where the operation could otherwise duplicate.
- Use brief state transitions for selection, expansion, and committed status changes. Keep motion interruptible, lifecycle-safe, and respectful of reduced-motion settings when available.
- Use snackbars for transient confirmation; keep important financial, sync, or destructive outcomes visible in durable screen state or history.
