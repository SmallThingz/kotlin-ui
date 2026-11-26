package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.roundToInt

/** Retains the query editor and actions while spending wide, short windows on content.
 * The host owns query/callback/lifetime state. This container only measures and places
 * the same two children; it never reparents them during a layout/configuration change.
 */
open class GallerySearchInputLayout(context: Context) : ViewGroup(context) {
    private var inlineLayout = false
    private var gap = 0

    fun setSearchContent(query: View, actions: View) {
        check(childCount == 0) { "Search content is installed once and retained" }
        require(query !== actions && query.parent == null && actions.parent == null)
        addView(query)
        addView(actions)
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        gap = (10f * density).roundToInt()
        val padding = paddingLeft + paddingRight
        if (childCount != 2) {
            setMeasuredDimension(resolveSize(padding, widthMeasureSpec),
                resolveSize(paddingTop + paddingBottom, heightMeasureSpec))
            return
        }
        val query = getChildAt(0)
        val actions = getChildAt(1)
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val bound = (MeasureSpec.getSize(widthMeasureSpec) - padding).coerceAtLeast(0)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val configuration = resources.configuration
        val compactLandscape = bounded && configuration.screenHeightDp in 1..480 &&
            configuration.screenWidthDp > configuration.screenHeightDp
        // Use the canonical labelled-field width as an editing-space floor. Actual
        // placeholder/slot height below still determines whether the query fits.
        val queryFloor = (280f * density).roundToInt()
        if (!bounded || compactLandscape) {
            actions.measure(MeasureSpec.makeMeasureSpec(bound,
                if (bounded) MeasureSpec.AT_MOST else MeasureSpec.UNSPECIFIED), unspecified)
        }
        val available = if (bounded) bound else max(queryFloor, actions.measuredWidth)
        val fullQueryWidthSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY)
        query.measure(fullQueryWidthSpec, unspecified)
        inlineLayout = false
        if (compactLandscape) {
            val fullQueryHeight = query.measuredHeight
            val remaining = available - actions.measuredWidth - gap
            inlineLayout = remaining >= queryFloor && actions.measuredHeight <= fullQueryHeight
            if (inlineLayout && remaining < available) {
                query.measure(MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.EXACTLY), unspecified)
                // A wrapping query may need its full-width measurement for the stacked layout.
                inlineLayout = query.measuredHeight <= fullQueryHeight
                if (!inlineLayout) query.measure(fullQueryWidthSpec, unspecified)
            }
        }
        if (!inlineLayout) {
            actions.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY), unspecified)
        }
        val contentHeight = if (inlineLayout) max(query.measuredHeight, actions.measuredHeight)
            else query.measuredHeight + gap + actions.measuredHeight
        val childState = combineMeasuredStates(query.measuredState, actions.measuredState)
        setMeasuredDimension(resolveSizeAndState(available + padding, widthMeasureSpec, childState),
            resolveSizeAndState(contentHeight + paddingTop + paddingBottom, heightMeasureSpec,
                childState shl MEASURED_HEIGHT_STATE_SHIFT))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount != 2) return
        val query = getChildAt(0)
        val actions = getChildAt(1)
        if (inlineLayout) {
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val band = max(query.measuredHeight, actions.measuredHeight)
            val queryX = if (rtl) width - paddingRight - query.measuredWidth else paddingLeft
            val actionX = if (rtl) queryX - gap - actions.measuredWidth else queryX + query.measuredWidth + gap
            val queryY = paddingTop + (band - query.measuredHeight) / 2
            val actionY = paddingTop + (band - actions.measuredHeight) / 2
            query.layout(queryX, queryY, queryX + query.measuredWidth, queryY + query.measuredHeight)
            actions.layout(actionX, actionY, actionX + actions.measuredWidth, actionY + actions.measuredHeight)
        } else {
            query.layout(paddingLeft, paddingTop, paddingLeft + query.measuredWidth, paddingTop + query.measuredHeight)
            val actionY = query.bottom + gap
            actions.layout(paddingLeft, actionY, paddingLeft + actions.measuredWidth, actionY + actions.measuredHeight)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        requestLayout()
    }
}
