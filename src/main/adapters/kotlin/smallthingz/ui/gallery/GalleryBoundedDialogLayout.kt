package smallthingz.ui.gallery

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.widget.NestedScrollView
import kotlin.math.roundToInt

/**
 * Retained dialog chrome around a bounded, independently scrolling body (for example a recycler).
 * All three slots stay attached. When chrome and the minimum body cannot fit, the whole panel
 * scrolls; the body is never measured with an unbounded height to discover its contents.
 * The caller retains dialog, focus restoration, preview lifetime and action ownership.
 */
class GalleryBoundedDialogLayout(
    context: Context,
    dark: Boolean,
    heading: View,
    body: View,
    footer: View,
    private val maximumWidthDp: Int = 560,
    private val minimumBodyHeightDp: Int = 96,
) : FrameLayout(context) {
    private val surface = GalleryDialogSurface(context, dark)
    private val panel = Panel(context, heading, body, footer)
    private val scroll = object : NestedScrollView(context) {
        private var previousGeometry: Triple<Int, Int, Int>? = null
        private var previousFocus: View? = null
        private var previousFocusBounds: Rect? = null

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val geometry = Triple(width, height, panel.height)
            // Accessibility focus can differ from keyboard focus; reveal it without moving either.
            // Only mounted views are visited, never adapter items or offscreen rows.
            val focused = accessibilityFocus(panel) ?: findFocus()?.takeIf { it !== this && it.isShown }
            val bounds = focused?.let { view ->
                Rect(0, 0, view.width, view.height).also { panel.offsetDescendantRectToMyCoords(view, it) }
            }
            if (focused != null && (previousGeometry != geometry || previousFocus !== focused || previousFocusBounds != bounds)) {
                focused.requestRectangleOnScreen(Rect(0, 0, focused.width, focused.height), true)
            }
            previousGeometry = geometry
            previousFocus = focused
            previousFocusBounds = bounds
        }
    }.apply {
        isFillViewport = false
        isSmoothScrollingEnabled = false
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    init {
        require(maximumWidthDp > 0 && minimumBodyHeightDp > 0)
        surface.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bindTheme(dark: Boolean) = surface.bindTheme(dark)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maximumWidth = dp(maximumWidthDp)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) maximumWidth
            else galleryDialogExtent(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY, maximumWidth)
        val bounded = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        val height = if (bounded) galleryDialogExtent(MeasureSpec.getSize(heightMeasureSpec),
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY, fraction = .9f) else 0
        panel.viewportHeight = height
        panel.minimumBodyHeight = dp(minimumBodyHeightDp)
        panel.setPadding(dp(GalleryDialogSurface.horizontalPaddingDp), dp(GalleryDialogSurface.verticalPaddingDp),
            dp(GalleryDialogSurface.horizontalPaddingDp), dp(GalleryDialogSurface.verticalPaddingDp))
        panel.gap = dp(GalleryDialogSurface.bodyGapDp)
        // ScrollView passes the same UNSPECIFIED spec to its child across viewport changes.
        // Invalidate that cache explicitly while keeping all live children in their parents.
        panel.forceLayout()
        surface.forceLayout()
        scroll.forceLayout()
        surface.measure(exact(width), if (bounded) exact(height) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(width, if (bounded) height else surface.measuredHeight)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private class Panel(context: Context, private val heading: View, private val body: View,
                        private val footer: View) : ViewGroup(context) {
        var viewportHeight = 0
        var minimumBodyHeight = 0
        var gap = 0
        private var headingGap = 0
        private var footerGap = 0

        init {
            require(heading !== body && heading !== footer && body !== footer)
            addView(heading)
            addView(body)
            addView(footer)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val innerWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
            fun chromeHeight(view: View): Int {
                if (view.visibility == GONE) return 0
                view.measure(exact(innerWidth), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                return view.measuredHeight
            }
            val headingHeight = chromeHeight(heading)
            val footerHeight = chromeHeight(footer)
            headingGap = if (headingHeight > 0 && body.visibility != GONE) gap else 0
            footerGap = if (footerHeight > 0 && (headingHeight > 0 || body.visibility != GONE)) gap else 0
            val chrome = paddingTop + paddingBottom + headingHeight + footerHeight + headingGap + footerGap
            val plan = galleryBoundedDialogPlan(viewportHeight, chrome,
                if (body.visibility == GONE) 0 else minimumBodyHeight)
            if (body.visibility != GONE) body.measure(exact(innerWidth), exact(plan.bodyHeight))
            setMeasuredDimension(width, plan.contentHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var top = paddingTop
            fun place(view: View) {
                if (view.visibility != GONE) {
                    view.layout(paddingLeft, top, paddingLeft + view.measuredWidth, top + view.measuredHeight)
                    top += view.measuredHeight
                }
            }
            place(heading)
            top += headingGap
            place(body)
            top += footerGap
            place(footer)
        }

        override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private companion object {
        fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

        fun accessibilityFocus(view: View): View? {
            if (!view.isShown) return null
            if (view.isAccessibilityFocused) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                accessibilityFocus(view.getChildAt(index))?.let { return it }
            }
            return null
        }
    }
}

internal data class GalleryBoundedDialogPlan(val bodyHeight: Int, val contentHeight: Int)

/** Preferred modal caps only constrain AT_MOST; an exact parent allocation remains exact. */
internal fun galleryDialogExtent(limit: Int, exact: Boolean, maximum: Int = Int.MAX_VALUE, fraction: Float = 1f): Int {
    require(limit >= 0 && maximum >= 0 && fraction > 0f && fraction <= 1f)
    return if (exact) limit else minOf(maximum, (limit * fraction).roundToInt())
}

/** Chrome is intrinsically measured at the actual width; only the body receives a fixed budget. */
internal fun galleryBoundedDialogPlan(viewportHeight: Int, chromeHeight: Int, minimumBodyHeight: Int): GalleryBoundedDialogPlan {
    require(viewportHeight >= 0 && chromeHeight >= 0 && minimumBodyHeight >= 0)
    val body = maxOf(minimumBodyHeight, (viewportHeight - chromeHeight).coerceAtLeast(0))
    return GalleryBoundedDialogPlan(body, chromeHeight + body)
}
