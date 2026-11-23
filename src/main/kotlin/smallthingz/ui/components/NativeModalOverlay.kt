package smallthingz.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.RequiresApi

/** Mounts a modal layer over the content children of an explicitly supplied window host. */
internal class NativeModalOverlay(
    private val host: FrameLayout,
    private val layer: FrameLayout,
    private val onBack: () -> Unit,
) : AutoCloseable {
    private class SiblingState(val view: View) {
        private val importance = view.importantForAccessibility
        private val focusable = view.focusable
        private val focusableInTouchMode = view.isFocusableInTouchMode
        private val descendantFocusability = (view as? ViewGroup)?.descendantFocusability

        fun suppress() {
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (view is ViewGroup) view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            view.isFocusableInTouchMode = false
            view.focusable = View.NOT_FOCUSABLE
        }

        fun restore() {
            view.importantForAccessibility = importance
            if (view is ViewGroup) view.descendantFocusability = checkNotNull(descendantFocusability)
            view.isFocusableInTouchMode = focusableInTouchMode
            view.focusable = focusable
        }
    }

    private val siblings = ArrayList<SiblingState>()
    private var previousFocus: View? = null
    private var previousTranslationZ = 0f
    private var unregisterBack: (() -> Unit)? = null
    private var shown = false
    private var closed = false

    fun show() {
        check(!shown && !closed && host.isAttachedToWindow && layer.parent == null)
        shown = true
        previousFocus = host.rootView.findFocus()
        previousTranslationZ = layer.translationZ
        try {
            var highestZ = layer.z
            for (index in 0 until host.childCount) {
                val sibling = host.getChildAt(index)
                siblings += SiblingState(sibling)
                highestZ = maxOf(highestZ, sibling.z)
            }
            siblings.forEach { it.suppress() }
            layer.z = highestZ
            host.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            if (!closed) {
                relayout()
                unregisterBack = registerBack()
                layer.requestFocus()
            }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun relayout() {
        if (closed || layer.parent !== host) return
        // The host is already laid out. Size the new input layer now, before the next traversal.
        val left = host.paddingLeft
        val top = host.paddingTop
        val width = (host.width - left - host.paddingRight).coerceAtLeast(0)
        val height = (host.height - top - host.paddingBottom).coerceAtLeast(0)
        val exact = View.MeasureSpec.EXACTLY
        layer.measure(View.MeasureSpec.makeMeasureSpec(width, exact), View.MeasureSpec.makeMeasureSpec(height, exact))
        layer.layout(left, top, left + width, top + height)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!shown) return
        try {
            unregisterBack?.invoke()
        } finally {
            unregisterBack = null
            try {
                if (layer.parent === host) host.removeView(layer)
            } finally {
                layer.translationZ = previousTranslationZ
                siblings.forEach { it.restore() }
                siblings.clear()
                val focus = previousFocus
                previousFocus = null
                if (focus != null && focus.isAttachedToWindow && focus.isShown && focus.windowToken == host.windowToken) {
                    focus.requestFocus()
                }
            }
        }
    }

    private fun registerBack(): (() -> Unit)? {
        val activity = findActivity(layer.context)
        val dismiss = { if (!closed) onBack() }
        val owner = layer.findViewTreeOnBackPressedDispatcherOwner()
            ?: activity as? OnBackPressedDispatcherOwner
        if (owner == null) {
            if (Build.VERSION.SDK_INT < 33) return null
            return ModalOverlayBackApi33.register(layer, activity, dismiss)
        }
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismiss()
        }
        owner.onBackPressedDispatcher.addCallback(callback)
        return callback::remove
    }

    private fun findActivity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext ?: return null
            if (base === current) return null
            current = base
        }
        return null
    }
}

@RequiresApi(33)
private object ModalOverlayBackApi33 {
    fun register(layer: View, activity: Activity?, onBack: () -> Unit): (() -> Unit)? {
        val dispatcher = layer.findOnBackInvokedDispatcher() ?: activity?.onBackInvokedDispatcher ?: return null
        val callback = OnBackInvokedCallback { onBack() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        return { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}
