# NativePermissionDialog

Use `smallthingz.ui.components.NativePermissionDialog` from an Activity on the main thread. Supply a trusted requester name and icon, a short localized description, localized action labels, and the app's `smallthingz.ui.controls.ControlPalette`. `scopeBody` accepts an optional unattached Android `View`. The caller owns its selection, request identity, authorization and lifecycle.

```kotlin
lateinit var prompt: NativePermissionDialog
prompt = NativePermissionDialog(
    activity = this,
    palette = palette,
    icon = requesterIcon,
    appName = requesterLabel,
    summary = requestSummary,
    denyLabel = denyLabel,
    allowLabel = allowLabel,
    scopeBody = selector,
    onDeny = {
        declineRequest()
        prompt.dismiss()
    },
    onAllow = {
        val selected = readScope()
        prompt.setError(null)
        prompt.setBusy(true)
        submitDecision(selected) { errorMessage, canRetry -> // Caller delivers these on the main thread.
            if (errorMessage == null) {
                prompt.dismiss()
            } else {
                prompt.setAllowEnabled(canRetry)
                prompt.setBusy(false)
                prompt.setError(errorMessage)
            }
        }
    },
    onCancel = { abandonRequest() },
)
prompt.show()
```

`show()` presents a centered, dimmed platform dialog. Its width follows the host window up to 400 dp. Its content wraps up to 560 dp or the available window height. The icon, heading, summary, selector and inline error scroll together. The two buttons sit below the scroll viewport, each with a 48 dp minimum touch target. They wrap localized labels and grow with the font size. Extremely small windows must still have room for the footer, its labels and the panel padding.

Deny and Allow invoke their callbacks and leave the prompt open. `setBusy(true)` disables both decisions and user cancellation. Capture the selected scope before asynchronous submission and manage the selector's editable state in the caller. `setError(message)` updates a polite live region. Pass `null` to clear it. Back, an outside tap or Escape close an idle prompt and invoke `onCancel`. The platform handles IME dismissal and the window requests resize for text editors. `dismiss()` closes silently, including during Activity teardown. Cancel pending work or ignore stale completions when the requesting Activity or request changes.

`setAllowEnabled(false)` disables Allow independently of busy state. This choice survives subsequent `setBusy` calls. For an expired request or consumed token, disable Allow before clearing busy and showing the terminal error. Deny and user cancellation are then available. `setAllowEnabled(true)` restores Allow when idle.

Allow rejects an entire touch gesture after a fully obscured event, and after a partially obscured event on Android 10 and later. Android exposes the partial-obscuration flag from API 29. Keyboard and accessibility activation use the native button action.

## Layout references and vendoring

Shizuku's confirmation layout centers a decorative icon and permission question above its decisions. Dhizuku's request Activity shows the requesting app's icon and label in a centered alert. This independent Android View implementation draws on those layouts. [Shizuku confirmation layout](https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/res/layout/confirmation_dialog.xml) and [Dhizuku request Activity](https://github.com/iamr0s/Dhizuku/blob/bb933067e8b8510d01d4f6bbf4abe55b11139450/app/src/main/java/com/rosan/dhizuku/ui/activity/RequestPermissionActivity.kt)

The production addition is `src/main/kotlin/smallthingz/ui/components/NativePermissionDialog.kt`. A consumer vendoring this file into its NativeUI source tree can record the reviewed Git commit and the SHA-256 of that file. It uses `NativeText`, `NativeTextStyle`, `NativeTextRole`, `ControlPalette` and `ControlStyle` from the existing library. The component requires no additional Android resources.

## Host regression tests

Run the opt-in Robolectric suite from the standalone library root. It measures and lays out the real View hierarchy, exercises native button gestures and dispatches cancellation to the platform dialog. Physical IME geometry, OEM window placement and spoken TalkBack output require device verification.

```sh
./gradlew -I tests/permission-dialog/host.gradle testDebugUnitTest \
  --tests smallthingz.ui.components.NativePermissionDialogTest
```
