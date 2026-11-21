package smallthingz.ui.components

import android.content.Context
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontStyle

/** Borderless raised input for compact page controls. The native editor owns input and focus. */
data class NativeCompactFieldRecipe(
    val style: NativeTextFieldStyle = compactFieldStyle(NativeTypography.Default),
) {
    internal constructor(typography: NativeTypography) : this(compactFieldStyle(typography))

    init { require(style.filled && !style.outlined) }

    fun createField(context: Context, palette: NativeColorScheme): NativeTextField =
        NativeTextField(context, palette.pageControls, style)

    /** Restyles a standalone editor in place, retaining its text, IME settings and drawable slots. */
    fun bind(field: NativeTextField, palette: NativeColorScheme) {
        field.bindAppearance(palette.pageControls, style)
    }
}

private fun compactFieldStyle(typography: NativeTypography): NativeTextFieldStyle {
    val role = NativeTypographyRole.BODY_MEDIUM
    val text = typography[role]
    val size = if (text.fontSize.isSp) text.fontSize.value else role.style.fontSizeSp
    val height = when {
        text.lineHeight.isSp -> text.lineHeight.value
        text.lineHeight.isEm -> text.lineHeight.value * size
        else -> role.style.lineHeightSp
    }
    return NativeTextFieldStyle(
        radiusDp = 16f,
        minHeightDp = 48f,
        horizontalPaddingDp = 12f,
        verticalPaddingDp = 8f,
        textStyle = NativeTextStyle(
            sizeSp = size,
            lineHeightSp = height,
            weight = text.fontWeight?.weight ?: role.style.fontWeight,
            letterSpacing = if (text.letterSpacing.isEm) text.letterSpacing.value else 0f,
            letterSpacingSp = if (text.letterSpacing.isSp) text.letterSpacing.value else null,
            includeFontPadding = text.platformStyle?.includeFontPadding ?: false,
            fontFamily = text.fontFamily?.familyName ?: "sans-serif",
            lineHeightStyle = text.lineHeightStyle,
            italic = text.fontStyle == FontStyle.Italic,
        ),
        outlined = false,
        filled = true,
        compoundDrawablePaddingDp = 8f,
    )
}
