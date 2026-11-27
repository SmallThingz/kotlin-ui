package smallthingz.ui.gallery

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeTabBar
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Retains the category nodes and adds breathing room below their bottom indicator. */
class GalleryEditorCategoryBar(context: Context, palette: ControlPalette) : FrameLayout(context) {
    val tabs = NativeTabBar(context, palette,
        NativeChoiceStyle(radiusDp = 10f, minHeightDp = 52f, horizontalPaddingDp = 0f,
            verticalPaddingDp = 6f, outlined = false, iconSizeDp = 20f, iconGapDp = 2f,
            iconAbove = true, selectedFill = false,
            indicatorHeightDp = 2f, indicatorWidthDp = 28f, indicatorInsetDp = 0f,
            textStyle = GalleryDesign.textStyle(NativeTypographyRole.LABEL_MEDIUM).copy(weight = 400),
            selectedTextStyle = GalleryDesign.textStyle(NativeTypographyRole.LABEL_MEDIUM).copy(weight = 600),
            retainContentColorsWhenDisabled = true),
        gapDp = 0f, maxColumns = 4, activateOnFocusNavigation = false).apply {
        setPadding(dp(4), 0, dp(4), 0)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        tabs.measure(widthMeasureSpec, getChildMeasureSpec(heightMeasureSpec, 0, LayoutParams.WRAP_CONTENT))
        // Match the library navigation: natural height gains 8dp; an exact-height host
        // keeps the original content budget instead of squeezing its labels or targets.
        setMeasuredDimension(resolveSize(tabs.measuredWidth, widthMeasureSpec),
            resolveSize(tabs.measuredHeight + dp(8), heightMeasureSpec))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
