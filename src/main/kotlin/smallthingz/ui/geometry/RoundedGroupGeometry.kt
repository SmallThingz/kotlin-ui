package smallthingz.ui.geometry

import kotlin.math.min
import kotlin.math.ceil

/** Pure geometry kept independent of Android objects for density/RTL/corner regression coverage. */
data class RoundedGroupGeometry(
    val width: Float,
    val height: Float,
    val radius: Float,
    val strokeWidth: Float,
    val strokeRadius: Float,
    val fillBorder: Boolean,
) {
    fun contains(x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f || x >= width || y >= height || !x.isFinite() || !y.isFinite()) return false
        val dx = x - x.coerceIn(radius, width - radius)
        val dy = y - y.coerceIn(radius, height - radius)
        return dx * dx + dy * dy <= radius * radius
    }

    fun acceptsPointer(x: Float, y: Float, touch: Boolean, minimumTarget: Float): Boolean {
        if (contains(x, y)) return true
        if (!touch || width <= 0f || height <= 0f || !x.isFinite() || !y.isFinite() ||
            (width >= minimumTarget && height >= minimumTarget)
        ) return false
        // NodeCoordinator permits touch-only near hits for undersized clipped layouts. Native
        // parents still need a TouchDelegate if a future caller needs delivery outside View bounds;
        // Settings groups already exceed the minimum in both dimensions.
        val horizontal = ((minimumTarget - width) / 2f).coerceAtLeast(0f)
        val vertical = ((minimumTarget - height) / 2f).coerceAtLeast(0f)
        return x >= -horizontal && x <= width + horizontal &&
            y >= -vertical && y <= height + vertical
    }
}

fun roundedGroupGeometry(
    width: Float,
    height: Float,
    density: Float,
    cornerRadiusDp: Float = 16f,
): RoundedGroupGeometry {
    val minimum = min(width, height).coerceAtLeast(0f)
    val radius = min(cornerRadiusDp * density, minimum / 2f)
    // Compose BorderModifierNode rounds a non-hairline dp stroke up to device pixels.
    val stroke = min(ceil(density), ceil(minimum / 2f))
    return RoundedGroupGeometry(
        width = width,
        height = height,
        radius = radius,
        strokeWidth = stroke,
        strokeRadius = (radius - stroke / 2f).coerceAtLeast(0f),
        fillBorder = stroke * 2f > minimum,
    )
}
