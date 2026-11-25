package smallthingz.ui.graphics

import android.graphics.Canvas
import android.graphics.Xfermode
import android.graphics.drawable.Drawable
import android.view.View

/** Keep the invalidation callback alive for a grain layer painted directly by a view. */
internal class ViewGrain(private val view: View) : Drawable.Callback {
    private val grain = NoiseDrawable().apply { callback = this@ViewGrain }

    fun setIntensity(intensity: Float) = grain.setNoiseIntensity(intensity)
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, corner: Float = 0f, coverage: Int = 255, blend: Xfermode? = null) =
        grain.drawGrain(canvas, left, top, right, bottom, corner, coverage, blend)
    fun draw(canvas: Canvas, path: android.graphics.Path, coverage: Int = 255) = grain.drawGrain(canvas, path, coverage)
    fun surfaceRange(color: Int, coverage: Int = 255): Long = grain.surfaceRange(color, coverage)

    fun surfaceRangeOver(surface: Long, fill: Int): Long = grain.surfaceRangeOver(surface, fill)

    override fun invalidateDrawable(who: Drawable) = view.invalidate()
    override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = Unit
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
}

internal fun View.bindGrainLayer(current: ViewGrain?, intensity: Float): ViewGrain? {
    require(intensity in 0f..1f)
    if (current == null && intensity == 0f) return null
    return (current ?: ViewGrain(this).also { invalidate() }).also { it.setIntensity(intensity) }
}
