package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import smallthingz.ui.controls.ControlPalette
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Two independently focusable integer thumbs on one shared track. [step] is anchored at [min].
 * [minGap] rounds up to whole steps and must fit between the reachable endpoints.
 * The value grid supplies tick positions. Set [NativeSliderStyle.tickSizeDp] to zero to hide them.
 * Bindings normalize silently. User previews include cancellation rollback. A changed touch or
 * key sequence commits once on release, and a changed accessibility action commits immediately.
 * Each thumb describes its exact integer value. Android's direct-set argument is a Float and
 * can represent only some large integers. Accessibility scroll actions move by one exact step.
 */
class NativeRangeSlider(
    context: Context,
    palette: ControlPalette,
    min: Int,
    max: Int,
    step: Int,
    lower: Int,
    upper: Int,
    lowerLabel: CharSequence,
    upperLabel: CharSequence,
    private var style: NativeSliderStyle = NativeSliderStyle(),
    minGap: Int = 0,
) : ViewGroup(context) {
    private val grid = RangeSliderGrid(min, max, step, minGap)
    private var bound = grid.bind(lower, upper)
    private var draft = bound
    val lower: Int get() = draft.first
    val upper: Int get() = draft.last
    var onRangePreview: ((Int, Int) -> Unit)? = null
    var onRangeCommitted: ((Int, Int) -> Unit)? = null

    private val density get() = resources.displayMetrics.density
    private val track = RangeSliderTrack(this, style, palette, grid)
    private val low = Thumb(true, lowerLabel)
    private val high = Thumb(false, upperLabel)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var edit: Edit? = null
    private var downX = 0f
    private var dragStarted = false
    private var touchedValue: Int? = null
    private var detaching = false
    private var ready = false

    // The object identity guards completion if a preview synchronously rebinds the control.
    private inner class Edit(var thumb: Thumb?, val pointer: Int = -1, val key: Int = -1, val time: Long = 0)

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(low)
        addView(high)
        low.nextFocusForwardId = high.id
        high.accessibilityTraversalAfter = low.id
        ready = true
    }

    fun applyPalette(palette: ControlPalette) = track.applyPalette(palette)

    /** Tick size controls visibility; tick positions always follow the min-anchored value grid. */
    fun applyStyle(style: NativeSliderStyle) {
        if (this.style == style) return
        this.style = style
        track.applyStyle(style)
        requestLayout()
        invalidate()
    }

    fun setValues(lower: Int, upper: Int) {
        retire()
        bound = grid.bind(lower, upper)
        show(bound, preview = false)
    }

    fun cancelInteraction() {
        retire()
        show(bound, preview = true)
    }

    private fun retire() {
        if (dragStarted) parent?.requestDisallowInterceptTouchEvent(false)
        dragStarted = false
        edit = null
        invalidate()
    }

    private fun show(values: IntRange, preview: Boolean) {
        if (draft == values) return
        draft = values
        positionThumbs()
        invalidate()
        if (isAttachedToWindow) {
            low.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            high.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        if (preview) onRangePreview?.invoke(lower, upper)
    }

    private fun finish(owner: Edit) {
        if (edit !== owner) return
        if (!canEdit(owner.thumb)) {
            cancelInteraction()
            return
        }
        retire()
        if (bound == draft) return
        bound = draft
        onRangeCommitted?.invoke(lower, upper)
    }

    private fun canEdit(thumb: Thumb? = null): Boolean =
        !detaching && isAttachedToWindow && isShown && windowVisibility == VISIBLE && isEnabled &&
            acceptsNativeInput() && (thumb == null || thumb.parent === this && thumb.isEnabled && thumb.isShown)

    private val trackLeft get() = paddingLeft + inset
    private val trackRight get() = (width - paddingRight - inset).coerceAtLeast(trackLeft)
    private val inset get() = (style.horizontalPaddingDp + style.thumbWidthDp / 2) * density
    private val rtl get() = layoutDirection == LAYOUT_DIRECTION_RTL

    private fun x(value: Int): Float {
        val span = grid.last.toDouble() - grid.min
        val fraction = if (span == 0.0) 0.0 else (value - grid.min.toDouble()) / span
        return (trackLeft + (trackRight - trackLeft) * if (rtl) 1.0 - fraction else fraction).toFloat()
    }

    private fun valueAt(x: Float): Double {
        val span = trackRight - trackLeft
        val fraction = if (span <= 0f) 0.0 else ((x - trackLeft) / span.toDouble()).coerceIn(0.0, 1.0)
        return grid.min + (grid.last.toDouble() - grid.min) * if (rtl) 1.0 - fraction else fraction
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val target = context.componentDp(SLIDER_MINIMUM_TOUCH_DP)
        val desiredWidth = paddingLeft + paddingRight + maxOf(target * 2, context.componentDp(style.minWidthDp))
        val desiredHeight = paddingTop + paddingBottom + maxOf(target, context.componentDp(style.minHeightDp),
            context.componentDp(maxOf(style.thumbHeightDp, style.trackHeightDp) + 2 * style.verticalPaddingDp))
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) desiredWidth
            else MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
        val handleWidth = target.coerceAtMost((width - paddingLeft - paddingRight).coerceAtLeast(0))
        val handleHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        for (index in 0 until childCount) getChildAt(index).measure(
            MeasureSpec.makeMeasureSpec(handleWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(handleHeight, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = positionThumbs()

    private fun positionThumbs() {
        for (index in 0 until childCount) {
            val thumb = getChildAt(index) as Thumb
            val w = thumb.measuredWidth
            val left = (x(thumb.value) - w / 2f).roundToInt()
                .coerceIn(paddingLeft, (width - paddingRight - w).coerceAtLeast(paddingLeft))
            thumb.layout(left, paddingTop, left + w, paddingTop + thumb.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val touching = edit?.takeIf { it.pointer >= 0 }?.thumb
        track.draw(canvas, trackLeft, trackRight, (height + paddingTop - paddingBottom) / 2f,
            x(lower), x(upper), touching === low, touching === high)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!canEdit() || grid.intervals == 0L) {
            cancelInteraction()
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelInteraction()
            downX = event.x
            val lowDistance = abs(event.x - x(lower))
            val highDistance = abs(event.x - x(upper))
            val thumb = when {
                lower == upper && lowDistance <= touchSlop -> null
                lowDistance < highDistance -> low
                highDistance < lowDistance -> high
                valueAt(event.x) < lower -> low
                else -> high
            }
            touchedValue = if (minOf(lowDistance, highDistance) <= touchSlop) (thumb ?: low).value else null
            edit = Edit(thumb, pointer = event.getPointerId(0), time = event.downTime)
            invalidate()
            return true
        }
        val owner = edit ?: return false
        if (owner.pointer < 0 || owner.time != event.downTime) return false
        val index = event.findPointerIndex(owner.pointer)
        when {
            index < 0 || event.actionMasked == MotionEvent.ACTION_CANCEL ||
                (event.actionMasked == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex) -> cancelInteraction()
            event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP -> {
                val physicalX = event.getX(index)
                val released = event.actionMasked == MotionEvent.ACTION_UP
                if (!released && !dragStarted) {
                    if (abs(physicalX - downX) < touchSlop) return true
                    dragStarted = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val pointerValue = valueAt(physicalX)
                val anchor = touchedValue
                val target = if (anchor == null || physicalX <= trackLeft || physicalX >= trackRight) pointerValue
                    else anchor + (pointerValue - valueAt(downX))
                val thumb = owner.thumb ?: (if (target < lower) low else high).also { owner.thumb = it }
                if (!canEdit(thumb)) {
                    cancelInteraction()
                    return false
                }
                thumb.requestFocus()
                if (edit === owner) thumb.move(target)
                if (released) finish(owner)
            }
        }
        return true
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = enabled != isEnabled
        super.setEnabled(enabled)
        for (index in 0 until childCount) getChildAt(index).isEnabled = enabled
        if (ready && changed) {
            if (!enabled) cancelInteraction()
            track.onEnabledChanged()
        }
        invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelInteraction()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && edit != null) cancelInteraction()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE && edit != null) cancelInteraction()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detaching = false
    }

    override fun onDetachedFromWindow() {
        detaching = true
        try {
            cancelInteraction()
        } finally {
            super.onDetachedFromWindow()
        }
    }

    private inner class Thumb(val isLower: Boolean, label: CharSequence) : View(this@NativeRangeSlider.context) {
        val value get() = if (isLower) lower else upper
        private val first get() = if (isLower) grid.min else (lower + grid.gap).toInt()
        private val last get() = if (isLower) (upper - grid.gap).toInt() else grid.last

        init {
            id = generateViewId()
            contentDescription = label
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        private fun clamped(requested: Double) = grid.snap(requested).coerceIn(first, last)

        fun move(requested: Double) {
            val next = clamped(requested)
            show(if (isLower) next..upper else lower..next, preview = true)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val target = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> value.toDouble() + if (rtl) grid.step else -grid.step
                KeyEvent.KEYCODE_DPAD_RIGHT -> value.toDouble() + if (rtl) -grid.step else grid.step
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> value.toDouble() + grid.step
                KeyEvent.KEYCODE_MINUS -> value.toDouble() - grid.step
                KeyEvent.KEYCODE_MOVE_HOME -> first.toDouble()
                KeyEvent.KEYCODE_MOVE_END -> last.toDouble()
                else -> return super.onKeyDown(keyCode, event)
            }
            if (!canEdit(this)) {
                cancelInteraction()
                return false
            }
            if (event.repeatCount == 0) {
                if (edit != null) return false
                // A fresh boundary press can move focus. An edited hold keeps its matching release.
                if (clamped(target) == value) return super.onKeyDown(keyCode, event)
                edit = Edit(this, key = keyCode, time = event.downTime)
            }
            if (!ownsKey(keyCode, event)) return false
            move(target)
            return true
        }

        private fun ownsKey(keyCode: Int, event: KeyEvent): Boolean =
            edit?.let { it.thumb === this && it.key == keyCode && it.time == event.downTime } == true

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            if (!ownsKey(keyCode, event)) return super.onKeyUp(keyCode, event)
            if (event.isCanceled) cancelInteraction() else finish(checkNotNull(edit))
            return true
        }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            if (!gainFocus && edit?.thumb === this && edit?.key != -1) cancelInteraction()
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (visibility != VISIBLE && edit?.thumb === this) cancelInteraction()
        }

        override fun getAccessibilityClassName(): CharSequence = SeekBar::class.java.name

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            AccessibilityNodeInfoCompat.wrap(info).stateDescription =
                NumberFormat.getIntegerInstance(resources.configuration.locales[0]).format(value.toLong())
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                first.toFloat(), last.toFloat(), value.toFloat())
            if (!canEdit(this) || first == last) return
            info.isScrollable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            if (value < last) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if (value > first) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            val target = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> value.toDouble() + grid.step
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> value.toDouble() - grid.step
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> arguments
                    ?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN)
                    ?.takeIf { it.isFinite() }?.toDouble() ?: return false
                else -> return super.performAccessibilityAction(action, arguments)
            }
            if (!canEdit(this) || edit != null || value == clamped(target)) return false
            val owner = Edit(this)
            edit = owner
            move(target)
            finish(owner)
            return true
        }
    }
}
