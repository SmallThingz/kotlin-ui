package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import kotlin.math.roundToInt

/** Compact toolbar with the title after an existing leading action in logical direction. */
open class NativeLeadingTitleTopBarLayout(
    context: Context,
    density: Float,
    style: NativeToolbarStyle,
    private val action: View,
    private val title: View,
) : ViewGroup(context) {
    private var density = density
    private var style = style
    private var metrics = style.leadingTitleMetrics(density)
    private var insets = Insets.NONE

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(action)
        addView(title)
    }

    fun bindMetrics(density: Float, style: NativeToolbarStyle) {
        if (this.density == density && this.style == style) return
        this.density = density
        this.style = style
        metrics = style.leadingTitleMetrics(density)
        requestLayout()
    }

    fun setWindowInsets(value: Insets) {
        if (insets == value) return
        insets = value
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val actionSize = MeasureSpec.makeMeasureSpec(metrics.actionSize, MeasureSpec.EXACTLY)
        action.measure(actionSize, actionSize)
        title.measure(MeasureSpec.makeMeasureSpec(metrics.titleWidth(width, insets), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(metrics.height, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, resolveSize(metrics.height + insets.top + insets.bottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        metrics.place(width, insets, layoutDirection == LAYOUT_DIRECTION_RTL,
            action.measuredWidth, action.measuredHeight, title.measuredWidth, title.measuredHeight) { ax, ay, tx, ty ->
            action.layout(ax, ay, ax + action.measuredWidth, ay + action.measuredHeight)
            title.layout(tx, ty, tx + title.measuredWidth, ty + title.measuredHeight)
        }
    }
}

internal fun NativeToolbarStyle.leadingTitleMetrics(density: Float): NativeLeadingTitleMetrics {
    fun px(value: Float) = (density * value).roundToInt()
    return NativeLeadingTitleMetrics(compactHeightPx(density), px(compactEdgeInsetDp), px(compactEdgeInsetDp * 2f),
        px(action.sizeDp), px(action.minimumTargetDp), px(leadingTitleGapDp))
}

internal data class NativeLeadingTitleMetrics(
    val height: Int,
    val edgeInset: Int,
    val totalEdgeInset: Int,
    val actionSize: Int,
    val minimumTarget: Int,
    val titleGap: Int,
) {
    fun titleWidth(width: Int, insets: Insets): Int =
        (width - insets.left - insets.right - totalEdgeInset - actionSize - titleGap).coerceAtLeast(0)

    inline fun <T> place(width: Int, insets: Insets, rtl: Boolean,
        actionWidth: Int, actionHeight: Int, titleWidth: Int, titleHeight: Int,
        result: (Int, Int, Int, Int) -> T,
    ): T {
        val actionX = if (rtl) width - insets.right - edgeInset - actionWidth else insets.left + edgeInset
        val actionY = insets.top + ((height - actionHeight) / 2f).roundToInt()
        // The half-pixel minimum-layout residual applies only to the action visual.
        val visualX = actionX + nativeMinimumInteractiveVisualOffset(actionWidth, minimumTarget)
        val visualY = actionY + nativeMinimumInteractiveVisualOffset(actionHeight, minimumTarget)
        val titleX = if (rtl) actionX - titleGap - titleWidth else actionX + actionWidth + titleGap
        val titleY = insets.top + ((height - titleHeight) / 2f).roundToInt()
        return result(visualX, visualY, titleX, titleY)
    }
}
