/*
 * Generic border algorithm adapted from AndroidX foundation 1.11.2 Border.kt.
 * Copyright 2020 The Android Open Source Project; Apache License 2.0.
 */
package smallthingz.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.phase.NativeNestedPointerRoot
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import kotlin.math.ceil
import smallthingz.ui.graphics.ViewGrain
import smallthingz.ui.graphics.bindGrainLayer
import kotlin.math.min
import kotlin.math.roundToInt
import smallthingz.ui.animation.view.ViewColorTransition

/**
 * Exact released two-lobe fill and pinned generic-path border. Fill/border are outside the child
 * clipping layer. This does not replace the shape with a rounded rectangle or center-line stroke.
 */
abstract class NativeCaptureControlSurface(context: Context) : ViewGroup(context) {
    // This existing clipped content is also the retained-pointer phase owner. Android's ordinary
    // rectangular child dispatch cannot reach a 40dp tab's source-expanded 48dp touch target.
    protected val inputCoordinates: NativeWindowPointerCoordinates = NativeWindowPointerCoordinates { content }
    private val inputLayout: NativeViewPointerHitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = content.isAttachedToWindow && content.visibility == VISIBLE
        override val size get() = Size(width.toFloat(), height.toFloat())
        override val minimumTouchTarget get() = Size.Zero
        override val nodes = emptyList<NativeViewPhasePointerNode>()
        override val childrenInHitOrder get() = childHitLayouts()
        override fun screenToLocal(screen: Offset) = inputCoordinates.coordinates(content).local(screen)
        override fun withinLayerBounds(local: Offset) = contains(local.x, local.y)
    }
    protected val content: ControlContent = ControlContent(context, inputCoordinates, inputLayout)
    protected abstract fun childHitLayouts(): List<NativeViewPointerHitLayout>
    private val fill = Paint(FLAGS)
    private val borderPaint = Paint(FLAGS)
    private val rasterPaint = Paint(FLAGS)
    private val shape = Path()
    private var grain: ViewGrain? = null
    private var paintedSurfaceRange = 0L
    private val mask = Path()
    private val bounds = RectF()
    private val sourceRect = Rect()
    private val destinationRect = RectF()
    private var borderColorFilter: PorterDuffColorFilter? = null
    private val hitPoint = Path()
    private val hitIntersection = Path()
    private var bitmap: Bitmap? = null
    private var surfaceBound = false
    private var colors: ViewColorTransition? = null
    private var displayedBorder = 0
    private var density = resources.displayMetrics.density
    private var fullBorder = false
    private var borderDirty = true
    private var borderTint = 0
    private var style = NativeCaptureControlsStyle.Default
    private var metrics = style.resolve(density, NativeBufferSegmentStyle())
    private var selectors: List<View> = emptyList()
    private var actions: List<View> = emptyList()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(content)
    }

    protected fun addControlRows(selectors: List<View>, actions: List<View>) {
        this.selectors = selectors
        this.actions = actions
        selectors.forEach(content::addView)
        actions.forEach(content::addView)
    }

    protected fun bindSurface(
        palette: NativeColorScheme,
        density: Float,
        style: NativeCaptureControlsStyle,
        segmentStyle: NativeBufferSegmentStyle,
    ) {
        surfaceBound = true
        grain = bindGrainLayer(grain, palette.surfacePreset?.foregroundGrain ?: 0f)
        val motion = colors ?: ViewColorTransition(this, intArrayOf(palette.field, palette.border), {
            colors?.let { applyColors(it.color(0), it.color(1)) }
        }).also {
            colors = it
            applyColors(palette.field, palette.border)
        }
        motion.retarget(palette.field, palette.border)
        publishSurfaceRange()
        if (this.density != density) borderDirty = true
        this.density = density
        val nextMetrics = style.resolve(density, segmentStyle)
        if (metrics != nextMetrics) {
            metrics = nextMetrics
            requestLayout()
        }
        if (this.style != style) {
            this.style = style
            if (width > 0 && height > 0) updateShape(width, height)
        }
        invalidate()
    }

    private fun applyColors(background: Int, border: Int) {
        fill.color = background
        publishSurfaceRange()
        if (displayedBorder != border) {
            displayedBorder = border
            borderDirty = true
        }
        invalidate()
    }

    /** Descendant ink follows this sampled fill, including palette-only grain changes. */
    protected open fun onSurfacePaintChanged(surface: Long) = Unit

    private fun publishSurfaceRange() {
        val range = grain?.surfaceRange(fill.color)
            ?: smallthingz.ui.controls.textSurfaceRange(fill.color)
        if (paintedSurfaceRange == range) return
        paintedSurfaceRange = range
        onSurfacePaintChanged(range)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        updateShape(w, h)
    }

    private fun updateShape(w: Int, h: Int) {
        captureControlUnionPath(w.toFloat(), h.toFloat(), style, shape)
        shape.computeBounds(bounds, false)
        content.setShape(shape)
        borderDirty = true
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = resolveSize(metrics.preferredWidth, w)
        val height = resolveSize(metrics.preferredHeight, h)
        setMeasuredDimension(width, height)
        content.measure(exact(width), exact(height))
        measureRow(selectors, min(metrics.selectorWidth, width), min(metrics.selectorHeight, height),
            metrics.selectorSpacing, metrics.segmentWidth, MeasureSpec.AT_MOST)
        measureRow(actions, min(metrics.preferredWidth, width), min(metrics.actionHeight, height),
            metrics.actionSpacing, metrics.actionSize, MeasureSpec.EXACTLY)
    }

    private fun measureRow(views: List<View>, width: Int, height: Int, spacing: Int, childWidth: Int, heightMode: Int) {
        var remaining = (width - spacing * 2).coerceAtLeast(0)
        val innerHeight = (height - spacing * 2).coerceAtLeast(0)
        val childHeight = if (heightMode == MeasureSpec.EXACTLY) min(childWidth, innerHeight) else innerHeight
        val heightSpec = MeasureSpec.makeMeasureSpec(childHeight, heightMode)
        for (index in views.indices) {
            val child = views[index]
            child.measure(exact(min(childWidth, remaining)), heightSpec)
            remaining = (remaining - child.measuredWidth - spacing).coerceAtLeast(0)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(0, 0, width, height)
        layoutRow(selectors, min(metrics.selectorWidth, width), 0, min(metrics.selectorHeight, height),
            metrics.selectorSpacing)
        val actionHeight = min(metrics.actionHeight, height)
        layoutRow(actions, min(metrics.preferredWidth, width), height - actionHeight, actionHeight,
            metrics.actionSpacing)
    }

    private fun layoutRow(views: List<View>, rowWidth: Int, rowTop: Int, rowHeight: Int, spacing: Int) {
        val rowLeft = ((width - rowWidth) / 2f).roundToInt()
        val innerHeight = (rowHeight - spacing * 2).coerceAtLeast(0)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var start = spacing
        for (index in views.indices) {
            val child = views[index]
            val x = rowLeft + if (rtl) rowWidth - start - child.measuredWidth else start
            val y = rowTop + spacing + ((innerHeight - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            start += child.measuredWidth + spacing
        }
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        requestLayout()
    }

    private fun exact(size: Int): Int = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(shape, fill)
        grain?.draw(canvas, shape, fill.alpha)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (!surfaceBound) return
        val color = displayedBorder
        if (width <= 0 || height <= 0) return
        if (borderDirty) updateBorder(color)
        if (fullBorder) {
            borderPaint.colorFilter = null
            borderPaint.color = color
            canvas.drawPath(shape, borderPaint)
        } else bitmap?.let { image ->
            borderPaint.color = android.graphics.Color.BLACK
            borderPaint.colorFilter = borderColorFilter
            val checkpoint = canvas.save()
            canvas.translate(bounds.left, bounds.top)
            canvas.drawBitmap(image, sourceRect, destinationRect, borderPaint)
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun updateBorder(color: Int) {
        borderDirty = false
        val strokeWidth = min(ceil(density), ceil(min(width, height) / 2f))
        fullBorder = strokeWidth * 2 > min(width, height)
        if (fullBorder) return
        val cacheWidth = ceil(bounds.width()).toInt()
        val cacheHeight = ceil(bounds.height()).toInt()
        if (cacheWidth <= 0 || cacheHeight <= 0) return
        var image = bitmap
        if (image == null || image.width < cacheWidth || image.height < cacheHeight) {
            // Old display lists may retain the old Bitmap; do not recycle it before their retirement.
            image = Bitmap.createBitmap(cacheWidth, cacheHeight, Bitmap.Config.ALPHA_8)
            bitmap = image
        }
        image.eraseColor(android.graphics.Color.TRANSPARENT)
        val cache = Canvas(image)
        sourceRect.set(0, 0, cacheWidth, cacheHeight)
        destinationRect.set(0f, 0f, cacheWidth.toFloat(), cacheHeight.toFloat())
        mask.rewind()
        mask.addRect(bounds, Path.Direction.CCW)
        mask.op(shape, Path.Op.DIFFERENCE)
        val saved = cache.save()
        cache.clipRect(0, 0, cacheWidth, cacheHeight)
        cache.translate(-bounds.left, -bounds.top)
        rasterPaint.color = color
        rasterPaint.style = Paint.Style.STROKE
        rasterPaint.strokeWidth = strokeWidth * 2
        rasterPaint.strokeCap = Paint.Cap.BUTT
        rasterPaint.strokeJoin = Paint.Join.MITER
        rasterPaint.strokeMiter = 4f
        rasterPaint.xfermode = null
        cache.drawPath(shape, rasterPaint)
        // DrawScope scale uses the cache's logical size, not the retained larger allocation.
        cache.scale((cacheWidth + 1f) / cacheWidth, (cacheHeight + 1f) / cacheHeight,
            cacheWidth / 2f, cacheHeight / 2f)
        rasterPaint.style = Paint.Style.FILL
        rasterPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        cache.drawPath(mask, rasterPaint)
        rasterPaint.xfermode = null
        cache.restoreToCount(saved)
        borderTint = color or -0x1000000
        borderColorFilter = PorterDuffColorFilter(borderTint, PorterDuff.Mode.SRC_IN)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !contains(event.x, event.y)) return false
        return super.dispatchTouchEvent(event)
    }

    private fun contains(x: Float, y: Float): Boolean {
        // Source ShapeContainingUtil generic path admission is a tiny intersecting square.
        hitPoint.rewind()
        hitPoint.addRect(x - .005f, y - .005f, x + .005f, y + .005f, Path.Direction.CCW)
        hitIntersection.op(shape, hitPoint, Path.Op.INTERSECT)
        return !hitIntersection.isEmpty
    }

    override fun onTouchEvent(event: MotionEvent) = true // Surface inert-area shield.

    protected class ControlContent(
        context: Context,
        coordinates: NativeWindowPointerCoordinates,
        hitLayout: NativeViewPointerHitLayout,
    ) : NativeNestedPointerRoot(context, coordinates, hitLayout) {
        private val shape = Path()
        private var manualClip = false
        private val sourceOutline = Outline()
        init {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.set(sourceOutline) }
            }
        }
        fun setShape(value: Path) {
            shape.set(value)
            sourceOutline.setEmpty()
            if (Build.VERSION.SDK_INT >= 30) sourceOutline.setPath(shape)
            else if (Build.VERSION.SDK_INT > 28 || shape.isConvex) {
                @Suppress("DEPRECATION")
                sourceOutline.setConvexPath(shape)
            }
            manualClip = (Build.VERSION.SDK_INT <= 28 && !shape.isConvex) || !sourceOutline.canClip()
            clipToOutline = !manualClip
            invalidateOutline()
            invalidate()
        }
        override fun dispatchDraw(canvas: Canvas) {
            if (!manualClip) { super.dispatchDraw(canvas); return }
            val checkpoint = canvas.save()
            canvas.clipPath(shape)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }
        // The capture surface measures and positions the actual row children.
        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
    }

    private companion object {
        const val FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
    }
}

