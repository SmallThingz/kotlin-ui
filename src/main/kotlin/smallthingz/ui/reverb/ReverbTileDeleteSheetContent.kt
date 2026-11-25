package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeButtonInputPolicy
import smallthingz.ui.components.NativeMergedActionLayout
import smallthingz.ui.components.forMergedActions
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
    private val recipe = environment.appearance.buttons.text.forMergedActions()
    private val input = NativeButtonInputPolicy(inputRoot = inputRoot)
    private val cancel = NativeMaterialButtonView(context, recipe, environment.textDensity,
        environment.sharedFontResolver, input, onCancel)
    private val confirm = NativeMaterialButtonView(context, recipe, environment.textDensity,
        environment.sharedFontResolver, input, onConfirm)
    private var closed = false
    val actions = NativeMergedActionLayout(context, listOf(cancel, confirm), environment.palette)

    fun bind(next: ReverbEnvironment, canCancel: Boolean, canConfirm: Boolean) {
        if (closed) return
        environment = next
        body.setTypography(next.appearance.typography.defaultTextStyle, next.textDensity, next.sharedFontResolver)
        body.animateTextColor(next.palette.onSurface)
        actions.bindPalette(next.palette)
        cancel.bindTypography(next.textDensity, next.sharedFontResolver)
        confirm.bindTypography(next.textDensity, next.sharedFontResolver)
        cancel.bind(cancelLabel, canCancel, next.palette.buttonPalette)
        confirm.bind(deleteLabel, canConfirm, next.palette.buttonPalette)
        actions.requestLayout()
    }

    override fun close() {
        if (closed) return
        closed = true
        val failure = closePreservingPrimaryFailure(null, cancel::close)
        closePreservingPrimaryFailure(failure, confirm::close)?.let { throw it }
    }
}
