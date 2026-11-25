package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.graphics.Insets
import smallthingz.ui.components.NativeButtonRecipe
import smallthingz.ui.components.NativeMaterialButtonView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-width primary action with rounded top corners and a square, inset-filled bottom edge.
 * The existing button retains input, accessibility, state, callback and lifetime ownership.
 * Pass remaining insets only; the navigation region is painted but is not an action target.
 */
class ReverbBottomActionDock(
    context: Context,
    theme: ReverbTheme,
    val action: NativeMaterialButtonView,
) : ViewGroup(context) {
    private val density = theme.density.density
    val topCornerRadiusPx: Float = 28f * density
    val minimumActionHeightPx: Int = (64f * density).roundToInt()
    private var safeInsets = Insets.NONE
    val safeBottomInset: Int get() = safeInsets.bottom
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = action.sampledContainerColor }
    private val updateColor: (Int) -> Unit = { color ->
        if (paint.color != color) { paint.color = color; invalidate() }
    }

    init {
        require(action.parent == null) { "Action already belongs to a parent" }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        action.joinActionSurface()
        action.onContainerColorChanged = updateColor
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
        canvas.drawRect(0f, action.bottom.toFloat(), width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, 0f, action.left.toFloat(), action.bottom.toFloat(), paint)
        canvas.drawRect(action.right.toFloat(), 0f, width.toFloat(), action.bottom.toFloat(), paint)
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
        action.onContainerColorChanged = updateColor
        updateColor(action.sampledContainerColor)
    }

    override fun onDetachedFromWindow() {
        action.onContainerColorChanged = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** Optional construction recipe; the dock also accepts an existing retained button. */
        fun buttonStyle(theme: ReverbTheme): NativeButtonRecipe = theme.appearance.buttons.filled.let {
            it.copy(surface = it.surface.copy(minimumHeightDp = 64, radiusDp = 0f), elevated = false)
        }
        private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    }
}
