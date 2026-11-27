# Anchored dropdown

## Interaction

The slot keeps the trigger's bounds and joined corners. The outline expands
around it, dims the background, and shrinks at either end of the option list.
Options retain their original order. The viewport respects the visible
display frame and system bar, cutout, and IME insets on API 30+.

A drag can cross several rows. A fling picks a whole-row destination at
release. The first tap interrupts the fling and settles it. Arrow keys,
accessibility scrolling, and localized Previous/Next buttons step one row
and announce its label on settling. The wheel exposes NumberPicker semantics,
Dismiss, and labeled buttons for fully revealed options. Motion follows the
system animator duration scale.

- **Progressive** browsing delivers changes as options reach the slot. A tap
  on an option closes the wheel. A tap during collapse can reopen it.
- **Explicit** browsing stages a candidate. Touch confirmation requires full
  visibility at press and release. A tap on a partial option centers it.
  With wheel focus, Enter, Numpad Enter, D-pad Center, or Space confirms the
  centered choice. Wheel touches during collapse are consumed.

With the default `COMMIT` policy, an outside tap, Back, or accessibility Dismiss
can deliver a changed candidate in either mode. Dismissing an untouched wheel
keeps the current value, even if unavailable. Use `EXPLICIT` with `CANCEL` to
require confirmation. Escape and lifecycle closure discard pending choices
and keep values already delivered.

## Integration

Use `DropdownGroup` for retained typed fields or
`NativeAnchoredDropdown(context, overlayHost, theme)` directly. Pass the
window's `ControlTheme` and a `FrameLayout` outside scrolling or clipping
content. `createField` uses `PROGRESSIVE` with `COMMIT`. Choose other policies
with `DropdownGroup` or the controller's `open` overload.
[Reverb and Email integration](reference-guide.md#dropdown-selection-and-dismissal)
describes how the apps use these policies.

Supply unique option values and localized labels. Rebind accepted state from
`onSelected`. Progressive fields need at least two options and a current value
from the list. Explicit fields can replace an unavailable value even with
one valid replacement. The expansion recipe is `NativeUiAppearance.anchoredDropdown`.

Opening captures labels and lays out the same-window modal synchronously,
measuring rows as they enter the reveal. The modal blocks keyboard and
accessibility access to its existing siblings in the host. Closing restores
their properties and requests prior keyboard focus if that View is still
attached, shown, and in the same window. For system Back, use an AndroidX
dispatcher owner on API 28–32. API 33+ can use the platform dispatcher.
The wheel also handles the local Back key.

Dismiss on screen stop and close fields and their controller or group when
their owners retire. A detached anchor or a change to anchor or viewport
geometry retires the open wheel.

## Regression tests

`NativeAnchoredDropdownPlanTest` checks option order. Unit tests also cover
viewport bounds, overflow cues, and fling path and timing. Run them from the
library root with `./gradlew testDebugUnitTest`.

On API 30+, the `smallthingz.ui.components.dropdown` instrumentation package
covers insets, focus and accessibility, gesture reversal, fling, lifecycle,
and Email choice behavior. Run timing cases with system animations enabled.
The lifecycle test can write light and dark window receipts when the
instrumentation argument `dropdownViewportCapture=true` is supplied.
The inertia test writes motion traces. Both store diagnostics in the test
APK's private files directory under `dropdown-regressions/`.
