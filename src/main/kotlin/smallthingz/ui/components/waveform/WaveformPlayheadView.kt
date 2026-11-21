// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.waveform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.min
import smallthingz.ui.animation.view.ViewColorTransition

/** Display-only sampled position; editable range/trim handles stay in separate children above it. */
class WaveformPlayheadView(context: Context, private var density: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction = 0f
    private var shown = false
    private var colorMotion: ViewColorTransition? = null
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }
    fun bind(style: WaveformStyle, density: Float, shown: Boolean) {
        this.density = density
        this.shown = shown
        val color = style.markerColor
        val motion = colorMotion ?: ViewColorTransition(this, intArrayOf(color), {
            colorMotion?.let { paint.color = it.color(0); invalidate() }
        }).also { colorMotion = it; paint.color = color }
        motion.retarget(color)
        invalidate()
    }
    fun setPosition(value: Float) {
        if (fraction == value) return
        fraction = value
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        if (!shown || width <= 0 || height <= 0) return
        val stroke = min(width.toFloat(), density)
        val inset = stroke * .5f
        val x = (width * fraction).coerceIn(inset, width - inset)
        val lineHeight = height * WAVEFORM_CURSOR_HEIGHT_FRACTION
        val top = (height - lineHeight) * .5f
        paint.strokeWidth = stroke
        paint.alpha = 160
        canvas.drawLine(x, top, x, height - top, paint)
    }
}
