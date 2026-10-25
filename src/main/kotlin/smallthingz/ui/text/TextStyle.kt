package smallthingz.ui.text

import smallthingz.ui.graphics.Color
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.text.font.FontSynthesis
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.style.LineHeightStyle
import smallthingz.ui.text.style.TextDecoration
import smallthingz.ui.unit.LayoutDirection
import smallthingz.ui.unit.TextUnit
import smallthingz.ui.unit.sp

/** Immutable paragraph inputs. Android owns shaping, glyph fallback and rasterization. */
data class TextStyle(
    val color: Color = Color.Unspecified,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val fontWeight: FontWeight? = null,
    val fontStyle: FontStyle? = null,
    val fontSynthesis: FontSynthesis? = null,
    val fontFamily: FontFamily? = null,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val lineHeight: TextUnit = TextUnit.Unspecified,
    val platformStyle: PlatformTextStyle? = null,
    val lineHeightStyle: LineHeightStyle? = null,
    val textDecoration: TextDecoration? = null,
    val layoutDirection: LayoutDirection? = null,
) {
    /** Same released cache boundary: foreground and decoration redraw without reshaping text. */
    fun hasSameLayoutAffectingAttributes(other: TextStyle): Boolean = this === other ||
        (fontSize == other.fontSize && fontWeight == other.fontWeight &&
            fontStyle == other.fontStyle && fontSynthesis == other.fontSynthesis &&
            fontFamily == other.fontFamily && letterSpacing == other.letterSpacing &&
            lineHeight == other.lineHeight && platformStyle == other.platformStyle &&
            lineHeightStyle == other.lineHeightStyle && layoutDirection == other.layoutDirection)

    fun merge(other: TextStyle?): TextStyle {
        if (other == null || other == Default) return this
        return TextStyle(
            if (other.color != Color.Unspecified) other.color else color,
            if (other.fontSize != TextUnit.Unspecified) other.fontSize else fontSize,
            other.fontWeight ?: fontWeight,
            other.fontStyle ?: fontStyle,
            other.fontSynthesis ?: fontSynthesis,
            other.fontFamily ?: fontFamily,
            if (other.letterSpacing != TextUnit.Unspecified) other.letterSpacing else letterSpacing,
            if (other.lineHeight != TextUnit.Unspecified) other.lineHeight else lineHeight,
            other.platformStyle ?: platformStyle,
            other.lineHeightStyle ?: lineHeightStyle,
            other.textDecoration ?: textDecoration,
            other.layoutDirection ?: layoutDirection,
        )
    }

    fun merge(color: Color): TextStyle = if (color == Color.Unspecified) this else copy(color = color)

    companion object { val Default = TextStyle() }
}

data class PlatformTextStyle(val includeFontPadding: Boolean = false)

private val defaultPlatformTextStyle = PlatformTextStyle(includeFontPadding = false)

private fun TextStyle.hasResolvedDefaults(): Boolean =
    color != Color.Unspecified && fontSize != TextUnit.Unspecified &&
        fontWeight != null && fontStyle != null && fontSynthesis != null &&
        fontFamily != null && letterSpacing != TextUnit.Unspecified &&
        platformStyle != null && lineHeightStyle != null &&
        textDecoration != null && layoutDirection != null

/** Resolve once at a configuration boundary, retaining explicit role values. */
fun resolveDefaults(style: TextStyle, layoutDirection: LayoutDirection): TextStyle {
    // Paragraph callers may already have resolved the immutable style at their layout boundary.
    if (style.hasResolvedDefaults()) return style
    return style.copy(
        color = if (style.color == Color.Unspecified) Color.Black else style.color,
        fontSize = if (style.fontSize == TextUnit.Unspecified) 14.sp else style.fontSize,
        fontWeight = style.fontWeight ?: FontWeight.Normal,
        fontStyle = style.fontStyle ?: FontStyle.Normal,
        fontSynthesis = style.fontSynthesis ?: FontSynthesis.All,
        fontFamily = style.fontFamily ?: FontFamily.Default,
        letterSpacing = if (style.letterSpacing == TextUnit.Unspecified) 0.sp else style.letterSpacing,
        platformStyle = style.platformStyle ?: defaultPlatformTextStyle,
        lineHeightStyle = style.lineHeightStyle ?: LineHeightStyle.Default,
        textDecoration = style.textDecoration ?: TextDecoration.None,
        layoutDirection = style.layoutDirection ?: layoutDirection,
    )
}

/** Plain text only: application spans and inline content have separate native owners. */
data class AnnotatedString(val text: String) : CharSequence {
    override val length: Int get() = text.length
    override fun get(index: Int): Char = text[index]
    override fun subSequence(startIndex: Int, endIndex: Int): AnnotatedString {
        require(startIndex <= endIndex) { "start ($startIndex) should be less than or equal to end ($endIndex)" }
        if (startIndex == 0 && endIndex == length) return this
        return AnnotatedString(text.substring(startIndex, endIndex))
    }
    fun subSequence(range: TextRange) = subSequence(range.min, range.max)
    override fun toString(): String = text
}

data class TextRange(val start: Int, val end: Int = start) {
    init { require(start >= 0 && end >= 0) }
    val min: Int get() = minOf(start, end)
    val max: Int get() = maxOf(start, end)
    val length: Int get() = max - min
    val collapsed: Boolean get() = start == end
    val reversed: Boolean get() = start > end
    operator fun contains(offset: Int): Boolean = offset >= min && offset < max
    operator fun contains(other: TextRange): Boolean = min <= other.min && max >= other.max
    fun intersects(other: TextRange): Boolean = min < other.max && other.min < max
    override fun toString(): String = "TextRange(" + start + ", " + end + ")"
    companion object { val Zero = TextRange(0) }
}

fun TextRange.coerceIn(minimumValue: Int, maximumValue: Int): TextRange =
    TextRange(start.coerceIn(minimumValue, maximumValue), end.coerceIn(minimumValue, maximumValue))
