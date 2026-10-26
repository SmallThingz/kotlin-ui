package smallthingz.ui.util

/** Preserve start + delta / 2 rounding toward the start, without overflowing the Int delta. */
internal fun midpoint(start: Int, end: Int): Int =
    (start.toLong() + (end.toLong() - start.toLong()) / 2L).toInt()

/** Keep ordinary Float rounding and nonfinite inputs; widen only a finite-endpoint overflow. */
internal fun midpoint(start: Float, end: Float): Float {
    val difference = end - start
    return if (difference.isInfinite() && start.isFinite() && end.isFinite()) {
        ((start.toDouble() + end.toDouble()) / 2.0).toFloat()
    } else {
        start + difference / 2f
    }
}
