package smallthingz.ui.components

import kotlin.math.ceil

/** Minimum label space used by NativeChip and the existing choice/tab column layout. */
enum class NativeChoiceLabelWidth {
    /** Existing compact policy: one representative glyph, icons, padding and a 48dp target. */
    GlyphMinimum,
    /** Reserve the widest explicit line at its current typography; do not shrink the font.
     * A label wider than the entire available row still follows the normal TextView break policy.
     * This does not override a host's forced single-row or constrained-height presentation.
     */
    WholeLabel,
}

/** Android measures the actual displayed text; this pure geometry preserves fractional advances. */
internal fun wholeLabelChoiceCellWidth(
    minimumTarget: Int,
    horizontalPadding: Int,
    labelWidth: Float,
    leadingIconWidth: Int,
    trailingIconWidth: Int,
    iconGap: Int,
    iconAbove: Boolean,
): Int {
    val text = ceil(labelWidth.toDouble()).toInt().coerceAtLeast(0).toLong()
    val leading = leadingIconWidth.toLong()
    val content = if (iconAbove) maxOf(leading, text) else
        leading + text + if (leading > 0 && text > 0) iconGap else 0
    val width = horizontalPadding.toLong() + content + trailingIconWidth +
        if (trailingIconWidth > 0 && content > 0) iconGap else 0
    return maxOf(minimumTarget.toLong(), width).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** Always retain one column; a narrower-than-one-label host keeps its normal text break policy. */
internal fun choiceColumnsThatFit(available: Int, gap: Int, minimumCell: Int, maximum: Int): Int =
    minOf(maximum, maxOf(1, ((available.toLong() + gap) /
        maxOf(1L, minimumCell.toLong() + gap)).toInt()))
