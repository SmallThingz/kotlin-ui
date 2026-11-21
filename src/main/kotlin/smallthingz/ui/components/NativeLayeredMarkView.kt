package smallthingz.ui.components

import smallthingz.ui.animation.view.ViewColorTransition

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

data class NativeLayeredMarkPalette(val background: Int, val accent: Int, val main: Int)

/** One stroke layer in caller-supplied brand art. Paths are stroked separately in each layer. */
data class NativeLayeredMarkStroke(val translationX: Float, val opacity: Float, val accent: Boolean)

class NativeLayeredMarkArt(
    val sizeDp: Float,
    val viewport: Float,
    val strokeWidth: Float,
    val originX: Float,
    paths: List<Path>,
    layers: List<NativeLayeredMarkStroke>,
) {
    internal val paths = paths.map(::Path)
    internal val layers = layers.toList()
}

/** Circular FillBounds mark with a retained full-color cache and live resource color resolution. */
open class NativeLayeredMarkView(
    context: Context,
    description: CharSequence?,
    private val art: NativeLayeredMarkArt,
    private val resolvePalette: (Context) -> NativeLayeredMarkPalette,
) : View(context) {
    private var palette = resolvePalette(context)
    private var paletteTarget = palette
    private val paletteMotion:ViewColorTransition by lazy {ViewColorTransition(this,
        intArrayOf(palette.background,palette.accent,palette.main),{
            palette=NativeLayeredMarkPalette(paletteMotion.color(0),paletteMotion.color(1),paletteMotion.color(2))
            cacheDirty=true;invalidate()
        })}
    private val backgroundPaint = Paint(BRAND_PAINT_FLAGS)
    private val strokePaint = Paint(BRAND_PAINT_FLAGS).apply {
        style = Paint.Style.STROKE
        strokeWidth = art.strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeMiter = 4f
    }
    private val bitmapPaint = Paint(BRAND_PAINT_FLAGS)
    private val clearPaint = Paint(BRAND_PAINT_FLAGS).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val softwareClip = Path()
    private val bounds = RectF()
    private val cacheRect = Rect()
    private var bitmap: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var cacheDirty = true
    private var previousDrawWidth = Float.NaN
    private var previousDrawHeight = Float.NaN
    private var geometry = nativeLayeredMarkBounds(art.sizeDp, resources.displayMetrics.density, 0, 0)
    private var cornerRadius = 0f

    init {
        isClickable = false
        isLongClickable = false
        isFocusable = false
        contentDescription = description
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    override fun setContentDescription(contentDescription: CharSequence?) {
        super.setContentDescription(contentDescription)
        importantForAccessibility = if (contentDescription == null) {
            IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.ImageView"

    /** Re-resolves the same three released resources, without selecting a new palette locally. */
    fun refreshResourcePalette() {
        val next = resolvePalette(context)
        if (next != paletteTarget) {
            paletteTarget=next
            paletteMotion.retarget(next.background,next.accent,next.main)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshResourcePalette()
        updateGeometry()
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A retained View may have been detached while its resource configuration changed.
        refreshResourcePalette()
        updateGeometry()
    }

    override fun onDetachedFromWindow() {
        // Drop our references; do not recycle a bitmap still referenced by a hardware display list.
        bitmap = null
        cacheCanvas = null
        cacheDirty = true
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val intrinsic = nativeLayeredMarkIntrinsicPx(art.sizeDp, resources.displayMetrics.density).roundToInt()
        fun dimension(spec: Int): Int = when (MeasureSpec.getMode(spec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec)
            else -> intrinsic
        }
        // Image.fillMaxSize consumes bounded constraints; intrinsic size supplies an unbounded axis.
        setMeasuredDimension(dimension(widthMeasureSpec), dimension(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry()
    }

    private fun updateGeometry() {
        geometry = nativeLayeredMarkBounds(art.sizeDp, resources.displayMetrics.density, width, height)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        cornerRadius = min(width, height) / 2f
        softwareClip.rewind()
        softwareClip.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        invalidateOutline()
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            // CircleShape's simple round rect uses Android's outline-backed clipping too.
            super.draw(canvas)
        } else {
            val checkpoint = canvas.save()
            canvas.clipPath(softwareClip)
            super.draw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backgroundPaint.color = palette.background
        canvas.drawRect(bounds, backgroundPaint)
        if (geometry.cacheWidth <= 0 || geometry.cacheHeight <= 0) return
        ensureCache()
        val image = bitmap ?: return
        val checkpoint = canvas.save()
        canvas.translate(geometry.left.toFloat(), geometry.top.toFloat())
        // Compose draws the logical ceil-sized cache 1:1. Explicit rectangles prevent Android's
        // density-aware drawBitmap(left, top) overload from silently scaling the image.
        canvas.drawBitmap(image, cacheRect, cacheRect, bitmapPaint)
        canvas.restoreToCount(checkpoint)
    }

    private fun ensureCache() {
        val old = bitmap
        if (old == null || old.width < geometry.cacheWidth || old.height < geometry.cacheHeight) {
            bitmap = Bitmap.createBitmap(geometry.cacheWidth, geometry.cacheHeight, Bitmap.Config.ARGB_8888)
            cacheCanvas = Canvas(checkNotNull(bitmap))
            cacheDirty = true
        }
        if (!cacheDirty && previousDrawWidth == geometry.drawWidth &&
            previousDrawHeight == geometry.drawHeight) return
        val target = checkNotNull(cacheCanvas)
        cacheRect.set(0, 0, geometry.cacheWidth, geometry.cacheHeight)
        target.drawRect(0f, 0f, geometry.cacheWidth.toFloat(), geometry.cacheHeight.toFloat(), clearPaint)
        val checkpoint = target.save()
        target.scale(geometry.drawWidth / art.viewport, geometry.drawHeight / art.viewport)
        // Keep root/child transforms separate, in the released vector's exact draw order.
        target.translate(art.originX, 0f)
        for (index in art.layers.indices) {
            val layer = art.layers[index]
            drawGlyph(target, layer.translationX, if (layer.accent) palette.accent else palette.main, layer.opacity)
        }
        target.restoreToCount(checkpoint)
        checkNotNull(bitmap).prepareToDraw()
        previousDrawWidth = geometry.drawWidth
        previousDrawHeight = geometry.drawHeight
        cacheDirty = false
    }

    private fun drawGlyph(canvas: Canvas, translateX: Float, color: Int, strokeAlpha: Float) {
        val checkpoint = canvas.save()
        canvas.translate(translateX, 0f)
        // SolidColor.applyTo multiplies Color alpha then converts to sRGB with half-up rounding.
        val alpha = (Color.alpha(color) / 255f * strokeAlpha * 255f + 0.5f).toInt()
        strokePaint.color = (color and 0x00ffffff) or (alpha shl 24)
        // These are separate stroked paths in the source; joining them changes overlap alpha.
        // Source transparent-black fills are no-ops, but make its cache non-tintable/ARGB_8888.
        for (index in art.paths.indices) canvas.drawPath(art.paths[index], strokePaint)
        canvas.restoreToCount(checkpoint)
    }
}

private const val BRAND_PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG

data class NativeLayeredMarkBounds(
    val drawWidth: Float,
    val drawHeight: Float,
    val cacheWidth: Int,
    val cacheHeight: Int,
    val left: Int,
    val top: Int,
)

private fun nativeLayeredMarkIntrinsicPx(sizeDp: Float, density: Float): Float {
    // XmlVectorParser: getDimension -> divide resource density -> Dp.toPx. Keep its Float order.
    val resourcePx = sizeDp * density
    return (resourcePx / density) * density
}

fun nativeLayeredMarkBounds(sizeDp: Float, density: Float, width: Int, height: Int): NativeLayeredMarkBounds {
    require(density.isFinite() && density > 0f && width >= 0 && height >= 0)
    if (width == 0 || height == 0) return NativeLayeredMarkBounds(0f, 0f, 0, 0, (width / 2f).roundToInt(), (height / 2f).roundToInt())
    val intrinsic = nativeLayeredMarkIntrinsicPx(sizeDp, density)
    // Deliberately retain Float multiply after division: replacing this with integer bounds can
    // lose a fractional cache pixel, just as with PainterModifier's Fit path.
    val drawWidth = intrinsic * (width.toFloat() / intrinsic)
    val drawHeight = intrinsic * (height.toFloat() / intrinsic)
    return NativeLayeredMarkBounds(
        drawWidth, drawHeight, ceil(drawWidth).toInt(), ceil(drawHeight).toInt(),
        ((width - drawWidth.roundToInt()) / 2f).roundToInt(),
        ((height - drawHeight.roundToInt()) / 2f).roundToInt(),
    )
}
