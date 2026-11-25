package smallthingz.ui.components

/** Rounded outer edges, with optional solid square caps at a break inside the group. */
data class NativeGroupEdges(
    val first: Boolean,
    val last: Boolean,
    val squareTop: Boolean = false,
    val squareBottom: Boolean = false,
)

/** Joined rows retain independently constructed fill radii and raw-dp outlines. */
internal data class JoinedGroupGeometry(
    val topRadius: Float,
    val bottomRadius: Float,
    val strokeWidth: Float,
    val strokeInset: Float,
    val strokeRadius: Float,
    val cornersFit: Boolean,
)

internal fun joinedGroupGeometry(
    width: Float,
    height: Float,
    density: Float,
    edges: NativeGroupEdges,
    outlineWidthDp: Float = 1f,
): JoinedGroupGeometry {
    val requestedTop = if (edges.first) 16f * density else 0f
    val requestedBottom = if (edges.last) 16f * density else 0f
    val total = requestedTop + requestedBottom
    val minimum = minOf(width, height).coerceAtLeast(0f)
    val scale = if (total > minimum && total > 0f) minimum / total else 1f
    val top = requestedTop * scale
    val bottom = requestedBottom * scale
    // Bound enlarged outlines while preserving the raw 1 dp stroke on narrow rows.
    val stroke = minOf(outlineWidthDp * density, maxOf(density, minimum / 2f))
    val inset = stroke / 2f
    // Preserve the released Float subtraction order, including constrained dimensions.
    val strokeRadius = (16f * density - inset)
        .coerceAtMost(((width - inset) - inset) / 2f)
        .coerceAtMost(((height - inset) - inset) / 2f)
        .coerceAtLeast(0f)
    return JoinedGroupGeometry(top, bottom, stroke, inset, strokeRadius,
        top + top <= width && bottom + bottom <= width && top + bottom <= height)
}
