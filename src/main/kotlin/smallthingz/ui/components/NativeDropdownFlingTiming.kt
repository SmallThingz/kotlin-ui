package smallthingz.ui.components

import smallthingz.ui.animation.AndroidFlingSpline
import kotlin.math.abs

/** Compress the complete spline's clock in proportion to the distance retained at the edge. */
internal fun nativeDropdownFlingDurationNanos(
    startPosition: Float,
    targetRow: Int,
    rowHeight: Int,
    predictedDistancePx: Float,
    nativeDurationNanos: Long,
    fallbackDurationNanos: Long,
    needsSettle: Boolean,
): Long {
    require(startPosition.isFinite() && targetRow >= 0 && rowHeight > 0)
    require(predictedDistancePx.isFinite() && nativeDurationNanos >= 0 && fallbackDurationNanos > 0)
    val travel = abs(targetRow.toDouble() - startPosition.toDouble()) * rowHeight
    if (travel == 0.0) return 0L
    if (needsSettle || predictedDistancePx == 0f) return fallbackDurationNanos
    val ratio = (travel / abs(predictedDistancePx.toDouble())).coerceAtMost(1.0)
    return (nativeDurationNanos * ratio).toLong().coerceAtLeast(1L)
}

/**
 * One release-owned clock sampling the existing Android spline, without wall-clock OverScroller.
 * Each accepted interval advances source time by dt/current scale. Scale changes never rewrite
 * accumulated time; zero reaches the exact endpoint once. ValueAnimator motion is not passed here.
 */
internal class NativeDropdownFlingTiming(val durationNanos: Long, startNanos: Long) {
    private var lastNanos = startNanos
    private var elapsedNanos = 0.0
    private var fraction = if (durationNanos == 0L) 1f else 0f
    var isFinished = durationNanos == 0L
        private set

    init { require(durationNanos >= 0L) }

    fun sample(nowNanos: Long, durationScale: Float): Float {
        if (isFinished) return fraction
        if (durationScale == 0f) {
            fraction = 1f
            isFinished = true
            return fraction
        }
        val scale = if (durationScale.isFinite() && durationScale > 0f) durationScale.toDouble() else 1.0
        val now = maxOf(nowNanos, lastNanos)
        elapsedNanos += (now.toDouble() - lastNanos.toDouble()) / scale
        lastNanos = now
        val progress = (elapsedNanos / durationNanos).coerceIn(0.0, 1.0).toFloat()
        // The baked spline's first coefficient has a tiny epsilon: preserve the exact start.
        fraction = when {
            progress <= 0f -> 0f
            progress >= 1f -> 1f.also { isFinished = true }
            else -> AndroidFlingSpline.distanceCoefficient(progress)
        }
        return fraction
    }

    /** Retire this owner without synthesizing the endpoint on an interruption. */
    fun cancel() { isFinished = true }
}
