package smallthingz.ui.reverb

enum class InlineFineSeekTarget {
    PLAYHEAD,
    TRIM_START,
    TRIM_END,
}

fun inlineTrimBoundaryLimits(durationMillis: Int, target: InlineFineSeekTarget): IntRange? {
    if (durationMillis <= 0) return null
    val minimumRange = minOf(50, durationMillis)
    return when (target) {
        InlineFineSeekTarget.TRIM_START -> 0..(durationMillis - minimumRange)
        InlineFineSeekTarget.TRIM_END -> minimumRange..durationMillis
        InlineFineSeekTarget.PLAYHEAD -> null
    }
}

/** Advertised Float endpoints retain the exact source bounds even on multi-day recordings. */
fun inlineTrimBoundaryProgressMillis(
    seconds: Float,
    durationMillis: Int,
    target: InlineFineSeekTarget,
): Double? {
    if (!seconds.isFinite()) return null
    val limits = inlineTrimBoundaryLimits(durationMillis, target) ?: return null
    return when {
        seconds <= limits.first.toFloat() / 1000f -> limits.first.toDouble()
        seconds >= limits.last.toFloat() / 1000f -> limits.last.toDouble()
        else -> seconds.toDouble() * 1000.0
    }
}
