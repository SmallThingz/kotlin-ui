package smallthingz.ui.graphics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Xfermode
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/**
 * Native background fill and deterministic grain in component-local coordinates.
 *
 * A null background draws only grain. The bitmap and standard phase shaders are process-shared;
 * each drawable owns its paints. Once ready, ordinary drawing performs no allocations or cache
 * lookup. Alpha/color-filter composition alone uses a saveLayer and a lazily allocated paint.
 * Disable platform force-dark on the owning View when exact supplied colors must be preserved.
 */
class NoiseDrawable(
    @ColorInt backgroundColor: Int? = null,
    private var seed: Int? = SEED_BACKGROUND,
) : Drawable() {
    private val backgroundPaint = Paint(PAINT_FLAGS)
    private val noisePaint = Paint(PAINT_FLAGS).apply {
        // ShaderBrush.applyTo sets black, alpha=1, no color filter, and SrcOver blending.
        color = Color.BLACK
        shader = seed?.let(NoiseTexture::shader)
    }
    private var drawsBackground = backgroundColor != null
    private var drawableAlpha = 255
    private var outputColorFilter: ColorFilter? = null
    private var compositePaint: Paint? = null
    private var textSurfaceRange = backgroundColor?.let(::grainSurfaceRange) ?: 0L

    /** Bounds enclose the solid fill and any black/white grain. */
    val paintedTextSurface: Long
        get() = if (drawsBackground && drawableAlpha == 255 && outputColorFilter == null)
            textSurfaceRange else 0L

    internal fun surfaceRange(color: Int, coverage: Int = 255): Long =
        noiseSurfaceRange(color, if (seed == null) 0 else (noisePaint.alpha * coverage + 127) / 255)

    internal fun surfaceRangeOver(surface: Long, fill: Int): Long = noiseSurfaceRangeOver(surface, fill,
        if (seed == null) 0 else (noisePaint.alpha * (fill ushr 24) + 127) / 255)

    private fun grainSurfaceRange(color: Int): Long = surfaceRange(color)

    init {
        NoiseTexture.register(this)
        backgroundColor?.let { backgroundPaint.color = it }
        if (seed != null) NoiseTexture.observe(this)
    }

    fun setGrainSeed(value: Int?) {
        if (seed == value) return
        seed = value
        noisePaint.shader = value?.let(NoiseTexture::shader)
        if (value != null && noisePaint.shader == null) NoiseTexture.observe(this)
        textSurfaceRange = if (drawsBackground) grainSurfaceRange(backgroundPaint.color) else 0L
        invalidateSelf()
    }

    fun setBackgroundColor(@ColorInt color: Int?) {
        if (drawsBackground == (color != null) && (color == null || backgroundPaint.color == color)) return
        drawsBackground = color != null
        color?.let { backgroundPaint.color = it }
        textSurfaceRange = color?.let(::grainSurfaceRange) ?: 0L
        invalidateSelf()
    }

    /** Set grain opacity from 0 (only the fill) to 1 (original texture). */
    fun setNoiseIntensity(intensity: Float) {
        require(intensity in 0f..1f)
        val alpha = (intensity * 255f).roundToInt()
        if (noisePaint.alpha == alpha) return
        noisePaint.alpha = alpha
        textSurfaceRange = if (drawsBackground) grainSurfaceRange(backgroundPaint.color) else 0L
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || drawableAlpha == 0) return
        val checkpoint = canvas.save()
        try {
            // Never move the cached shader's local matrix: other same-seed instances share it.
            // Compose's modifier also starts at its own local origin rather than the window origin.
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            if (drawableAlpha != 255 || outputColorFilter != null) {
                // Optional Drawable-level effects apply once to the composed fill+noise. Applying
                // alpha independently to both paints would change translucent overlap pixels.
                val paint = compositePaint ?: Paint(PAINT_FLAGS).also { compositePaint = it }
                paint.alpha = drawableAlpha
                paint.colorFilter = outputColorFilter
                canvas.saveLayer(0f, 0f, width, height, paint)
            }
            if (drawsBackground) canvas.drawRect(0f, 0f, width, height, backgroundPaint)
            drawGrain(canvas, 0f, 0f, width, height)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    /** Paint shared grain over the owner's shape; report false when no draw was issued. */
    internal fun drawGrain(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        corner: Float = 0f, coverage: Int = 255, blend: Xfermode? = null): Boolean {
        if (right <= left || bottom <= top || !prepareGrain(coverage)) return false
        val alpha = noisePaint.alpha
        // Ordinary drawing leaves the cached paint mode untouched.
        val previousBlend = if (blend != null) noisePaint.xfermode else null
        try {
            noisePaint.alpha = (alpha * coverage + 127) / 255
            if (blend != null) noisePaint.xfermode = blend
            if (corner > 0f) canvas.drawRoundRect(left, top, right, bottom, corner, corner, noisePaint)
            else canvas.drawRect(left, top, right, bottom, noisePaint)
            return true
        } finally {
            noisePaint.alpha = alpha
            if (blend != null) noisePaint.xfermode = previousBlend
        }
    }

    /** Use the owner's retained geometry, avoiding a second path and per-frame clip. */
    internal fun drawGrain(canvas: Canvas, path: android.graphics.Path, coverage: Int = 255) {
        if (!prepareGrain(coverage)) return
        val alpha = noisePaint.alpha
        noisePaint.alpha = (alpha * coverage + 127) / 255
        try { canvas.drawPath(path, noisePaint) } finally { noisePaint.alpha = alpha }
    }

    /** Hidden-process retirement must clear each Paint as well as the shared shader cache. */
    internal fun releaseUiResources() {
        noisePaint.shader = null
        invalidateSelf()
    }

    private fun prepareGrain(coverage: Int): Boolean {
        if (noisePaint.alpha == 0 || coverage == 0) return false
        val currentSeed = seed ?: return false
        if (noisePaint.shader == null) {
            noisePaint.shader = NoiseTexture.shader(currentSeed)
            if (noisePaint.shader == null) NoiseTexture.observe(this)
        }
        // Until generation completes, leave the owner's exact fill visible.
        return noisePaint.shader != null
    }

    override fun setAlpha(alpha: Int) {
        val next = alpha.coerceIn(0, 255)
        if (drawableAlpha == next) return
        drawableAlpha = next
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (outputColorFilter === colorFilter) return
        outputColorFilter = colorFilter
        invalidateSelf()
    }

    override fun getColorFilter(): ColorFilter? = outputColorFilter

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val SEED_BACKGROUND = 0
        const val SEED_TOP_BAR = 0x5A17
        private const val PAINT_FLAGS =
            Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
    }

}
