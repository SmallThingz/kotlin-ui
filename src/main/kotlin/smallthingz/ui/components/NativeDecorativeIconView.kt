package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition

/** Icon whose label is supplied by its enclosing content. */
class NativeDecorativeIconView(context: Context, private val glyph: Drawable, tint: Int) : View(context) {
    private val tintMotion: ViewColorTransition = ViewColorTransition(this, intArrayOf(tint), ::publishTint)

    init {
        glyph.setTint(tint)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setColor(color: Int) = tintMotion.retarget(color)

    private fun publishTint() {
        glyph.setTint(tintMotion.color(0))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = glyph.setBounds(0, 0, w, h)

    override fun onDraw(canvas: Canvas) = glyph.draw(canvas)
}
