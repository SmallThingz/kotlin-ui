package smallthingz.ui.gallery

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import smallthingz.ui.components.NativeToolbarStyle
import kotlin.math.roundToInt

/**
 * Retained video editor slots. Normal controls scroll below a fixed preview/header. When their
 * measured minima cannot fit, the body scrolls as one column and controls have no nested range.
 * The header joins scrolling when pinning it would leave no usable action viewport.
 * No slot is ever reparented.
 */
class GalleryVideoEditorLayout(
    context: Context,
    private val header: View,
    private val preview: View,
    private val controls: View,
    private val measureTransportHeight: (widthPx: Int) -> Int,
    private val preferredPreviewHeight: (widthPx: Int, availableHeightPx: Int, transportHeightPx: Int) -> Int,
) : ScrollView(context) {
    private var viewportHeight: Int? = null
    private var plan = GalleryVideoEditorPlan(0, 0, false)
    private val controlsScroll = ScrollView(context).apply {
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(controls, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val body = object : ViewGroup(context) {
        init {
            addView(preview)
            addView(controlsScroll)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            preview.measure(exact(width), exact(plan.previewHeight))
            controlsScroll.forceLayout()
            controlsScroll.measure(exact(width), exact(plan.controlsHeight))
            setMeasuredDimension(width, plan.contentHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            preview.layout(0, 0, r - l, plan.previewHeight)
            controlsScroll.layout(0, plan.previewHeight, r - l, plan.contentHeight)
        }

        override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private val bodyScroll = ScrollView(context).apply {
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(body, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val column = object : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            header.measure(exact(width), natural)
            val headerHeight = if (header.visibility == GONE) 0 else header.measuredHeight
            controls.measure(exact(width), natural)
            val controlsHeight = if (controls.visibility == GONE) 0 else controls.measuredHeight
            val visiblePreview = preview.visibility != GONE
            val transport = if (visiblePreview) measureTransportHeight(width).coerceAtLeast(0) else 0
            val density = resources.displayMetrics.density
            // One 48dp interaction target plus the shared 4dp edges: a usable 56dp image strip.
            val minimumImage = ((NativeToolbarStyle.Default.action.minimumTargetDp +
                2 * NativeToolbarStyle.Default.centeredPaddingDp) * density).roundToInt()
            val viewport = viewportHeight
            // A positive strip is not a usable viewport: a complete measured action must fit.
            val minimumControls = maxOf(minimumImage, measuredActionHeight(controls))
            val minimumBody = maxOf(minimumControls, transport)
            val bodyHeight = galleryVideoEditorBodyViewport(viewport, headerHeight, minimumBody)
            val preferred = if (bodyHeight != null && visiblePreview)
                preferredPreviewHeight(width, bodyHeight, transport) else transport + minimumImage
            plan = galleryVideoEditorPlan(bodyHeight, preferred, transport, minimumImage,
                minimumControls, controlsHeight, visiblePreview)
            body.forceLayout()
            bodyScroll.forceLayout()
            bodyScroll.measure(exact(width), exact(bodyHeight ?: plan.contentHeight))
            setMeasuredDimension(width, headerHeight + bodyScroll.measuredHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val headerHeight = if (header.visibility == GONE) 0 else header.measuredHeight
            header.layout(0, 0, r - l, headerHeight)
            bodyScroll.layout(0, headerHeight, r - l, headerHeight + bodyScroll.measuredHeight)
        }

        override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    init {
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        column.addView(header)
        column.addView(bodyScroll)
        addView(column, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        viewportHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) null
            else (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        // ScrollView passes UNSPECIFIED to its child even when its own viewport changes.
        column.forceLayout()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), MeasureSpec.EXACTLY)

    private fun measuredActionHeight(view: View): Int {
        if (view.visibility != VISIBLE) return 0
        var height = if (view.isClickable || view.isFocusable) view.measuredHeight else 0
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            height = maxOf(height, measuredActionHeight(view.getChildAt(index)))
        }
        return height
    }
}

/** Null selects whole-column scrolling when a pinned header would leave an unusable strip. */
internal fun galleryVideoEditorBodyViewport(viewportHeight: Int?, headerHeight: Int, minimumBodyHeight: Int): Int? =
    viewportHeight?.minus(headerHeight)?.takeIf { it >= minimumBodyHeight && it > 0 }

internal data class GalleryVideoEditorPlan(val previewHeight: Int, val controlsHeight: Int, val overflow: Boolean) {
    val contentHeight: Int get() = previewHeight + controlsHeight
}

/** Measured pixel policy only: overflow must not create a smaller nested controls viewport. */
internal fun galleryVideoEditorPlan(availableHeight: Int?, preferredPreviewHeight: Int,
    transportHeight: Int, minimumImageHeight: Int, minimumControlsHeight: Int,
    naturalControlsHeight: Int, previewVisible: Boolean = true): GalleryVideoEditorPlan {
    require(availableHeight == null || availableHeight >= 0)
    require(transportHeight >= 0 && minimumImageHeight >= 0 && minimumControlsHeight >= 0 && naturalControlsHeight >= 0)
    val previewMinimum = if (previewVisible) transportHeight + minimumImageHeight else 0
    val controlsMinimum = minOf(minimumControlsHeight, naturalControlsHeight)
    if (availableHeight == null || availableHeight < previewMinimum + controlsMinimum) {
        return GalleryVideoEditorPlan(previewMinimum, naturalControlsHeight, availableHeight != null)
    }
    val previewHeight = if (previewVisible)
        preferredPreviewHeight.coerceIn(previewMinimum, availableHeight - controlsMinimum) else 0
    return GalleryVideoEditorPlan(previewHeight, availableHeight - previewHeight, false)
}
