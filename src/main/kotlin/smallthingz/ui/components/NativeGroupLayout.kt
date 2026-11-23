package smallthingz.ui.components

import smallthingz.ui.geometry.roundedGroupGeometry

import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.DrawableWrapper
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import smallthingz.ui.animation.view.ViewColorTransition
import kotlin.math.ceil
import kotlin.math.min

private const val GROUP_PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG

/**
 * Retained grouped surface with explicit child dividers and one content clip.
 *
 * The caller owns the existing 16dp external inset and every child's padding. This layout adds
 * neither gaps nor padding. Divider flags are explicit: the source does not separate every child
 * (for example, a validation message belongs to the preceding setting).
 *
 */
class NativeGroupLayout(
    context: Context,
    private var cornerRadiusDp: Float = 16f,
    orientation: Int = LinearLayout.VERTICAL,
    private val onPaintedSurfaceChanged: (View) -> Unit = {},
) : FrameLayout(context) {
    private val fillPaint = Paint(GROUP_PAINT_FLAGS)
    private val borderPaint = Paint(GROUP_PAINT_FLAGS).apply { style = Paint.Style.STROKE }
    private val bounds = RectF()
    private val borderBounds = RectF()
    private var geometry = roundedGroupGeometry(0f, 0f, resources.displayMetrics.density, cornerRadiusDp)
    private val content = ClippedGroupContent(context)
    private var joinedEdges: NativeGroupEdges? = null
    private var joinedShape: JoinedGroupShape? = null
    @ColorInt private var joinedSeparatorColor = 0
    private var paletteTransition: ViewColorTransition? = null
    private val progressPaint = Paint(GROUP_PAINT_FLAGS)
    private var progressFraction = 0f

    fun setBackgroundProgress(fraction: Float, color: Int) {
        val wasCovered=progressFraction>0f
        progressFraction = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        progressPaint.color = color
        if(wasCovered!=(progressFraction>0f))onPaintedSurfaceChanged(this)
        invalidate()
    }

    init {
        require(cornerRadiusDp.isFinite() && cornerRadiusDp >= 0f)
        content.orientation = orientation
        setWillNotDraw(false)
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        // Keep the fill and outer border outside this child layer, exactly as Surface's
        // border -> background -> graphicsLayer(clip) modifier chain does.
        super.addView(
            content,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // AndroidComposeView explicitly disables Force Dark; preserve that per-view contract.
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun addRow(child: View, params: LayoutParams) {
        content.addView(child, params)
    }

    fun addRow(index: Int, child: View, params: LayoutParams) {
        content.addView(child, index, params)
    }

    fun removeRow(child: View) {
        content.removeView(child)
    }

    fun setRowOrientation(orientation: Int) {
        require(orientation == LinearLayout.HORIZONTAL || orientation == LinearLayout.VERTICAL)
        if (content.orientation != orientation) content.orientation = orientation
    }

    fun setCornerRadiusDp(value: Float) {
        require(value.isFinite() && value >= 0f)
        if (cornerRadiusDp == value) return
        cornerRadiusDp = value
        updateShape()
        invalidate()
    }

    /** Actual source clip, shared with the window's phase hit tree; no geometry approximation. */
    fun containsVisualPoint(x: Float, y: Float): Boolean =
        if (joinedEdges == null) geometry.contains(x, y)
        else checkNotNull(joinedShape).acceptsPointer(x, y, false, 0f)

    /** Surface's content layer, rather than its outer draw/border layer. Read-only identity. */
    val pointerContent: ViewGroup get() = content

    /** The sampled fill currently painted, including an in-flight palette transition. */
    val paintedFillColor: Int get() = fillPaint.color

    /** Corner ownership of an anchored child, in physical TL/TR/BR/BL order. */
    internal fun descendantCornerRadii(child: View): FloatArray {
        val box = Rect(0, 0, child.width, child.height)
        offsetDescendantRectToMyCoords(child, box)
        val joined = joinedEdges?.let { joinedGroupGeometry(width.toFloat(), height.toFloat(), resources.displayMetrics.density, it) }
        val top = joined?.topRadius ?: geometry.radius
        val bottom = joined?.bottomRadius ?: geometry.radius
        return floatArrayOf(
            if (box.left == 0 && box.top == 0) top else 0f,
            if (box.right == width && box.top == 0) top else 0f,
            if (box.right == width && box.bottom == height) bottom else 0f,
            if (box.left == 0 && box.bottom == height) bottom else 0f,
        )
    }
    /** Progress overlays are not a uniform text underlay; leave their existing ink owner intact. */
    val paintedTextSurface: Long get() = if (progressFraction == 0f)
        smallthingz.ui.controls.textSurfaceRange(fillPaint.color) else 0L

    fun setPalette(@ColorInt field: Int, @ColorInt border: Int) {
        val motion=paletteTransition ?: ViewColorTransition(this,intArrayOf(field,border),{
            paletteTransition?.let { applyPalette(it.color(0),it.color(1)) }
        }).also { paletteTransition=it;applyPalette(field,border) }
        motion.retarget(field,border)
    }

    /** Multi-channel frame owners can publish already sampled paint without another transition. */
    fun setPaletteImmediately(@ColorInt field:Int,@ColorInt border:Int) {
        paletteTransition?.close();paletteTransition=null
        applyPalette(field,border)
    }

    private fun applyPalette(field:Int,border:Int) {
        val fillChanged = fillPaint.color != field
        if (fillPaint.color != field || borderPaint.color != border) {
            fillPaint.color = field
            if (fillChanged) onPaintedSurfaceChanged(this)
            borderPaint.color = border
            invalidate()
        }
        content.setDividerColor(border)
    }

    /** Null restores the uniform surface; joined rows share first/last edge decoration. */
    fun setJoinedEdges(edges: NativeGroupEdges?) {
        if (joinedEdges == edges) return
        joinedEdges = edges
        if (edges != null && joinedShape == null) joinedShape = JoinedGroupShape()
        updateShape()
        invalidate()
    }

    fun setJoinedSeparatorColor(@ColorInt color: Int) {
        if (joinedSeparatorColor == color) return
        joinedSeparatorColor = color
        if (joinedEdges != null) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShape()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateShape()
        invalidate()
    }

    private fun updateShape() {
        geometry = roundedGroupGeometry(width.toFloat(), height.toFloat(), resources.displayMetrics.density, cornerRadiusDp)
        bounds.set(0f, 0f, geometry.width, geometry.height)
        borderBounds.set(bounds)
        borderBounds.inset(geometry.strokeWidth / 2f, geometry.strokeWidth / 2f)
        borderPaint.strokeWidth = geometry.strokeWidth
        val edges = joinedEdges
        if (edges == null) content.setCornerRadiusPx(geometry.radius) else {
            val shape = checkNotNull(joinedShape)
            shape.update(width.toFloat(), height.toFloat(), resources.displayMetrics.density, edges)
            content.setJoinedShape(shape)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (joinedEdges == null) canvas.drawRoundRect(bounds, geometry.radius, geometry.radius, fillPaint)
        else checkNotNull(joinedShape).drawFill(canvas, fillPaint)
        if (progressFraction > 0f) {
            val save = canvas.save()
            val extent = width * progressFraction
            if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.clipRect(width - extent, 0f, width.toFloat(), height.toFloat())
            else canvas.clipRect(0f, 0f, extent, height.toFloat())
            if (joinedEdges == null) canvas.drawRoundRect(bounds, geometry.radius, geometry.radius, progressPaint)
            else checkNotNull(joinedShape).drawFill(canvas, progressPaint)
            canvas.restoreToCount(save)
        }
        super.onDraw(canvas)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (joinedEdges != null) {
            // Joined-row outlines use raw 1.dp; uniform borders retain rounded Surface strokes.
            checkNotNull(joinedShape).drawBorder(canvas, borderPaint.color, joinedSeparatorColor)
            return
        }
        // Compose's border modifier draws after its content, including the per-row dividers.
        if (geometry.strokeWidth == 0f) return
        if (geometry.fillBorder) {
            borderPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(bounds, geometry.radius, geometry.radius, borderPaint)
            borderPaint.style = Paint.Style.STROKE
        } else {
            canvas.drawRoundRect(borderBounds, geometry.strokeRadius, geometry.strokeRadius, borderPaint)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Clipping drawing alone does not clip Android's rectangular child hit testing. Admit a
        // gesture only inside the same outer shape; an admitted gesture keeps normal View ownership.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val touch = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
            val minimumTarget = 48f * resources.displayMetrics.density
            val accepted = if (joinedEdges == null) geometry.acceptsPointer(event.x, event.y, touch, minimumTarget)
                else checkNotNull(joinedShape).acceptsPointer(event.x, event.y, touch, minimumTarget)
            if (!accepted) return false
        }
        return super.dispatchTouchEvent(event)
    }

    // Material Surface's empty pointerInput node shields underlying siblings even in inert areas.
    // This does not make the group clickable or intercept child/ancestor scroll ownership.
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    class LayoutParams : LinearLayout.LayoutParams {
        var dividerTop = false
        var dividerStart = false

        constructor(width: Int, height: Int, weight: Float = 0f) : super(width, height, weight)

        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

        constructor(source: ViewGroup.LayoutParams) : super(source)

        constructor(source: LinearLayout.LayoutParams) : super(source) {
            if (source is LayoutParams) {
                dividerTop = source.dividerTop
                dividerStart = source.dividerStart
            }
        }
    }
}

/**
 * Compose's simple round-rect graphics layer uses Android Outline hardware clipping. An always-on
 * Canvas.clipPath is not equivalent at fractional radii (the physical-device parity fixture caught
 * the edge coverage difference). A normal child View supplies that same outline-backed layer on
 * every supported API, without a separate RenderNode implementation or an offscreen bitmap.
 */
private class ClippedGroupContent(context: Context) : NativeDividerLayout(context) {
    private var radius = 0f
    private var joinedShape: JoinedGroupShape? = null
    private var nativeClip = true
    private val softwareClip = Path()
    private val softwareBounds = RectF()

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) {
        val firstAdded = outChildren.size
        super.addChildrenForAccessibility(outChildren)
        // A wholly clipped source row has no node. Never advertise its native ID and then return
        // null when the service resolves it; preserve every unrelated/non-row child unchanged.
        for (index in outChildren.lastIndex downTo firstAdded) {
            val child = outChildren[index]
            if (child is NativeMergedRowAccessibilityHost && !child.mergedRowAccessibility.isNodePresent()) {
                outChildren.removeAt(index)
            }
        }
    }

    init {
        orientation = VERTICAL
        // Surface clips this entire content layer. Its Column does not additionally clip each
        // Row to its layout bounds; ViewGroup's default would set that on every child RenderNode.
        clipChildren = false
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                fillOutline(outline)
            }
        }
    }

    fun setCornerRadiusPx(value: Float) {
        if (joinedShape == null && radius == value) return
        joinedShape = null
        radius = value
        rebuildClip()
    }

    fun setJoinedShape(value: JoinedGroupShape) {
        joinedShape = value
        rebuildClip()
    }

    @Suppress("DEPRECATION")
    private fun fillOutline(outline: Outline) {
        val shape = joinedShape
        if (shape == null) {
            outline.setRoundRect(0, 0, width, height, radius)
        } else if (width <= 0 || height <= 0) {
            outline.setEmpty()
        } else if (shape.topRadius == shape.bottomRadius) {
            outline.setRoundRect(0, 0, width, height, shape.topRadius)
        } else if (Build.VERSION.SDK_INT >= 30) {
            outline.setPath(softwareClip)
        } else if (Build.VERSION.SDK_INT > 28 || softwareClip.isConvex) {
            outline.setConvexPath(softwareClip)
        } else outline.setEmpty()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildClip()
    }

    private fun rebuildClip() {
        val shape = joinedShape
        if (shape == null) {
            softwareBounds.set(0f, 0f, width.toFloat(), height.toFloat())
            softwareClip.rewind()
            softwareClip.addRoundRect(softwareBounds, radius, radius, Path.Direction.CW)
            nativeClip = true
        } else {
            shape.copyFillPath(softwareClip)
            val outline = Outline()
            fillOutline(outline)
            nativeClip = outline.canClip()
        }
        clipToOutline = nativeClip
        invalidateOutline()
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated && nativeClip) {
            // View's RenderNode applies the rounded outline around this entire child subtree.
            super.dispatchDraw(canvas)
        } else {
            // Compose's ViewLayer/OutlineResolver uses the path fallback for software Canvas too.
            val checkpoint = canvas.save()
            canvas.clipPath(softwareClip)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }
}

