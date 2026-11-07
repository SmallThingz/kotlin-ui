// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.ProgressBar
import smallthingz.ui.animation.core.CubicBezierEasing
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An indeterminate circular indicator with explicit visual geometry and native progress semantics.
 * The caller owns its loading label. [paddingDp] is 12dp for a 48dp editor slot, or 0dp for a 24dp
 * inline indicator. Color and geometry updates retain the drawable and its animation phase.
 */
class NativeCircularProgress(
    context: Context,
    color: Int,
    diameterDp: Float = 24f,
    strokeWidthDp: Float = 2f,
    paddingDp: Float = 12f,
) : ProgressBar(context, null, android.R.attr.progressBarStyleSmall) {
    private var componentDiameterDp = diameterDp
    private var componentStrokeWidthDp = strokeWidthDp
    private var componentPaddingDp = paddingDp
    private var indicator: CircularIndicatorDrawable? = null
    private var ready = false
    private var animationOwner: NativeProgressAnimationOwner? = null
    private val colorMotion=smallthingz.ui.animation.view.ViewColorTransition(this,intArrayOf(color),::publishColor)
    private fun publishColor(){indicator?.applyColor(colorMotion.color(0))}

    init {
        validateGeometry(diameterDp, strokeWidthDp, paddingDp)
        val density = resources.displayMetrics.density
        indicator = CircularIndicatorDrawable(color).apply {
            applyGeometry(context.componentDp(diameterDp).toFloat(), strokeWidthDp * density)
        }
        indeterminateTintList = null
        indeterminateDrawable = indicator
        isIndeterminate = true
        background = null
        isFocusable = false
        // Circular motion is clockwise in both writing directions, including mirrored platform themes.
        layoutDirection = LAYOUT_DIRECTION_LTR
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        ready = true
        updateGeometry()
        animationOwner = NativeProgressAnimationOwner(this, checkNotNull(indicator))
    }

    fun applyColor(color: Int) {
        colorMotion.retarget(color)
    }

    fun applyGeometry(diameterDp: Float, strokeWidthDp: Float, paddingDp: Float) {
        validateGeometry(diameterDp, strokeWidthDp, paddingDp)
        if (componentDiameterDp == diameterDp && componentStrokeWidthDp == strokeWidthDp &&
            componentPaddingDp == paddingDp) return
        componentDiameterDp = diameterDp
        componentStrokeWidthDp = strokeWidthDp
        componentPaddingDp = paddingDp
        updateGeometry()
    }

    override fun setIndeterminateDrawable(drawable: Drawable?) {
        // ProgressBar construction can dispatch here before this component's fields exist.
        indicator?.takeIf { it !== drawable }?.allowAnimation(false)
        super.setIndeterminateDrawable(drawable)
        animationOwner?.update()
    }

    override fun setIndeterminate(indeterminate: Boolean) {
        super.setIndeterminate(indeterminate)
        animationOwner?.update()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = preferredSize()
        setMeasuredDimension(resolveSizeAndState(size, widthMeasureSpec, 0),
            resolveSizeAndState(size, heightMeasureSpec, 0))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (ready) updateIndicatorBounds()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        animationOwner?.onVisibilityAggregated(isVisible)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        animationOwner?.update()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        animationOwner?.update()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ready) updateGeometry()
    }

    private fun updateGeometry() {
        val density = resources.displayMetrics.density
        val padding = context.componentDp(componentPaddingDp)
        setPadding(padding, padding, padding, padding)
        val size = preferredSize()
        minimumWidth = size
        minimumHeight = size
        indicator?.applyGeometry(context.componentDp(componentDiameterDp).toFloat(), componentStrokeWidthDp * density)
        updateIndicatorBounds()
        requestLayout()
        invalidate()
    }

    // Round content and padding separately so fractional densities cannot steal a content pixel.
    private fun preferredSize(): Int = context.componentDp(componentDiameterDp) +
        context.componentDp(componentPaddingDp) * 2

    private fun updateIndicatorBounds() {
        indicator?.setBounds(0, 0, (width - paddingLeft - paddingRight).coerceAtLeast(0),
            (height - paddingTop - paddingBottom).coerceAtLeast(0))
    }

    private companion object {
        fun validateGeometry(diameterDp: Float, strokeWidthDp: Float, paddingDp: Float) {
            require(diameterDp.isFinite() && diameterDp > 0f)
            require(strokeWidthDp.isFinite() && strokeWidthDp > 0f && strokeWidthDp <= diameterDp)
            require(paddingDp.isFinite() && paddingDp >= 0f)
            require((diameterDp + paddingDp * 2f).isFinite())
        }
    }
}

