package smallthingz.ui.components

import android.view.MotionEvent
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Independent standalone touch bounds for an intrinsic button. The button remains the accessible action. */
class NativeButtonTouchTarget(val button: NativeMaterialButtonView) : ViewGroup(button.context) {
    private var targetLeft = 0
    private var targetTop = 0
    private var targetRight = 0
    private var targetBottom = 0
    internal var expandsFingerTarget = false
        private set
    private val minimumTargetPx get() = (button.minimumTouchTargetDp * resources.displayMetrics.density).roundToInt()

    init {
        require(button.parent == null) { "Button already belongs to another parent" }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(button)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        button.measure(fit(widthMeasureSpec), fit(heightMeasureSpec))
        val minimum = minimumTargetPx
        setMeasuredDimension(resolveSize(max(minimum, button.measuredWidth), widthMeasureSpec),
            resolveSize(max(minimum, button.measuredHeight), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = ((width - button.measuredWidth) * .5f).roundToInt()
        val top = ((height - button.measuredHeight) * .5f).roundToInt()
        button.layout(left, top, left + button.measuredWidth, top + button.measuredHeight)
        val minimum = minimumTargetPx
        val targetWidth = min(width, max(minimum, button.measuredWidth))
        val targetHeight = min(height, max(minimum, button.measuredHeight))
        targetLeft = ((width - targetWidth) * .5f).roundToInt() - left
        targetTop = ((height - targetHeight) * .5f).roundToInt() - top
        targetRight = targetLeft + targetWidth
        targetBottom = targetTop + targetHeight
        expandsFingerTarget = targetWidth > button.measuredWidth || targetHeight > button.measuredHeight
    }

    internal fun containsFingerTarget(x: Float, y: Float): Boolean =
        x >= targetLeft && x < targetRight && y >= targetTop && y < targetBottom

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!onFilterTouchEventForSecurity(event)) return false
        val dx = -button.left.toFloat()
        val dy = -button.top.toFloat()
        event.offsetLocation(dx, dy)
        return try {
            button.dispatchTouchEvent(event)
        } finally {
            event.offsetLocation(-dx, -dy)
        }
    }

    private fun fit(spec: Int): Int = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY)
        MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(spec), MeasureSpec.AT_MOST) else spec
}
