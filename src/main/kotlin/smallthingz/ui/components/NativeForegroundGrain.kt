package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Xfermode
import android.view.View
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.graphics.ViewGrain
import smallthingz.ui.graphics.bindGrainLayer

/** Window-owned grain for a caller-painted foreground; keep one instance per drawing View. */
class NativeForegroundGrain {
    private var intensity = 0f
    private var owner: View? = null
    private var grain: ViewGrain? = null

    fun bind(palette: ControlPalette?) {
        val next = palette?.surfacePreset?.foregroundGrain ?: 0f
        if (intensity == next) return
        intensity = next
        val layer = grain
        if (layer != null) layer.setIntensity(next) else owner?.invalidate()
    }

    /** The caller retains its fill, bounds, rounded geometry and outline. */
    fun draw(canvas: Canvas, view: View, bounds: RectF, cornerRadius: Float, fillColor: Int): Unit {
        draw(canvas, view, bounds, cornerRadius, fillColor, null)
    }

    /** Internal callers may mask grain within their own bounded layer. */
    internal fun draw(canvas: Canvas, view: View, bounds: RectF, cornerRadius: Float, fillColor: Int,
        blend: Xfermode?): Boolean {
        // Retain a flat drawing owner too, so first-time grain enablement schedules its redraw.
        if (owner !== view) {
            owner = view
            grain = null
        }
        if (intensity == 0f || fillColor ushr 24 == 0) return false
        val layer = grain ?: view.bindGrainLayer(null, intensity).also { grain = it }
        return layer?.draw(canvas, bounds.left, bounds.top, bounds.right, bounds.bottom,
            cornerRadius, fillColor ushr 24, blend) ?: false
    }
}
