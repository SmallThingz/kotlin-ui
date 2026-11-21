package smallthingz.ui.components

import android.view.View
import android.view.View.MeasureSpec
import kotlin.math.roundToInt

/** Places a title and retained selector inline when both fit, or in the same order on two rows. */
class NativeLabeledSegmentedLayout(private val title: View, private val selector: NativeSegmentedControl) {
    var measuredHeight: Int = 0
        private set
    private var stacked = false
    private var stackedGap = 0

    internal fun preferredWidth(inlineGap: Int): Int {
        val unconstrained = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        title.measure(unconstrained, unconstrained)
        selector.measure(unconstrained, unconstrained)
        return title.measuredWidth + inlineGap + selector.measuredWidth
    }

    /** [preferredInlineWidth] preserves a host's compact selector width while its choices fit. */
    fun measure(availableWidth: Int, inlineGap: Int, stackedGap: Int, preferredInlineWidth: Int? = null) {
        val unconstrained = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        title.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST), unconstrained)
        val remaining = (availableWidth - title.measuredWidth - inlineGap).coerceAtLeast(0)
        val firstWidth = minOf(remaining, preferredInlineWidth ?: remaining)
        selector.measure(exact(firstWidth), unconstrained)
        val preferredWidth = selector.preferredWidthPixels
        stacked = preferredWidth > remaining && title.measuredWidth > 0
        val finalWidth = if (stacked) availableWidth else maxOf(firstWidth, minOf(remaining, preferredWidth))
        if (finalWidth != firstWidth) selector.measure(exact(finalWidth), unconstrained)
        this.stackedGap = stackedGap
        measuredHeight = if (stacked) title.measuredHeight + stackedGap + selector.measuredHeight
            else maxOf(title.measuredHeight, selector.measuredHeight)
    }

    fun layout(width: Int, inset: Int, top: Int, rtl: Boolean) {
        val titleY = if (stacked) top else top + ((measuredHeight - title.measuredHeight) / 2f).roundToInt()
        val selectorY = if (stacked) top + title.measuredHeight + stackedGap
            else top + ((measuredHeight - selector.measuredHeight) / 2f).roundToInt()
        place(title, width, inset, titleY, rtl)
        place(selector, width, width - inset - selector.measuredWidth, selectorY, rtl)
    }

    private fun exact(width: Int) = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)

    private fun place(child: View, width: Int, logicalX: Int, top: Int, rtl: Boolean) {
        val left = if (rtl) width - logicalX - child.measuredWidth else logicalX
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }
}
