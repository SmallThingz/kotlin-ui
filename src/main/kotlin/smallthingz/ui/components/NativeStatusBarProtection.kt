package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.Window
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Caller-owned status-bar appearance and foreground protection for one window.
 * Supply an opaque, contrasting background; never share this drawable between windows.
 * Feed the window root's existing inset stream before child inset consumption.
 */
class NativeStatusBarProtection(
    background: Drawable,
    val darkIcons: Boolean = false,
) : DrawableWrapper(background) {
    internal var appearance: NativeStatusBarAppearance? = null
        private set

    internal constructor(background: Drawable, appearance: NativeStatusBarAppearance) :
        this(background, appearance.darkIcons) {
        this.appearance = appearance
    }

    private var statusInsets = Insets.NONE

    fun updateInsets(insets: WindowInsetsCompat) {
        val next = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        if (statusInsets == next) return
        statusInsets = next
        invalidateSelf()
    }

    fun applyWindowAppearance(window: Window) {
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = darkIcons
    }

    // The child may be opaque, but this foreground leaves the window's content area untouched.
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        outline.setEmpty()
        outline.alpha = 0f
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || statusInsets == Insets.NONE) return
        val topEnd = bounds.top + statusInsets.top.coerceIn(0, bounds.height())
        val bottomStart = (bounds.bottom - statusInsets.bottom.coerceAtLeast(0)).coerceIn(topEnd, bounds.bottom)
        val leftEnd = bounds.left + statusInsets.left.coerceIn(0, bounds.width())
        val rightStart = (bounds.right - statusInsets.right.coerceAtLeast(0)).coerceIn(leftEnd, bounds.right)
        drawRegion(canvas, bounds.left, bounds.top, bounds.right, topEnd)
        drawRegion(canvas, bounds.left, bottomStart, bounds.right, bounds.bottom)
        drawRegion(canvas, bounds.left, topEnd, leftEnd, bottomStart)
        drawRegion(canvas, rightStart, topEnd, bounds.right, bottomStart)
    }

    private fun drawRegion(canvas: Canvas, left: Int, top: Int, right: Int, bottom: Int) {
        if (left >= right || top >= bottom) return
        val checkpoint = canvas.save()
        try {
            canvas.clipRect(left, top, right, bottom)
            super.draw(canvas)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }
}
