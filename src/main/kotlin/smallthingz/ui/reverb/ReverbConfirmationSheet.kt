package smallthingz.ui.reverb

import android.view.View
import android.view.Window
import android.view.WindowManager
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.util.closePreservingPrimaryFailure

/** A single disclosure/authorization route using Reverb's shared bottom-sheet recipe.
 * Callbacks and authorization belong to the caller. close() is silent; a response is delivered
 * once, only while this window owns input. No state or callback survives retirement.
 * Destructive confirmations opt into Reverb's destructive button recipe and Delete glyph.
 */
class ReverbConfirmationSheet(
    anchor: View,
    theme: ReverbTheme,
    title: String,
    message: String,
    cancelText: String,
    confirmText: String,
    destructive: Boolean = false,
    private val onCancel: () -> Unit,
    private val onConfirm: () -> Unit,
) : AutoCloseable {
    /** Retain the original positional/trailing-lambda constructor and JVM signature. */
    constructor(
        anchor: View,
        theme: ReverbTheme,
        title: String,
        message: String,
        cancelText: String,
        confirmText: String,
        onCancel: () -> Unit,
        onConfirm: () -> Unit,
    ) : this(anchor, theme, title, message, cancelText, confirmText, false, onCancel, onConfirm)

    private var closed=false
    private var enabled=true
    private val dialog=createReverbActionSheet(anchor,theme,title,::completeCancellation)
    private val host=dialog.contentContext
    private val body=theme.paragraph(anchor.context,message,NativeTypographyRole.BODY_LARGE)
    private val cancel=ReverbSheetButton(anchor.context,host,theme,false,cancelText,{respond(false)},leadingIcon=ReverbIcon.Close)
    private val confirm=ReverbSheetButton(anchor.context,host,theme,true,confirmText,{respond(true)},
        leadingIcon=if(destructive)ReverbIcon.Delete else ReverbIcon.Check,errorColors=destructive)
    private val actions=ReverbSheetActions(anchor.context,host,theme,listOf(cancel,confirm))
    private val text=body.bindPlainTextAccessibility(host.accessibility.cursor,host.accessibility.geometry,
        {host.accessibility.sourceNode(body)}) {host.accessibility.sourceNode(body).present}

    init {
        dialog.protectSensitiveInput()
        dialog.setDismissalAdmission { !closed&&dialog.hasCurrentWindowFocus }
        dialog.setContent(body,actions,listOf(actions.hitLayout,host.hit(body,emptyList())),scrollable=true)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Window access is for host security/test admission, never for app-local presentation. */
    val window:Window? get()=dialog.window
    val canRespond:Boolean get()=!closed&&enabled&&dialog.isShowing&&dialog.canAcceptSensitiveAction

    fun show() {check(!closed);dialog.show()}

    /** Same current-window admission as pointer, keyboard and accessibility actions. */
    fun respond(accept:Boolean):Boolean {
        if(!canRespond)return false
        close()
        if(accept)onConfirm() else onCancel()
        return true
    }

    private fun completeCancellation() {
        if(closed||!dialog.isShowing||!dialog.hasCurrentWindowFocus)return
        close()
        onCancel()
    }

    override fun close() {
        if(closed)return
        closed=true
        enabled=false
        var failure=closePreservingPrimaryFailure(null,dialog::dismiss)
        failure=closePreservingPrimaryFailure(failure,actions::close)
        failure=closePreservingPrimaryFailure(failure,text::close)
        failure?.let {throw it}
    }
}
