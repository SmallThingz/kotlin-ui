package smallthingz.ui.animation

import kotlin.math.roundToInt

private const val PREDICTIVE_BACK_COMMIT_MAX_DURATION_MS = 220

fun predictiveBackCommitDurationMillis(progress: Float): Int {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    if (remaining <= 0f) return 0
    return (PREDICTIVE_BACK_COMMIT_MAX_DURATION_MS * remaining)
        .roundToInt()
        .coerceAtLeast(1)
}

