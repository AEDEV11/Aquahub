# AquaHub design-system guidance

## Preserve and consolidate

- Inspect the current `AquaHubTheme`, theme tokens, and `core/designsystem` components before adding styles. The established AquaHub palette and water-service identity remain the baseline.
- Prefer semantic Material 3 values (`primary`, `surface`, `error`, and their container/on-colors) over screen-specific literals. Add a token only when it represents a repeated semantic role.
- Keep light/dark behavior aligned with the app's supported theme modes. Never hard-code a color that becomes unreadable in another supported mode.
- Reuse a shared component when semantics match; do not create a universal component with many unrelated flags merely to reduce file count.

## Visual rhythm

- Use a small consistent spacing scale and apply it to page insets, section gaps, field groups, cards, and actions.
- Use content max widths for reading, forms, and detail panels. Let dashboards and data grids use more width only when extra columns or comparison improve the task.
- Use Material typography roles with clear hierarchy. Preserve adequate line height and allow user font scaling without clipping.
- Use consistent corner radii and restrained elevation. Reserve prominent color/elevation for hierarchy and actionable state, not decoration.
- Use Material icons or project assets with meaningful accessibility semantics; decorative icons should not create redundant announcements.

## AquaHub workflow consistency

- Keep order, payment, inventory, and sync status colors semantically stable across cards, tables, dialogs, and charts.
- Keep currency formatted from integer centavos or the existing money formatter. UI changes must not reintroduce floating-point money calculations.
- Keep pending/offline/sync-needed states visible but distinct from failed and completed states.
- Do not change business semantics to fit a compact card; use secondary detail, expansion, or a detail pane instead.
