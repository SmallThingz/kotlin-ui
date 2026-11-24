package smallthingz.ui.gallery

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import smallthingz.ui.components.NativeToolbarStyle
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

enum class GalleryEditorArrangement { Stacked, SideBySide }

/**
 * Retained editor slots. The host owns insets (root padding), actions, scrolling and preview
 * lifetime. Measurement never reparents a slot, changes focus or resets the photo transform.
 * [minimumControlsHeight] is queried after measuring controls at their actual allocated width.
 */
class GalleryEditorLayout(
    context: Context,
    private val actions: View,
    private val controls: View,
    preview: View,
    snackbarHost: View? = null,
    private val minimumControlsHeight: () -> Int = { 0 },
    /** Measurement policy only; never replace children or dispatch domain actions here. */
    private val prepareControls: (GalleryEditorArrangement) -> Unit = {},
) : ViewGroup(context) {
    private val previewBox = FrameLayout(context).apply {
        val edge = previewEdgePx(context.resources.displayMetrics.density)
        setPadding(edge, edge, edge, edge)
        addView(preview, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        snackbarHost?.let {
            addView(it, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }
    private var plan = GalleryEditorPlan(false, GalleryEditorBounds(0, 0, 0, 0), GalleryEditorBounds(0, 0, 0, 0))

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(actions)
        addView(previewBox)
        addView(controls)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val horizontalPadding = paddingLeft + paddingRight
        val verticalPadding = paddingTop + paddingBottom
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            maxOf(suggestedMinimumWidth, (320 * density).roundToInt() + horizontalPadding)
        else MeasureSpec.getSize(widthMeasureSpec)
        val innerWidth = (width - horizontalPadding).coerceAtLeast(0)
        val boundedHeight = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        val innerHeight = if (boundedHeight)
            (MeasureSpec.getSize(heightMeasureSpec) - verticalPadding).coerceAtLeast(0) else Int.MAX_VALUE
        actions.measure(exact(innerWidth), MeasureSpec.makeMeasureSpec(if (boundedHeight) innerHeight else 0,
            if (boundedHeight) MeasureSpec.AT_MOST else MeasureSpec.UNSPECIFIED))
        val actionHeight = if (actions.visibility == GONE) 0 else
            minOf(maxOf(actionRowHeight(context), actions.measuredHeight), innerHeight)
        actions.measure(exact(innerWidth), exact(actionHeight))
        val available = if (boundedHeight) innerHeight - actionHeight else Int.MAX_VALUE
        // An unbounded host requests natural stacked content, not an arbitrary sidebar.
        val panelWidth = if (boundedHeight && controls.visibility != GONE)
            galleryEditorPanelWidth(innerWidth, available, density) else 0
        fun measureControls(sideBySide: Boolean): Pair<Int, Int> {
            prepareControls(if (sideBySide) GalleryEditorArrangement.SideBySide else GalleryEditorArrangement.Stacked)
            // The callback can change descendant sizing without changing parent MeasureSpecs.
            controls.forceLayout()
            controls.measure(exact(if (sideBySide) panelWidth else innerWidth),
                MeasureSpec.makeMeasureSpec(if (boundedHeight) available else 0,
                    if (!boundedHeight) MeasureSpec.UNSPECIFIED else if (sideBySide) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
            if (controls.visibility == GONE) return 0 to 0
            val desired = controls.measuredHeight
            return desired to minimumControlsHeight().coerceAtLeast(0)
        }
        var sideBySide = panelWidth > 0
        var measuredControls = measureControls(sideBySide)
        if (sideBySide && measuredControls.second > available) {
            // A wrapped footer may fit at full width even when it cannot fit in the sidebar.
            sideBySide = false
            measuredControls = measureControls(false)
        }
        val bodyHeight = if (boundedHeight) available else measuredControls.first + previewMinimumPx(density)
        plan = galleryEditorPlan(innerWidth, bodyHeight, measuredControls.first, measuredControls.second, density,
            layoutDirection == LAYOUT_DIRECTION_RTL, allowSideBySide = sideBySide)
        // Intrinsic footer probes also invalidate an optional controls wrapper at unchanged specs.
        controls.forceLayout()
        controls.measure(exact(plan.controls.width), exact(plan.controls.height))
        previewBox.measure(exact(plan.preview.width), exact(plan.preview.height))
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
            resolveSize(maxOf(suggestedMinimumHeight, actionHeight + bodyHeight + verticalPadding), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        actions.layout(paddingLeft, paddingTop, paddingLeft + actions.measuredWidth, paddingTop + actions.measuredHeight)
        val bodyTop = paddingTop + actions.measuredHeight
        fun place(view: View, bounds: GalleryEditorBounds) {
            view.layout(paddingLeft + bounds.left, bodyTop + bounds.top,
                paddingLeft + bounds.right, bodyTop + bounds.bottom)
        }
        place(previewBox, plan.preview)
        place(controls, plan.controls)
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    companion object {
        /** Shared label metrics leave room for scaled text and the toolbar touch target. */
        fun actionRowHeight(context: Context): Int {
            val metrics = context.resources.displayMetrics
            val line = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                NativeTypographyRole.LABEL_LARGE.style.lineHeightSp, metrics)
            return maxOf((NativeToolbarStyle.Default.action.minimumTargetDp * metrics.density).roundToInt(),
                line.roundToInt() + (16 * metrics.density).roundToInt())
        }

        private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), MeasureSpec.EXACTLY)
    }
}

internal data class GalleryEditorBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal data class GalleryEditorPlan(val sideBySide: Boolean, val preview: GalleryEditorBounds, val controls: GalleryEditorBounds)

private fun previewEdgePx(density: Float) = (NativeToolbarStyle.Default.centeredPaddingDp * density).roundToInt()
private fun previewMinimumPx(density: Float) =
    (NativeToolbarStyle.Default.action.minimumTargetDp * density).roundToInt() + 2 * previewEdgePx(density)

/** A sidebar is useful only when both panes keep at least 240dp of usable width. */
internal fun galleryEditorPanelWidth(width: Int, height: Int, density: Float): Int {
    require(width >= 0 && height >= 0 && density.isFinite() && density > 0)
    val minimum = (240 * density).roundToInt().coerceAtLeast(1)
    val photoMinimum = minimum + 2 * previewEdgePx(density)
    if (height == 0 || width <= height || width - photoMinimum < minimum) return 0
    return (width * .38f).roundToInt().coerceIn(minimum, minOf(maxOf(minimum, (320 * density).roundToInt()), width - photoMinimum))
}

/** Pixel-only planner; no configuration orientation, view mutation or rendering state. */
internal fun galleryEditorPlan(width: Int, height: Int, desiredControlsHeight: Int,
    minimumControlsHeight: Int, density: Float, rtl: Boolean = false, allowSideBySide: Boolean = true): GalleryEditorPlan {
    require(width >= 0 && height >= 0 && density.isFinite() && density > 0)
    val panel = if (allowSideBySide) galleryEditorPanelWidth(width, height, density) else 0
    if (panel > 0 && minimumControlsHeight.coerceAtLeast(0) <= height) {
        return if (rtl)
            GalleryEditorPlan(true, GalleryEditorBounds(panel, 0, width, height), GalleryEditorBounds(0, 0, panel, height))
        else GalleryEditorPlan(true, GalleryEditorBounds(0, 0, width - panel, height), GalleryEditorBounds(width - panel, 0, width, height))
    }
    // Preserve a photo strip only after the measured footer and minimum scroll viewport fit.
    val reserve = minOf(previewMinimumPx(density), (height - minimumControlsHeight.coerceAtLeast(0)).coerceAtLeast(0))
    val controls = desiredControlsHeight.coerceIn(0, height - reserve)
    return GalleryEditorPlan(false, GalleryEditorBounds(0, 0, width, height - controls),
        GalleryEditorBounds(0, height - controls, width, height))
}
