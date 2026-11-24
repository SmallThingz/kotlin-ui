package smallthingz.ui.components

/** Places the selected row at its anchor within one finite, possibly rotated option order. */
internal class NativeAnchoredDropdownPlan(
    val count: Int,
    val selected: Int,
    private val spaceAbove: Int,
    private val spaceBelow: Int,
) {
    val above: Int
    val below: Int
    val firstOptionIndex: Int
    val selectedPosition: Int
    val initialScrollRows: Int
    val visibleRows: Int get() = above + 1 + below

    init {
        require(count > 0 && selected in 0 until count)
        require(spaceAbove >= 0 && spaceBelow >= 0)

        val naturalAbove = minOf(selected, spaceAbove)
        val naturalBelow = minOf(count - 1 - selected, spaceBelow)
        val remaining = count - 1 - naturalAbove - naturalBelow
        val wrappedAbove = minOf(remaining, spaceAbove - naturalAbove)
        val wrappedBelow = minOf(remaining - wrappedAbove, spaceBelow - naturalBelow)
        above = naturalAbove + wrappedAbove
        below = naturalBelow + wrappedBelow

        // Move the seam only when the initial viewport would cross either end of the option order.
        selectedPosition = selected.coerceIn(above, count - 1 - below)
        firstOptionIndex = Math.floorMod(selected - selectedPosition, count)
        initialScrollRows = selectedPosition - above
    }

    fun optionAt(position: Int): Int {
        require(position in 0 until count)
        val unwrappedRows = count - firstOptionIndex
        return if (position < unwrappedRows) firstOptionIndex + position else position - unwrappedRows
    }

    // Keep one neighboring option beyond the fully expanded viewport in each direction.
    // Fractional movement can expose part of a row at both edges.
    fun firstMounted(position: Float): Int = (position.toInt() - spaceAbove - 1).coerceAtLeast(0)

    fun lastMounted(position: Float): Int =
        (kotlin.math.ceil(position).toLong() + spaceBelow + 1).coerceAtMost(count - 1L).toInt()
}
