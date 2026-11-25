package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.roundToInt
import smallthingz.ui.common.mergedButtonGroup
import smallthingz.ui.input.phase.NativePointerLayerShape

/** Opt-in geometry only: the action retains its recipe, callbacks and accessibility node. */
interface NativeMergedActionChild {
    val actionMinimumWidthPx: Int
    /** One-way for this action instance's lifetime. Group construction calls it once; no unjoin/reparent styling reset. */
    fun joinActionSurface()
}

/**
 * Natural-height action content for an owning scroll viewport. A constrained wrapper clips without
 * compressing targets. Sheet, Library and Settings scroll owners measure this content with an
 * unbounded height so every action is reachable; this is not an independent viewport.
 */
open class NativeMergedActionLayout(
    context: Context,
    private val actions: List<View>,
    palette: NativeColorScheme,
    cornerRadiusDp: Float,
    private val fillAvailableWidth: Boolean,
) : ViewGroup(context), NativePointerLayerShape {
    /** Retain compact trailing groups for existing callers. */
    constructor(context: Context, actions: List<View>, palette: NativeColorScheme,
        cornerRadiusDp: Float = 14f,
    ) : this(context, actions, palette, cornerRadiusDp, false)

    companion object {
        /** Visual space below a Cancel/confirm footer, excluding all host/window insets. */
        const val ACTION_FOOTER_BOTTOM_PADDING_DP = 6f
    }

    var isActionFooter: Boolean = false
        private set

    /** Fixed visual spacing for Cancel/confirm; ordinary merged/icon groups stay unchanged. */
    fun asActionFooter(): NativeMergedActionLayout = asActionFooter(resources.displayMetrics.density)

    /** Use the same display density as the library's retained controls; never font scale. */
    internal fun asActionFooter(density: Float): NativeMergedActionLayout {
        require(density.isFinite() && density > 0f)
        if (!isActionFooter) {
            isActionFooter = true
            requestLayout()
        }
        val pixels = (ACTION_FOOTER_BOTTOM_PADDING_DP * density).roundToInt()
        if (paddingBottom != pixels) setPadding(paddingLeft, paddingTop, paddingRight, pixels)
        return this
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isActionFooter) asActionFooter()
    }

    private val surface = mergedButtonGroup(context, cornerRadiusDp, actions.onEach {
        (it as? NativeMergedActionChild)?.joinActionSurface()
    })
    private val params = actions.map { (it.layoutParams as NativeGroupLayout.LayoutParams).apply { weight = 0f } }
    private val preferredWidths = IntArray(actions.size)
    private val minimumWidths = IntArray(actions.size)
    private val childHeights = IntArray(actions.size)
    private val assignedWidths = IntArray(actions.size)
    /** The actual retained shape and placement, for phase hit trees that skip Android ancestors. */
    val actionSurface: NativeGroupLayout get() = surface

    init {
        require(actions.isNotEmpty())
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = true
        clipToPadding = true
        addView(surface)
        bindPalette(palette)
    }

    override fun containsPointerLayer(x: Float, y: Float): Boolean =
        x >= 0f && y >= 0f && x < width && y < height - paddingBottom &&
            surface.containsVisualPoint(x - surface.left, y - surface.top)

    fun bindPalette(palette: NativeColorScheme) {
        surface.setSurfacePreset(palette.surfacePreset)
        surface.setPalette(palette.surfaceContainerHigh, palette.border)
    }

    override fun onMeasure(w: Int, h: Int) {
        val boundedWidth = MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED
        val width = MeasureSpec.getSize(w)
        val boundedHeight = MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED
        val heightLimit = (MeasureSpec.getSize(h) - paddingBottom).coerceAtLeast(0)
        var totalWidth = 0
        var minimumWidth = 0
        var widest = 0
        var stackHeight = 0
        var visibleCount = 0
        for (index in actions.indices) {
            val child = actions[index]
            if (child.visibility == GONE) continue
            visibleCount++
            child.measure(if (boundedWidth) atMost(width) else unspecified(), unspecified())
            preferredWidths[index] = child.measuredWidth
            minimumWidths[index] = (child as? NativeMergedActionChild)?.actionMinimumWidthPx
                ?: (48f * resources.displayMetrics.density).roundToInt()
            minimumWidth += minimumWidths[index]
            totalWidth += child.measuredWidth
            widest = max(widest, child.measuredWidth)
            stackHeight += child.measuredHeight
        }
        val needsStack = boundedWidth &&
            (if (fillAvailableWidth) widest.toLong() * visibleCount > width else totalWidth > width)
        val horizontalFallback = needsStack && boundedHeight && stackHeight > heightLimit && minimumWidth <= width
        var stacked = needsStack && !horizontalFallback
        var groupWidth = if (boundedWidth && fillAvailableWidth) width
            else if (stacked) widest else if (horizontalFallback) width else totalWidth
        var spare = if (horizontalFallback) width - minimumWidth
            else if (boundedWidth && fillAvailableWidth) width else 0
        var remaining = visibleCount
        var tallest = 0
        for (index in actions.indices) {
            val child = actions[index]
            if (child.visibility == GONE) continue
            val childWidth = when {
                stacked -> groupWidth
                horizontalFallback -> (minimumWidths[index] + spare / remaining).also {
                    spare -= it - minimumWidths[index]
                    remaining--
                }
                boundedWidth && fillAvailableWidth -> (spare / remaining).also {
                    spare -= it
                    remaining--
                }
                else -> preferredWidths[index]
            }
            assignedWidths[index] = childWidth
            child.measure(exact(childWidth), unspecified())
            childHeights[index] = child.measuredHeight
            tallest = max(tallest, child.measuredHeight)
        }
        // Width minimums alone are insufficient: wrapped labels must fit the height too.
        if (horizontalFallback && tallest > heightLimit) {
            stacked = true
            groupWidth = if (boundedWidth && fillAvailableWidth) width else widest
            for (index in actions.indices) {
                val child = actions[index]
                if (child.visibility == GONE) continue
                assignedWidths[index] = groupWidth
                child.measure(exact(groupWidth), unspecified())
                childHeights[index] = child.measuredHeight
            }
        }
        surface.setRowOrientation(if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL)
        var visibleIndex = 0
        for (index in actions.indices) {
            val child = actions[index]
            if (child.visibility == GONE) continue
            val childWidth = assignedWidths[index]
            val childHeight = if (stacked) childHeights[index] else tallest
            val p = params[index]
            val top = stacked && visibleIndex > 0
            val start = !stacked && visibleIndex > 0
            visibleIndex++
            if (p.width != childWidth || p.height != childHeight || p.dividerTop != top || p.dividerStart != start) {
                p.width = childWidth
                p.height = childHeight
                p.dividerTop = top
                p.dividerStart = start
                child.layoutParams = p
            }
        }
        surface.measure(exact(groupWidth), unspecified())
        setMeasuredDimension(resolveSize(if (boundedWidth) width else groupWidth, w),
            resolveSize(surface.measuredHeight + paddingBottom, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) 0 else width - surface.measuredWidth
        val available = height - paddingBottom - surface.measuredHeight
        val y = (if (isActionFooter) available else available / 2).coerceAtLeast(0)
        surface.layout(x, y, x + surface.measuredWidth, y + surface.measuredHeight)
    }
}

/** Keep the action's semantic paints while the group owns corners, border and elevation. */
internal fun NativeButtonRecipe.forMergedActions(): NativeButtonRecipe = copy(
    surface = surface.copy(radiusDp = 0f, minimumHeightDp = max(48, surface.minimumHeightDp)),
    elevated = false,
)
