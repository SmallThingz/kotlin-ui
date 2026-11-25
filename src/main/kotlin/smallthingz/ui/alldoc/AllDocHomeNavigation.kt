package smallthingz.ui.alldoc

import android.content.Context
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
 * that same bar at its natural width, keeping one row in a bounded-height Home layout.
 * The host supplies choices/callbacks and retains its existing outer margins and lifecycle.
 */
object AllDocHomeNavigation {
    fun create(context: Context, palette: ControlPalette): NativeTabBar = NativeTabBar(context, palette,
        NativeChoiceStyle(radiusDp = 0f, textSizeSp = 12f, horizontalPaddingDp = 10f,
            outlined = false, iconSizeDp = 20f, iconGapDp = 6f, selectedFill = false,
            iconAbove = context.resources.configuration.fontScale > 1.05f,
            indicatorHeightDp = 2f, indicatorWidthDp = 24f,
            normalTextRole = NativeTextRole.MUTED, normalIconRole = NativeTextRole.MUTED,
            selectedTextRole = NativeTextRole.CONTENT, selectedIconRole = NativeTextRole.CONTENT,
            textStyle = NativeTextStyle(12f, 16f, weight = 500),
            selectedTextStyle = NativeTextStyle(12f, 16f, weight = 600),
            minimumLabelWidth = NativeChoiceLabelWidth.WholeLabel),
        gapDp = 4f, activateOnFocusNavigation = false, fixedColumns = 3, fillRowHeight = true)

    /** A bounded-height host uses one natural-width row, scrolling instead of clipping targets.
     * This standard viewport owns no paint, input admission, typography or selection state.
     * The app requests visibility of its authoritative selected/focused destination after layout.
     */
    fun scrollHost(context: Context, tabs: NativeTabBar): HorizontalScrollView = HorizontalScrollView(context).apply {
        isFillViewport = true
        isFocusable = false
        // Match Gallery's native navigation/action viewports; the tabs retain all foreground grain.
        isHorizontalScrollBarEnabled = false
        background = null
        // Native scrolling/focus/accessibility supplies reachability; no custom gesture owner.
        addView(tabs, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
}
