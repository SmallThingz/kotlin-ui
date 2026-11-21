package smallthingz.ui.components

import android.content.Context
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole

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

private fun compactFieldStyle(typography: NativeTypography) = NativeTextFieldStyle(
    radiusDp = 16f,
    minHeightDp = 48f,
    horizontalPaddingDp = 12f,
    verticalPaddingDp = 8f,
    textStyle = typography.nativeTextStyle(NativeTypographyRole.BODY_MEDIUM),
    outlined = false,
    filled = true,
    compoundDrawablePaddingDp = 8f,
)
