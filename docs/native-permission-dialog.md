# NativePermissionDialog

Use `smallthingz.ui.components.NativePermissionDialog` from an Activity on the main thread. Supply a trusted requester name and icon, a short localized description, localized action labels, and the app's `smallthingz.ui.components.NativeColorScheme`. `scopeBody` accepts an optional unattached Android `View`. The caller owns its selection, request identity, authorization and lifecycle.

```kotlin
lateinit var prompt: NativePermissionDialog
prompt = NativePermissionDialog(
    activity = this,
    colors = colors,
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

`show()` presents one centered platform dialog using `NativeUiAppearance.modals.permissionDialog` (360 dp maximum width by default). Content and the stacked Allow/Deny actions have separate scroll viewports, so both remain reachable under a reduced window height or large fonts. The optional handle and `showAtEdge` allow explicit edge placement without changing the default.

An accepted Deny or Allow sets busy before invoking its callback, preventing duplicate responses. The caller owns authorization and may clear busy after a recoverable failure. `setAllowEnabled(false)` survives busy transitions. `setError` and `setProgress` retain their own localized feedback. Back or a matched Escape cancels an idle focused dialog once; outside taps do not dismiss it. `dismiss()` is silent and terminal; create a new instance for each new request.

Pointer gestures are rejected after full or partial obscuration, and focus transitions retire pending input. Pointer, keyboard and accessibility decisions require current window ownership. The host owns the secure-window flag, scope state and any asynchronous operation lifetime.

## Layout references and vendoring

Shizuku's confirmation layout centers a decorative icon and permission question above its decisions. Dhizuku's request Activity shows the requesting app's icon and label in a centered alert. This independent Android View implementation draws on those layouts. [Shizuku confirmation layout](https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/res/layout/confirmation_dialog.xml) and [Dhizuku request Activity](https://github.com/iamr0s/Dhizuku/blob/bb933067e8b8510d01d4f6bbf4abe55b11139450/app/src/main/java/com/rosan/dhizuku/ui/activity/RequestPermissionActivity.kt)

The production addition is `src/main/kotlin/smallthingz/ui/components/NativePermissionDialog.kt`. A consumer vendoring this file into its NativeUI source tree can record the reviewed Git commit and the SHA-256 of that file. Vendor the library as a unit: the current implementation uses shared native button recipes, modal resources and window/input ownership helpers.

## Host regression tests

Run the opt-in Robolectric suite from the standalone library root. The host suite follows the current color-scheme and retained-button APIs. It explicitly models window focus in Robolectric and checks layout, busy state, input and cancellation. It does not establish physical focus, IME geometry, OEM window placement or spoken TalkBack behavior; those belong to `NativePermissionDialogIntegrationTest` on a real device. The consolidation did not execute either suite.

```sh
./gradlew -I tests/permission-dialog/host.gradle testDebugUnitTest \
  --tests smallthingz.ui.components.NativePermissionDialogTest
```
