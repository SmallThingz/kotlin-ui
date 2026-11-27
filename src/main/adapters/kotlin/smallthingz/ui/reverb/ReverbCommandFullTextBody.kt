package smallthingz.ui.reverb

import android.content.Context
import android.graphics.text.LineBreaker
import android.text.Layout
import android.view.View
import android.widget.LinearLayout
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.components.nativeTextStyle
import smallthingz.ui.text.NativeTypographyRole

/** Already-redacted/localized display text. No command parsing, truncation, execution or I/O. */
data class ReverbCommandFullText(val folderLabel: String?, val command: String?, val technical: Boolean)

/** Content-only sheet body; admission, identity, opening and dismissal stay with the host. */
class ReverbCommandFullTextBody(context: Context, theme: ReverbTheme, content: ReverbCommandFullText) :
    LinearLayout(context) {
    init {
        orientation = VERTICAL
        content.folderLabel?.let { label ->
            addView(NativeText(context, theme.palette.pageControls,
                theme.appearance.typography.nativeTextStyle(NativeTypographyRole.BODY_SMALL),
                NativeTextRole.MUTED).apply { text = label })
        }
        content.command?.let { command ->
            addView(NativeText(context, theme.palette.pageControls,
                theme.appearance.typography.nativeTextStyle(NativeTypographyRole.BODY_MEDIUM)).apply {
                text = command
                textDirection = if (content.technical) TEXT_DIRECTION_LTR else TEXT_DIRECTION_LOCALE
                textAlignment = TEXT_ALIGNMENT_TEXT_START
                breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            })
        }
    }

    fun installIn(sheet: NativeActionSheet) {
        sheet.setContent(this, View(context), emptyList(), scrollable = true)
    }
}
