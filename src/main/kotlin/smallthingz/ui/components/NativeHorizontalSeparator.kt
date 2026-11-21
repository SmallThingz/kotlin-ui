package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition
import kotlin.math.roundToInt

/** Fractional-width stroke with the independently rounded native layout height. */
class NativeHorizontalSeparator(context: Context, private var thicknessDp: Float = 1f) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val motion: ViewColorTransition = ViewColorTransition(this, intArrayOf(paint.color), ::publishColor)
    private val thicknessPx get() = thicknessDp * resources.displayMetrics.density

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setColor(color: Int) = motion.retarget(color)

    fun bindThickness(value: Float) {
        if (thicknessDp == value) return
        thicknessDp = value
        requestLayout()
        invalidate()
    }

    private fun publishColor() {
        paint.color = motion.color(0)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), thicknessPx.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val thickness = thicknessPx
        paint.strokeWidth = thickness
        canvas.drawLine(0f, thickness / 2f, width.toFloat(), thickness / 2f, paint)
    }
}
