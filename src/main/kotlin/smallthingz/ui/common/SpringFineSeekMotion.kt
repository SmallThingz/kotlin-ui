package smallthingz.ui.common

import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/** A gesture's admission belongs to the editing host. */
interface UiInputGesture {
    fun perform(action: () -> Unit): Boolean
    fun finish(action: () -> Unit): Boolean
    fun cancel()
}

private const val RANGE_FINE_TUNE_HORIZONTAL_SEEK_GAIN = 1f / 0.62f
private const val RANGE_FINE_TUNE_STATE_UPDATE_NANOS = 33_333_333L
const val RANGE_FINE_TUNE_PUCK_RADIUS_DP = 24f
const val RANGE_FINE_TUNE_PUCK_HIT_RADIUS_DP = 32f

fun rangeFineTunePuckContains(
    pointerX: Float,
    pointerY: Float,
    puckX: Float,
    puckY: Float,
    radius: Float,
): Boolean {
    val dx = pointerX - puckX
    val dy = pointerY - puckY
    val safeRadius = radius.coerceAtLeast(0f)
    return dx * dx + dy * dy <= safeRadius * safeRadius
}

fun rangeFineTuneMovementExceedsSlop(
    deltaX: Float,
    deltaY: Float,
    touchSlop: Float,
): Boolean {
    val slop = touchSlop.coerceAtLeast(0f)
    return deltaX * deltaX + deltaY * deltaY > slop * slop
}

/** The existing clickable occupies a square, independently of the circular drag-start test. */
fun fineSeekPointerTapInsideBounds(
    pointerX: Float,
    pointerY: Float,
    puckX: Float,
    puckY: Float,
    halfSize: Float,
): Boolean = abs(pointerX - puckX) <= halfSize && abs(pointerY - puckY) <= halfSize

private class FineAdjustCommitAccumulator {
    var deltaSeconds = 0f
    var elapsedNanos = 0L

    fun reset() {
        deltaSeconds = 0f
        elapsedNanos = 0L
    }

    fun add(deltaSeconds: Float, elapsedNanos: Long) {
        this.deltaSeconds += deltaSeconds
        this.elapsedNanos += elapsedNanos
    }

    fun takeDelta(): Float {
        val delta = deltaSeconds
        reset()
        return delta
    }
}

/** Pointer, frame and final-delta callbacks share one guard and one private accumulator. */
class FineSeekInputSession(
    createInputGesture: ((Boolean) -> Unit) -> UiInputGesture,
    onCancel: (FineSeekInputSession, Boolean) -> Unit,
) {
    private val commits = FineAdjustCommitAccumulator()
    private var pointerTapAllowed = true
    private val input = createInputGesture { stillCurrent ->
        commits.reset()
        onCancel(this, stillCurrent)
    }

    fun perform(action: () -> Unit): Boolean = input.perform(action)

    fun frame(
        deltaSeconds: Float,
        elapsedNanos: Long,
        onCommit: (Float) -> Unit,
        onShuttle: (Float) -> Unit,
    ): Boolean = input.perform {
        commits.add(deltaSeconds, elapsedNanos)
        if (commits.elapsedNanos >= RANGE_FINE_TUNE_STATE_UPDATE_NANOS) {
            val delta = commits.takeDelta()
            if (delta != 0f) onCommit(delta)
        }
        onShuttle(commits.deltaSeconds)
    }

    fun finishFineAdjust(onCommit: (Float) -> Unit, onEnd: () -> Unit): Boolean = input.finish {
        val delta = commits.takeDelta()
        if (delta != 0f) onCommit(delta)
        onEnd()
    }

    fun finish(action: () -> Unit): Boolean = input.finish(action)

    fun observePointerTap(insideBounds: Boolean, consumed: Boolean) {
        if (!insideBounds || consumed) pointerTapAllowed = false
    }

    fun finishPointerTap(action: () -> Unit): Boolean = input.finish {
        if (pointerTapAllowed) action()
    }

    fun cancel() = input.cancel()
}

fun rangeFineTuneHorizontalTouchPull(
    pointerX: Float,
    width: Float,
    horizontalTravel: Float,
): Float {
    val centerX = width * 0.5f
    return ((pointerX - centerX) / horizontalTravel.coerceAtLeast(1f)).coerceIn(-1f, 1f)
}

fun rangeFineTuneSeekPull(horizontalVisualPull: Float): Float =
    (horizontalVisualPull * RANGE_FINE_TUNE_HORIZONTAL_SEEK_GAIN).coerceIn(-1f, 1f)

fun rangeFineTuneVerticalDragPull(
    startRawVertical: Float,
    dragDeltaY: Float,
    verticalTravel: Float,
): Float = startRawVertical + dragDeltaY / verticalTravel.coerceAtLeast(1f)

fun rangeFineTuneConstrainedY(
    rawVerticalPull: Float,
    horizontalPull: Float,
): Float {
    val x = abs(horizontalPull.coerceIn(-1f, 1f))
    val edgeStiffness = cosh(1.65f * x)
    val localRadius = 0.72f / edgeStiffness.pow(0.28f)
    val inputScale = localRadius * 1.18f * edgeStiffness.pow(0.72f)
    return localRadius * tanh(rawVerticalPull / inputScale)
}

fun rangeFineTuneTimelineRate(
    horizontalPull: Float,
    verticalPull: Float = 0f,
): Float {
    val pull = horizontalPull.coerceIn(-1f, 1f)
    val magnitude = abs(pull)
    if (magnitude <= 0.002f) return 0f
    val normalized = ((magnitude - 0.002f) / 0.998f).coerceIn(0f, 1f)
    // Fraction of the whole timeline traversed per second. This deliberately
    // contains no absolute seconds, so gesture feel scales with clip length.
    val horizontalRate =
        0.00002f +
            0.00040f * normalized +
            0.0040f * normalized.pow(3) +
            0.055f * normalized.pow(7)
    val y = verticalPull.coerceIn(-1f, 1f)
    val speedScale = if (y <= 0f) {
        1f + 5f * (-y).pow(1.45f)
    } else {
        0.018f + 0.982f * (1f - y).pow(3.1f)
    }
    return sign(pull) * horizontalRate * speedScale
}

fun rangeFineTuneDeltaSeconds(
    horizontalPull: Float,
    verticalPull: Float,
    durationSeconds: Float,
    dtSeconds: Float,
): Float = rangeFineTuneTimelineRate(horizontalPull, verticalPull) *
    durationSeconds.coerceAtLeast(0f) *
    dtSeconds.coerceAtLeast(0f)

/** The signed source seconds advanced per elapsed second at this pull, before frame caps. */
fun rangeFineTuneShuttleRate(
    horizontalPull: Float,
    verticalPull: Float,
    durationSeconds: Float,
): Float {
    val duration = durationSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    if (duration <= 0f) return 0f
    return rangeFineTuneTimelineRate(
        horizontalPull = rangeFineTuneSeekPull(horizontalPull),
        verticalPull = verticalPull,
    ) * duration
}
