package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.geometry.Offset
import smallthingz.ui.graphics.NoiseDrawable

/** Top panel paint, bottom-rounded child clipping and matching pointer shape. */
open class NativeFloatingPanelSurface(
    context: Context,
    private var density: Float,
    private var recipe: NativeFloatingPanelRecipe,
    palette: NativeColorScheme,
) : ViewGroup(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = recipe.surfaceColor(palette)
    }
    private val fillMotion: ViewColorTransition = ViewColorTransition(this, intArrayOf(fill.color), ::publishFill)
    private val path = Path()
    private val bounds = RectF()
    private val radii = FloatArray(8)
    private val clipped = NativeFloatingPanelClip(context)
    private val grain = NoiseDrawable(seed = recipe.grainSeed)
    val clipHost: View get() = clipped

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(clipped)
    }

    protected fun setPanelViewport(view: View) {
        check(clipped.childCount == 0)
        view.background = grain
        clipped.addView(view, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        clipped.setPadding(left, top, right, bottom)
        requestLayout()
    }

    fun bindSurface(density: Float, recipe: NativeFloatingPanelRecipe, palette: NativeColorScheme) {
        val shapeChanged = this.density != density || this.recipe.cornerRadiusDp != recipe.cornerRadiusDp
        this.density = density
        this.recipe = recipe
        fillMotion.retarget(recipe.surfaceColor(palette))
        grain.setGrainSeed(recipe.grainSeed)
        if (shapeChanged) rebuildShape()
    }

    private fun publishFill() {
        fill.color = fillMotion.color(0)
        invalidate()
    }

    fun containsShape(point: Offset): Boolean {
        val x = point.x
        val y = point.y
        if (x < 0f || y < 0f || x >= width || y >= height) return false
        val radius = recipe.cornerRadiusPx(density, width, height)
        if (y <= height - radius || x in radius..(width - radius)) return true
        val centerX = if (x < radius) radius else width - radius
        val dx = x - centerX
        val dy = y - (height - radius)
        return dx * dx + dy * dy <= radius * radius
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        clipped.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(clipped.measuredWidth, clipped.measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        clipped.layout(0, 0, width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuildShape()

    private fun rebuildShape() {
        val radius = recipe.cornerRadiusPx(density, width, height)
        for (index in 4..7) radii[index] = radius
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        path.rewind()
        path.addRoundRect(bounds, radii, Path.Direction.CW)
        clipped.setShape(path)
        invalidate()
    }

    // Keep fill outside the child clip, preserving the shape's edge coverage.
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, fill)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = true
}

private class NativeFloatingPanelClip(context: Context) : android.widget.FrameLayout(context) {
    private val shape = Path()
    private var nativeClip = false
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { fillOutline(outline) }
        }
    }
    fun setShape(path: Path) {
        shape.set(path)
        val outline = Outline()
        fillOutline(outline)
        nativeClip = outline.canClip()
        clipToOutline = nativeClip
        invalidateOutline()
        invalidate()
    }
    @Suppress("DEPRECATION")
    private fun fillOutline(outline: Outline) {
        if (Build.VERSION.SDK_INT >= 30) outline.setPath(shape) else outline.setConvexPath(shape)
    }
    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated && nativeClip) super.dispatchDraw(canvas) else {
            val checkpoint = canvas.save()
            canvas.clipPath(shape)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }
}