/** Draws the same explicit top/start strokes as Modifier.settingsAudioDivider, after each child. */
open class NativeDividerLayout(context: Context) : LinearLayout(context) {
    @ColorInt private var dividerColor = 0

    init {
        isBaselineAligned = false
        isFocusable = false
        isClickable = false
        // The surface is not a second actionable accessibility node. NO preserves its descendants;
        // NO_HIDE_DESCENDANTS would incorrectly remove the child controls from accessibility.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    fun setDividerColor(@ColorInt color: Int) {
        if (dividerColor == color) return
        dividerColor = color
        for (index in 0 until childCount) updateDivider(getChildAt(index))
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        updateDivider(child)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        for (index in 0 until childCount) updateDivider(getChildAt(index))
    }

    override fun onViewRemoved(child: View) {
        val foreground = child.foreground
        if (foreground is GroupDividerForeground && foreground.owner === this) {
            restoreForeground(child, foreground)
        }
        super.onViewRemoved(child)
    }

    private fun updateDivider(child: View) {
        val params = child.layoutParams as? NativeGroupLayout.LayoutParams
        val foreground = child.foreground
        if (params == null || (!params.dividerTop && !params.dividerStart)) {
            if (foreground is GroupDividerForeground && foreground.owner === this) {
                restoreForeground(child, foreground)
            }
            return
        }
        val divider = if (foreground is GroupDividerForeground && foreground.owner === this) {
            foreground
        } else {
            GroupDividerForeground(this, child).also {
                // Install first: View.setForeground clears the old drawable's callback. Wrapping
                // it beforehand would accidentally clear DrawableWrapper's callback ownership.
                child.foreground = it
                it.drawable = foreground
                child.requestLayout()
            }
        }
        divider.update(dividerColor, params.dividerTop, params.dividerStart)
    }

