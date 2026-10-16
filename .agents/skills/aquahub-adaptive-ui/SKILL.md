---
name: aquahub-adaptive-ui
description: Design, review, implement, and test responsive Jetpack Compose Material 3 interfaces for AquaHub Owner across phones, tablets, foldables, and resizable Android windows.
---

# AquaHub adaptive UI

Use this skill for UI discovery, review, redesign, implementation, or testing in AquaHub Owner. Treat every production-accessible screen and shared component as in scope when the request says “all screens”; enumerate them from the current source rather than relying on old maps.

Before changing UI, read the nearest `AGENTS.md`, the relevant current feature/context map, and the actual screen → ViewModel → use-case/repository flow. Preserve the established AquaHub water-service identity and all business actions. A visual redesign must not replace persisted behavior with demo data, remove controls, or alter business rules for layout convenience. For shared order, payment, product, inventory, customer, or station contracts, inspect the other app and backend as required by workspace instructions.

Base layout decisions on the app window’s current available space and posture, not device model, physical screen size, orientation labels, or fixed pixel dimensions. Prefer the maintained Compose Material 3 Adaptive window APIs and canonical layouts after verifying their versions and compatibility with this project. Keep the existing Navigation Compose architecture unless a separately approved navigation migration is justified; do not introduce Navigation 3 just to obtain adaptive layouts. Use `NavigationSuiteScaffold` where it integrates cleanly with the existing navigation state and dependencies.

Use the compact/medium/expanded window classes as the baseline (roughly <600dp, 600–839dp, and ≥840dp). Add content-level breakpoints only when a real screen needs them; they refine a class and do not replace window classes. Re-evaluate layouts during resizing, split-screen, rotation, and fold/unfold. Keep selected destinations, list selection, and unfinished form state stable across those changes.

Design a genuinely useful expanded presentation: appropriate max-widths, columns or panes, information density, and persistent task controls. Never make every card or form fill a large window by default. Use list-detail for searchable entities and queues when supported, a persistent cart for tablet POS, and supporting panes for contextual work. On phones, retain focused single-column flows and use modal bottom sheets for short contextual tasks; use full-screen routes for lengthy work. On wider windows, prefer a detail pane or right-sized dialog for complex work, while short confirmations may remain dialogs.

Use Material 3 components and semantic theme tokens. Keep colors, typography, spacing, shapes, elevations, icon sizes, and status meaning consistent. Use text/icon semantics rather than emoji as interface controls. Every interaction should have loading, empty, validation, offline/retry, error, and success behavior appropriate to its data source. Do not show success before a server-backed operation is committed, and prevent repeated submission where duplicate business operations are possible.

Preserve accessibility and Compose performance: meaningful labels and state descriptions, usable touch targets, font scaling, contrast, keyboard/IME operation, focus order, mouse/trackpad and stylus input, reduced-motion behavior where available, lifecycle-safe animations, stable lazy-list keys, and no unnecessary state reads or collectors in composition.

For every changed screen, validate the interaction and data path as well as the rendering. Add previews and UI/screenshot tests for representative compact and expanded layouts where useful. Record device/emulator/window sizes actually tested; do not mark a form factor or visual review PASS based on compilation alone. See the focused references below:

- [Design system](references/design-system.md) for tokens and shared component consistency.
- [Tablet patterns](references/tablet-patterns.md) for content organization by window size and workflow.
- [Component guidelines](references/component-guidelines.md) for forms, menus, dialogs, sheets, and state behavior.
- [UI testing checklist](references/ui-testing-checklist.md) for interaction, resizing, accessibility, and screenshot evidence.

Use current official Android adaptive guidance when API behavior or library compatibility is uncertain: <https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts> and <https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation>.
