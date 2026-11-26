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
 * Side-inset primary action with rounded top corners and a square, inset-filled bottom edge.
 * The existing button retains input, accessibility, state, callback and lifetime ownership.
 * Pass remaining insets only; the navigation region is painted but is not an action target.
 */
open class NativeBottomActionDock @JvmOverloads constructor(
    context: Context,
    density: Density,
    val action: NativeMaterialButtonView,
    horizontalInsetDp: Int = NativeScreenSpacing.HORIZONTAL_DP,
) : ViewGroup(context) {
    private val densityScale = density.density
    private val horizontalInsetPx = (horizontalInsetDp.coerceAtLeast(0) * densityScale).roundToInt()
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
        val width = resolveSize(action.preferredWidth(false) + safeInsets.left + safeInsets.right +
            2 * horizontalInsetPx, w)
        val actionWidth = surfaceRight(width) - surfaceLeft(width)
        val wanted = max(minimumActionHeightPx, action.intrinsicHeight(actionWidth))
        val height = resolveSize(wanted + safeInsets.bottom, h)
        action.measure(exact(actionWidth), exact((height - safeInsets.bottom).coerceAtLeast(0)))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = surfaceLeft(width)
        val right = surfaceRight(width)
        action.layout(left, 0, left + action.measuredWidth, action.measuredHeight)
        val radius = surfaceRadius()
        clip.rewind()
        clip.addRoundRect(left.toFloat(), 0f, right.toFloat(), height.toFloat(),
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
        drawInset(canvas, action.left.toFloat(), action.bottom.toFloat(), action.right.toFloat(), height.toFloat())
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
            val left = surfaceLeft(width).toFloat()
            val right = surfaceRight(width).toFloat()
            val radius = surfaceRadius()
            val x = event.x
            val y = event.y
            // Neither the transparent gutters nor the navigation fill is an action target.
            if (x < left || x >= right || y < 0f || y >= action.bottom) return false
            if (y < radius && (x < left + radius || x > right - radius)) {
                val center = if (x < left + radius) left + radius else right - radius
                val dx = x - center
                val dy = y - radius
                if (dx * dx + dy * dy > radius * radius) return false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // Design spacing is additional to remaining safe insets; never re-read window insets here.
    private fun surfaceLeft(width: Int) = (safeInsets.left + horizontalInsetPx).coerceAtMost(width)
    private fun surfaceRight(width: Int) =
        (width - safeInsets.right - horizontalInsetPx).coerceAtLeast(surfaceLeft(width))
    private fun surfaceRadius() =
        min(topCornerRadiusPx, min((surfaceRight(width) - surfaceLeft(width)) / 2f, height.toFloat()))

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
