package smallthingz.ui.components

/** Places the selected row at its anchor in the original, finite option order. */
internal class NativeAnchoredDropdownPlan(
    val count: Int,
    val selected: Int,
    private val spaceAbove: Int,
    private val spaceBelow: Int,
) {
    val above: Int
    val below: Int
    val selectedPosition: Int get() = selected
    val initialScrollRows: Int
    val visibleRows: Int get() = above + 1 + below

    init {
        require(count > 0 && selected in 0 until count)
        require(spaceAbove >= 0 && spaceBelow >= 0)

        above = minOf(selected, spaceAbove)
        below = minOf(count - 1 - selected, spaceBelow)
        initialScrollRows = selected - above
    }

    fun optionAt(position: Int): Int {
        require(position in 0 until count)
        return position
    }

    // Keep one neighboring option beyond the fully expanded viewport in each direction.
    // Fractional movement can expose part of a row at both edges.
    fun firstMounted(position: Float): Int = (position.toInt() - spaceAbove - 1).coerceAtLeast(0)

    fun lastMounted(position: Float): Int =
        (kotlin.math.ceil(position).toLong() + spaceBelow + 1).coerceAtMost(count - 1L).toInt()
}
