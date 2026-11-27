package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Retained Gallery library chrome: fixed action targets and reflowing heading/access/filter slots. */
open class GalleryLibraryChromeLayout(context: Context) : ViewGroup(context) {
    private lateinit var leading: View
    private lateinit var heading: View
    private var actions: List<View> = emptyList()
    private var access: View? = null
    private var filterAction: View? = null
    private var brandedRoot = false
    private var rowHeight = 0
    private var accessInline = false
    private var filterInline = false
    private var filterRowTop = 0

    protected fun bindChromeViews(leading: View, heading: View, actions: List<View>,
        access: View?, filterAction: View?, brandedRoot: Boolean) {
        check(childCount == 0)
        require(actions.isNotEmpty())
        this.leading = leading
        this.heading = heading
        this.actions = actions.toList()
        this.access = access
        this.filterAction = filterAction
        this.brandedRoot = brandedRoot
        bindMetrics()
        addView(leading)
        addView(heading)
        access?.let(::addView)
        actions.forEach(::addView)
        filterAction?.let(::addView)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun bindMetrics() {
        setPadding(dp(4), dp(2), dp(4), dp(2))
        minimumHeight = dp(52)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindMetrics()
        requestLayout()
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val access = this.access
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val iconSpec = MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY)
        leading.measure(iconSpec, iconSpec)
        actions.forEach { it.measure(iconSpec, iconSpec) }
        // The destination label may need more height under system font scaling.
        // Measure its intrinsic height instead of squeezing it into the icon's 48dp box.
        val headingHeightSpec = free
        heading.measure(free, headingHeightSpec)
        // Measure the complete permission label before deciding whether it fits beside the mark.
        access?.measure(free, free)
        val filter = filterAction?.takeIf { it.visibility != View.GONE }
        filter?.measure(free, free)
        val startWidth = leading.measuredWidth
        val actionWidth = actions.sumOf { it.measuredWidth }
        val desiredWidth = paddingLeft + maxOf(startWidth + heading.measuredWidth + actionWidth,
            access?.measuredWidth ?: 0, filter?.measuredWidth ?: 0) + paddingRight
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) desiredWidth
            else MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val headingRoom = (available - startWidth - actionWidth).coerceAtLeast(0)
        heading.measure(MeasureSpec.makeMeasureSpec(if (brandedRoot) minOf(heading.measuredWidth, headingRoom) else headingRoom,
            MeasureSpec.EXACTLY), headingHeightSpec)
        val accessRoom = (available - heading.measuredWidth) / 2 - actionWidth - dp(4) * 2
        accessInline = brandedRoot && access != null && access.measuredWidth <= accessRoom
        if (!accessInline) access?.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), free)
        rowHeight = maxOf(dp(48), heading.measuredHeight, if (accessInline) access?.measuredHeight ?: 0 else 0)
        val extraHeight = access?.takeUnless { accessInline }?.let { dp(4) + it.measuredHeight } ?: 0
        val filterRoom = (available - heading.measuredWidth) / 2 - startWidth - dp(4) * 2
        filterInline = filter != null && access == null && filter.measuredWidth <= filterRoom &&
            filter.measuredHeight <= rowHeight
        if (!filterInline) filter?.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY), free)
        filterRowTop = paddingTop + rowHeight + extraHeight + paddingBottom
        val filterHeight = if (filterInline) 0 else filter?.measuredHeight ?: 0
        setMeasuredDimension(resolveSizeAndState(width, widthMeasureSpec, 0),
            resolveSizeAndState(filterRowTop + filterHeight, heightMeasureSpec, 0))
        // Follow visual rows without detaching or recreating any control.
        heading.accessibilityTraversalAfter = if (filterInline) requireNotNull(filter).id else leading.id
        actions.forEachIndexed { index, action ->
            action.accessibilityTraversalAfter = if (index > 0) actions[index - 1].id
                else if (accessInline) requireNotNull(access).id else heading.id
        }
        access?.accessibilityTraversalAfter = if (accessInline) heading.id else actions.last().id
        filter?.accessibilityTraversalAfter = if (filterInline) leading.id
            else if (access != null && !accessInline) access.id else actions.last().id
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var cursor = if (rtl) width - paddingRight else paddingLeft
        fun place(view: View) {
            val x = if (rtl) cursor - view.measuredWidth else cursor
            val y = paddingTop + (rowHeight - view.measuredHeight) / 2
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
            cursor += view.measuredWidth * if (rtl) -1 else 1
        }
        place(leading)
        if (brandedRoot) {
            val actionWidth = actions.sumOf { it.measuredWidth }
            val minimumX = paddingLeft + if (rtl) actionWidth else leading.measuredWidth
            val maximumX = (width - paddingRight - heading.measuredWidth -
                if (rtl) leading.measuredWidth else actionWidth).coerceAtLeast(minimumX)
            val centeredX = paddingLeft + (width - paddingLeft - paddingRight - heading.measuredWidth) / 2
            val x = centeredX.coerceIn(minimumX, maximumX)
            val y = paddingTop + (rowHeight - heading.measuredHeight) / 2
            heading.layout(x, y, x + heading.measuredWidth, y + heading.measuredHeight)
            cursor = if (rtl) paddingLeft + actionWidth else width - paddingRight - actionWidth
        } else place(heading)
        actions.forEach(::place)
        access?.let { view ->
            val actionInset = if (accessInline) actions.sumOf { it.measuredWidth } + dp(4) else 0
            val x = if (rtl) paddingLeft + actionInset else width - paddingRight - actionInset - view.measuredWidth
            val y = if (accessInline) paddingTop + (rowHeight - view.measuredHeight) / 2 else paddingTop + rowHeight + dp(4)
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }
        filterAction?.takeIf { it.visibility != View.GONE }?.let { view ->
            val x = if (!filterInline) paddingLeft else if (rtl)
                width - paddingRight - leading.measuredWidth - dp(4) - view.measuredWidth
                else paddingLeft + leading.measuredWidth + dp(4)
            val y = if (filterInline) paddingTop + (rowHeight - view.measuredHeight) / 2 else filterRowTop
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }
    }

}
