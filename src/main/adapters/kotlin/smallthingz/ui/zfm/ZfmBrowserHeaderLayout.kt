package smallthingz.ui.zfm

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.centeredTopBarPlacement
import smallthingz.ui.components.compactToolbarActionTop
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTextSurface
import kotlin.math.max
import kotlin.math.roundToInt

/** Reverb chrome with a retained full-width location row when the inline label cannot fit.
 * Only measurement/placement changes: no child moves between parents or loses its owner. */
class ZfmBrowserHeaderLayout(
    context: Context,
    theme: ZfmTheme,
    private val navigation: View,
    private val location: ZfmLocationControl,
    private val actions: View,
) : ViewGroup(context) {
    private val style = ReverbDesign.appearance.toolbars
    private val backgroundBinding = NativeToolbarBackground(this, style, theme.colors) {
        ReverbTextSurface.invalidate(this)
    }
    private val density get() = resources.displayMetrics.density
    private var stacked = false
    private var actionRowHeight = 0
    private var logicalHeight = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(navigation)
        addView(location)
        addView(actions)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        require(MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            "ZFM browser header requires bounded width"
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - 2 * dp(style.compactEdgeInsetDp)).coerceAtLeast(0)
        val target = exact(dp(max(style.action.sizeDp, style.action.minimumTargetDp)))
        if (navigation.occupiesSlot) navigation.measure(atMost(available), target)
        if (actions.occupiesSlot) actions.measure(atMost((available - navigation.slotWidth).coerceAtLeast(0)), target)
        val inlineWidth = (available - navigation.slotWidth - actions.slotWidth -
            navigation.locationGap - actions.locationGap).coerceAtLeast(0)
        stacked = inlineWidth < location.minimumReadableWidth
        val locationWidth = if (stacked) available else inlineWidth
        location.measure(atMost(locationWidth), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        actionRowHeight = max(style.compactHeightPx(density), max(navigation.slotHeight, actions.slotHeight))
        logicalHeight = if (stacked) actionRowHeight + location.measuredHeight
            else max(actionRowHeight, location.measuredHeight)
        setMeasuredDimension(width, resolveSize(logicalHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val edge = dp(style.compactEdgeInsetDp)
        val leadingGap = navigation.locationGap
        val trailingGap = actions.locationGap
        val titleWidth = location.measuredWidth + leadingGap + trailingGap
        val inline = centeredTopBarPlacement(width, logicalHeight,
            navigation.slotWidth + edge, navigation.slotHeight, titleWidth, location.measuredHeight,
            actions.slotWidth + edge, actions.slotHeight, density, rtl, style.centeredTitleStartDp)
        layoutEdgeAction(navigation, edge, alignRight = rtl)
        layoutEdgeAction(actions, edge, alignRight = !rtl)
        val titleX = if (stacked) {
            if (rtl) width - edge - location.measuredWidth else edge
        } else inline.titleX + if (rtl) trailingGap else leadingGap
        val titleY = if (stacked) actionRowHeight else inline.titleY
        location.layout(titleX, titleY, titleX + location.measuredWidth, titleY + location.measuredHeight)
    }

    private val View.occupiesSlot get() = visibility != GONE
    private val View.slotWidth get() = if (occupiesSlot) measuredWidth else 0
    private val View.slotHeight get() = if (occupiesSlot) measuredHeight else 0
    private val View.locationGap get() = if (occupiesSlot) dp(style.centeredPaddingDp) else 0

    private fun layoutEdgeAction(view: View, edge: Int, alignRight: Boolean) {
        if (!view.occupiesSlot) return
        val x = if (alignRight) width - edge - view.measuredWidth else edge
        val y = compactToolbarActionTop(actionRowHeight, view.measuredHeight)
        view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        requestLayout()
    }

    override fun onTouchEvent(event: MotionEvent) = true
    private fun dp(value: Float) = (value * density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.AT_MOST)
}
