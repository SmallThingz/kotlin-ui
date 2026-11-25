package smallthingz.ui.alldoc

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import smallthingz.ui.components.NativeForegroundGrain
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.controls.textSurfaceRange

/** App-owned selection artwork, with one painter per drawing View.
 * Document pixels, endpoint placement and hit targets stay with the reader.
 */
class AllDocSelectionHandlePainter {
    private val grain = NativeForegroundGrain()
    private val bounds = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private var palette: ControlPalette? = null
    private var colorOwner: View? = null
    private var noisy = false

    fun bind(value: ControlPalette) {
        val previous = palette
        palette = value
        noisy = (value.surfacePreset?.foregroundGrain ?: 0f) > 0f
        grain.bind(value)
        if (previous != null && (previous.accent != value.accent || previous.content != value.content)) {
            colorOwner?.invalidate()
        }
    }

    /** Preserve the platform drawable's exact alpha silhouette, including transparent corners. */
    fun drawArtwork(canvas: Canvas, owner: View, artwork: Drawable) {
        bounds.set(artwork.bounds)
        if (!noisy || bounds.isEmpty) {
            artwork.draw(canvas)
            // Register a flat owner so enabling grain later invalidates the retained drawing view.
            grain.draw(canvas, owner, bounds, 0f, Color.WHITE)
            return
        }
        // Both layers are bounded by the small handle, never the page or document viewport.
        val shape = canvas.saveLayer(bounds, null)
        try {
            artwork.draw(canvas)
            val ink = canvas.saveLayer(bounds, mask)
            try { grain.draw(canvas, owner, bounds, 0f, Color.WHITE) }
            finally { canvas.restoreToCount(ink) }
        } finally { canvas.restoreToCount(shape) }
    }

    /** Worksheet rings use app ink and the same shared grain, independently of page paper. */
    fun drawRing(canvas: Canvas, owner: View, x: Float, y: Float, radius: Float, innerRadius: Float) {
        val colors = checkNotNull(palette) { "Bind the app control palette before painting a selection ring" }
        circle(canvas, owner, x, y, radius, colors.accent)
        circle(canvas, owner, x, y, innerRadius,
            textSurfaceForeground(textSurfaceRange(colors.accent), colors.content))
    }

    /** Fallback for platforms without usable selection-handle artwork. */
    fun drawDot(canvas: Canvas, owner: View, x: Float, y: Float, radius: Float) {
        circle(canvas, owner, x, y, radius, checkNotNull(palette).accent)
    }

    private fun circle(canvas: Canvas, owner: View, x: Float, y: Float, radius: Float, color: Int) {
        colorOwner = owner
        fill.color = color
        canvas.drawCircle(x, y, radius, fill)
        bounds.set(x - radius, y - radius, x + radius, y + radius)
        grain.draw(canvas, owner, bounds, radius, color)
    }
}
