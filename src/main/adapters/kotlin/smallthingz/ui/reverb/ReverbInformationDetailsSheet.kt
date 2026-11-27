package smallthingz.ui.reverb

import android.view.View
import androidx.core.view.ViewCompat
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.util.closePreservingPrimaryFailure

/**
 * Read-only full text using the shared scrollable sheet, with one localized Close action.
 * Caller owns message formatting and closes on navigation/data retirement. Owner close is silent;
 * user dismissal notifies once. Opening this sheet must not retire it on anchor window-focus loss.
 */
class ReverbInformationDetailsSheet(
    private val anchor: View,
    theme: ReverbTheme,
    title: String,
    message: String,
    closeLabel: String,
    onDismiss: () -> Unit,
) : AutoCloseable {
    private var closed = false
    private var dismissal: (() -> Unit)? = onDismiss
    private val dialog = createReverbActionSheet(anchor, theme, title, ::dismissFromUser)
    private val host = dialog.contentContext
    private val body = theme.paragraph(anchor.context, message, NativeTypographyRole.BODY_MEDIUM)
    private val closeButton = ReverbSheetButton(anchor.context, host, theme, false, closeLabel,
        ::dismissFromUser, leadingIcon = ReverbIcon.Close)
    private val actions = ReverbSheetActions(anchor.context, host, theme, listOf(closeButton))
    private val text = body.bindPlainTextAccessibility(host.accessibility.cursor, host.accessibility.geometry,
        { host.accessibility.sourceNode(body) }) { host.accessibility.sourceNode(body).present }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = close()
    }

    init {
        dialog.setDismissalAdmission { !closed && dialog.hasCurrentWindowFocus }
        dialog.setContent(body, actions, listOf(actions.hitLayout, host.hit(body, emptyList())), scrollable = true)
        ViewCompat.setAccessibilityPaneTitle(dialog.contentView, title)
        dialog.setOnDismissListener { close() }
        anchor.addOnAttachStateChangeListener(attachment)
    }

    fun show() {
        check(!closed)
        if (dialog.isShowing) return
        if (!reverbInformationInputAllowed(anchor)) {
            close()
            return
        }
        try {
            dialog.show()
        } catch (failure: Throwable) {
            throw requireNotNull(closePreservingPrimaryFailure(failure, ::close))
        }
    }

    private fun dismissFromUser() {
        if (closed || !dialog.isShowing || !dialog.hasCurrentWindowFocus) return
        val notify = dismissal
        close()
        notify?.invoke()
    }

    override fun close() {
        if (closed) return
        closed = true
        dismissal = null
        anchor.removeOnAttachStateChangeListener(attachment)
        var failure = closePreservingPrimaryFailure(null, dialog::dismiss)
        failure = closePreservingPrimaryFailure(failure, actions::close)
        failure = closePreservingPrimaryFailure(failure, text::close)
        failure?.let { throw it }
    }
}
