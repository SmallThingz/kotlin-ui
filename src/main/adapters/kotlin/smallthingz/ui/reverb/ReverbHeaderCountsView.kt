package smallthingz.ui.reverb

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.NativeTopBarInsets
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.style.TextOverflow
import kotlin.math.roundToInt

internal class ReverbHeaderCountsView(context: Context) : LinearLayout(context) {
    private val style = ReverbDesign.appearance.toolbars
    private var chrome: NativeToolbarBackground? = null

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(theme: ReverbTheme, insets: NativeTopBarInsets, counts: List<ReverbHeaderCount>) {
        visibility = if (counts.isEmpty()) GONE else VISIBLE
        if (counts.isEmpty()) return
        val fill = style.backgroundColor(theme.palette)
        val background = chrome ?: NativeToolbarBackground(this, style, fill).also { chrome = it }
        background.bind(style, fill)
        val edge = (style.compactEdgeInsetDp * theme.density.density).roundToInt()
        val gap = (style.centeredPaddingDp * theme.density.density).roundToInt()
        setPadding(insets.left + edge, 0, insets.right + edge, gap)
        while (childCount > counts.size) removeViewAt(childCount - 1)
        counts.forEachIndexed { index, item ->
            val row = getChildAt(index) as? CountRow ?: CountRow(context, theme).also {
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            row.bind(theme, item, gap)
        }
    }

    private class CountRow(context: Context, theme: ReverbTheme) : LinearLayout(context) {
        private val role = NativeTypographyRole.BODY_SMALL
        private val label = theme.paragraph(context, "", role).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextLayout(maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        private val value = theme.paragraph(context, "", role).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextLayout(maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        init {
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        fun bind(theme: ReverbTheme, count: ReverbHeaderCount, gap: Int) {
            for (paragraph in listOf(label, value)) {
                paragraph.setTypography(ReverbDesign.appearance.typography[role], theme.density, theme.fontResolver)
                paragraph.animateTextColor(theme.palette.muted)
            }
            val params = value.layoutParams as LayoutParams
            if (params.marginStart != gap) {
                params.marginStart = gap
                value.layoutParams = params
            }
            label.setText(count.label)
            value.setText(count.compactValue)
            contentDescription = count.spokenDescription
        }
    }
}
