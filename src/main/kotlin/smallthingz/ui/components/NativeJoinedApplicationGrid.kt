package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import smallthingz.ui.graphics.NoiseDrawable
import kotlin.math.ceil

/** One textured surface, one perimeter and single separators. The cells retain their controls. */
private class JoinedApplicationGridSurface(context: Context, palette: NativeColorScheme,
    recipe: NativeApplicationGridRecipe, private val fill: NoiseDrawable) {
    private val density = context.resources.displayMetrics.density
    private val radius = recipe.cornerRadiusDp * density
    private val stroke = density * (palette.surfacePreset?.borderWidthDp ?: 1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.border; style = Paint.Style.STROKE; strokeWidth = stroke
        strokeJoin = Paint.Join.ROUND
    }
    private val shape = Path()
    private val mask = Path()
    private val region = Region()
    private val regionClip = Region()
    private val visibleClip = Rect()
    private var top = 0f
    private var height = 0f
    private var width = 0
    private var viewportHeight = 0
    private var count = 0
    private var columns = 1
    private var cellHeight = 1
    private var rtl = false

    fun update(width: Int, viewportHeight: Int, top: Float, count: Int, columns: Int, cellHeight: Int, rtl: Boolean) {
        this.top = top
        if (this.width == width && this.viewportHeight == viewportHeight && this.count == count &&
            this.columns == columns && this.cellHeight == cellHeight && this.rtl == rtl) return

        this.width = width; this.viewportHeight = viewportHeight; this.count = count
        this.columns = columns; this.cellHeight = cellHeight; this.rtl = rtl
        shape.rewind(); mask.rewind(); region.setEmpty()
        if (count == 0 || width <= 0) return

        height = ((count - 1) / columns + 1) * cellHeight.toFloat()
        val corners = joinedGridCorners(count, columns, width.toFloat(), cellHeight.toFloat(), radius, rtl)
        shape.moveTo(corners.first().start.x, corners.first().start.y)
        for (corner in corners) {
            shape.lineTo(corner.start.x, corner.start.y)
            if (!corner.concave) {
            shape.quadTo(corner.vertex.x, corner.vertex.y, corner.end.x, corner.end.y)
            }
        }
        shape.close()

        // A header can occupy at most the visible space above the first app row.
        // Cache that allowance in local coordinates so scroll only changes translation.
        mask.set(shape)
        mask.addRect(0f, -viewportHeight.toFloat(), width.toFloat(), 0f, Path.Direction.CW)
        val bottom = ceil(height).toInt()
        regionClip.set(0, 0, width, bottom)
        region.setPath(shape, regionClip)
        fill.setBounds(0, 0, width, bottom)
    }

    inline fun draw(canvas: Canvas, content: () -> Unit) {
        val offset = top
        if (count == 0 || width <= 0 || offset >= viewportHeight) { content(); return }
        var save = canvas.save()
        canvas.translate(0f, offset)
        canvas.clipPath(shape)
        fill.draw(canvas)
        canvas.restoreToCount(save)

        save = canvas.save()
        canvas.translate(0f, offset)
        canvas.clipPath(if (offset > 0f) mask else shape)
        canvas.translate(0f, -offset)
        try { content() } finally { canvas.restoreToCount(save) }

        save = canvas.save()
        canvas.translate(0f, offset)
        canvas.clipPath(shape)
        drawBorders(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawBorders(canvas: Canvas) {
        if (!canvas.getClipBounds(visibleClip)) return
        val rows = (count - 1) / columns + 1
        val last = (count - 1) % columns + 1
        val firstRow = (visibleClip.top / cellHeight).coerceAtLeast(0)
        val lastRow = (visibleClip.bottom / cellHeight).coerceAtMost(rows - 1)
        for (row in firstRow..lastRow) {
            val cells = if (row == rows - 1) last else columns
            val y = row * cellHeight.toFloat()
            if (row > 0) {
                val span = width * cells.toFloat() / columns
                canvas.drawLine(if (rtl) width - span else 0f, y,
                    if (rtl) width.toFloat() else span, y, paint)
            }
            for (column in 1 until cells) {
                val x = width * column.toFloat() / columns
                val physical = if (rtl) width - x else x
                canvas.drawLine(physical, y, physical, y + cellHeight, paint)
            }
        }
        // A double-width clipped stroke leaves one full-width perimeter inside the surface.
        paint.strokeWidth = stroke * 2; canvas.drawPath(shape, paint); paint.strokeWidth = stroke
    }

    fun acceptsDown(x: Float, y: Float): Boolean = count == 0 || y < top || y >= top + height ||
        (x >= 0f && x < width && region.contains(x.toInt(), (y - top).toInt()))
}

/** Virtualized joined grid. Header items span all columns and are not painted as app cells. */
open class NativeJoinedApplicationRecyclerView(context: Context, palette: NativeColorScheme,
    private val recipe: NativeApplicationGridRecipe) : RecyclerView(context) {
    var headerItemCount: Int = 0
    private val fill = NativeForeground.foregroundBackground(palette.settingsControls, palette.field)
    private val surface = JoinedApplicationGridSurface(context, palette, recipe, fill)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fill.callback = this
    }

    override fun onDetachedFromWindow() {
        fill.callback = null
        super.onDetachedFromWindow()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === fill || super.verifyDrawable(who)

    override fun invalidateDrawable(who: Drawable) {
        // The fill's bounds are grid-local; the visible part may be shifted past a header.
        if (who === fill) invalidate() else super.invalidateDrawable(who)
    }

    private fun prepare() {
        val manager = layoutManager as? GridLayoutManager
        if (manager == null) {
            surface.update(width, height, 0f, 0, 1, 1, false)
            return
        }
        val count = ((adapter?.itemCount ?: 0) - headerItemCount).coerceAtLeast(0)
        val columns = manager.spanCount
        val cellHeight = recipe.heightPx(context)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val position = getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || position < headerItemCount) continue
            val top = child.top - (position - headerItemCount) / columns * cellHeight.toFloat()
            surface.update(width, height, top, count, columns, cellHeight, layoutDirection == LAYOUT_DIRECTION_RTL)
            return
        }
        surface.update(width, height, 0f, 0, columns, cellHeight, layoutDirection == LAYOUT_DIRECTION_RTL)
    }

    override fun dispatchDraw(canvas: Canvas) { prepare(); surface.draw(canvas) { super.dispatchDraw(canvas) } }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            prepare(); if (!surface.acceptsDown(event.x, event.y)) return false
        }
        return super.dispatchTouchEvent(event)
    }
}

