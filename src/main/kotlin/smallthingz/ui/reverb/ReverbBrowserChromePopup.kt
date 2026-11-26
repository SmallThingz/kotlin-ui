package smallthingz.ui.reverb

import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import smallthingz.ui.components.NativeAnchoredMenuPanel
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeInputGate
import smallthingz.ui.components.acceptsNativeInput
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

/** One response can be claimed only after its requested dismissal. Cancellation is terminal. */
internal class ReverbBrowserChromeResponse {
    private var selected: String? = null
    private var retired = false
    private var dismissed = false
    fun choose(id: String): Boolean {
        if (retired || selected != null) return false
        selected = id
        return true
    }
    fun dismiss(deliberate: Boolean): Boolean {
        if (retired) return false
        if (!deliberate || selected == null) { cancel(); return false }
        dismissed = true
        return true
    }
    fun take(admitted: Boolean): String? {
        if (retired || !dismissed) return null
        val result = selected.takeIf { admitted }
        cancel()
        return result
    }
    fun cancel() { retired = true; selected = null }
    val answering get() = !retired && selected != null
}

/** Shared anchored browser menu. No application View or layout recipe is accepted. */
class ReverbBrowserChromePopup(
    private val anchor: View,
    private val theme: ReverbTheme,
    private val kind: ReverbBrowserChromeMenuKind,
    content: ReverbBrowserChromeMenuContent,
    canUse: () -> Boolean,
    onAction: (String) -> Unit,
    onRetired: () -> Unit,
) : AutoCloseable {
    private var admission: (() -> Boolean)? = canUse
    private var callback: ((String) -> Unit)? = onAction
    private var retiredCallback: (() -> Unit)? = onRetired
    private val response = ReverbBrowserChromeResponse()
    private var closed = false
    private var shown = false
    private var intentionalDismiss = false
    private var waitingForFocus = false
    private var observed: ViewTreeObserver? = null
    private val anchorBounds = Rect()
    private val visibleFrame = Rect()
    private var direction = anchor.layoutDirection
    private val wasFocused = anchor.hasFocus()
    private val actions = (content.navigation.map { it.id to it.enabled } + content.rows.mapNotNull { row ->
        row.id?.let { it to row.enabled }
    }).also { require(it.map { action -> action.first }.distinct().size == it.size) { "Menu action IDs must be unique" } }.toMap()
    private val body = ReverbBrowserChromeMenuBody(anchor.context, theme, content, ::choose).apply {
        if (kind == ReverbBrowserChromeMenuKind.NAVIGATION) useClickMenuSurface(theme)
    }
    private val scroll: ScrollView = object : ScrollView(anchor.context), NativeInputGate {
        private var blockedGesture = true
        private var dismissKey: Int? = null
        override val acceptsNativeInput get() = ownsInput()
        override fun onWindowFocusChanged(focused: Boolean) {
            super.onWindowFocusChanged(focused)
            blockedGesture = true; dismissKey = null; body.cancelBrowserPanelInput()
            if (!focused && !intentionalDismiss && response.answering) close()
        }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && ownsInput()) blockedGesture = false
            val obscured = event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
            if (obscured || !ownsInput()) {
                blockedGesture = true
                body.cancelBrowserPanelInput()
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            }
            return blockedGesture || super.dispatchTouchEvent(event)
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (!ownsInput()) { dismissKey = null; body.cancelBrowserPanelInput(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                    dismissKey = event.keyCode.takeIf { event.hasNoModifiers() }
                if (event.action == KeyEvent.ACTION_UP) {
                    val dismiss = dismissKey == event.keyCode && !event.isCanceled && event.hasNoModifiers()
                    dismissKey = null
                    if (dismiss) close()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }.apply {
        addView(body, FrameLayout.LayoutParams(-1, -2))
        ViewCompat.setAccessibilityPaneTitle(this, content.title)
    }
    internal val panel: NativeAnchoredMenuPanel = ReverbDesign.appearance.modals.anchoredPanel.createPanel(scroll, theme.density, theme.palette)
    internal val window: PopupWindow = PopupWindow(panel, -2, -2, true).apply {
        panel.applyWindowStyle(this)
        isOutsideTouchable = true
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = close()
    }
    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { if (it && waitingForFocus) complete() }
    private val layout = ViewTreeObserver.OnGlobalLayoutListener {
        if (!closed) {
            if (!anchorVisible() || !anchor.acceptsNativeInput() || !anchor.isEnabled) close()
            else if (window.isShowing && (anchorBounds != bounds() || visibleFrame != frame() || direction != anchor.layoutDirection))
                if (!position()) close()
        }
    }
    init {
        window.setOnDismissListener {
            if (closed) return@setOnDismissListener
            closePreservingPrimaryFailure(null) { body.cancelBrowserPanelInput() }?.let(::closeAfterFailure)
            if (response.dismiss(intentionalDismiss)) {
                waitingForFocus = true
                complete()
            } else finish()
        }
    }
    private fun anchorVisible() = anchor.isAttachedToWindow && anchor.isShown && anchor.windowVisibility == View.VISIBLE &&
        anchor.width > 0 && anchor.height > 0
    private fun ownsInput() = !closed && !response.answering && window.isShowing && panel.isAttachedToWindow &&
        panel.isShown && panel.hasWindowFocus() && anchorVisible() && anchor.isEnabled && anchor.acceptsNativeInput()
    private fun ownerAdmitted(): Boolean = try {
        admission?.invoke() == true && !closed && anchorVisible() && anchor.isEnabled && anchor.acceptsNativeInput()
    } catch (failure: Throwable) { closeAfterFailure(failure) }
    fun show(): Boolean {
        if (closed || shown) return false
        shown = true
        if (!anchorVisible() || !anchor.hasWindowFocus() || !ownerAdmitted()) { close(); return false }
        anchor.addOnAttachStateChangeListener(detach)
        observed = anchor.viewTreeObserver.also { it.addOnWindowFocusChangeListener(focus); it.addOnGlobalLayoutListener(layout) }
        return try {
            if (!position()) { close(); false } else {
                panel.expanded(true)
                true
            }
        } catch (failure: WindowManager.BadTokenException) {
            val cleanup = closePreservingPrimaryFailure(null, ::close)
            if (cleanup != null) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
                throw failure
            }
            false
        } catch (failure: Throwable) { closeAfterFailure(failure) }
    }
    private fun choose(id: String) {
        if (actions[id] != true || !ownsInput()) return
        if (!ownerAdmitted()) { close(); return }
        if (!ownsInput() || !response.choose(id)) return
        body.cancelBrowserPanelInput()
        panel.expanded(false) {
            if (closed) return@expanded
            // Focus lost during collapse cannot authorize a later dismissal response.
            if (!panel.hasWindowFocus() || !ownerAdmitted()) { close(); return@expanded }
            intentionalDismiss = true
            window.dismiss()
        }
    }
    private fun complete() {
        if (closed || !waitingForFocus || window.isShowing) return
        if (!anchorVisible() || !ownerAdmitted()) { close(); return }
        if (!anchor.hasWindowFocus()) return
        if (wasFocused) {
            try { anchor.requestFocus() } catch (failure: Throwable) { closeAfterFailure(failure) }
            if (!ownerAdmitted()) { close(); return }
            if (!anchor.hasWindowFocus()) return
        }
        val action = response.take(true)
        val deliver = callback
        // Retire all input before dispatch; the callback may synchronously open another menu.
        completeReverbBrowserChromeAction(
            retire = { finish(notify = false) },
            deliver = { if (action != null) deliver?.invoke(action) },
            notifyRetired = ::notifyRetired,
        )
    }
    private fun bounds() = Rect().also {
        val location = IntArray(2); anchor.getLocationOnScreen(location)
        it.set(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
    }
    private fun frame() = Rect().also(anchor::getWindowVisibleDisplayFrame)
    private fun position(): Boolean {
        val frame = frame()
        if (frame.isEmpty) return false
        val maximum = when (kind) {
            ReverbBrowserChromeMenuKind.NAVIGATION -> 248
            ReverbBrowserChromeMenuKind.SITE -> 312
            else -> 344
        }
        val width = minOf((frame.width() - dp(16)).coerceAtLeast(1), dp(maximum))
        val height = window.getMaxAvailableHeight(anchor, dp(4)).coerceAtLeast(1)
        panel.layoutDirection = anchor.layoutDirection
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        anchorBounds.set(bounds()); visibleFrame.set(frame); direction = anchor.layoutDirection
        val start = kind == ReverbBrowserChromeMenuKind.SITE
        val alignRight = (direction == View.LAYOUT_DIRECTION_RTL) == start
        val x = if (alignRight) -dp(8) else dp(8)
        if (window.isShowing) window.update(anchor, x, dp(4), width, panel.measuredHeight)
        else {
            window.width = width; window.height = panel.measuredHeight
            window.showAsDropDown(anchor, x, dp(4), if (start) Gravity.START else Gravity.END)
        }
        updatePivot()
        panel.doOnLayout { if (!closed && window.isShowing) updatePivot() }
        panel.post { if (!closed && window.isShowing) updatePivot() }
        return true
    }
    private fun updatePivot() {
        val location = IntArray(2); panel.getLocationOnScreen(location)
        val width = panel.width.takeIf { it > 0 } ?: panel.measuredWidth
        val start = maxOf(anchorBounds.left, location[0]); val end = minOf(anchorBounds.right, location[0] + width)
        val x = if (width > 0) (((start + end) / 2f - location[0]) / width).coerceIn(0f, 1f) else .5f
        panel.setAnimationPivot(x, browserChromePopupPivotY(window.isAboveAnchor))
    }
    override fun close() {
        // Retry the platform dismissal if an earlier attempt failed after logical retirement.
        if (closed) window.dismiss() else finish()
    }
    private fun finish(notify: Boolean = true) {
        if (closed) return
        closed = true; waitingForFocus = false
        response.cancel()
        admission = null; callback = null
        val observer = observed
        observed = null
        var failure = closePreservingPrimaryFailure(null, body::close)
        failure = closePreservingPrimaryFailure(failure) { anchor.removeOnAttachStateChangeListener(detach) }
        failure = closePreservingPrimaryFailure(failure) { observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus) }
        failure = closePreservingPrimaryFailure(failure) { observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout) }
        failure = closePreservingPrimaryFailure(failure) { window.setOnDismissListener(null) }
        failure = closePreservingPrimaryFailure(failure, window::dismiss)
        if (notify) failure = closePreservingPrimaryFailure(failure, ::notifyRetired)
        failure?.let { throw it }
    }
    private fun closeAfterFailure(failure: Throwable): Nothing =
        throw checkNotNull(closePreservingPrimaryFailure(failure, ::close))
    private fun notifyRetired() { val notify = retiredCallback; retiredCallback = null; notify?.invoke() }
    private fun dp(value: Int) = (value * theme.density.density).roundToInt()
}

