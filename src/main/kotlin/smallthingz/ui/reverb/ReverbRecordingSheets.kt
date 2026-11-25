package smallthingz.ui.reverb

import android.view.View

/** Presentation of one retained recording-info window. */
class ReverbRecordingInfoSheet(
    anchor: View,
    environment: ReverbTheme,
    title: String,
    closeLabel: String,
    displayName: String,
    values: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) : AutoCloseable {
    private val dialog = createReverbActionSheet(anchor, environment, title, onDismiss)
    private val body = ReverbRecordingInfoBody(anchor.context, dialog.contentContext, environment, displayName, values)
    private val closeButton = ReverbSheetButton(anchor.context, dialog.contentContext, environment, true, closeLabel, onDismiss)
    private val actions = ReverbSheetActions(anchor.context, dialog.contentContext, environment, listOf(closeButton))
    private var closed = false

    init { dialog.setContent(body, actions, listOf(actions.hitLayout, body.hitLayout), scrollable = true) }

    fun show() = dialog.show()

    fun bindEnvironment(theme: ReverbTheme) {
        if (closed) return
        dialog.bindReverbTheme(theme)
        body.bindEnvironment(theme)
        actions.bindEnvironment(theme)
    }

    fun bind(title: String, closeLabel: String, name: String, values: List<Pair<String, String>>, onDismiss: () -> Unit) {
        if (closed) return
        body.bind(name, values)
        dialog.bind(title, onDismiss)
        closeButton.bind(closeLabel, true, onDismiss)
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseSheetParts(listOf(dialog::dismiss, actions::close, body::close))
    }
}

/** Presentation of one retained editor. Its caller owns draft validation and accepted Rename work. */
class ReverbRecordingRenameSheet(
    anchor: View,
    environment: ReverbEnvironment,
    private val title: String,
    private val cancelLabel: String,
    initialName: String,
    initialError: String?,
    private val readName: () -> String,
    private val canDismiss: () -> Boolean,
    private val onEdit: (String) -> Unit,
    private val onDismiss: () -> Unit,
    private val onConfirm: () -> Unit,
) : AutoCloseable {
    private val dialog = createReverbActionSheet(anchor, environment.theme, title, onDismiss)
    private val field = ReverbRenameFieldView(anchor.context, dialog.contentContext, environment,
        initialName, initialError, readName, onEdit, onConfirm)
    private val cancel = ReverbSheetButton(anchor.context, dialog.contentContext, environment.theme, false,
        cancelLabel, onDismiss, leadingIcon = ReverbIcon.Close)
    private val confirm = ReverbSheetButton(anchor.context, dialog.contentContext, environment.theme, true,
        title, onConfirm, leadingIcon = ReverbIcon.Edit)
    private val actions = ReverbSheetActions(anchor.context, dialog.contentContext, environment.theme, listOf(cancel, confirm))
    private var closed = false

    init {
        dialog.setDismissalAdmission(canDismiss)
        dialog.setContent(field, actions, listOf(actions.hitLayout, field.hitLayout), scrollable = true)
    }

    fun show() = dialog.show()

    fun bindEnvironment(next: ReverbEnvironment) {
        if (closed) return
        dialog.bindReverbTheme(next.theme)
        field.bindEnvironment(next)
        actions.bindEnvironment(next.theme)
    }

    fun bind(name: String, error: String?, confirmationEnabled: Boolean) {
        if (closed) return
        dialog.refreshDismissalAdmission()
        field.bind(name, error, readName, onEdit, onConfirm)
        cancel.bind(cancelLabel, canDismiss(), onDismiss)
        confirm.bind(title, confirmationEnabled, onConfirm)
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseSheetParts(listOf(dialog::dismiss, actions::close, field::close))
    }
}