/** Small non-recycled picker uses the same surface and fixed logical columns, including short rows. */
class NativeJoinedApplicationGridLayout(context: Context, palette: NativeColorScheme,
    private val recipe: NativeApplicationGridRecipe) : ViewGroup(context) {
    var columnCount = 1
        set(value) { require(value > 0); if (field != value) { field = value; requestLayout() } }
    private val fill = NativeForeground.foregroundBackground(palette.settingsControls, palette.field)
    private val surface = JoinedApplicationGridSurface(context, palette, recipe, fill)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fill.callback = this
    }

    override fun onDetachedFromWindow() {
        fill.callback = null
        super.onDetachedFromWindow()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === fill || super.verifyDrawable(who)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = recipe.heightPx(context)
        for (index in 0 until childCount) {
            val col = index % columnCount
            val cellWidth = width * (col + 1) / columnCount - width * col / columnCount
            getChildAt(index).measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
        val rows = if (childCount == 0) 0 else (childCount - 1) / columnCount + 1
        setMeasuredDimension(width, resolveSize(rows * height, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cellHeight = recipe.heightPx(context)
        for (index in 0 until childCount) {
            val col = index % columnCount
            val a = width * col / columnCount; val b = width * (col + 1) / columnCount
            val y = index / columnCount * cellHeight
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            getChildAt(index).layout(if (rtl) width - b else a, y, if (rtl) width - a else b, y + cellHeight)
        }
    }
    private fun prepare() = surface.update(width, height, 0f, childCount, columnCount, recipe.heightPx(context),
        layoutDirection == LAYOUT_DIRECTION_RTL)
    override fun dispatchDraw(canvas: Canvas) { prepare(); surface.draw(canvas) { super.dispatchDraw(canvas) } }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            prepare(); if (!surface.acceptsDown(event.x, event.y)) return false
        }
        return super.dispatchTouchEvent(event)
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}
