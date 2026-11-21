package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.roundToInt

/** Two ordered actions aligned to the logical trailing edge. */
class NativeTrailingActionPair(
    context: Context,
    private val first: View,
    private val second: View,
    private val onOwnerVisibility: () -> Unit,
) : ViewGroup(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(first)
        addView(second)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        onOwnerVisibility()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onOwnerVisibility()
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        first.measure(atMost(width), unspecified())
        second.measure(atMost((width - first.measuredWidth).coerceAtLeast(0)), unspecified())
        setMeasuredDimension(resolveSize(width, w),
            resolveSize(max(first.measuredHeight, second.measuredHeight), h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var start = width - first.measuredWidth - second.measuredWidth
        for (index in 0..1) {
            val child = if (index == 0) first else second
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            start += child.measuredWidth
        }
    }
}
