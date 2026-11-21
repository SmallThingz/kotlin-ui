package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kotlin.math.roundToInt
import smallthingz.ui.animation.view.ViewColorTransition

/** Pixel bounds used by both the renderer and the caller's editor gesture. */
data class NativeCurvePlot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun x(fraction: Float): Float = left + fraction * width
    fun y(fraction: Float): Float = top + fraction * height
}

private data class CurveTextLabel(val text: String, val bounds: RectF, val baseline: Float, val textSize: Float)

/** Draws frequency and level data directly from the retained editor state. */
abstract class NativeFrequencyCurveView(
    context: Context,
    private val recipe: NativeCalibrationRecipe,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    // TypedValue uses Android's SP conversion, including nonlinear font scaling on Android 14+.
    private val tickTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, recipe.tickTextSp, resources.displayMetrics)
    private val valueTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, recipe.valueTextSp, resources.displayMetrics)
    private val path = Path()
    private val plot = RectF()
    var plotGeometry: NativeCurvePlot = NativeCurvePlot(0f, 0f, 0f, 0f)
        private set
    protected val pointHitRadiusPx get() = recipe.pointHitRadiusDp * density
    protected abstract val pointCount: Int
    protected abstract val selected: Int
    protected abstract fun frequencyHz(index: Int): Int
    protected abstract fun cutoffDb(index: Int): Int
    protected abstract fun frequencyFraction(hz: Int): Float
    private var accent = android.graphics.Color.TRANSPARENT
    private var ink = android.graphics.Color.TRANSPARENT
    private var muted = android.graphics.Color.TRANSPARENT
    private var pointColor = android.graphics.Color.TRANSPARENT
    private var colorMotion:ViewColorTransition?=null
    private var sampled: List<Float>? = null
    private var maximumHz = 20_000

    private companion object {
        val gridDecibels = intArrayOf(0, -24, -48, -72, -96)
        val gridFrequencies = intArrayOf(20, 100, 1_000, 10_000, 20_000)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        isClickable = true
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }

    protected fun bindPlot(peaks: List<Float>?, maxHz: Int, accentColor: Int, inkColor: Int,
                           mutedColor: Int, editable: Boolean, modelChanged: Boolean) {
        var changed = modelChanged
        val nextSamples = peaks?.takeIf { it.size >= 2 }
        if (sampled != nextSamples) {
            // Keep an owned snapshot so a caller's in-place sample update is observable.
            sampled = nextSamples?.toList()
            changed = true
        }
        val nextMaximumHz = maxHz.coerceIn(20, 20_000)
        if (maximumHz != nextMaximumHz) {
            maximumHz = nextMaximumHz
            changed = true
        }
        if (isEnabled != editable) {
            isEnabled = editable
            changed = true
        }
        val point=if(editable)accentColor else mutedColor
        val motion=colorMotion ?: ViewColorTransition(this,intArrayOf(accentColor,inkColor,mutedColor,point),{
            colorMotion?.let {applyColors(it.color(0),it.color(1),it.color(2),it.color(3))}
        }).also {colorMotion=it;applyColors(accentColor,inkColor,mutedColor,point)}
        motion.retarget(accentColor,inkColor,mutedColor,point)
        if (changed) invalidate()
    }

    private fun applyColors(accent:Int,ink:Int,muted:Int,point:Int) {
        this.accent=accent;this.ink=ink;this.muted=muted;pointColor=point;invalidate()
    }

    private fun x(hz: Int) = plotGeometry.x(frequencyFraction(hz))
    private fun y(db: Float) = plotGeometry.y((-db / 96f).coerceIn(0f, 1f))
    private fun alpha(color: Int, amount: Int): Int = (color and 0xffffff) or (amount shl 24)

    private fun textWidth(text: String, size: Float): Float {
        labelPaint.textSize = size
        return labelPaint.measureText(text)
    }

    private fun textHeight(size: Float): Float {
        labelPaint.textSize = size
        return labelPaint.fontMetrics.let { it.bottom - it.top }
    }

    private fun headerLines(width: Float): Int =
        if (textWidth("20000 Hz", valueTextSize) + textWidth("-96 dBFS", valueTextSize) + 12 * density <= width) 1 else 2

    private fun headerHeight(width: Float): Float =
        headerLines(width) * textHeight(valueTextSize) + (headerLines(width) - 1) * 4 * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize((280 * density).roundToInt(), widthMeasureSpec)
        val tickHeight = textHeight(tickTextSize)
        val plotHeight = maxOf(160 * density, 4 * (tickHeight + 8 * density))
        val preferred = headerHeight(measuredWidth.toFloat()) + 16 * density + tickHeight * 1.5f + plotHeight
        setMeasuredDimension(measuredWidth, resolveSize(maxOf(245 * density, preferred).roundToInt(), heightMeasureSpec))
    }

    private class LabelGeometryCache(
        val width: Int,
        val height: Int,
        val density: Float,
        val tickSize: Float,
        val valueSize: Float,
        val typeface: android.graphics.Typeface?,
        var frequency: Int,
        var cutoff: Int,
        val plot: NativeCurvePlot,
        val labels: MutableList<CurveTextLabel>,
    )
    private var labelCache: LabelGeometryCache? = null

    private fun drawingLabels(): List<CurveTextLabel> {
        val frequency = frequencyHz(selected)
        val cutoff = cutoffDb(selected)
        val cached = labelCache
        if (cached != null && cached.width == width && cached.height == height &&
            cached.density == density && cached.tickSize == tickTextSize && cached.valueSize == valueTextSize &&
            cached.typeface === labelPaint.typeface && cached.plot == plotGeometry) {
            if (cached.frequency != frequency || cached.cutoff != cutoff) {
                // Only the selected values change while dragging; static ticks keep their exact measured bounds.
                cached.labels[0] = frequencyLabel(frequency)
                cached.labels[1] = cutoffLabel(cutoff)
                cached.frequency = frequency
                cached.cutoff = cutoff
            }
            return cached.labels
        }
        return createLabels().also {
            labelCache = LabelGeometryCache(width, height, density, tickTextSize, valueTextSize,
                labelPaint.typeface, frequency, cutoff, plotGeometry, it)
        }
    }

    private fun label(text: String, left: Float, top: Float, size: Float): CurveTextLabel {
        labelPaint.textSize = size
        val metrics = labelPaint.fontMetrics
        return CurveTextLabel(text, RectF(left, top, left + labelPaint.measureText(text),
            top + metrics.bottom - metrics.top), top - metrics.top, size)
    }

    private fun frequencyLabel(frequency: Int) = label("$frequency Hz", 0f, 0f, valueTextSize)

    private fun cutoffLabel(db: Int): CurveTextLabel {
        val cutoff = "$db dBFS"
        val cutoffTop = if (headerLines(width.toFloat()) == 1) 0f else textHeight(valueTextSize) + 4 * density
        return label(cutoff, (width - textWidth(cutoff, valueTextSize)).coerceAtLeast(0f), cutoffTop, valueTextSize)
    }

    private fun createLabels(): MutableList<CurveTextLabel> {
        val labels = mutableListOf<CurveTextLabel>()
        val frequency = frequencyHz(selected)
        val cutoff = cutoffDb(selected)
        labels += frequencyLabel(frequency)
        labels += cutoffLabel(cutoff)
        for (db in gridDecibels) {
            val text = db.toString()
            labels += label(text, plot.left - 8 * density - textWidth(text, tickTextSize),
                y(db.toFloat()) - textHeight(tickTextSize) / 2f, tickTextSize)
        }
        val bottomTop = plot.bottom + 8 * density
        val last = label("20k", plot.right - textWidth("20k", tickTextSize), bottomTop, tickTextSize)
        val first = label("20", plot.left, bottomTop, tickTextSize)
        labels += first
        var previousRight = first.bounds.right
        for ((hz, text) in listOf(100 to "100", 1_000 to "1k", 10_000 to "10k")) {
            val candidate = label(text, x(hz) - textWidth(text, tickTextSize) / 2, bottomTop, tickTextSize)
            if (candidate.bounds.left >= previousRight + 6 * density && candidate.bounds.right <= last.bounds.left - 6 * density) {
                labels += candidate
                previousRight = candidate.bounds.right
            }
        }
        labels += last
        return labels
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pointCount == 0 || plot.width() <= 0 || plot.height() <= 0) return
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        val labels = drawingLabels()
        for (index in labels.indices) {
            val label = labels[index]
            paint.textSize = label.textSize
            paint.color = if (index < 2) ink else muted
            canvas.drawText(label.text, label.bounds.left, label.baseline, paint)
        }
        for (db in gridDecibels) {
            paint.color = alpha(muted, 38); paint.strokeWidth = density
            canvas.drawLine(plot.left, y(db.toFloat()), plot.right, y(db.toFloat()), paint)
        }
        for (hz in gridFrequencies) {
            val xp = x(hz)
            paint.color = alpha(muted, 32)
            canvas.drawLine(xp, plot.top, xp, plot.bottom, paint)
        }
        canvas.save()
        canvas.clipRect(plot)
        path.reset()
        path.moveTo(plot.left, plot.bottom)
        sampled?.let { levels ->
            levels.forEachIndexed { i, db -> path.lineTo(plot.left + i.toFloat() / (levels.size - 1) * plot.width(), y(db)) }
            path.lineTo(plot.right, plot.bottom); path.close()
            paint.color = alpha(muted, 66); paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
        }
        path.reset()
        for (index in 0 until pointCount) {
            val px = x(frequencyHz(index))
            val py = y(cutoffDb(index).toFloat())
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.lineTo(plot.right, plot.bottom); path.lineTo(plot.left, plot.bottom); path.close()
        paint.color = alpha(accent, 24); paint.style = Paint.Style.FILL; canvas.drawPath(path, paint)
        if (maximumHz < 20_000) {
            paint.color = alpha(muted, 35)
            canvas.drawRect(x(maximumHz), plot.top, plot.right, plot.bottom, paint)
            paint.color = alpha(muted, 100); paint.strokeWidth = density
            canvas.drawLine(x(maximumHz), plot.top, x(maximumHz), plot.bottom, paint)
        }
        canvas.restore()
        path.reset()
        for (index in 0 until pointCount) {
            val px = x(frequencyHz(index))
            val py = y(cutoffDb(index).toFloat())
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = recipe.curveStrokeDp * density; paint.color = accent
        paint.strokeJoin = Paint.Join.ROUND; canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        for (index in 0 until pointCount) {
            val px = x(frequencyHz(index))
            val py = y(cutoffDb(index).toFloat())
            val isSelected = index == selected
            if (isSelected) {
                paint.color = alpha(accent, 55)
                canvas.drawCircle(px, py, recipe.selectionHaloRadiusDp * density, paint)
            }
            paint.color = pointColor
            val radius = if (isSelected) recipe.selectedPointRadiusDp else recipe.pointRadiusDp
            canvas.drawCircle(px, py, radius * density, paint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val tickHeight = textHeight(tickTextSize)
        plot.set(textWidth("-96", tickTextSize) + 8 * density,
            headerHeight(w.toFloat()) + 8 * density + tickHeight / 2,
            w - 16 * density, h - tickHeight - 8 * density)
        plotGeometry = NativeCurvePlot(plot.left, plot.top, plot.right, plot.bottom)
    }

}
