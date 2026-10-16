# Adaptive UI verification checklist

Record `PASS`, `FAIL`, `BLOCKED`, or `NOT TESTED` with actual evidence. Compilation is not visual verification.

## Window and device coverage

- [ ] Compact phone portrait and landscape.
- [ ] Medium-width window / constrained tablet.
- [ ] Expanded 7–8 inch and 10–11 inch tablet, portrait and landscape.
- [ ] Large tablet or resizable desktop-style window where available.
- [ ] Split-screen and runtime resize across breakpoints.
- [ ] Fold/unfold and tabletop posture when a foldable emulator/device exists.
- [ ] Small usable height and edge-to-edge/system-bar insets.

## Per-screen interaction

- [ ] No clipped, overlapped, excessively stretched, or unreachable content.
- [ ] Navigation destination, back stack, selected record, and scroll position behave through resize and rotation.
- [ ] Forms preserve edits, validate inline, scroll with the IME, and keep actions reachable.
- [ ] Dialogs/sheets use an appropriate presentation for the current window.
- [ ] Empty, loading, refreshing, offline, retry, permission, error, and success states are distinguishable.
- [ ] Critical business action reaches the existing ViewModel/repository path and displays success only after persistence.
- [ ] Repeated input does not duplicate orders, payments, stock adjustments, or other mutations.

## Accessibility and input

- [ ] Large font scale and display size do not clip key content or actions.
- [ ] TalkBack labels, roles, selected/expanded/error states, and traversal order are useful.
- [ ] Touch targets and contrast are sufficient; meaning is not conveyed by color alone.
- [ ] Keyboard tab/focus, Enter/Space activation, Escape/back, mouse/trackpad, and stylus entry work where relevant.
- [ ] Motion is brief, interruptible, and nonessential to understanding state.

## Evidence

- [ ] Add previews for representative compact and expanded states when they improve review.
- [ ] Use Compose UI tests for semantics, actions, and responsive branches; add screenshot tests where the project supports deterministic capture.
- [ ] Review actual screenshots at the relevant densities and document test environment, window size, font scale, and outstanding defects.
- [ ] Do not claim tablet/device/screenshot coverage when no corresponding device, emulator, or rendered artifact was used.