internal fun browserChromePopupPivotY(aboveAnchor: Boolean) = if (aboveAnchor) 1f else 0f

/** The three-dot menu shares Reverb's single foreground surface, with adjoining action rows. */
private fun ReverbBrowserChromeMenuBody.useClickMenuSurface(theme: ReverbTheme) {
    val surface = ReverbDesign.appearance.modals.anchoredPanel.surfaceColor(theme.palette)
    val colors = theme.palette.settingsControls.copy(surface = surface, raised = surface)
    // Keep the original row handles, callbacks and close ownership. Removing the Settings
    // wrapper lets the existing anchored panel own the fill, foreground grain and outer clip.
    // removeRow also retires the group's divider foreground without replacing row feedback.
    for (index in childCount - 1 downTo 0) {
        val group = getChildAt(index) as? NativeGroupLayout ?: continue
        removeView(group)
        var insertion = index
        while (group.pointerContent.childCount > 0) {
            val row = group.pointerContent.getChildAt(0)
            group.removeRow(row)
            (row as? ReverbBrowserPanelSettingRow)?.applyPalette(colors)
            addView(row, insertion++, LinearLayout.LayoutParams(-1, -2))
        }
    }
}

/** Retirement always completes; a later callback failure must never replace the action's failure. */
internal fun completeReverbBrowserChromeAction(retire: () -> Unit, deliver: () -> Unit, notifyRetired: () -> Unit) {
    var failure = closePreservingPrimaryFailure(null, retire)
    if (failure == null) failure = closePreservingPrimaryFailure(null, deliver)
    closePreservingPrimaryFailure(failure, notifyRetired)?.let { throw it }
}
