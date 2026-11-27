package smallthingz.ui.gallery

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import smallthingz.ui.components.ChoiceArrangement
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeTabBar
import smallthingz.ui.components.NativeStackedIconSurfacePadding
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Stable destination slots: label length never turns primary navigation into wrapping chips. */
class GalleryNavigationBar(
    context: Context,
    palette: ControlPalette,
    private val destinations: List<NativeChoice>,
    selectedId: String,
    onSelectionRequest: (String) -> Boolean,
) : FrameLayout(context) {
    private val tabs = NativeTabBar(context, GalleryDesign.navigationPalette(palette), NativeChoiceStyle(
        radiusDp = 16f,
        minHeightDp = 64f,
        horizontalPaddingDp = 4f,
        verticalPaddingDp = 6f,
        outlined = false,
        selectedFill = true,
        // Paint only around the icon; keep complete labels and full native focus/ripple targets.
        stackedIconSurfacePadding = NativeStackedIconSurfacePadding(16f, 4f),
        iconAbove = true,
        iconSizeDp = 24f,
        iconGapDp = 4f,
        textStyle = GalleryDesign.textStyle(NativeTypographyRole.LABEL_MEDIUM),
        normalTextRole = NativeTextRole.MUTED,
        selectedTextRole = NativeTextRole.CONTENT, // The label stays on the page, outside the fill.
        normalIconRole = NativeTextRole.MUTED,
        selectedIconRole = null,
        indicatorHeightDp = 0f,
    ), gapDp = 0f, arrangement = ChoiceArrangement.EqualWidth,
        fixedColumns = destinations.size, maxColumns = destinations.size,
        fillRowHeight = true, activateOnFocusNavigation = false)
    private var viewportWidth = 0
    private val viewport = object : HorizontalScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var width = maxOf(viewportWidth, minimumRowWidth())
            val childHeight = getChildMeasureSpec(heightMeasureSpec, 0, LayoutParams.WRAP_CONTENT)
            tabs.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), childHeight)
            // A retained chip can refresh font/configuration metrics during measurement.
            // Only that change needs a second pass; ordinary layout keeps one row pass.
            val refreshed = maxOf(viewportWidth, minimumRowWidth())
            if (refreshed != width) {
                width = refreshed
                tabs.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), childHeight)
            }
            setMeasuredDimension(resolveSize(viewportWidth, widthMeasureSpec),
                resolveSize(tabs.measuredHeight, heightMeasureSpec))
        }
    }.apply {
        isHorizontalScrollBarEnabled = false
        addView(tabs, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    init {
        require(destinations.isNotEmpty())
        setBackgroundColor(palette.surface)
        addView(viewport, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        tabs.onSelectionRequest = onSelectionRequest
        bind(selectedId, palette)
    }

    fun bind(selectedId: String, palette: ControlPalette) {
        setBackgroundColor(palette.surface)
        tabs.applyPalette(GalleryDesign.navigationPalette(palette))
        tabs.bind(destinations, selectedId)
        destinations.forEach { destination -> tabs.choiceView(destination.id)?.apply {
            // Larger text may grow the one navigation row; no ellipsis or hidden destination.
            // Anchor icons at the same top inset even when only one label wraps.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            maxLines = Int.MAX_VALUE
            ellipsize = null
        } }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            (360f * resources.displayMetrics.density).roundToInt() else MeasureSpec.getSize(widthMeasureSpec)
        // Wide windows retain reachable destinations without a full-screen selected pill.
        viewportWidth = minOf(width, (600f * resources.displayMetrics.density).roundToInt())
        viewport.measure(MeasureSpec.makeMeasureSpec(viewportWidth, MeasureSpec.EXACTLY),
            getChildMeasureSpec(heightMeasureSpec, 0, LayoutParams.WRAP_CONTENT))
        // A gesture-hidden device can report no bottom system inset. Keep the selection
        // indicator inside the visual dock without shrinking a height-constrained row.
        val bottomClearance = (8f * resources.displayMetrics.density).roundToInt()
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
            resolveSize(viewport.measuredHeight + bottomClearance, heightMeasureSpec))
    }

    private fun minimumRowWidth(): Int {
        var cell = (48f * resources.displayMetrics.density).roundToInt()
        for (index in destinations.indices) {
            cell = maxOf(cell, tabs.choiceView(destinations[index].id)?.minimumCellWidth() ?: 0)
        }
        return cell * destinations.size
    }
}
