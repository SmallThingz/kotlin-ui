package smallthingz.ui.components

import kotlin.math.hypot

/** Clockwise boundary of occupied cells, including the inward turn of a short last row. */
internal data class JoinedGridPoint(val x: Float, val y: Float)
internal data class JoinedGridCorner(val start: JoinedGridPoint, val vertex: JoinedGridPoint,
    val end: JoinedGridPoint, val concave: Boolean)

internal fun joinedGridCorners(count: Int, columns: Int, width: Float, cellHeight: Float,
    radius: Float, rtl: Boolean = false): List<JoinedGridCorner> {
    require(count >= 0 && columns > 0 && width.isFinite() && width >= 0 &&
        cellHeight.isFinite() && cellHeight > 0 && radius.isFinite() && radius >= 0)
    if (count == 0 || width == 0f) return emptyList()
    val rows = (count - 1) / columns + 1
    val last = (count - 1) % columns + 1
    val fullWidth = if (rows == 1) width * last / columns else width
    val height = rows * cellHeight
    val vertices = mutableListOf(JoinedGridPoint(0f, 0f), JoinedGridPoint(fullWidth, 0f))
    if (rows > 1 && last < columns) {
        val stepY = (rows - 1) * cellHeight
        val stepX = width * last / columns
        vertices += listOf(JoinedGridPoint(width, stepY), JoinedGridPoint(stepX, stepY),
            JoinedGridPoint(stepX, height))
    } else vertices += JoinedGridPoint(fullWidth, height)
    vertices += JoinedGridPoint(0f, height)
    fun mirror(p: JoinedGridPoint) = if (rtl) JoinedGridPoint(width - p.x, p.y) else p
    return vertices.indices.map { index ->
        val previous = vertices[(index + vertices.size - 1) % vertices.size]
        val vertex = vertices[index]
        val next = vertices[(index + 1) % vertices.size]
        val cross = (vertex.x - previous.x) * (next.y - vertex.y) -
            (vertex.y - previous.y) * (next.x - vertex.x)
        // Rectangular child selection fills meet exactly here; never extend a curve into the gap.
        if (cross < 0) {
            val point = mirror(vertex)
            return@map JoinedGridCorner(point, point, point, true)
        }
        val incoming = hypot(previous.x - vertex.x, previous.y - vertex.y)
        val outgoing = hypot(next.x - vertex.x, next.y - vertex.y)
        val r = minOf(radius, incoming / 2, outgoing / 2)
        val start = JoinedGridPoint(vertex.x + (previous.x - vertex.x) * r / incoming,
            vertex.y + (previous.y - vertex.y) * r / incoming)
        val end = JoinedGridPoint(vertex.x + (next.x - vertex.x) * r / outgoing,
            vertex.y + (next.y - vertex.y) * r / outgoing)
        JoinedGridCorner(mirror(start), mirror(vertex), mirror(end), false)
    }
}
