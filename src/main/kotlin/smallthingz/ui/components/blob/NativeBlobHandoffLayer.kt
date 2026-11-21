// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.ViewGroup
import smallthingz.ui.animation.view.ViewColorTransition

/** Paints a frozen blob body above the new content until its renderer takes over. */
abstract class NativeBlobHandoffLayer(context: Context, initialColor: Int) : ViewGroup(context) {
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = initialColor
    }
    private val circleMotion = ViewColorTransition(this, intArrayOf(initialColor), ::applyCircleColor)
    private var visible = false
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    protected fun bindHandoffCircle(x: Float, y: Float, diameter: Float) {
        centerX = x
        centerY = y
        radius = diameter * .5f
        visible = true
    }

    protected fun clearHandoffCircle() {
        visible = false
    }

    protected fun setHandoffColor(color: Int) = circleMotion.retarget(color)

    private fun applyCircleColor() {
        circle.color = circleMotion.color(0)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (visible) canvas.drawCircle(centerX, centerY, radius, circle)
    }
}
