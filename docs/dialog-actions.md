# Application dialog actions

Use `NativeMaterialButtonView` with the semantic button recipe and `forDialogAction()` for standalone confirmations. Use the shared `MaterialRoundedIconDrawable` glyphs (Close for Cancel, Check for confirmation, Delete for destructive actions), not app copies. The recipe reserves 128dp for short labels, 20dp horizontal content padding, a 20dp icon, and a minimum 48dp painted target. Longer labels retain their natural width.

Wrap each button with `NativeButtonTouchTarget` and put Cancel then confirmation in `NativeDialogActionLayout`. It measures complete labels and targets, preserves full padding, and stacks at narrow widths. It does not own callbacks or close buttons; the dialog owner retains disposal. Use the existing `NativeMergedActionLayout` for joined action surfaces, including permission prompts.

Use the scheme-aware switch constructor and `NativeToggleRow.bindToggle(..., scheme)` to carry the same surface policy as Reverb. A retained caller can pass its explicit display density. Dropdown selections should use `DropdownGroup`, which owns common field typography, symbols, popup policy, and lifecycle.

`NativeScreenSpacing` exposes the Settings gutter and section rhythm without absorbing system insets or floating-action clearance. `NativePageTransition` and the panel animation resources expose Reverb Main's 220ms panel motion for activity/scene hosts. `NativeScreenTransition` is the existing small retained route-entry motion; `ReverbScreenTransition` remains its compatibility name.

The focused `NativeDialogActionsIntegrationTest` checks actual short/long label measurement, narrow windows, large text, RTL, and theme-aware switch geometry. Run it with Android instrumentation; compilation alone is not physical verification.