/** Literal captureControlUnionShape path, evaluated in the actual constrained surface size. */
private fun captureControlUnionPath(width: Float, height: Float, style: NativeCaptureControlsStyle, destination: Path) {
    val centerX = width * .5f
    val topHeight = style.outlineSelectorHeight(height)
    val bottomTop = style.outlineActionTop(height)
    val topWidth = style.outlineSelectorWidth(width)
    val bottomWidth = width
    val topLeft = centerX - topWidth * .5f
    val topRight = centerX + topWidth * .5f
    val bottomLeft = centerX - bottomWidth * .5f
    val bottomRight = centerX + bottomWidth * .5f
    val topRadius = topHeight * .40f
    val bottomRadius = (height - bottomTop) * .40f
    val squircleControl = .44f
    val transitionStart = topHeight * .52f
    val transitionEnd = bottomTop + bottomRadius * .62f
    val transitionSpan = (transitionEnd - transitionStart).coerceAtLeast(1f)
    val handle = transitionSpan * .46f
    destination.rewind()
    destination.moveTo(topLeft + topRadius, 0f)
    destination.lineTo(topRight - topRadius, 0f)
    destination.cubicTo(topRight - topRadius * squircleControl, 0f,
        topRight, topRadius * squircleControl, topRight, topRadius)
    destination.lineTo(topRight, transitionStart)
    destination.cubicTo(topRight, transitionStart + handle,
        bottomRight, transitionEnd - handle, bottomRight, transitionEnd)
    destination.lineTo(bottomRight, height - bottomRadius)
    destination.cubicTo(bottomRight, height - bottomRadius * squircleControl,
        bottomRight - bottomRadius * squircleControl, height, bottomRight - bottomRadius, height)
    destination.lineTo(bottomLeft + bottomRadius, height)
    destination.cubicTo(bottomLeft + bottomRadius * squircleControl, height,
        bottomLeft, height - bottomRadius * squircleControl, bottomLeft, height - bottomRadius)
    destination.lineTo(bottomLeft, transitionEnd)
    destination.cubicTo(bottomLeft, transitionEnd - handle, topLeft, transitionStart + handle,
        topLeft, transitionStart)
    destination.lineTo(topLeft, topRadius)
    destination.cubicTo(topLeft, topRadius * squircleControl, topLeft + topRadius * squircleControl,
        0f, topLeft + topRadius, 0f)
    destination.close()
}
