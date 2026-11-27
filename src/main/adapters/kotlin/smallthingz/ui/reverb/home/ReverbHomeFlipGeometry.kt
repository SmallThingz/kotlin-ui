package smallthingz.ui.reverb.home

fun bufferSwipeProgress(
    sourceIsOneShot: Boolean,
    horizontalDragPx: Float,
    viewportWidthPx: Float,
): Float {
    if (viewportWidthPx <= 0f) return 0f
    val forwardDistance = if (sourceIsOneShot) -horizontalDragPx else horizontalDragPx
    return (forwardDistance / viewportWidthPx).coerceIn(0f, 1f)
}

private const val BUFFER_SWIPE_COMMIT_PROGRESS = 0.16f
private const val BUFFER_FLIP_MIDPOINT_SCALE = 0.94f

fun shouldCommitBufferSwipe(progress: Float): Boolean =
    progress.coerceIn(0f, 1f) >= BUFFER_SWIPE_COMMIT_PROGRESS

fun <S> bufferTransitionDisplayedSlot(
    source: S,
    target: S,
    progress: Float,
): S = if (progress.coerceIn(0f, 1f) >= 0.5f) target else source

fun bufferTransitionFlipDegrees(
    sameBuffer: Boolean,
    targetIsOneShot: Boolean,
    progress: Float,
): Float {
    if (sameBuffer) return 0f
    val p = progress.coerceIn(0f, 1f)
    // Opposite navigation directions use opposite rotations. The outgoing face reaches
    // +/-90 degrees and the incoming face appears from the complementary angle.
    val direction = if (targetIsOneShot) 1f else -1f
    return if (p < 0.5f) {
        direction * p * 180f
    } else {
        // The rendered face switches to the target at the midpoint, so use the
        // complementary edge-on angle from that exact frame onward.
        direction * (p - 1f) * 180f
    }
}

fun bufferTransitionPivotFractionX(): Float = 0.5f

fun bufferTransitionDepthScale(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    val distanceFromMidpoint = kotlin.math.abs(p * 2f - 1f)
    return BUFFER_FLIP_MIDPOINT_SCALE + (1f - BUFFER_FLIP_MIDPOINT_SCALE) * distanceFromMidpoint
}
