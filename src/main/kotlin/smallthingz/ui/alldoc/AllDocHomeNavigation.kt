package smallthingz.ui.alldoc

import android.content.Context
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import smallthingz.ui.components.NativeChoiceLabelWidth
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeTabBar
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.controls.ControlPalette

/** AllDoc's three home destinations, rendered and owned entirely by the common tab bar.
 * Standalone [create] may wrap into fewer columns at a bounded width. [scrollHost] measures
 * that same bar at its natural width, keeping one row when Home reserves its measured height.
 * The host supplies choices/callbacks and retains its existing outer margins and lifecycle.
 */
object AllDocHomeNavigation {
    /** Use [inlineIcons] for a compact Home slot. The viewport still needs the measured row height. */
    @JvmOverloads
    fun create(context: Context, palette: ControlPalette, inlineIcons: Boolean = false): NativeTabBar = NativeTabBar(context, palette,
        NativeChoiceStyle(radiusDp = 0f, textSizeSp = 12f, horizontalPaddingDp = 10f,
            outlined = false, iconSizeDp = 20f, iconGapDp = 6f, selectedFill = false,
            iconAbove = !inlineIcons && context.resources.configuration.fontScale > 1.05f,
            indicatorHeightDp = 2f, indicatorWidthDp = 24f,
            normalTextRole = NativeTextRole.MUTED, normalIconRole = NativeTextRole.MUTED,
            selectedTextRole = NativeTextRole.CONTENT, selectedIconRole = NativeTextRole.CONTENT,
            textStyle = NativeTextStyle(12f, 16f, weight = 500),
            selectedTextStyle = NativeTextStyle(12f, 16f, weight = 600),
            minimumLabelWidth = NativeChoiceLabelWidth.WholeLabel),
        gapDp = 4f, activateOnFocusNavigation = false, fixedColumns = 3, fillRowHeight = true)

    /** Scroll horizontally while keeping a single row. Use WRAP_CONTENT in Home and reserve
     * the measured height, including the current font and icons. For a fixed-height slot, measure
     * with unspecified height first. A smaller slot reports MEASURED_STATE_TOO_SMALL and clips
     * the row. The app reveals its authoritative selected/focused destination after layout.
     */
    fun scrollHost(context: Context, tabs: NativeTabBar): HorizontalScrollView = object : HorizontalScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val rowHeight = measuredHeight
            val height = resolveSizeAndState(rowHeight, heightMeasureSpec, measuredHeightAndState)
            val state = if ((height and MEASURED_SIZE_MASK) < rowHeight) MEASURED_STATE_TOO_SMALL else 0
            setMeasuredDimension(measuredWidthAndState, height or state)
        }
    }.apply {
        isFillViewport = true
        isFocusable = false
        // Match Gallery's native navigation/action viewports; the tabs retain all foreground grain.
        isHorizontalScrollBarEnabled = false
        background = null
        // Native scrolling/focus/accessibility supplies reachability; no custom gesture owner.
        addView(tabs, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
}
