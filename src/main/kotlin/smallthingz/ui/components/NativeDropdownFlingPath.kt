package smallthingz.ui.components

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Choose the legal row once, from the full native fling's displacement in pixels. */
internal fun nativeDropdownFlingTarget(
    startPosition: Float,
    nativeDistancePx: Int,
    rowHeight: Int,
    lastPosition: Int,
    velocity: Float,
): Int? {
    if (!startPosition.isFinite() || !velocity.isFinite() || rowHeight <= 0 || lastPosition < 0) return null
    val start = startPosition.toDouble().coerceIn(0.0, lastPosition.toDouble())
    val predicted = (start + nativeDistancePx.toDouble() / rowHeight).coerceIn(0.0, lastPosition.toDouble())
    val nearest = floor(predicted + .5).toInt().coerceIn(0, lastPosition)
    val target = when {
        velocity > 0f -> maxOf(nearest, ceil(start).toInt())
        velocity < 0f -> minOf(nearest, floor(start).toInt())
        else -> nearest
    }.coerceIn(0, lastPosition)
    // The displayed position is Float; never promise an integer row it cannot represent.
    return target.takeIf { it.toFloat().toDouble() == it.toDouble() }
}

/**
 * A tiny native coast must not become a row-wide jump at many times the release speed.
 * Select one shared-spline settle clock at release on true, using configured positive
 * settle duration or the dropdown default when zero. No coast followed by correction,
 * and no promise of exact release-velocity continuity for this forced extension.
 */
internal fun nativeDropdownFlingNeedsScroll(
    startPosition: Float,
    targetRow: Int,
    nativeDistancePx: Int,
    rowHeight: Int,
): Boolean {
    require(startPosition.isFinite() && startPosition >= 0f && targetRow >= 0 && rowHeight > 0)
    val travel = abs(targetRow.toDouble() - startPosition.toDouble()) * rowHeight
    return travel > 2.0 * abs(nativeDistancePx.toDouble())
}

/**
 * One immutable mapping per release, shared by every native animation sample.
 * The caller supplies a monotone, non-overscrolling native trajectory, preferably the full
 * untruncated fling spline. The caller owns its clipping- and scale-aware clock;
 * the fixed endpoint never changes. Interruptions belong to the caller.
 */
internal class NativeDropdownFlingPath(
    private val startPosition: Float,
    val targetRow: Int,
    private val nativeStartY: Int,
    nativeFinalY: Int,
) {
    private val nativeDistance = nativeFinalY.toDouble() - nativeStartY.toDouble()
    private val rowDistance = targetRow.toDouble() - startPosition.toDouble()

    init {
        require(startPosition.isFinite() && startPosition >= 0f && targetRow >= 0)
        require(targetRow.toFloat().toDouble() == targetRow.toDouble())
        require(nativeDistance != 0.0 || rowDistance == 0.0)
    }

    /** Sample finalY at native completion; that yields the exact row without another animation. */
    fun positionAt(nativeY: Int): Float {
        if (rowDistance == 0.0) return startPosition
        val progress = ((nativeY.toDouble() - nativeStartY) / nativeDistance).coerceIn(0.0, 1.0)
        if (progress == 0.0) return startPosition
        if (progress == 1.0) return targetRow.toFloat()
        return (startPosition.toDouble() + rowDistance * progress).toFloat()
    }

    fun positionAtFraction(fraction: Float): Float {
        require(fraction.isFinite())
        if (rowDistance == 0.0 || fraction <= 0f) return startPosition
        if (fraction >= 1f) return targetRow.toFloat()
        return (startPosition.toDouble() + rowDistance * fraction).toFloat()
    }
}
