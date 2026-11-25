package smallthingz.ui.core

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import smallthingz.ui.components.NativeActionSheetStyle
import smallthingz.ui.components.NativeSheetDragHandle
import smallthingz.ui.input.DEFAULT_MINIMUM_TOUCH_TARGET_DP
import kotlin.math.roundToInt

/**
 * Core's retained host for the shared sheet mark. The caller owns its description, accessibility
 * actions, enabled/focus policy and optional click listener; the panel remains the sole drag owner.
 * This view installs no input controller, callback, tooltip or expanded hit region.
 *
 * The desired allocation is at least 48dp and includes recipe spacing, subject to parent bounds.
 * A parent deciding whether to show a GONE handle may directly measure it with the current width
 * spec and an UNSPECIFIED height spec before budgeting measuredHeight. A parent normally skips
 * GONE children: do not use a skipped measurement, stale height or a fixed historical allowance.
 */
class CorePanelHandle(
    context: Context,
    private var style: NativeActionSheetStyle,
) : FrameLayout(context) {
    private val mark = NativeSheetDragHandle.HandleMark(context, style).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isScreenReaderFocusable = false
        contentDescription = null
        isFocusableInTouchMode = false
        isFocusable = false
        isClickable = false
        isLongClickable = false
    }

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        isFocusableInTouchMode = false
        isFocusable = false
        isClickable = false
        isLongClickable = false
        addView(mark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** Supply the resolved style again when palette, recipe or display density changes. */
    fun updateStyle(next: NativeActionSheetStyle) {
        val layoutChanged = style.textDensity.density != next.textDensity.density ||
            style.recipe.handleHeightDp != next.recipe.handleHeightDp ||
            style.recipe.handleVerticalPaddingDp != next.recipe.handleVerticalPaddingDp
        style = next
        mark.bindColor(next.palette.onSurfaceVariant)
        if (layoutChanged) requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = style.textDensity.density
        // The existing shared handle owns this 32dp visual width, independently of touch bounds.
        val markWidth = (32f * density).roundToInt().coerceAtLeast(0)
        val markHeight = (style.recipe.handleHeightDp * density).roundToInt().coerceAtLeast(1)
        val recipePadding = (style.recipe.handleVerticalPaddingDp * density).roundToInt().coerceAtLeast(0)
        val minimumTarget = (DEFAULT_MINIMUM_TOUCH_TARGET_DP * density).roundToInt()
        val desiredWidth = maxOf(suggestedMinimumWidth,
            paddingLeft + paddingRight + maxOf(minimumTarget, markWidth))
        val desiredHeight = maxOf(suggestedMinimumHeight,
            paddingTop + paddingBottom + maxOf(minimumTarget, markHeight + recipePadding * 2))
        setMeasuredDimension(resolveSizeAndState(desiredWidth, widthMeasureSpec, 0),
            resolveSizeAndState(desiredHeight, heightMeasureSpec, 0))
        // Exact/maximum parent bounds win over the preferred target; never draw beyond them.
        val availableWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        mark.measure(MeasureSpec.makeMeasureSpec(minOf(markWidth, availableWidth), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(minOf(markHeight, availableHeight), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val x = (paddingLeft + (availableWidth - mark.measuredWidth) / 2)
            .coerceIn(0, (width - mark.measuredWidth).coerceAtLeast(0))
        val y = (paddingTop + (availableHeight - mark.measuredHeight) / 2)
            .coerceIn(0, (height - mark.measuredHeight).coerceAtLeast(0))
        mark.layout(x, y, x + mark.measuredWidth, y + mark.measuredHeight)
    }
}