    private fun restoreForeground(child: View, foreground: GroupDividerForeground) {
        val original = foreground.drawable
        foreground.drawable = null
        child.foreground = original
    }

    override fun generateDefaultLayoutParams(): NativeGroupLayout.LayoutParams =
        NativeGroupLayout.LayoutParams(super.generateDefaultLayoutParams())

    override fun generateLayoutParams(attrs: AttributeSet?): NativeGroupLayout.LayoutParams =
        NativeGroupLayout.LayoutParams(context, attrs)

    override fun generateLayoutParams(params: ViewGroup.LayoutParams): NativeGroupLayout.LayoutParams =
        if (params is LinearLayout.LayoutParams) NativeGroupLayout.LayoutParams(params)
        else NativeGroupLayout.LayoutParams(params)

    override fun checkLayoutParams(params: ViewGroup.LayoutParams): Boolean =
        params is NativeGroupLayout.LayoutParams
}

/**
 * A child-owned decoration, not a wrapper View. The row and its divider must enter the same native
 * alpha/transform/layer: painting a separately alpha-scaled divider from the parent is not equivalent
 * when translucent content overlaps it. DrawableWrapper retains existing ripple, state and bounds.
 */
private class GroupDividerForeground(
    val owner: NativeDividerLayout,
    private val child: View,
) : DrawableWrapper(null) {
    private val paint = Paint(GROUP_PAINT_FLAGS).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        strokeCap = Paint.Cap.BUTT
    }
    private val dirtyBounds = Rect()
    private var top = false
    private var start = false

    fun update(@ColorInt color: Int, top: Boolean, start: Boolean) {
        if (paint.color == color && this.top == top && this.start == start) return
        paint.color = color
        this.top = top
        this.start = start
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        // Use full child bounds even if an existing foreground has CENTER gravity/intrinsic size.
        // Its own bounds remain unchanged; native View does not translate the foreground canvas.
        if (top) canvas.drawLine(0f, 0.5f, child.width.toFloat(), 0.5f, paint)
        if (start) {
            val x = groupStartDividerX(child.width.toFloat(), child.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            canvas.drawLine(x, 0f, x, child.height.toFloat(), paint)
        }
    }

    override fun getDirtyBounds(): Rect = dirtyBounds.apply { set(0, 0, child.width, child.height) }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun groupStartDividerX(width: Float, rtl: Boolean): Float =
    if (rtl) width - 0.5f else 0.5f
