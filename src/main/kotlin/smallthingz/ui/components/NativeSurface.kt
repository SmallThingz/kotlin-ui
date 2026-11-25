package smallthingz.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import smallthingz.ui.graphics.NoiseDrawable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Logical corners retain their meaning when a surface switches between LTR and RTL. */
data class NativeCornerRadii(
    val topStartDp: Float,
    val topEndDp: Float,
    val bottomEndDp: Float,
    val bottomStartDp: Float,
) {
    init {
        require(floatArrayOf(topStartDp, topEndDp, bottomEndDp, bottomStartDp).all { it.isFinite() && it >= 0f })
    }
}

/** Explicit visual tokens: a surface never infers or changes its caller's theme. */
data class NativeSurfaceStyle(
    val fillColor: Int,
    val cornerRadiusDp: Float = 16f,
    val outlineColor: Int? = null,
    val outlineWidthDp: Float = 1f,
    val elevationDp: Float = 0f,
    val noise: Boolean = false,
    val noiseSeed: Int = NoiseDrawable.SEED_BACKGROUND,
    val rippleColor: Int? = null,
    val cornerRadii: NativeCornerRadii? = null,
    val focusOutlineColor: Int? = null,
    val focusOutlineWidthDp: Float = 2f,
) {
    init {
        require(cornerRadiusDp.isFinite() && cornerRadiusDp >= 0f)
        require(outlineWidthDp.isFinite() && outlineWidthDp >= 0f)
        require(elevationDp.isFinite() && elevationDp >= 0f)
        require(focusOutlineWidthDp.isFinite() && focusOutlineWidthDp >= 0f)
    }
}

