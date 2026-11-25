package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import kotlin.math.min
import smallthingz.ui.graphics.ViewGrain
import smallthingz.ui.graphics.bindGrainLayer
import smallthingz.ui.geometry.roundedGroupContains
import smallthingz.ui.geometry.roundedGroupGeometry

/** A uniform rounded surface shared by the fill, foreground clip and pointer geometry. */
abstract class NativeRoundedControlSurface(
    context: Context,
    private var density: Float,
    private var cornerRadiusDp: Float?,
    drawFill: Boolean,
) : ViewGroup(context) {
    private val fill = if (drawFill) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    } else null
    private var grain: ViewGrain? = null

    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, width, height, paintedRadius())
            }
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        if (drawFill) setWillNotDraw(false)
    }

    protected fun setRoundedGeometry(density: Float, cornerRadiusDp: Float?) {
        this.density = density
        this.cornerRadiusDp = cornerRadiusDp
    }

    protected fun setRoundedSurfacePreset(preset: NativeSurfacePreset?) {
        if (fill == null) return
        grain = bindGrainLayer(grain, preset?.foregroundGrain ?: 0f)
    }

    protected fun roundedSurfaceRangeOver(underlay: Long, color: Int): Long =
        grain?.surfaceRangeOver(underlay, color)
            ?: smallthingz.ui.graphics.noiseSurfaceRangeOver(underlay, color, 0)

    protected fun setRoundedFillColor(color: Int) {
        fill?.color = color
    }

    protected fun acceptsRoundedPointer(x: Float, y: Float, touch: Boolean, minimumTouchSize: Float): Boolean =
        roundedGroupGeometry(width.toFloat(), height.toFloat(), density, pointerRadius())
            .acceptsPointer(x, y, touch, minimumTouchSize)

    protected fun containsRoundedPoint(x: Float, y: Float): Boolean =
        roundedGroupContains(width.toFloat(), height.toFloat(), density, pointerRadius(), x, y)

    private fun pointerRadius(): Float = cornerRadiusDp ?: min(width, height) / (2f * density)

    private fun paintedRadius(): Float {
        val maximum = min(width, height) / 2f
        return min(cornerRadiusDp?.let { it * density } ?: maximum, maximum)
    }

    override fun onDraw(canvas: Canvas) {
        val paint = fill ?: return
        val radius = paintedRadius()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        grain?.draw(canvas, 0f, 0f, width.toFloat(), height.toFloat(), radius, paint.alpha)
    }
}
