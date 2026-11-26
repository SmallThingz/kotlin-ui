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
 * Select one native startScroll at release on true. Use the configured settle duration
 * when positive, capped at Int.MAX_VALUE; otherwise use the public default-duration
 * startScroll overload. Do not read a hidden native duration or coast first and correct
 * afterward. This does not promise exact release-velocity continuity.
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
 * untruncated fling spline. This retains its duration/profile with bounded distance scaling;
 * it does not claim exact release-velocity continuity. Interruptions belong to the caller.
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
}
