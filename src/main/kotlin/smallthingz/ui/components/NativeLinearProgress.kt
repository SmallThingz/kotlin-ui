// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ProgressBar
import smallthingz.ui.animation.core.CubicBezierEasing
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Retained native progress; explicit geometry keeps the caller's loading slot stable. */
class NativeLinearProgress(
    context: Context,
    color: Int,
    trackColor: Int,
    heightDp: Float = 3f,
    gapDp: Float = 4f,
) : ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal) {
    private var componentHeightDp = heightDp
    private var componentGapDp = gapDp
    private var indicator: LinearIndicatorDrawable? = null
    private var animationOwner: NativeProgressAnimationOwner? = null
    private var fractionOverride: Float? = null
    private var ready = false
    private val colorMotion=smallthingz.ui.animation.view.ViewColorTransition(this,intArrayOf(color,trackColor),::publishColors)
    private fun publishColors(){indicator?.applyColors(colorMotion.color(0),colorMotion.color(1))}

    init {
        validateGeometry(heightDp, gapDp)
        val density = resources.displayMetrics.density
        indicator = LinearIndicatorDrawable(color, trackColor).apply {
            applyGeometry(context.componentDp(240f), context.componentDp(heightDp),
                gapDp * density, 4f * density, 6f * density)
        }
        indeterminateTintList = null
        progressTintList = null
        secondaryProgressTintList = null
        progressBackgroundTintList = null
        indeterminateDrawable = indicator
        progressDrawable = indicator
        isIndeterminate = true
        background = null
        setPadding(0, 0, 0, 0)
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        ready = true
        updateGeometry()
        animationOwner = NativeProgressAnimationOwner(this, checkNotNull(indicator))
    }

    fun applyColors(color: Int, trackColor: Int) { colorMotion.retarget(color,trackColor) }

    fun applyGeometry(heightDp: Float, gapDp: Float) {
        validateGeometry(heightDp, gapDp)
        if (componentHeightDp == heightDp && componentGapDp == gapDp) return
        componentHeightDp = heightDp
        componentGapDp = gapDp
        updateGeometry()
    }

    /**
     * Changes drawing only; native progress, layout and animation ownership stay intact.
     * With [roundedEnds] false, track and active segments end exactly at their bounds.
     * [showStopMarker] controls the terminal marker in determinate mode only.
     */
    fun applyAppearance(roundedEnds: Boolean, showStopMarker: Boolean) {
        indicator?.applyAppearance(roundedEnds, showStopMarker)
    }

    /** The caller selects determinate mode; this publishes a finite, clamped native progress value. */
    fun setProgressFraction(fraction: Float) {
        val normalized = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        min = 0
        max = 1_000_000
        progress = (normalized * max).roundToInt()
        fractionOverride = normalized
        invalidate()
    }

    override fun setProgress(progress: Int) {
        fractionOverride = null
        super.setProgress(progress)
        invalidate()
    }

    override fun setProgress(progress: Int, animate: Boolean) {
        fractionOverride = null
        super.setProgress(progress, animate)
        invalidate()
    }

    override fun setMin(min: Int) {
        fractionOverride = null
        super.setMin(min)
    }

    override fun setMax(max: Int) {
        fractionOverride = null
        super.setMax(max)
    }

    override fun setIndeterminateDrawable(drawable: Drawable?) {
        indicator?.takeIf { it !== drawable }?.allowAnimation(false)
        super.setIndeterminateDrawable(drawable)
        animationOwner?.update()
    }

    override fun setProgressDrawable(drawable: Drawable?) {
        super.setProgressDrawable(drawable)
        if (ready) updateIndicatorBounds()
    }

    override fun setIndeterminate(indeterminate: Boolean) {
        super.setIndeterminate(indeterminate)
        animationOwner?.update()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSizeAndState(context.componentDp(240f), widthMeasureSpec, 0),
            resolveSizeAndState(context.componentDp(componentHeightDp), heightMeasureSpec, 0))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (ready) updateIndicatorBounds()
    }

    override fun onDraw(canvas: Canvas) {
        val owned = indicator
        val current = if (isIndeterminate) indeterminateDrawable else progressDrawable
        if (owned == null || current !== owned) {
            super.onDraw(canvas)
            return
        }
        // Bypass the platform's private theme mirroring; this drawable resolves RTL exactly once.
        owned.indeterminate = isIndeterminate
        owned.rightToLeft = layoutDirection == LAYOUT_DIRECTION_RTL
        owned.fraction = fractionOverride ?: (owned.level / 10000f).coerceIn(0f, 1f)
        val save = canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        owned.draw(canvas)
        canvas.restoreToCount(save)
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

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ready) updateGeometry()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (width <= 0 || height <= 0) return
        val extra = context.componentDp(10f)
        val bounds = Rect()
        info.getBoundsInParent(bounds)
        bounds.inset(0, -extra)
        info.setBoundsInParent(bounds)
        info.getBoundsInScreen(bounds)
        bounds.inset(0, -extra)
        info.setBoundsInScreen(bounds)
    }

    private fun updateGeometry() {
        val density = resources.displayMetrics.density
        minimumHeight = context.componentDp(componentHeightDp)
        minimumWidth = 0
        indicator?.applyGeometry(context.componentDp(240f), minimumHeight,
            componentGapDp * density, 4f * density, 6f * density)
        updateIndicatorBounds()
        requestLayout()
        invalidate()
    }

    private fun updateIndicatorBounds() {
        indicator?.setBounds(0, 0, (width - paddingLeft - paddingRight).coerceAtLeast(0),
            (height - paddingTop - paddingBottom).coerceAtLeast(0))
    }

    private companion object {
        fun validateGeometry(heightDp: Float, gapDp: Float) {
            require(heightDp.isFinite() && heightDp > 0f)
            require(gapDp.isFinite() && gapDp >= 0f)
        }
    }
}