/** A retained content host with one rounded outline for clipping, shadow and ripple. */
open class NativeSurface(context: Context, style: NativeSurfaceStyle,
    private val onPaintedSurfaceChanged: (View) -> Unit = {},
) : FrameLayout(context) {
    private var requestedStyle = style
    private var surfacePreset: NativeSurfacePreset? = null
    private val fill = GradientDrawable()
    private val border = NativeSurfaceBorderDrawable()
    private val mask = GradientDrawable().apply { setColor(Color.WHITE) }
    private var grain: NoiseDrawable? = null
    private var grainSeed: Int? = null
    private var grainIntensity = 1f
    private var ripple: RippleDrawable? = null
    private var appliedStyle: NativeSurfaceStyle? = null
    private var appliedDensity = Float.NaN
    private var appliedDirection = -1
    private var radius = 0f
    private val cornerPath = Path()
    private val cornerBounds = RectF()
    private val physicalCorners = FloatArray(8)
    private var updatingBorder = false
    private val colorMotion=smallthingz.ui.animation.view.ViewColorTransition(this,
        intArrayOf(style.fillColor,style.outlineColor ?: Color.TRANSPARENT,style.rippleColor ?: Color.TRANSPARENT),::publishColors)

    /** The sampled fill currently painted, including an in-flight palette transition. */
    val paintedFillColor: Int get() = colorMotion.color(0)
    val paintedTextSurface: Long get() = if (appliedStyle?.noise == true)
        grain?.surfaceRange(paintedFillColor) ?: smallthingz.ui.graphics.noiseSurfaceRange(paintedFillColor, 0)
        else smallthingz.ui.graphics.noiseSurfaceRange(paintedFillColor, 0)

    internal fun setSurfacePreset(preset: NativeSurfacePreset?) {
        if (surfacePreset == preset) return
        surfacePreset = preset
        applyStyle(requestedStyle)
        publishColors()
    }

    private fun publishColors() {
        fill.setColor(colorMotion.color(0))
        grain?.setNoiseIntensity((surfacePreset?.foregroundGrain ?: grainIntensity) * (paintedFillColor ushr 24) / 255f)
        ripple?.setColor(ColorStateList.valueOf(colorMotion.color(2)))
        border.setColor(colorMotion.color(1))
        onPaintedSurfaceChanged(this)
        invalidate()
    }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 0 || view.height <= 0) outline.setEmpty()
                else if (appliedStyle?.cornerRadii == null) {
                    outline.setRoundRect(0, 0, view.width, view.height,
                        min(radius, min(view.width, view.height) / 2f))
                } else {
                    @Suppress("DEPRECATION")
                    outline.setConvexPath(cornerPath)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        applyStyle(style)
    }

    /** Paint-only updates retain children, focus, callbacks and an unchanged active ripple. */
    fun applyStyle(style: NativeSurfaceStyle) {
        requestedStyle = style
        val preset = surfacePreset
        applyResolvedStyle(if (preset == null) style else style.copy(
            noise = preset.foregroundGrain > 0f))
    }

    private fun applyResolvedStyle(style: NativeSurfaceStyle) {
        val density = resources.displayMetrics.density
        val previous = appliedStyle
        if (previous == style && appliedDensity == density && appliedDirection == layoutDirection) return
        // State callbacks triggered by Drawable assignment must see the new style.
        appliedStyle = style
        appliedDensity = density
        appliedDirection = layoutDirection
        radius = style.cornerRadiusDp * density
        elevation = style.elevationDp * density

        val layersChanged = previous == null || previous.noise != style.noise ||
            (style.noise && previous.noiseSeed != style.noiseSeed) ||
            (previous.rippleColor == null) != (style.rippleColor == null)
        if (layersChanged) {
            val content = if (style.noise) {
                val noise = if (grainSeed == style.noiseSeed) grain else null
                (noise ?: NoiseDrawable(seed = style.noiseSeed)).also {
                    it.setNoiseIntensity(surfacePreset?.foregroundGrain ?: grainIntensity)
                    grain = it
                    grainSeed = style.noiseSeed
                }
                LayerDrawable(arrayOf(fill, checkNotNull(grain)))
            } else fill
            ripple = style.rippleColor?.let { RippleDrawable(ColorStateList.valueOf(it), content, mask) }
            background = ripple ?: content
        }
        updateCorners()
        publishColors()
        invalidate()
    }

    /** Change an enabled grain layer's opacity without rebuilding the surface. */
    fun setNoiseIntensity(intensity: Float) {
        require(intensity in 0f..1f)
        if (grainIntensity == intensity) return
        grainIntensity = intensity
        grain?.setNoiseIntensity((surfacePreset?.foregroundGrain ?: intensity) * (paintedFillColor ushr 24) / 255f)
        onPaintedSurfaceChanged(this)
        invalidate()
    }

    private fun updateBorder() {
        // View can dispatch state callbacks before subclass fields have been initialized.
        val style = appliedStyle ?: return
        if (updatingBorder) return
        updatingBorder = true
        try {
            val density = resources.displayMetrics.density
            val focused = isEnabled && isFocused && style.focusOutlineColor != null
            val color = if (focused) style.focusOutlineColor else style.outlineColor
            val width = (if (focused) style.focusOutlineWidthDp else style.outlineWidthDp) * density
            colorMotion.retarget(style.fillColor,color ?: Color.TRANSPARENT,style.rippleColor ?: Color.TRANSPARENT)
            border.applyGeometry(colorMotion.color(1), width, physicalCorners)
            val hasOutline = (style.outlineColor != null && style.outlineWidthDp > 0f) ||
                (style.focusOutlineColor != null && style.focusOutlineWidthDp > 0f)
            val desired = if (hasOutline) border else null
            // A caller's overlay owns its own state. Configuration/RTL repaint must not erase it.
            if ((foreground == null || foreground === border) && foreground !== desired) foreground = desired
        } finally {
            updatingBorder = false
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        updateBorder()
    }

    override fun performClick(): Boolean = acceptsNativeInput() && super.performClick()

    private fun updateCorners() {
        val logical = appliedStyle?.cornerRadii
        clipToOutline = logical == null
        val minDimension = min(width, height).coerceAtLeast(0).toFloat()
        if (logical == null) {
            fill.cornerRadius = radius
            mask.cornerRadius = radius
            physicalCorners.fill(min(radius, minDimension / 2f))
        } else {
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val density = resources.displayMetrics.density
            var topStart = logical.topStartDp * density
            var topEnd = logical.topEndDp * density
            var bottomEnd = logical.bottomEndDp * density
            var bottomStart = logical.bottomStartDp * density
            // CornerBasedShape scales each logical vertical pair before physical RTL mapping.
            // Native round-rect/path drawing still performs its own final radius fitting.
            val topStartTotal = topStart + bottomStart
            if (topStartTotal > minDimension) {
                if (topStartTotal.isFinite()) {
                    val scale = minDimension / topStartTotal
                    topStart *= scale
                    bottomStart *= scale
                } else {
                    // Finite DP values can overflow in pixels; their Double ratio stays finite.
                    val fraction = logical.topStartDp.toDouble() /
                        (logical.topStartDp.toDouble() + logical.bottomStartDp.toDouble())
                    topStart = (minDimension * fraction).toFloat()
                    bottomStart = minDimension - topStart
                }
            }
            val topEndTotal = topEnd + bottomEnd
            if (topEndTotal > minDimension) {
                if (topEndTotal.isFinite()) {
                    val scale = minDimension / topEndTotal
                    topEnd *= scale
                    bottomEnd *= scale
                } else {
                    // Finite DP values can overflow in pixels; their Double ratio stays finite.
                    val fraction = logical.topEndDp.toDouble() /
                        (logical.topEndDp.toDouble() + logical.bottomEndDp.toDouble())
                    topEnd = (minDimension * fraction).toFloat()
                    bottomEnd = minDimension - topEnd
                }
            }
            fun corner(index: Int, pixels: Float) {
                physicalCorners[index] = pixels
                physicalCorners[index + 1] = pixels
            }
            corner(0, if (rtl) topEnd else topStart)
            corner(2, if (rtl) topStart else topEnd)
            corner(4, if (rtl) bottomStart else bottomEnd)
            corner(6, if (rtl) bottomEnd else bottomStart)
            fill.cornerRadii = physicalCorners
            mask.cornerRadii = physicalCorners
            cornerBounds.set(0f, 0f, width.toFloat(), height.toFloat())
            cornerPath.reset()
            cornerPath.addRoundRect(cornerBounds, physicalCorners, Path.Direction.CW)
        }
        updateBorder()
        invalidateOutline()
    }

    override fun draw(canvas: Canvas) {
        if (appliedStyle?.cornerRadii == null) {
            super.draw(canvas)
            return
        }
        // Outline clipping only supports a uniform round rect on older supported Androids.
        val checkpoint = canvas.save()
        try {
            canvas.clipPath(cornerPath)
            super.draw(canvas)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateCorners()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (appliedStyle != null) applyStyle(requestedStyle)
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (appliedStyle != null) applyStyle(requestedStyle)
    }
}

/** Border geometry follows the declared Compose Foundation source, without a Compose dependency. */
internal class NativeSurfaceBorderDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = RectF()
    private val center = RectF()
    private val inner = RectF()
    private val borderPath = Path()
    private val innerPath = Path()
    private val outerCorners = FloatArray(8)
    private val innerCorners = FloatArray(8)
    private var requestedWidth = 0f
    private var strokeWidth = 0f
    private var borderColor: Int? = null
    private var drawableAlpha = 255
    private var simple = true
    private var fillArea = false

    fun applyGeometry(color: Int?, width: Float, corners: FloatArray) {
        val geometryChanged = requestedWidth != width || !outerCorners.contentEquals(corners)
        requestedWidth = width
        borderColor = color
        if (geometryChanged) {
            corners.copyInto(outerCorners)
            rebuildGeometry()
        }
        updatePaint()
        invalidateSelf()
    }
    fun setColor(color:Int?) {
        if(borderColor==color)return
        borderColor=color;updatePaint();invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        outer.set(bounds)
        val minDimension = min(outer.width(), outer.height())
        // Width zero remains this component's explicit disabled-border value.
        if (requestedWidth <= 0f || minDimension <= 0f) {
            strokeWidth = 0f
            return
        }
        strokeWidth = min(ceil(requestedWidth), ceil(minDimension / 2f))
        paint.strokeWidth = strokeWidth
        fillArea = strokeWidth * 2f > minDimension
        simple = outerCorners.all { it == outerCorners[0] }
        center.set(outer)
        center.inset(strokeWidth / 2f, strokeWidth / 2f)
        inner.set(outer)
        inner.inset(strokeWidth, strokeWidth)
        if (!simple) {
            borderPath.rewind()
            borderPath.addRoundRect(outer, outerCorners, Path.Direction.CW)
            if (!fillArea && !inner.isEmpty) {
                for (index in innerCorners.indices) {
                    innerCorners[index] = max(0f, outerCorners[index] - strokeWidth)
                }
                innerPath.rewind()
                innerPath.addRoundRect(inner, innerCorners, Path.Direction.CW)
                borderPath.op(innerPath, Path.Op.DIFFERENCE)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (borderColor == null || strokeWidth <= 0f || outer.isEmpty) return
        val radius = outerCorners[0]
        when {
            !simple -> {
                paint.style = Paint.Style.FILL
                canvas.drawPath(borderPath, paint)
            }
            fillArea -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(outer, radius, radius, paint)
            }
            radius == 0f -> {
                paint.style = Paint.Style.STROKE
                canvas.drawRect(center, paint)
            }
            radius < strokeWidth / 2f -> {
                paint.style = Paint.Style.FILL
                val checkpoint = canvas.save()
                try {
                    canvas.clipOutRect(inner)
                    canvas.drawRoundRect(outer, radius, radius, paint)
                } finally {
                    canvas.restoreToCount(checkpoint)
                }
            }
            else -> {
                paint.style = Paint.Style.STROKE
                val centerRadius = radius - strokeWidth / 2f
                canvas.drawRoundRect(center, centerRadius, centerRadius, paint)
            }
        }
    }

    private fun updatePaint() {
        val color = borderColor ?: Color.TRANSPARENT
        paint.color = color
        paint.alpha = (Color.alpha(color) * drawableAlpha + 127) / 255
    }

    override fun setAlpha(alpha: Int) {
        val next = alpha.coerceIn(0, 255)
        if (drawableAlpha == next) return
        drawableAlpha = next
        updatePaint()
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (paint.colorFilter === colorFilter) return
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getColorFilter(): ColorFilter? = paint.colorFilter

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
