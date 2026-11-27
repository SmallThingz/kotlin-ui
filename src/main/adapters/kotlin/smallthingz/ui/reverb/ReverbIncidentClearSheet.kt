// Reverb application presentation source, retained under the Reverb project GPL-3.0 license.

package smallthingz.ui.reverb

import android.view.View
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.util.closePreservingPrimaryFailure

internal class ReverbIncidentClearSheet(
    anchor: View,
    theme: ReverbTheme,
    texts: ReverbIncidentTexts,
    onDismiss: () -> Unit,
    private val onConfirm: () -> Unit,
) : AutoCloseable {
    private val context = anchor.context
    private val title = texts.clearAll
    private val dialog = createReverbActionSheet(anchor, theme, title, onDismiss)
    private val host = dialog.contentContext
    private val body = theme.paragraph(context, texts.clearConfirmation, NativeTypographyRole.BODY_LARGE)
    private val confirm = ReverbSheetButton(context, host, theme, true, title, onConfirm, leadingIcon = ReverbIcon.Delete, errorColors = true)
    private val actions = ReverbSheetActions(context, host, theme, listOf(
        ReverbSheetButton(context, host, theme, false, texts.cancel, dialog::requestAnimatedDismiss, leadingIcon = ReverbIcon.Close),
        confirm,
    ))
    private val text = body.bindPlainTextAccessibility(host.accessibility.cursor, host.accessibility.geometry,
        { host.accessibility.sourceNode(body) }) { host.accessibility.sourceNode(body).present }
    private var closed = false

    init { dialog.setContent(body, actions, listOf(actions.hitLayout, host.hit(body, emptyList())), scrollable = true) }

    fun show() = dialog.show()

    fun setEnabled(enabled: Boolean) = confirm.bind(title, enabled, onConfirm)

    fun bindTheme(theme: ReverbTheme) {
        dialog.bindReverbTheme(theme)
        theme.bindParagraph(body, NativeTypographyRole.BODY_LARGE)
        actions.bindEnvironment(theme)
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure = closePreservingPrimaryFailure(null, dialog::dismiss)
        failure = closePreservingPrimaryFailure(failure, actions::close)
        failure = closePreservingPrimaryFailure(failure, text::close)
        failure?.let { throw it }
    }
}
