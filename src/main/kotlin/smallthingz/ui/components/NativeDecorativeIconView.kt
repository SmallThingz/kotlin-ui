package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition

/** Icon whose label is supplied by its enclosing content. */
class NativeDecorativeIconView(context: Context, private val glyph: Drawable, tint: Int) : View(context) {
    private var tintMotion: ViewColorTransition? = null
    private var currentTint = tint

    init {
        glyph.setTint(tint)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setColor(color: Int) {
        if (tintMotion == null && currentTint == color) return
        val motion = tintMotion ?: ViewColorTransition(this, intArrayOf(currentTint), ::publishTint)
            .also { tintMotion = it }
        motion.retarget(color)
    }

    internal fun setColorImmediately(color: Int) {
        tintMotion?.close()
        tintMotion = null
        applyTint(color)
    }

    private fun publishTint() {
        tintMotion?.let { applyTint(it.color(0)) }
    }

    private fun applyTint(color: Int) {
        if (currentTint == color) return
        currentTint = color
        glyph.setTint(color)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = glyph.setBounds(0, 0, w, h)

    override fun onDraw(canvas: Canvas) = glyph.draw(canvas)
}
