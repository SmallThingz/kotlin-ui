# Permission dialog

NativePermissionDialog is a single-presentation native window. Supply the window's resolved `ControlTheme`,
requesting-app icon/name, concise request, optional retained scope View, localized action
labels and callbacks. The theme's appearance supplies typography and action recipes.
`NativePermissionDialogRecipe` owns sizing. Authorization, caller identity validation,
scope choice, token lifetime and persistent storage belong to the consumer.

The structure follows Shizuku's official permission prompt at
[b844bc4](https://github.com/RikkaApps/Shizuku/tree/b844bc491f1790c72328e1a8e5b2349f8978f0ea):
[confirmation_dialog.xml](https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/res/layout/confirmation_dialog.xml),
[button styles](https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/res/values/styles.xml),
and [permission activity](https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/java/moe/shizuku/manager/authorization/RequestPermissionActivity.kt).
It uses a centered request and vertically joined, equally toned Allow/Deny actions with
56dp minimum height. A compact icon/name identity row and optional scope body are explicit
consumer requirements. The panel uses the
[shared modal fill](appearance-controls.md#shared-modal-surfaces) and outlines
its rounded perimeter in the palette's border color. Both actions use
`primary` and `onPrimary`. Consumer-owned scope selections should use
`primary` for their selected accent.

Actions enter busy state before invoking the consumer once. Clear busy only for a deliberate
retry or terminal error. setAllowEnabled(false) survives busy transitions. Optional
setProgress and setError accept localized text. The supplied scope body remains caller-owned:
disable its choices while saving. Capture the request token and scope before asynchronous
work; update only the presentation that initiated that work.

Back or a matched, unhandled Escape cancels when idle. Outside taps do not discard the
request. Programmatic dismiss is silent, including Activity recreation. Cancellation is
synchronous and once-only; create a new instance for another presentation. Host FLAG_SECURE
is sampled immediately before the window is shown. If presentation fails, the exception is propagated
and the instance is retired silently. Obscured or partially obscured pointer streams are rejected until a fresh DOWN.

The window appearance selects `appearance.modals.permissionDialog`. Its optional grain seed, native
window theme, dim amount and animations are selected at construction. The color scheme belongs
to this presentation.

Content scrolls independently of actions; exceptionally short windows also scroll the action
area so large labels remain reachable. Native action keyboard/accessibility behavior is retained.

Compile the attached physical-device fixtures with compileDebugAndroidTestKotlin. Run
NativePermissionDialogIntegrationTest only on a coordinated actual Android device.
A compile pass is not rendered, IME, TalkBack, OEM, or physical input qualification.
