/*
 * Derived from AndroidX Material3 1.4.0 PullToRefresh.kt.
 * Copyright 2023 The Android Open Source Project. Licensed under Apache-2.0.
 */
package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.animation.view.ViewColorTransition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Retained circle, shadow outline and centered indicator leaf. */
class NativePullRefreshSurface(
    context: Context,
    private var density: Float,
    private var style: NativePullRefreshIndicatorStyle,
    initialColor: Int,
) : NativeRoundedClipLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = initialColor
    }
    private val motion: ViewColorTransition = ViewColorTransition(this, intArrayOf(initialColor), ::publishColor)
    private val bounds = RectF()
    var color: Int
        get() = paint.color
        set(value) { motion.retarget(value) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        if (Build.VERSION.SDK_INT >= 28) {
            outlineAmbientShadowColor = Color.BLACK
            outlineSpotShadowColor = Color.BLACK
        }
    }

    fun bindStyle(density: Float, style: NativePullRefreshIndicatorStyle) {
        if (this.density == density && this.style == style) return
        this.density = density
        this.style = style
        requestLayout()
    }

    private fun publishColor() {
        paint.color = motion.color(0)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) / 2f
        canvas.drawRoundRect(bounds, radius, radius, paint)
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = (style.glyphDp * density).roundToInt()
        for (i in 0 until childCount) getChildAt(i).measure(
            MeasureSpec.makeMeasureSpec(size.coerceAtMost(MeasureSpec.getSize(w)), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size.coerceAtMost(MeasureSpec.getSize(h)), MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val x = ((width - child.measuredWidth) / 2f).roundToInt()
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}

class NativePullRefreshArrowView(
    context: Context,
    private var density: Float,
    private var style: NativePullRefreshIndicatorStyle,
    private var color: Int,
    initialProgress: Float,
    private val scope: CoroutineScope,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = this@NativePullRefreshArrowView.style.strokeDp * density
    }
    private val bounds = RectF()
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private var progress = initialProgress
    private var rotation = 0f
    private var startAngle = 0f
    private var endAngle = 0f
    private var arrowScale = 0f
    private var targetAlpha = if (progress >= 1f) 1f else style.pullingAlpha
    private val inkAlpha = Animatable(targetAlpha)
    private var inkJob: Job? = null
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        updateGeometry()
    }

    private val colorMotion: ViewColorTransition = ViewColorTransition(this, intArrayOf(color), ::publishColor)
    fun setColor(value: Int) = colorMotion.retarget(value)

    private fun publishColor() {
        color = colorMotion.color(0)
        invalidate()
    }

    fun bindStyle(density: Float, style: NativePullRefreshIndicatorStyle) {
        if (this.density == density && this.style == style) return
        this.density = density
        this.style = style
        paint.strokeWidth = style.strokeDp * density
        updateInkTarget()
        invalidate()
    }

    private fun updateGeometry() = pullRefreshArrow(progress) { rotation, start, end, scale ->
        this.rotation = rotation
        startAngle = start
        endAngle = end
        arrowScale = scale
    }

    fun bindProgress(value: Float) {
        if (progress == value) return
        progress = value
        updateGeometry()
        updateInkTarget()
        invalidate()
    }

    private fun updateInkTarget() {
        val alpha = if (progress >= 1f) 1f else style.pullingAlpha
        if (targetAlpha != alpha) {
            targetAlpha = alpha
            // Animatable's mutator performs source cancellation with its current velocity.
            inkJob = scope.launch {
                inkAlpha.animateTo(alpha, style.effects) { invalidate() }
            }
        }
    }

    override fun onDetachedFromWindow() {
        inkJob?.cancel()
        inkJob = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = style.strokeDp * density
        val radius = style.arcRadiusDp * density + stroke / 2f
        val cx = width / 2f
        val cy = height / 2f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.color = (color and 0x00ffffff) or
            (((Color.alpha(color) * inkAlpha.value + .5f).toInt().coerceIn(0, 255)) shl 24)
        val save = canvas.save()
        val arrowWidth = style.arrowWidthDp * density * arrowScale
        val arrowHeight = style.arrowHeightDp * density * arrowScale
        val x = radius + cx - arrowWidth / 2f
        val y = cy - stroke
        path.rewind()
        path.moveTo(x, y)
        path.lineTo(x + arrowWidth / 2f, y + arrowHeight)
        path.lineTo(x + arrowWidth, y)
        try {
            canvas.rotate(rotation, cx, cy)
            canvas.drawArc(bounds, startAngle, endAngle - startAngle, false, paint)
            canvas.rotate(endAngle - stroke, cx, cy)
            canvas.drawPath(path, paint)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun getAccessibilityClassName(): CharSequence =
        if (progress > 0f) "android.widget.ProgressBar" else View::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = accessibilityClassName
        if (progress > 0f) {
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, progress)
            val normalized = progress.coerceIn(0f, 1f)
            val percent = when (normalized) {
                0f -> 0
                1f -> 100
                else -> (normalized * 100f).roundToInt().coerceIn(1, 99)
            }
            AccessibilityNodeInfoCompat.wrap(info).stateDescription =
                context.getString(smallthingz.ui.R.string.template_percent, percent)
        }
    }
}

internal data class NativePullRefreshArrow(
    val rotation: Float,
    val startAngle: Float,
    val endAngle: Float,
    val scale: Float,
)

/** Keep source's operation order, including rotation being used as degrees by the outer rotate. */
internal fun nativePullRefreshArrow(progress: Float): NativePullRefreshArrow =
    pullRefreshArrow(progress, ::NativePullRefreshArrow)

private inline fun <T> pullRefreshArrow(progress: Float, result: (Float, Float, Float, Float) -> T): T {
    val adjustedPercent = max(min(1f, progress) - .4f, 0f) * 5 / 3
    val overshootPercent = abs(progress) - 1f
    val linearTension = overshootPercent.coerceIn(0f, 2f)
    val tensionPercent = linearTension - linearTension.pow(2) / 4
    val endTrim = adjustedPercent * .8f
    val rotation = (-.25f + .4f * adjustedPercent + tensionPercent) * .5f
    return result(rotation, rotation * 360, (rotation + endTrim) * 360,
        min(1f, adjustedPercent))
}