/** Material3 1.4.0 motion and drawing; source JAR SHA256 08a23a069407ba2a59685f88d676fd38ede207301a0805ee904ed63735f5d0b5. */
private class LinearIndicatorDrawable(color: Int, trackColor: Int) : NativeAnimatedProgressDrawable(1750L) {
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var activeColor = color
    private var inactiveColor = trackColor
    private var drawableAlpha = 255
    private var preferredWidth = 0
    private var preferredHeight = 0
    private var gap = 0f
    private var stopSize = 0f
    private var stopTrailingSpace = 0f
    private var roundedEnds = true
    private var showStopMarker = true
    var indeterminate = true
    var rightToLeft = false
    var fraction = 0f

    init { updateColors() }
    fun applyColors(color: Int, trackColor: Int) {
        if (activeColor == color && inactiveColor == trackColor) return
        activeColor = color
        inactiveColor = trackColor
        updateColors()
        invalidateSelf()
    }
    fun applyGeometry(width: Int, height: Int, gap: Float, stopSize: Float, stopTrailingSpace: Float) {
        preferredWidth = width
        preferredHeight = height
        this.gap = gap
        this.stopSize = stopSize
        this.stopTrailingSpace = stopTrailingSpace
        invalidateSelf()
    }
    fun applyAppearance(roundedEnds: Boolean, showStopMarker: Boolean) {
        if (this.roundedEnds == roundedEnds && this.showStopMarker == showStopMarker) return
        this.roundedEnds = roundedEnds
        this.showStopMarker = showStopMarker
        invalidateSelf()
    }
    override fun onLevelChange(level: Int): Boolean { invalidateSelf(); return true }
    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return
        val rounded = roundedEnds && height <= width
        val cap = if (rounded) Paint.Cap.ROUND else Paint.Cap.BUTT
        activePaint.strokeWidth = height
        trackPaint.strokeWidth = height
        activePaint.strokeCap = cap
        trackPaint.strokeCap = cap
        val gapFraction = (gap + if (rounded) height else 0f) / width
        if (indeterminate) {
            // Keep a visible authored-cycle frame when system motion is disabled.
            // Drawing must not overwrite the phase retained for normal animation.
            val time = if (ValueAnimator.areAnimatorsEnabled()) animationTimeMillis % 1750f else 875f
            val head1 = endpoint(time, 0f, 1000f)
            val tail1 = endpoint(time, 250f, 1000f)
            val head2 = endpoint(time, 650f, 850f)
            val tail2 = endpoint(time, 900f, 850f)
            if (head1 < 1f - gapFraction) {
                drawSegment(canvas, if (head1 > 0f) head1 + gapFraction else 0f, 1f, trackPaint, rounded)
            }
            if (head1 - tail1 > 0f) drawSegment(canvas, head1, tail1, activePaint, rounded)
            if (tail1 > gapFraction) {
                drawSegment(canvas, if (head2 > 0f) head2 + gapFraction else 0f,
                    if (tail1 < 1f) tail1 - gapFraction else 1f, trackPaint, rounded)
            }
            if (head2 - tail2 > 0f) drawSegment(canvas, head2, tail2, activePaint, rounded)
            if (tail2 > gapFraction) {
                drawSegment(canvas, 0f, if (tail2 < 1f) tail2 - gapFraction else 1f, trackPaint, rounded)
            }
        } else {
            val progress = fraction.coerceIn(0f, 1f)
            val trackStart = progress + min(progress, gapFraction)
            if (trackStart <= 1f) drawSegment(canvas, trackStart, 1f, trackPaint, rounded)
            drawSegment(canvas, 0f, progress, activePaint, rounded)
            if (showStopMarker) {
                val markerSize = min(stopSize, height)
                val offset = min((height - markerSize) / 2f, stopTrailingSpace)
                val logicalCenter = width - markerSize / 2f - offset
                val centerX = bounds.left + if (rightToLeft) width - logicalCenter else logicalCenter
                val centerY = bounds.exactCenterY()
                if (rounded) canvas.drawCircle(centerX, centerY, markerSize / 2f, stopPaint)
                else canvas.drawRect(centerX - markerSize / 2f, centerY - markerSize / 2f,
                    centerX + markerSize / 2f, centerY + markerSize / 2f, stopPaint)
            }
        }
    }

    private fun drawSegment(canvas: Canvas, start: Float, end: Float, paint: Paint, rounded: Boolean) {
        val width = bounds.width().toFloat()
        var from = (if (rightToLeft) 1f - end else start) * width
        var to = (if (rightToLeft) 1f - start else end) * width
        if (rounded) {
            if (abs(end - start) <= 0f) return
            val half = bounds.height() / 2f
            from = from.coerceIn(half, width - half)
            to = to.coerceIn(half, width - half)
        }
        // Preserve the round-cap dot when a nonzero fraction clamps both endpoints to one pixel.
        canvas.drawLine(bounds.left + from, bounds.exactCenterY(), bounds.left + to,
            bounds.exactCenterY(), paint)
    }
    override fun getIntrinsicWidth(): Int = preferredWidth
    override fun getIntrinsicHeight(): Int = preferredHeight
    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        updateColors()
        invalidateSelf()
    }
    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) {
        activePaint.colorFilter = colorFilter
        trackPaint.colorFilter = colorFilter
        stopPaint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    private fun updateColors() {
        activePaint.color = activeColor
        activePaint.alpha = (Color.alpha(activeColor) * drawableAlpha + 127) / 255
        stopPaint.color = activeColor
        stopPaint.alpha = activePaint.alpha
        trackPaint.color = inactiveColor
        trackPaint.alpha = (Color.alpha(inactiveColor) * drawableAlpha + 127) / 255
    }
    private companion object {
        val EASING = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        fun endpoint(time: Float, delay: Float, duration: Float): Float =
            EASING.transform(((time - delay) / duration).coerceIn(0f, 1f))
    }
}
