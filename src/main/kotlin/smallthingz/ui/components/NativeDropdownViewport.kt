package smallthingz.ui.components

import kotlin.math.ceil

/** Pixel geometry shared by painting, hit clipping and overflow cues; the anchor never moves. */
internal class NativeDropdownViewport(
    val top: Int,
    val bottom: Int,
    private val anchorTop: Int,
    private val rowHeight: Int,
) {
    init {
        require(rowHeight > 0 && top <= anchorTop && anchorTop.toLong() + rowHeight <= bottom)
    }

    val rowsAbove: Float = (anchorTop - top).toFloat() / rowHeight
    val rowsBelow: Float = (bottom - anchorTop - rowHeight).toFloat() / rowHeight

    // A short list that fits in whole slots uses a compact whole-slot budget.
    fun slotsAbove(count: Int): Int = slots(rowsAbove, count)
    fun slotsBelow(count: Int): Int = slots(rowsBelow, count)
    private fun slots(space: Float, count: Int): Int =
        if (count <= rowsAbove.toInt() + 1 + rowsBelow.toInt()) space.toInt() else ceil(space).toInt()

    fun revealTop(position: Float, reveal: Float): Float =
        anchorTop - minOf(position * rowHeight, (anchorTop - top).toFloat()) * reveal

    fun revealBottom(position: Float, lastIndex: Int, reveal: Float): Float =
        anchorTop + rowHeight + minOf((lastIndex - position) * rowHeight, (bottom - anchorTop - rowHeight).toFloat()) * reveal

    fun overflowsAbove(position: Float): Boolean = position * rowHeight > anchorTop - top + .5f
    fun overflowsBelow(position: Float, lastIndex: Int): Boolean =
        (lastIndex - position) * rowHeight > bottom - anchorTop - rowHeight + .5f
}

/** Intersect a screen-space safe edge with an already inset/resized overlay, exactly once. */
internal fun nativeDropdownLocalEdge(safeScreenEdge: Int, rootScreenTop: Int, rootHeight: Int): Int =
    (safeScreenEdge.toLong() - rootScreenTop).coerceIn(0L, rootHeight.toLong()).toInt()
