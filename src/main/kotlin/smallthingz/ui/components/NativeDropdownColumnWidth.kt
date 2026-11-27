package smallthingz.ui.components

import android.content.Context
import android.view.View
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.dropdownColors
import smallthingz.ui.text.NativeTypographyRole.BODY_LARGE
import smallthingz.ui.text.NativeTypographyRole.BODY_SMALL
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

/**
 * Query the real dropdown renderer for a column that fits its caption and longest option.
 * The detached probe never binds an action or touches a live popup anchor. Its measurement
 * includes the renderer's current insets, icon and gap, without a second chrome budget.
 */
internal fun dropdownColumnWidth(context: Context, theme: ControlTheme, caption: String,
    options: List<String>, minimumColumnWidthDp: Float): Int {
    fun paragraph(role: smallthingz.ui.text.NativeTypographyRole) = ParagraphView(context,
        theme.appearance.typography[role], theme.density, theme.fontResolver, theme.colors.ink)
    val title = paragraph(BODY_SMALL).apply { setText(caption) }
    val selected = paragraph(BODY_LARGE)
    var widestOption = ""
    var optionWidth = 0
    for (option in options) {
        selected.setText(option)
        val width = selected.maxIntrinsicTextWidth()
        if (width > optionWidth) { widestOption = option; optionWidth = width }
    }
    selected.setText(widestOption)
    val titleWidth = title.maxIntrinsicTextWidth()
    val probe = NativeDropdown<String>(context, title, selected,
        createDropdownIcon(context, theme.colors.muted), theme.dropdownColors(),
        inputRoot = { null }, onBinding = { _, _ -> }, open = { _, _ -> })
    try {
        fun fits(width: Int): Boolean {
            probe.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return title.measuredWidth >= titleWidth && selected.measuredWidth >= optionWidth
        }
        var low = maxOf(1, titleWidth, optionWidth,
            (minimumColumnWidthDp * context.resources.displayMetrics.density).roundToInt())
        if (fits(low)) return low
        // Android measure specs reserve two bits for mode. Use saturating arithmetic.
        val maximum = Int.MAX_VALUE ushr 1
        var high = low
        do {
            high = (high.toLong() * 2).coerceAtMost(maximum.toLong()).toInt()
        } while (!fits(high) && high < maximum)
        while (low < high) {
            val middle = low + (high - low) / 2
            if (fits(middle)) high = middle else low = middle + 1
        }
        return low
    } finally {
        probe.close()
    }
}
