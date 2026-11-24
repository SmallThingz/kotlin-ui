package smallthingz.ui.reverb

import android.view.View
import android.view.ViewTreeObserver

/** Retained content must not replay a pending key/press when its window regains focus. */
internal fun cancelInformationInputOnFocusLoss(view: View, cancel: () -> Unit): AutoCloseable {
    var observer: ViewTreeObserver? = null
    val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) cancel() }
    fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
        observer = null
        cancel()
    }
    fun attach() {
        observer = view.viewTreeObserver.also { it.addOnWindowFocusChangeListener(focus) }
    }
    val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = attach()
        override fun onViewDetachedFromWindow(v: View) = detach()
    }
    view.addOnAttachStateChangeListener(attachment)
    if (view.isAttachedToWindow) attach()
    return AutoCloseable { view.removeOnAttachStateChangeListener(attachment); detach() }
}
