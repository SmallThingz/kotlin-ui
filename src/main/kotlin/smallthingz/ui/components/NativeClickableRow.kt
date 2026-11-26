package smallthingz.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import smallthingz.ui.geometry.roundedGroupGeometry
import smallthingz.ui.input.phase.NativeClickPhaseHost
import smallthingz.ui.input.phase.ViewClickPhaseEndpoint
import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.RowIndication
import smallthingz.ui.interaction.view.RowIndicationSink
import smallthingz.ui.interaction.view.ViewClickController
import smallthingz.ui.interaction.view.isNativeClickActivationKey
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale

/** Common input, indication and terminal action ownership for clickable row controls. */
abstract class NativeClickableRow(
    context: Context,
    inputRoot: () -> View?,
) : ViewGroup(context), NativeClickPhaseHost {
    private val density get() = resources.displayMetrics.density
    private val indication = RowIndication.install(this, Color.BLACK) {
        readCachedAnimatorDurationScale(context)
    }
    private val input = ViewClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot get() = inputRoot()
        override val minimumTouchWidthPx get() = 48f * density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) =
            roundedGroupGeometry(width.toFloat(), height.toFloat(), density, 0f)
                .acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER,
                    minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, RowIndicationSink(indication) { x, y, point -> point.set(x, y) })
    final override val nativeClickEndpoint = ViewClickPhaseEndpoint(input)
    private var rowEnabled = false
    private var inputReady = false
    private var rowLongClick: (() -> Boolean)? = null
    protected var rowLongClickLabel: CharSequence? = null
        private set
    protected val hasRowLongAction get() = rowLongClick != null
    protected val rowInputEnabled get() = rowEnabled && isEnabled
    protected abstract val hasRowAction: Boolean
    protected abstract fun activateRow()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        setOnClickListener { if (canActivate()) activateRow() }
        inputReady = true
    }

    protected fun bindAction(enabled: Boolean, ink: Int) {
        rowEnabled = enabled
        indication.updateColor(ink)
        isEnabled = enabled
        isFocusable = enabled
        bindInput()
    }

    /** Explicit secondary action; the native controller owns long-press consumption. */
    internal fun bindRowLongClick(label: CharSequence?, action: (() -> Boolean)?) {
        val hadAction = hasRowLongAction
        if (!hadAction && action == null) return
        val previousLabel = rowLongClickLabel
        input.cancel()
        rowLongClick = action
        rowLongClickLabel = if (action == null) null else label
        bindInput()
        // The merged row owns Details semantics. The native long-click flag belongs to callers.
        if (isAttachedToWindow && (hadAction != hasRowLongAction || previousLabel != rowLongClickLabel)) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    private fun bindInput() = input.bind(NativeClickBinding(this, rowInputEnabled,
        onClick = { performClick() },
        onLongClick = if (hasRowLongAction) ({ performLongClick(); Unit }) else null))

    override fun setEnabled(enabled: Boolean) {
        val changed = isEnabled != enabled
        super.setEnabled(enabled)
        if (changed && inputReady) bindInput()
    }

    protected fun layoutRowIndication() = indication.onMeasured(width, height, density)

    /** An accepted tap routed by a parent instead of this row's click controller. */
    internal fun showTapIndication(x: Float, y: Float) {
        indication.press(x, y)
        indication.release()
    }

    protected fun retireRow() {
        rowLongClick = null
        rowLongClickLabel = null
        rowEnabled = false
        isEnabled = false
        isFocusable = false
        isClickable = false
        input.bind(null)
    }

    protected fun canActivate() = rowInputEnabled && hasRowAction && isAttachedToWindow && isShown

    override fun performClick() = canActivate() && super.performClick()
    override fun performLongClick(): Boolean {
        val action = rowLongClick ?: return super.performLongClick()
        return rowInputEnabled && isAttachedToWindow && isShown && hasWindowFocus() && action()
    }
    override fun onTouchEvent(event: MotionEvent) = input.touch(event)
    override fun onKeyDown(code: Int, event: KeyEvent) =
        if (isNativeClickActivationKey(code)) input.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent) =
        if (isNativeClickActivationKey(code)) input.key(event) else super.onKeyUp(code, event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val handled = super.onHoverEvent(event)
        return input.hover(event) || handled
    }
    override fun onFocusChanged(gain: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gain, direction, previous)
        input.focusChanged(gain)
    }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); input.cancel() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); input.attached(); bindInput() }
    override fun onDetachedFromWindow() { input.detached(); super.onDetachedFromWindow() }
}
