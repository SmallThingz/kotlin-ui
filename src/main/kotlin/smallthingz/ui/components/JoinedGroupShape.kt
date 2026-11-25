package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Joined rows use raw 1.dp outlines,
 * per-row selected side strokes, and an independently colored one-physical-pixel top separator.
 * A decoration of NativeGroupLayout, sharing its child clip and input owner.
 */
internal class JoinedGroupShape {
    private var width = 0f
    private var height = 0f
    private var density = 1f
    var topRadius = 0f
        private set
    var bottomRadius = 0f
        private set
    private var edges = NativeGroupEdges(true, true)
    private var cornersFit = true
    private val bounds = RectF()
    private val fill = Path()
    private val border = Path()
    private val arc = RectF()
    private val touchPoint = Path()
    private val intersection = Path()
    private val radii = FloatArray(8)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    fun update(width: Float, height: Float, density: Float, edges: NativeGroupEdges) {
        this.width = width
        this.height = height
        this.density = density
        this.edges = edges
        bounds.set(0f, 0f, width, height)
        val geometry = joinedGroupGeometry(width, height, density, edges)
        topRadius = geometry.topRadius
        bottomRadius = geometry.bottomRadius
        cornersFit = geometry.cornersFit
        for (index in 0..3) radii[index] = topRadius
        for (index in 4..7) radii[index] = bottomRadius
        fill.rewind()
        fill.addRoundRect(bounds, radii, Path.Direction.CCW)

        val inset = geometry.strokeInset
        val left = inset
        val right = width - inset
        val top = inset
        val bottom = height - inset
        val radius = geometry.strokeRadius
        border.rewind()
        if (edges.first) {
            border.moveTo(left, top + radius)
            arc.set(left, top, left + 2f * radius, top + 2f * radius)
            border.arcTo(arc, 180f, 90f, false)
            border.lineTo(right - radius, top)
            arc.set(right - 2f * radius, top, right, top + 2f * radius)
            border.arcTo(arc, 270f, 90f, false)
        } else if (edges.squareTop) {
            // Traverse the left side into this square corner so its stroke has a real join.
            // Two butt-capped segments meeting here would leave a missing outer corner pixel.
            border.moveTo(left, if (edges.last) bottom - radius else if (edges.squareBottom) bottom else height)
            border.lineTo(left, top)
            border.lineTo(right, top)
        } else border.moveTo(right, 0f)
        border.lineTo(right, if (edges.last) bottom - radius else if (edges.squareBottom) bottom else height)
        if (edges.last) {
            arc.set(right - 2f * radius, bottom - 2f * radius, right, bottom)
            border.arcTo(arc, 0f, 90f, false)
            border.lineTo(left + radius, bottom)
            arc.set(left, bottom - 2f * radius, left + 2f * radius, bottom)
            border.arcTo(arc, 90f, 90f, false)
        } else if (edges.squareBottom) border.lineTo(left, bottom)
        else border.moveTo(left, height)
        if (!edges.first && edges.squareTop) {
            if (edges.last || edges.squareBottom) border.close()
        } else border.lineTo(left, if (edges.first) top + radius else 0f)
    }

    fun copyFillPath(destination: Path) { destination.set(fill) }

    fun drawFill(canvas: Canvas, fillPaint: Paint) {
        if (topRadius == 0f && bottomRadius == 0f) canvas.drawRect(bounds, fillPaint)
        else if (topRadius == bottomRadius) canvas.drawRoundRect(bounds, topRadius, topRadius, fillPaint)
        else canvas.drawPath(fill, fillPaint)
    }

    fun drawBorder(canvas: Canvas, borderColor: Int, separatorColor: Int) {
        paint.color = borderColor
        paint.strokeWidth = density
        canvas.drawPath(border, paint)
        if (!edges.first && !edges.squareTop) {
            paint.color = separatorColor
            paint.strokeWidth = 1f
            canvas.drawLine(0f, 0.5f, width, 0.5f, paint)
        }
    }

    fun acceptsPointer(x: Float, y: Float, touch: Boolean, minimumTarget: Float): Boolean {
        if (contains(x, y)) return true
        if (!touch || width <= 0f || height <= 0f || !x.isFinite() || !y.isFinite() ||
            (width >= minimumTarget && height >= minimumTarget)) return false
        val horizontal = ((minimumTarget - width) / 2f).coerceAtLeast(0f)
        val vertical = ((minimumTarget - height) / 2f).coerceAtLeast(0f)
        return x >= -horizontal && x <= width + horizontal && y >= -vertical && y <= height + vertical
    }

    private fun contains(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || x < 0f || x >= width || y < 0f || y >= height) return false
        // ShapeContainingUtil falls back to a tiny path intersection when same-edge corners
        // overlap. CornerBasedShape normalizes vertical sums only; clamping each radius here
        // would disagree with Android's actual normalized Path for very narrow first/last rows.
        if (!cornersFit) {
            touchPoint.rewind()
            touchPoint.addRect(x - .005f, y - .005f, x + .005f, y + .005f, Path.Direction.CCW)
            intersection.op(fill, touchPoint, Path.Op.INTERSECT)
            val result = !intersection.isEmpty
            intersection.rewind()
            touchPoint.rewind()
            return result
        }
        val radius = when {
            y < topRadius -> topRadius
            y > height - bottomRadius -> bottomRadius
            else -> return true
        }
        val centerY = if (y < topRadius) topRadius else height - bottomRadius
        val centerX = when {
            x < radius -> radius
            x > width - radius -> width - radius
            else -> return true
        }
        val dx = x - centerX
        val dy = y - centerY
        return (dx * dx) / (radius * radius) + (dy * dy) / (radius * radius) <= 1f
    }
}
