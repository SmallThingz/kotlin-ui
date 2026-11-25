package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import smallthingz.ui.graphics.bindGrainLayer
import smallthingz.ui.graphics.ViewGrain
import kotlin.math.roundToInt

/** Rounded leading slot that shows an icon or the caller's existing interactive content. */
class NativeLeadingDisc(
    context: Context,
    private var density: Float,
    private var style: NativeLeadingDiscStyle,
    private var glyph: Drawable,
    initialFill: Int,
) : NativeRoundedClipLayout(context, style.radiusDp * density, softwareClip = false) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = initialFill
    }
    private var grain: ViewGrain? = null

    internal fun setSurfacePreset(preset: NativeSurfacePreset?) {
        grain = bindGrainLayer(grain, preset?.foregroundGrain ?: 0f)
    }

    var content: View? = null
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun bindContent(view: View?) {
        if (content === view) return
        content?.let(::removeView)
        content = view
        view?.let(::addView)
        requestLayout()
        invalidate()
    }

    fun bindColors(background: Int, tint: Int) {
        fill.color = background
        glyph.setTint(tint)
        invalidate()
    }

    fun bindStyle(density: Float, style: NativeLeadingDiscStyle, glyph: Drawable) {
        if (this.density == density && this.style == style && this.glyph === glyph) return
        this.density = density
        this.style = style
        this.glyph = glyph
        setClipRadiusPx(style.radiusDp * density)
        requestLayout()
        invalidate()
    }

    private fun dp(value: Float) = (value * density).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val size = dp(style.contentDp)
        content?.measure(exact(minOf(size, width)), exact(minOf(size, height)))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val glyphSize = dp(style.glyphDp)
        val glyphWidth = minOf(glyphSize, width)
        val glyphHeight = minOf(glyphSize, height)
        val glyphX = ((width - glyphWidth) / 2f).roundToInt()
        val glyphY = ((height - glyphHeight) / 2f).roundToInt()
        glyph.setBounds(glyphX, glyphY, glyphX + glyphWidth, glyphY + glyphHeight)
        content?.let { child ->
            val x = ((width - child.measuredWidth) / 2f).roundToInt()
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The clipped rectangular paint retains the source's translucent edge coverage.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        grain?.draw(canvas, 0f, 0f, width.toFloat(), height.toFloat(), coverage = fill.alpha)
        if (content == null) {
            glyph.layoutDirection = layoutDirection
            glyph.draw(canvas)
        }
    }

    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
