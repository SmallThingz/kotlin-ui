package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.graphics.Insets
import smallthingz.ui.graphics.ViewGrain
import smallthingz.ui.graphics.bindGrainLayer
import smallthingz.ui.unit.Density
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-width primary action with rounded top corners and a square, inset-filled bottom edge.
 * The existing button retains input, accessibility, state, callback and lifetime ownership.
 * Pass remaining insets only; the navigation region is painted but is not an action target.
 */
open class NativeBottomActionDock(
    context: Context,
    density: Density,
    val action: NativeMaterialButtonView,
) : ViewGroup(context) {
    private val densityScale = density.density
    val topCornerRadiusPx: Float = 28f * densityScale
    val minimumActionHeightPx: Int = (64f * densityScale).roundToInt()
    private var safeInsets = Insets.NONE
    val safeBottomInset: Int get() = safeInsets.bottom
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = action.sampledContainerColor }
    private var grain: ViewGrain? = null
    private var grainIntensity = 0f
    private val updatePaint: (Int) -> Unit = { color ->
        val intensity = action.sampledContainerGrainIntensity
        if (paint.color != color || grainIntensity != intensity) {
            paint.color = color
            if (grainIntensity != intensity) {
                grainIntensity = intensity
                grain?.setIntensity(intensity)
            }
            invalidate()
        }
    }

    init {
        require(action.parent == null) { "Action already belongs to a parent" }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        action.joinActionSurface()
        action.onContainerColorChanged = updatePaint
        updatePaint(action.sampledContainerColor)
        addView(action)
    }

    fun applySafeInsets(insets: Insets) {
        val next = Insets.of(max(0, insets.left), 0, max(0, insets.right), max(0, insets.bottom))
        if (next == safeInsets) return
        safeInsets = next
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = resolveSize(action.preferredWidth(false) + safeInsets.left + safeInsets.right, w)
        val actionWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(0)
        val wanted = max(minimumActionHeightPx, action.intrinsicHeight(actionWidth))
        val height = resolveSize(wanted + safeInsets.bottom, h)
        action.measure(exact(actionWidth), exact((height - safeInsets.bottom).coerceAtLeast(0)))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        action.layout(safeInsets.left, 0, safeInsets.left + action.measuredWidth, action.measuredHeight)
        val radius = min(topCornerRadiusPx, min(width / 2f, height.toFloat()))
        clip.rewind()
        clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(),
            floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        try {
            canvas.clipPath(clip)
            super.draw(canvas)
        } finally { canvas.restoreToCount(save) }
    }

    override fun onDraw(canvas: Canvas) {
        // Avoid double-compositing translucent disabled paint underneath the button itself.
        drawInset(canvas, 0f, action.bottom.toFloat(), width.toFloat(), height.toFloat())
        drawInset(canvas, 0f, 0f, action.left.toFloat(), action.bottom.toFloat())
        drawInset(canvas, action.right.toFloat(), 0f, width.toFloat(), action.bottom.toFloat())
    }

    private fun drawInset(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        if (right <= left || bottom <= top || paint.alpha == 0) return
        canvas.drawRect(left, top, right, bottom, paint)
        // Reuse the shared texture/owner binding and the button's animated fill coverage.
        if (grainIntensity > 0f) {
            val layer = grain ?: bindGrainLayer(null, grainIntensity).also { grain = it }
            layer?.draw(canvas, left, top, right, bottom, coverage = paint.alpha)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val radius = min(topCornerRadiusPx, min(width / 2f, height.toFloat()))
            val x = event.x
            val y = event.y
            if (y < radius && (x < radius || x > width - radius)) {
                val center = if (x < radius) radius else width - radius
                val dx = x - center
                val dy = y - radius
                if (dx * dx + dy * dy > radius * radius) return false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        action.onContainerColorChanged = updatePaint
        updatePaint(action.sampledContainerColor)
    }

    override fun onDetachedFromWindow() {
        if (action.onContainerColorChanged === updatePaint) action.onContainerColorChanged = null
        super.onDetachedFromWindow()
    }

    companion object {
        private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    }
}
