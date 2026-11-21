package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.view.ViewGroup

/** Clips the existing child draw pass, including child translation and overflow. */
abstract class NativeBoundsClipLayout(context: Context, private val topOnly: Boolean = false) : ViewGroup(context) {
    protected var verticalClipMarginPx = 0

    override fun dispatchDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        try {
            if (topOnly) canvas.clipRect(-Float.MAX_VALUE, 0f, Float.MAX_VALUE, Float.MAX_VALUE)
            else canvas.clipRect(0, -verticalClipMarginPx, width, height + verticalClipMarginPx)
            super.dispatchDraw(canvas)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }
}
