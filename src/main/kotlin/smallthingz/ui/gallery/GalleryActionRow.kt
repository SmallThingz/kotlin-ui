package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Keeps action views in place while fitting complete targets onto successive lines. */
class GalleryActionRow(
    context: Context,
    val gapDp: Float = 8f,
    private val verticalGapDp: Float = gapDp,
    private val alignEnd: Boolean = false,
    private val reverseLineOrder: Boolean = false,
) : ViewGroup(context) {
    private var lineEnds = IntArray(0)
    private var lineHeights = IntArray(0)
    private var lineWidths = IntArray(0)
    private var lineCount = 0
    private var gap = 0
    private var verticalGap = 0

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun checkLayoutParams(params: LayoutParams): Boolean = params is MarginLayoutParams

    override fun generateLayoutParams(params: LayoutParams): LayoutParams =
        if (params is MarginLayoutParams) MarginLayoutParams(params) else MarginLayoutParams(params)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (lineEnds.size < childCount) {
            lineEnds = IntArray(childCount)
            lineHeights = IntArray(childCount)
            if (alignEnd) lineWidths = IntArray(childCount)
        }
        val density = resources.displayMetrics.density
        gap = (gapDp * density).roundToInt()
        verticalGap = (verticalGapDp * density).roundToInt()
        lineCount = 0
        val horizontalPadding = paddingLeft + paddingRight
        val widthBounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val contentWidth = (MeasureSpec.getSize(widthMeasureSpec) - horizontalPadding).coerceAtLeast(0)
        var lineWidth = 0
        var lineHeight = 0
        var hasActions = false
        var usedWidth = 0
        var usedHeight = 0
        var childState = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val margins = child.layoutParams as MarginLayoutParams
            margins.resolveLayoutDirection(layoutDirection)
            val sideMargins = margins.leftMargin + margins.rightMargin
            val childWidth = if (widthBounded && margins.width >= 0) {
                // A fixed-width action must still fit on a line by itself.
                margins.width.coerceAtMost((contentWidth - sideMargins).coerceAtLeast(0))
            } else margins.width
            child.measure(
                getChildMeasureSpec(widthMeasureSpec, horizontalPadding + sideMargins, childWidth),
                MeasureSpec.makeMeasureSpec(margins.height.coerceAtLeast(0),
                    if (margins.height >= 0) MeasureSpec.EXACTLY else MeasureSpec.UNSPECIFIED),
            )
            childState = combineMeasuredStates(childState, child.measuredState)
            val actionWidth = child.measuredWidth + sideMargins
            if (hasActions && widthBounded && lineWidth + gap + actionWidth > contentWidth) {
                lineEnds[lineCount] = index
                if (alignEnd) lineWidths[lineCount] = lineWidth
                lineHeights[lineCount++] = lineHeight
                usedWidth = maxOf(usedWidth, lineWidth)
                usedHeight += lineHeight + verticalGap
                lineWidth = 0
                lineHeight = 0
                hasActions = false
            }
            lineWidth += actionWidth + if (hasActions) gap else 0
            lineHeight = maxOf(lineHeight, child.measuredHeight + margins.topMargin + margins.bottomMargin)
            hasActions = true
        }
        if (hasActions) {
            lineEnds[lineCount] = childCount
            if (alignEnd) lineWidths[lineCount] = lineWidth
            lineHeights[lineCount++] = lineHeight
            usedWidth = maxOf(usedWidth, lineWidth)
            usedHeight += lineHeight
        }
        setMeasuredDimension(
            resolveSizeAndState(maxOf(suggestedMinimumWidth, usedWidth + horizontalPadding), widthMeasureSpec, childState),
            resolveSizeAndState(maxOf(suggestedMinimumHeight, usedHeight + paddingTop + paddingBottom),
                heightMeasureSpec, childState shl View.MEASURED_HEIGHT_STATE_SHIFT),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var lineTop = paddingTop
        for (index in 0 until lineCount) {
            val line = if (reverseLineOrder) lineCount - 1 - index else index
            val lineStart = if (line == 0) 0 else lineEnds[line - 1]
            var offset = if (alignEnd) (width - paddingLeft - paddingRight - lineWidths[line]).coerceAtLeast(0) else 0
            for (index in lineStart until lineEnds[line]) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue
                val margins = child.layoutParams as MarginLayoutParams
                val childWidth = child.measuredWidth
                val x = if (rtl) width - paddingRight - offset - margins.rightMargin - childWidth
                    else paddingLeft + offset + margins.leftMargin
                val heightWithMargins = child.measuredHeight + margins.topMargin + margins.bottomMargin
                val y = lineTop + (lineHeights[line] - heightWithMargins) / 2 + margins.topMargin
                child.layout(x, y, x + childWidth, y + child.measuredHeight)
                offset += childWidth + margins.leftMargin + margins.rightMargin + gap
            }
            lineTop += lineHeights[line] + verticalGap
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        requestLayout()
    }
}
