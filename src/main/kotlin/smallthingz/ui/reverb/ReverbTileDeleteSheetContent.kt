package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeProgressActionView
import smallthingz.ui.components.NativeTrailingActionPair
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.util.closePreservingPrimaryFailure

/** Text and action geometry for the Settings tile-delete confirmation. */
class ReverbTileDeleteSheetContent(
    context: Context,
    private var environment: ReverbEnvironment,
    inputRoot: () -> View?,
    message: String,
    private val cancelLabel: String,
    private val deleteLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) : AutoCloseable {
    val body = ParagraphView(context, environment.appearance.typography.defaultTextStyle, environment.textDensity,
        environment.sharedFontResolver, environment.palette.onSurface).also { it.setText(message) }
    private val cancel = NativeProgressActionView(context, environment, inputRoot, action = onCancel)
    private val confirm = NativeProgressActionView(context, environment, inputRoot, action = onConfirm)
    private var canCancel = false
    private var canConfirm = false
    private var closed = false
    val actions = NativeTrailingActionPair(context, cancel, confirm) {
        bind(environment, canCancel, canConfirm)
    }

    fun bind(next: ReverbEnvironment, canCancel: Boolean, canConfirm: Boolean) {
        if (closed) return
        environment = next
        this.canCancel = canCancel
        this.canConfirm = canConfirm
        body.setTypography(next.appearance.typography.defaultTextStyle, next.textDensity, next.sharedFontResolver)
        body.animateTextColor(next.palette.onSurface)
        cancel.bind(cancelLabel, canCancel, next)
        confirm.bind(deleteLabel, canConfirm, next)
        actions.requestLayout()
    }

    override fun close() {
        if (closed) return
        closed = true
        val failure = closePreservingPrimaryFailure(null, cancel::close)
        closePreservingPrimaryFailure(failure, confirm::close)?.let { throw it }
    }
}
