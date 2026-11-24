package smallthingz.ui.reverb

import android.app.Activity
import android.graphics.drawable.Drawable
import android.widget.LinearLayout
import smallthingz.ui.components.NativePermissionDialog
import kotlin.math.roundToInt

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
    val widgets = ReverbApplicationWidgets(activity, theme)
    val scope = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(widgets.text(message), LinearLayout.LayoutParams(-1, -2))
        addView(widgets.text(replyHint, secondary = true).apply {
            val gap = theme.appearance.settingsSelectors.retentionSection.stackedGapDp
            setPaddingRelative(0, (gap * theme.density.density).roundToInt(), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
    }
    return NativePermissionDialog(activity, theme.palette,
        icon?.mutate()?.apply { setTint(theme.palette.ink) }, appName, summary,
        denyLabel, allowLabel, scope, onDeny, onAllow, onCancel, appearance = theme.appearance)
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
): NativePermissionDialog = NativePermissionDialog(activity, theme.palette,
    icon?.mutate()?.apply { setTint(theme.palette.muted) }, origin, summary,
    denyLabel, allowLabel, onDeny = onDeny, onAllow = onAllow, onCancel = onCancel,
    appearance = theme.appearance)
