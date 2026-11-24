package smallthingz.ui.reverb

import android.graphics.Rect
import android.os.Build
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
import smallthingz.ui.components.NativeInputGate
import smallthingz.ui.components.acceptsNativeInput
import kotlin.math.roundToInt

/**
 * One installed-app choice presentation. Reuses Reverb's anchored surface/motion and settings rows.
 * The application supplies identity and admission, never a content View, popup recipe, or appearance override.
 * Selection is delivered once after dismissal; cancellation and programmatic retirement are silent.
 */
class ReverbAppChoicePicker(
    private val anchor: View,
    private var theme: ReverbTheme,
    title: String,
    choices: List<ReverbAppChoice>,
    private val selectedId: String?,
    canSelect: () -> Boolean,
    onSelect: (String) -> Unit,
) : AutoCloseable {
    private val choices = choices.toList()
    private var admission: (() -> Boolean)? = canSelect
    private var selection: ((String) -> Unit)? = onSelect
    private var ended = false
    private var shown = false
    private var mounted = false
    private var answering = false
    private var focusReturnPending = false
    private var observing = false
    private var observer: ViewTreeObserver? = null
    internal var onRetired: (() -> Unit)? = null
    private val anchorBounds = Rect()
    private val visibleFrame = Rect()
    private var lastDirection = anchor.layoutDirection
    internal val rows = this.choices.map { choice ->
        ReverbAppChoiceRow(anchor.context, theme, trigger = false, action = ::choose).apply {
            bind(choice, choice.id == selectedId, theme)
        }
    }
    private val column = LinearLayout(anchor.context).apply {
        orientation = LinearLayout.VERTICAL
        rows.forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }
    private val scroll = object : ScrollView(anchor.context), NativeInputGate {
        private var dismissKey: Int? = null
        private var blockedGesture = false
        override val acceptsNativeInput get() = ownsWindowInput()

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            dismissKey = null
            blockedGesture = true
            rows.forEach { it.cancelPendingInputEvents() }
            super.onWindowFocusChanged(hasWindowFocus)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (!ownsWindowInput()) { dismissKey = null; return true }
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                    dismissKey = event.keyCode.takeIf { event.hasNoModifiers() }
                if (event.action == KeyEvent.ACTION_UP) {
                    val accepted = dismissKey == event.keyCode && !event.isCanceled && event.hasNoModifiers()
                    dismissKey = null
                    if (accepted) close()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (!ownsWindowInput()) { rows.forEach { it.cancelPendingInputEvents() }; return true }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) blockedGesture = false
            val obscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
                (Build.VERSION.SDK_INT >= 29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0)
            if (obscured) { blockedGesture = true; rows.forEach { it.cancelPendingInputEvents() } }
            return blockedGesture || super.dispatchTouchEvent(event)
        }
    }.apply {
        isFillViewport = false
        filterTouchesWhenObscured = true
        addView(column, FrameLayout.LayoutParams(-1, -2))
        ViewCompat.setAccessibilityPaneTitle(this, title)
    }
    internal val panel = ReverbDesign.appearance.modals.anchoredPanel.createPanel(scroll, theme.density, theme.palette)
    internal val window: PopupWindow = PopupWindow(panel, -2, -2, true).apply {
        panel.applyWindowStyle(this)
        isOutsideTouchable = true
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { close(); stopObserving() }
    }
    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused && focusReturnPending) restoreFocus()
    }
    private val layout = ViewTreeObserver.OnGlobalLayoutListener {
        if (!ended) {
            if (!anchorVisible() || !anchor.acceptsNativeInput() || !anchor.isEnabled) close()
            else if (anchorBounds != bounds() || visibleFrame != frame() || lastDirection != anchor.layoutDirection)
                if (!position()) close()
        }
    }

    init {
        require(this.choices.all { it.id.isNotEmpty() }) { "Choice identities must not be empty" }
        require(this.choices.map { it.id }.distinct().size == this.choices.size) { "Choice identities must be unique" }
        window.setOnDismissListener { finish() }
    }

    private fun anchorVisible() = anchor.isAttachedToWindow && anchor.isShown && anchor.windowVisibility == View.VISIBLE &&
        anchor.width > 0 && anchor.height > 0

    private fun ownsWindowInput() = !ended && !answering && window.isShowing && panel.isAttachedToWindow &&
        panel.isShown && panel.hasWindowFocus() && anchorVisible() && anchor.isEnabled && anchor.acceptsNativeInput()

    fun show() {
        if (ended || shown) return
        if (!anchorVisible() || !anchor.hasWindowFocus() || !anchor.isEnabled || !anchor.acceptsNativeInput() ||
            choices.isEmpty()) { close(); return }
        // Admission can synchronously dispose the trigger or replace this presentation.
        shown = true
        val allowed = try { admission?.invoke() == true } catch (failure: Throwable) { close(); throw failure }
        if (!allowed || ended || !anchorVisible() || !anchor.isEnabled || !anchor.acceptsNativeInput() ||
            !anchor.hasWindowFocus()) { close(); return }
        anchor.addOnAttachStateChangeListener(detach)
        observer = anchor.viewTreeObserver.also {
            it.addOnWindowFocusChangeListener(focus)
            it.addOnGlobalLayoutListener(layout)
        }
        observing = true
        try {
            if (!position()) { close(); return }
            scroll.doOnLayout {
                if (!ended && window.isShowing) {
                    val row = rows.firstOrNull { it.isSelected && it.isEnabled } ?: rows.firstOrNull { it.isEnabled }
                    row?.requestFocus()
                    row?.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
                    updatePivot()
                }
            }
            panel.expanded(true)
        } catch (_: WindowManager.BadTokenException) {
            close()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun bindTheme(theme: ReverbTheme) {
        if (ended || this.theme === theme) return
        // The shared panel retains its construction density; retire instead of mixing geometries.
        if (this.theme.density != theme.density) { close(); return }
        this.theme = theme
        rows.forEachIndexed { index, row -> row.bind(choices[index], choices[index].id == selectedId, theme) }
        panel.bindPalette(theme.palette)
        if (shown && !position()) close()
    }

    private fun bounds() = Rect().also { result ->
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        result.set(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
    }

    private fun frame() = Rect().also(anchor::getWindowVisibleDisplayFrame)

    private fun position(): Boolean {
        val frame = frame()
        if (frame.isEmpty) return false
        val density = theme.density.density
        // Same 112..280dp intrinsic width limits as NativeMenuRowView; identity text wraps.
        val width = anchor.width.coerceIn((112 * density).roundToInt(), (280 * density).roundToInt())
            .coerceAtMost(frame.width())
        val height = window.getMaxAvailableHeight(anchor).coerceAtLeast(1)
        panel.layoutDirection = anchor.layoutDirection
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        anchorBounds.set(bounds())
        visibleFrame.set(frame)
        lastDirection = anchor.layoutDirection
        if (window.isShowing) window.update(anchor, 0, 0, width, panel.measuredHeight)
        else {
            window.width = width
            window.height = panel.measuredHeight
            window.showAsDropDown(anchor, 0, 0, Gravity.START)
            mounted = true
        }
        updatePivot()
        panel.post { if (!ended) updatePivot() }
        return true
    }

    private fun updatePivot() {
        // Derive the pivot after the platform has resolved clipping and the actual anchor flip.
        val location = IntArray(2)
        panel.getLocationOnScreen(location)
        val width = panel.width.takeIf { it > 0 } ?: panel.measuredWidth
        val start = maxOf(anchorBounds.left, location[0])
        val end = minOf(anchorBounds.right, location[0] + width)
        val x = if (width > 0) (((start + end) / 2f - location[0]) / width).coerceIn(0f, 1f) else .5f
        panel.setAnimationPivot(x, if (window.isAboveAnchor) 1f else 0f)
    }

    private fun choose(id: String) {
        if (!ownsWindowInput() || choices.none { it.id == id && it.enabled }) return
        answering = true
        val allowed = try { admission?.invoke() == true } catch (failure: Throwable) { close(); throw failure }
        // The admission callback may replace this picker, disable the trigger, or remove its anchor.
        answering = false
        if (ended) return
        val callback = selection.takeIf { allowed && ownsWindowInput() }
        close()
        callback?.invoke(id)
    }

    override fun close() {
        if (ended) return
        finish()
        window.dismiss()
    }

    private fun finish() {
        if (ended) return
        ended = true
        admission = null
        selection = null
        rows.forEach { it.close() }
        focusReturnPending = mounted && anchorVisible() && anchor.isEnabled && anchor.acceptsNativeInput()
        if (focusReturnPending) restoreFocus() else stopObserving()
        val retired = onRetired
        onRetired = null
        retired?.invoke()
    }

    private fun restoreFocus() {
        if (!focusReturnPending) return
        if (!anchorVisible() || !anchor.isEnabled || !anchor.acceptsNativeInput()) {
            focusReturnPending = false
            stopObserving()
        } else if (anchor.hasWindowFocus()) {
            focusReturnPending = false
            stopObserving()
            anchor.requestFocus()
        }
    }

    private fun stopObserving() {
        if (!observing) return
        observing = false
        anchor.removeOnAttachStateChangeListener(detach)
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnWindowFocusChangeListener(focus)
            it.removeOnGlobalLayoutListener(layout)
        }
        observer = null
    }
}