/**
 * Motion follows Material3 1.4.0 ProgressIndicator.kt; source JAR SHA-256:
 * 08a23a069407ba2a59685f88d676fd38ede207301a0805ee904ed63735f5d0b5.
 */
private class CircularIndicatorDrawable(color: Int) : NativeAnimatedProgressDrawable(CYCLE_MILLIS) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = RectF()
    private var componentColor = color
    private var drawableAlpha = 255
    private var diameter = 0f
    private var strokeWidth = 0f

    init { updateColor() }

    fun applyColor(color: Int) {
        if (componentColor == color) return
        componentColor = color
        updateColor()
        invalidateSelf()
    }

    fun applyGeometry(diameter: Float, strokeWidth: Float) {
        this.diameter = diameter
        this.strokeWidth = strokeWidth
        paint.strokeWidth = strokeWidth
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val extent = min(diameter, min(bounds.width(), bounds.height()).toFloat())
        if (extent <= 0f || strokeWidth <= 0f) return
        val actualStroke = min(strokeWidth, extent)
        paint.strokeWidth = actualStroke
        val radius = (extent - actualStroke) / 2f
        arc.set(bounds.exactCenterX() - radius, bounds.exactCenterY() - radius,
            bounds.exactCenterX() + radius, bounds.exactCenterY() + radius)
        val time = animationTimeMillis.coerceIn(0f, CYCLE_MILLIS.toFloat())
        val segment = (time / ADDITIONAL_ROTATION_INTERVAL).toInt()
        val advance = ((time % ADDITIONAL_ROTATION_INTERVAL) / ADDITIONAL_ROTATION_DURATION).coerceAtMost(1f)
        val rotation = 1080f * time / CYCLE_MILLIS + 90f * (segment + advance)
        // Material keyframe easing belongs to the following interval: only the shrinking half eases.
        val progress = if (time <= HALF_CYCLE_MILLIS) {
            MIN_PROGRESS + PROGRESS_DELTA * time / HALF_CYCLE_MILLIS
        } else {
            MAX_PROGRESS - PROGRESS_DELTA * SHRINK_EASING.transform((time - HALF_CYCLE_MILLIS) / HALF_CYCLE_MILLIS)
        }
        canvas.drawArc(arc, rotation, progress * 360f, false, paint)
    }

    override fun getIntrinsicWidth(): Int = diameter.roundToInt()
    override fun getIntrinsicHeight(): Int = diameter.roundToInt()

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        updateColor()
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun updateColor() {
        paint.color = componentColor
        paint.alpha = (Color.alpha(componentColor) * drawableAlpha + 127) / 255
    }

    private companion object {
        const val CYCLE_MILLIS = 6000L
        const val HALF_CYCLE_MILLIS = 3000f
        const val ADDITIONAL_ROTATION_INTERVAL = 1500f
        const val ADDITIONAL_ROTATION_DURATION = 300f
        const val MIN_PROGRESS = 0.1f
        const val MAX_PROGRESS = 0.87f
        const val PROGRESS_DELTA = MAX_PROGRESS - MIN_PROGRESS
        val SHRINK_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}
