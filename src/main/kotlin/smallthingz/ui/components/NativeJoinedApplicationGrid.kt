package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ceil

/** One textured surface, one perimeter and single separators. The cells retain their controls. */
private class JoinedApplicationGridSurface(context: Context, palette: NativeColorScheme,
    recipe: NativeApplicationGridRecipe) {
    private val density = context.resources.displayMetrics.density
    private val radius = recipe.cornerRadiusDp * density
    private val fill = NativeForeground.foregroundBackground(palette.settingsControls, palette.field)
    private val stroke = density * (palette.surfacePreset?.borderWidthDp ?: 1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.border; style = Paint.Style.STROKE; strokeWidth = stroke
        strokeJoin = Paint.Join.MITER
    }
    private val shape = Path()
    private val mask = Path()
    private val region = Region()
    private var initialized = false
    private var top = 0f
    private var height = 0f
    private var width = 0
    private var count = 0
    private var columns = 1
    private var cellHeight = 1
    private var rtl = false

    fun update(width: Int, top: Float, count: Int, columns: Int, cellHeight: Int, rtl: Boolean) {
        if (initialized && this.width == width && this.top == top && this.count == count &&
            this.columns == columns && this.cellHeight == cellHeight && this.rtl == rtl) return
        initialized = true; this.top = top; this.width = width; this.count = count
        this.columns = columns; this.cellHeight = cellHeight; this.rtl = rtl
        shape.rewind(); mask.rewind(); region.setEmpty()
        if (count == 0 || width <= 0) return
        height = ((count - 1) / columns + 1) * cellHeight.toFloat()
        val corners = joinedGridCorners(count, columns, width.toFloat(), cellHeight.toFloat(), radius, rtl)
        shape.moveTo(corners.first().start.x, top + corners.first().start.y)
        for (corner in corners) {
            shape.lineTo(corner.start.x, top + corner.start.y)
            if (!corner.concave) {
                shape.quadTo(corner.vertex.x, top + corner.vertex.y, corner.end.x, top + corner.end.y)
            }
        }
        shape.close()
        mask.set(shape)
        // A scrolling search/header row is outside the joined application's surface.
        if (top > 0) mask.addRect(0f, 0f, width.toFloat(), top, Path.Direction.CW)
        region.setPath(shape, Region(0, kotlin.math.floor(top).toInt(), width, ceil(top + height).toInt()))
    }

    fun draw(canvas: Canvas, content: () -> Unit) {
        if (count == 0 || width <= 0) { content(); return }
        var save = canvas.save()
        canvas.clipPath(shape)
        fill.setBounds(0, top.toInt(), width, ceil(top + height).toInt()); fill.draw(canvas)
        canvas.restoreToCount(save)
        save = canvas.save(); canvas.clipPath(mask); content(); canvas.restoreToCount(save)
        save = canvas.save(); canvas.clipPath(shape)
        val rows = (count - 1) / columns + 1
        val last = (count - 1) % columns + 1
        val clip = canvas.clipBounds
        val firstRow = ((clip.top - top) / cellHeight).toInt().coerceAtLeast(0)
        val lastRow = ((clip.bottom - top) / cellHeight).toInt().coerceAtMost(rows - 1)
        for (row in firstRow..lastRow) {
            val cells = if (row == rows - 1) last else columns
            val y = top + row * cellHeight
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
        canvas.restoreToCount(save)
    }

    fun acceptsDown(x: Float, y: Float): Boolean = count == 0 || y < top || y >= top + height ||
        region.contains(x.toInt(), y.toInt())
}

/** Virtualized joined grid. Header items span all columns and are not painted as app cells. */
open class NativeJoinedApplicationRecyclerView(context: Context, palette: NativeColorScheme,
    private val recipe: NativeApplicationGridRecipe) : RecyclerView(context) {
    var headerItemCount: Int = 0
    private val surface = JoinedApplicationGridSurface(context, palette, recipe)
    private fun prepare() {
        val manager = layoutManager as? GridLayoutManager ?: return
        val count = ((adapter?.itemCount ?: 0) - headerItemCount).coerceAtLeast(0)
        var first: View? = null
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (getChildAdapterPosition(child) >= headerItemCount) { first = child; break }
        }
        val cellHeight = recipe.heightPx(context)
        val top = first?.let { it.top - (getChildAdapterPosition(it) - headerItemCount) / manager.spanCount * cellHeight }
        surface.update(width, top?.toFloat() ?: height.toFloat(), if (first == null) 0 else count,
            manager.spanCount, cellHeight, layoutDirection == LAYOUT_DIRECTION_RTL)
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
    private val surface = JoinedApplicationGridSurface(context, palette, recipe)
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
    private fun prepare() = surface.update(width, 0f, childCount, columnCount, recipe.heightPx(context),
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
