package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import smallthingz.ui.components.NativeInputGate
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.acceptsNativeInput

/** Candidate browser presentation contract. The host retains navigation and authorization. */
internal fun View.browserPanelAdmitsInput(): Boolean {
    fun present() = isEnabled && isAttachedToWindow && isShown && hasWindowFocus()
    return present() && acceptsNativeInput() && present()
}

internal fun View.cancelBrowserPanelInput() {
    cancelPendingInputEvents()
    isPressed = false
    if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).cancelBrowserPanelInput()
}

/** Shared retirement and pending-input cancellation for retained browser panels. */
open class ReverbBrowserPanelLayout(context: Context) : LinearLayout(context), NativeInputGate, AutoCloseable {
    protected var panelClosed = false
        private set
    final override val acceptsNativeInput get() = !panelClosed

    init { orientation = VERTICAL; isFocusableInTouchMode = true; filterTouchesWhenObscured = true }

    private var blockedTouch = false
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) blockedTouch = false
        val obscured = event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or
            MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        if (panelClosed || obscured || !isShown || !hasWindowFocus()) {
            blockedTouch = true
            cancelBrowserPanelInput()
        }
        return if (blockedTouch) true else super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) { blockedTouch = true; cancelBrowserPanelInput() }
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) { blockedTouch = true; cancelBrowserPanelInput() }
    }
    override fun onDetachedFromWindow() {
        blockedTouch = true
        cancelBrowserPanelInput()
        super.onDetachedFromWindow()
    }
    override fun close() {
        if (panelClosed) return
        panelClosed = true
        cancelBrowserPanelInput()
        findFocus()?.clearFocus()
    }
}

internal fun browserPanelToolbarAction(context: Context, theme: ReverbTheme, label: String,
    icon: ReverbIcon, onAction: () -> Unit): ReverbToolbarActionView =
    ReverbToolbarActionView(context, theme.density.density, theme.palette, { null }, label, rolePresent = true).apply {
        setGlyph(ReverbIconDrawable(icon, theme.density.density, sampledIconTint), sampledIconTint)
        accessibilityAction.filterTouchesWhenObscured = true
        accessibilityAction.tooltipText = label
        bindToolbarAction(theme.density.density, theme.palette, true, NativeToolbarActionTone.MUTED) {
            if (browserPanelAdmitsInput()) onAction()
        }
    }
