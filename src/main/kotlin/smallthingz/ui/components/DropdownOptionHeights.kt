package smallthingz.ui.components

import android.content.Context
import android.os.LocaleList
import smallthingz.ui.text.NativeEmojiFonts
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

/** The anchor and every wheel slot must fit the same wrapped value at the anchor's actual width. */
internal class DropdownOptionHeights(
    context: Context,
    private var style: TextStyle,
    private var density: Density,
    private var resolver: FontFamily.Resolver,
) {
    private val probe = ParagraphView(context, style, density, resolver, 0).apply { wrapDropdownValue() }
    private var labels = emptyList<String>()
    private var measuredWidth = -1
    private var measuredDirection = -1
    private var measuredLocales: LocaleList? = null
    private var measuredEmojiReady = false
    private var measuredHeight = 0

    fun bindTypography(nextStyle: TextStyle, nextDensity: Density, nextResolver: FontFamily.Resolver) {
        if (style == nextStyle && density === nextDensity && resolver === nextResolver) return
        style = nextStyle
        density = nextDensity
        resolver = nextResolver
        probe.setTypography(style, density, resolver)
        measuredWidth = -1
    }

    fun bindLabels(values: List<String>): Boolean {
        if (values == labels) return false
        labels = values
        measuredWidth = -1
        return true
    }

    fun height(width: Int, direction: Int): Int {
        val locales = probe.resources.configuration.locales
        val emojiReady = NativeEmojiFonts.ready
        if (width == measuredWidth && direction == measuredDirection && locales == measuredLocales &&
            emojiReady == measuredEmojiReady
        ) return measuredHeight
        probe.layoutDirection = direction
        measuredHeight = labels.maxOfOrNull { label ->
            probe.setText(label)
            probe.intrinsicTextHeight(width)
        } ?: 0
        measuredWidth = width
        measuredDirection = direction
        measuredLocales = locales
        measuredEmojiReady = emojiReady
        return measuredHeight
    }
}

/** Limits very long folder paths while keeping the complete value available to accessibility. */
internal fun ParagraphView.wrapDropdownValue() = setTextLayout(maxLines = 4, overflow = TextOverflow.Ellipsis)
