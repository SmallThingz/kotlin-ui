package smallthingz.ui.reverb

import android.app.Activity
import android.graphics.drawable.Drawable
import smallthingz.ui.components.NativePermissionDialog

/** App request content over the shared, single-presentation consent lifecycle. */
fun createReverbRequestPermissionDialog(
    activity: Activity,
    theme: ReverbTheme,
    icon: Drawable?,
    appName: CharSequence,
    summary: CharSequence,
    message: String,
    replyHint: String,
    denyLabel: CharSequence,
    allowLabel: CharSequence,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
    onCancel: () -> Unit,
): NativePermissionDialog {
    return NativePermissionDialog(ReverbPermissionSheet(activity, theme,
        icon?.mutate()?.apply { setTint(theme.palette.ink) }, appName, summary,
        denyLabel, allowLabel, onDeny, onAllow, onCancel, message, replyHint))
}

/** Site identity and authorization stay caller-owned; Reverb owns the permission icon tone. */
fun createReverbSitePermissionDialog(
    activity: Activity,
    theme: ReverbTheme,
    icon: Drawable?,
    origin: CharSequence,
    summary: CharSequence,
    denyLabel: CharSequence,
    allowLabel: CharSequence,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
    onCancel: () -> Unit,
): NativePermissionDialog = NativePermissionDialog(ReverbPermissionSheet(activity, theme,
    icon?.mutate()?.apply { setTint(theme.palette.muted) }, origin, summary,
    denyLabel, allowLabel, onDeny = onDeny, onAllow = onAllow, onCancel = onCancel))
