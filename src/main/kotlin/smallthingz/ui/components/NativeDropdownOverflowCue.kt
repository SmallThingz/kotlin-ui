package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.widget.Button
import smallthingz.ui.R
import smallthingz.ui.input.DEFAULT_MINIMUM_TOUCH_TARGET_DP
import smallthingz.ui.interaction.view.RowIndication
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** The wheel paints the cue and owns the pointer; this child owns its hit bounds and button action. */
internal class NativeDropdownOverflowCue(
    context: Context,
    private val direction: Int,
    private val density: Float,
    private val ink: Int,
    onStep: (Int) -> Unit,
) : View(context) {
    private val bounds = NativeDropdownOverflowCueBounds(direction,
        (DEFAULT_MINIMUM_TOUCH_TARGET_DP * density).roundToInt().coerceAtLeast(1))
    private var indication: RowIndication? = null

    init {
        contentDescription = context.getString(if (direction < 0) R.string.native_dropdown_previous_option
            else R.string.native_dropdown_next_option)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { onStep(direction) }
        setAvailable(false)
    }

    /** Use inward clip edges. The wheel also applies its rounded path to pointer admission. */
    fun place(width: Int, top: Float, bottom: Float, slotCenter: Float, slotHeight: Int,
        bothVisible: Boolean, visible: Boolean) {
        val available = bounds.update(width, top, bottom, slotCenter, slotHeight, bothVisible, visible)
        if (available) {
            val targetWidth = bounds.right - bounds.left
            val targetHeight = bounds.bottom - bounds.top
            if (targetWidth != measuredWidth || targetHeight != measuredHeight) {
                measure(MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY))
            }
            if (left != bounds.left || this.top != bounds.top || right != bounds.right || this.bottom != bounds.bottom) {
                layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }
        if ((visibility == VISIBLE) != available) setAvailable(available)
    }

    fun contains(x: Float, y: Float): Boolean = visibility == VISIBLE && isEnabled && bounds.contains(x, y)

    fun pressAt(x: Float, y: Float) {
        if (!isAttachedToWindow || !contains(x, y) || isPressed) return
        val feedback = indication ?: RowIndication.install(this, ink) {
            readCachedAnimatorDurationScale(context)
        }.also {
            it.onMeasured(width, height, density)
            indication = it
        }
        isPressed = true
        feedback.press(x - left, y - top)
    }

    fun releasePress() {
        if (!isPressed) return
        isPressed = false
        indication?.release()
    }

    fun cancelPress() {
        if (!isPressed) return
        isPressed = false
        indication?.let {
            it.resetForDetach()
            it.setVisible(isAttachedToWindow && isShown, false)
        }
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun performClick(): Boolean =
        isAttachedToWindow && isShown && isEnabled && super.performClick()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        indication?.onMeasured(w, h, density)
    }

    override fun onCancelPendingInputEvents() {
        super.onCancelPendingInputEvents()
        cancelPress()
    }

    override fun onDetachedFromWindow() {
        isPressed = false
        super.onDetachedFromWindow()
    }

    private fun setAvailable(available: Boolean) {
        if (!available) {
            isPressed = false
            indication?.resetForDetach()
        }
        visibility = if (available) VISIBLE else INVISIBLE
        isEnabled = available
        isClickable = available
        isFocusable = available
    }
}

/** Reused wheel-local bounds with an uncovered option lane between the two edge targets. */
internal class NativeDropdownOverflowCueBounds(private val direction: Int, private val targetPx: Int) {
    init {
        require(direction == -1 || direction == 1)
        require(targetPx > 0)
    }

    var left = 0
        private set
    var top = 0
        private set
    var right = 0
        private set
    var bottom = 0
        private set
    private var placed = false

    fun update(width: Int, clipTop: Float, clipBottom: Float, slotCenter: Float, slotHeight: Int,
        bothVisible: Boolean, visible: Boolean): Boolean {
        placed = false
        if (!visible || width <= 0 || slotHeight <= 0 || !slotCenter.isFinite() ||
            !clipTop.isFinite() || !clipBottom.isFinite()) return false
        val first = ceil(clipTop).toInt().coerceAtLeast(0)
        val end = floor(clipBottom).toInt()
        if (end <= first) return false
        val available = end - first
        // Keep the middle third free when both cues contend with a short option viewport.
        // A single cue grows to its normal target as soon as one full target remains free.
        val edge = if (bothVisible) available / 3 else maxOf(available / 3, available - targetPx)
        // The fixed slot need not be centered in the reveal (e.g. an anchor at a safe edge).
        // Reserve its middle third as an unambiguous option lane, even with only one cue.
        val slotHalfLane = slotHeight / 6f
        val beforeLane = floor(slotCenter - slotHalfLane).toInt() - first
        val afterLane = end - ceil(slotCenter + slotHalfLane).toInt()
        val height = minOf(edge, targetPx, if (direction < 0) beforeLane else afterLane)
        if (height <= 0) return false
        val targetWidth = minOf(width, targetPx)
        left = (width - targetWidth) / 2
        right = left + targetWidth
        top = if (direction < 0) first else end - height
        bottom = top + height
        placed = true
        return true
    }

    fun contains(x: Float, y: Float): Boolean =
        placed && x >= left && x < right && y >= top && y < bottom
}
