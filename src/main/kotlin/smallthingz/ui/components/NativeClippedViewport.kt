package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.dp

/** Clips page content across the scroll axis while allowing cross-axis shadows. */
abstract class NativeHorizontalPagerViewport(context: Context, density: Density) : NativeBoundsClipLayout(context) {
    init {
        clipChildren = false
        clipToPadding = false
        verticalClipMarginPx = with(density) { 30.dp.roundToPx() }
    }
}

/** Clips the owner's entire draw pass to the same rectangle used for hit admission. */
abstract class NativeRectangularViewport(context: Context) : ViewGroup(context) {
    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = true
        outlineProvider = RectangularViewportOutline
    }

    protected fun containsViewportPoint(x: Float, y: Float): Boolean = x >= 0f && y >= 0f && x < width && y < height

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            super.draw(canvas)
            return
        }
        val checkpoint = canvas.save()
        try {
            canvas.clipRect(0, 0, width, height)
            super.draw(canvas)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }
}

private object RectangularViewportOutline : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setRect(0, 0, view.width, view.height)
}
