package smallthingz.ui.components

import kotlin.math.roundToLong

/** A min-anchored grid. Long differences also cover Int.MIN_VALUE..Int.MAX_VALUE. */
internal class RangeSliderGrid(val min: Int, max: Int, val step: Int, minGap: Int) {
    init {
        require(min <= max && step > 0 && minGap >= 0)
    }

    val intervals = (max.toLong() - min) / step
    val last = (min + intervals * step).toInt()
    val gap = (minGap + step.toLong() - 1) / step * step

    init {
        require(gap <= last.toLong() - min) { "Minimum gap exceeds the reachable range" }
    }

    fun snap(value: Double): Int {
        val index = ((value.coerceIn(min.toDouble(), last.toDouble()) - min) / step).roundToLong()
        return (min + index * step).toInt()
    }

    fun bind(lower: Int, upper: Int): IntRange {
        val start = snap(lower.toDouble()).coerceAtMost((last - gap).toInt())
        return start..snap(upper.toDouble()).coerceAtLeast((start + gap).toInt())
    }
}
