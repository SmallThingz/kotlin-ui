package smallthingz.ui.reverb

import android.view.View
import smallthingz.ui.components.NativeFloatingDialog
import smallthingz.ui.components.NativeFloatingDialogContent

/** Top-origin brand dialog. The host supplies its version, link labels and application actions. */
class ReverbAboutDialog(
    anchor: View,
    theme: ReverbTheme,
    details: ReverbAboutDetails,
    restoredScrollPosition: Int = 0,
    openRepository: () -> Boolean,
    onDismiss: () -> Unit,
) {
    private val panel = ReverbDesign.appearance.modals.floating
    private lateinit var content: ReverbAboutContentView
    private val dialog = NativeFloatingDialog(anchor, theme.density.density, onDismiss,
        statusBarProtection = ReverbDesign.statusBarProtection(anchor.context), shadowDp = panel.shadowDp) { context, runtime, dismiss ->
        val body = ReverbAboutContentView(context, theme, details, dismiss, openRepository, runtime, restoredScrollPosition)
        content = body
        NativeFloatingDialogContent(body, body.predictiveMotionTarget, body::bindWindowAccessibility, body::dispose)
    }

    val window get() = dialog.window
    fun scrollPosition(): Int = content.scrollPosition()
    fun bind(theme: ReverbTheme, details: ReverbAboutDetails, onDismiss: () -> Unit) {
        dialog.bind(onDismiss, panel.shadowDp)
        content.bind(theme, details)
    }
    internal fun onWindowDismissed(callback: () -> Unit) {
        dialog.setOnDismissListener { callback() }
    }

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
    fun requestDismiss() = dialog.requestDismiss()
}
