package smallthingz.ui.reverb

import android.view.View
import smallthingz.ui.text.NativeTypographyRole

/**
 * MainActivity309-325's existing ReverbMessageSheet. The Activity still owns actual permission facts
 * and ACTION_APPLICATION_DETAILS_SETTINGS; this presentation never requests or changes permissions.
 */
class ReverbPermissionDeniedSheet(
    anchor:View,
    environment:ReverbTheme,
    title: String,
    messageText: String,
    closeText: String,
    allowText: String,
    onDismiss:()->Unit,
    onOpenApplicationSettings:()->Unit,
):AutoCloseable {
    private var currentEnvironment=environment
    val dialog=createReverbActionSheet(anchor,environment,title,onDismiss)
    private val context=dialog.context
    private val host=dialog.contentContext
    private val message=createReverbSupportingParagraph(context, messageText, NativeTypographyRole.BODY_MEDIUM, environment)
    private val close=ReverbSheetButton(context,host,environment,false,closeText,onDismiss)
    private val allow=ReverbSheetButton(context,host,environment,true,allowText,onOpenApplicationSettings)
    private val actions=ReverbSheetActions(context,host,environment,listOf(close,allow))
    private val messageLease=message.bindPlainTextAccessibility(host.accessibility.cursor,host.accessibility.geometry,
        sourceNode={host.accessibility.sourceNode(message)}) {host.accessibility.sourceNode(message).present}
    private var disposed=false
    init {
        message.isScreenReaderFocusable=true
        dialog.setContent(message,actions,listOf(actions.hitLayout))
        bind(title, messageText, closeText, allowText, onDismiss, onOpenApplicationSettings)
    }
    fun bind(title: String, messageText: String, closeText: String, allowText: String,
             onDismiss: () -> Unit, onOpenApplicationSettings: () -> Unit) {
        dialog.bind(title, onDismiss)
        message.setText(messageText)
        close.bind(closeText,true,onDismiss)
        // Released confirm launches Settings without dismissing this sheet.
        allow.bind(allowText,true,onOpenApplicationSettings)
    }
    fun bindEnvironment(next:ReverbTheme) {
        if(disposed||currentEnvironment===next)return
        currentEnvironment=next
        dialog.bindReverbTheme(next)
        next.bindSupportingParagraph(message, NativeTypographyRole.BODY_MEDIUM)
        actions.bindEnvironment(next)
    }
    fun show()=dialog.show()
    override fun close() {
        if(disposed)return
        disposed=true
        var failure:Throwable?=null
        fun release(action:()->Unit) {try{action()}catch(error:Throwable) {
            val first=failure
            if(first==null)failure=error else if(first!==error)first.addSuppressed(error)
        }}
        release {dialog.dismiss()}
        release {actions.close()}
        release {messageLease.close()}
        failure?.let{throw it}
    }
}
