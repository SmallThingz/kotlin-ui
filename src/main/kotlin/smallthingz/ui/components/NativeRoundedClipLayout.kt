package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** The original layout also owns its rounded outer clip. A null radius resolves to a circle. */
open class NativeRoundedClipLayout(
    context: Context,
    private var radiusPx: Float? = null,
    softwareClip: Boolean = true,
) : FrameLayout(context) {
    private val path = if (softwareClip) Path() else null
    private val bounds = if (softwareClip) RectF() else null

    init {
        // A backgroundless ViewGroup can skip draw() and dispatch straight to its children.
        if (softwareClip) setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, resolvedRadius(view.width, view.height))
            }
        }
    }

    protected fun setClipRadiusPx(value: Float?) {
        if (radiusPx == value) return
        radiusPx = value
        rebuildClip(width, height)
    }

    private fun resolvedRadius(width: Int, height: Int): Float {
        val maximum = minOf(width, height) / 2f
        return minOf(radiusPx ?: maximum, maximum)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildClip(w, h)
    }

    private fun rebuildClip(width: Int, height: Int) {
        if (path != null && bounds != null) {
            val radius = resolvedRadius(width, height)
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            path.rewind()
            path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        }
        invalidateOutline()
    }

    override fun draw(canvas: Canvas) {
        // Detached views drawn into another view's hardware canvas have no outline clip.
        if (path == null || (canvas.isHardwareAccelerated && isAttachedToWindow)) super.draw(canvas) else {
            val checkpoint = canvas.save()
            try {
                canvas.clipPath(path)
                super.draw(canvas)
            } finally {
                canvas.restoreToCount(checkpoint)
            }
        }
    }
}
