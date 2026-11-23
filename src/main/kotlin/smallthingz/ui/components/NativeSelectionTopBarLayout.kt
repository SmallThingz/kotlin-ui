package smallthingz.ui.components

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/** Compact bar with a measured title between a start action and a group of end actions. */
open class NativeSelectionTopBarLayout(
    context: Context,
    style: NativeToolbarStyle,
    private val start: View,
    private val title: View,
    private val end: View,
    private val endSlots: Int,
) : NativeTopBarLayout(context, style) {
    init { addView(start); addView(title); addView(end) }

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    private fun dp(value: Float) = (value * touchDensity).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val size = dp(toolbarStyle.action.selectionSizeDp)
        val endWidth = size * endSlots
        val edge = dp(toolbarStyle.selectionEdgeInsetDp)
        val available = (width - leftInsetPx - rightInsetPx - edge * 2 - size - endWidth - dp(toolbarStyle.selectionTitleGapDp)).coerceAtLeast(0)
        val contentHeight = toolbarStyle.compactHeightPx(touchDensity)
        val actionSpec = exact(size)
        start.measure(actionSpec, actionSpec)
        end.measure(exact(endWidth), actionSpec)
        title.measure(exact(available), MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, resolveSize(contentHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val gap = dp(toolbarStyle.selectionTitleGapDp)
        val titleStart = place(start, dp(toolbarStyle.selectionEdgeInsetDp)) + gap
        place(end, place(title, titleStart))
    }

    private fun place(child: View, logicalX: Int): Int {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL)
            width - rightInsetPx - logicalX - child.measuredWidth else leftInsetPx + logicalX
        val y = ((height - child.measuredHeight) / 2f).roundToInt()
        child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        return logicalX + child.measuredWidth
    }

    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
