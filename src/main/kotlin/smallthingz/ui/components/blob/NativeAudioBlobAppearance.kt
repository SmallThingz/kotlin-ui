package smallthingz.ui.components.blob

import androidx.annotation.ColorInt
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.unit.sp

/** Resolves the audio-reactive material for the current window. */
fun interface NativeAudioBlobAppearance {
    fun resolve(colors: NativeColorScheme, typography: NativeTypography): NativeAudioBlobStyle

    fun resolve(colors: NativeColorScheme): NativeAudioBlobStyle = resolve(colors, NativeTypography.Default)

    companion object {
        val Default = NativeAudioBlobAppearance { colors, typography ->
            NativeAudioBlobStyle(
                primary = colors.primary,
                tertiary = colors.tertiary,
                resting = colors.surfaceContainerHighest,
                filledResting = colors.primaryContainer,
                readoutBackground = colors.surface,
                idleInk = colors.onSurfaceVariant,
                filledInk = colors.onPrimaryContainer,
                focusInk = colors.onSurface,
                primaryTextStyle = typography[NativeTypographyRole.TITLE_LARGE].copy(
                    fontSize = 18.sp, lineHeight = 22.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                ),
                secondaryTextStyle = typography[NativeTypographyRole.LABEL_MEDIUM].copy(
                    fontSize = 14.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

/** Resolved material, readout text and focus appearance for one window. */
data class NativeAudioBlobStyle(
    @ColorInt val primary: Int,
    @ColorInt val tertiary: Int,
    @ColorInt val resting: Int,
    @ColorInt val filledResting: Int,
    @ColorInt val readoutBackground: Int,
    @ColorInt val idleInk: Int,
    @ColorInt val filledInk: Int,
    @ColorInt val focusInk: Int,
    val primaryTextStyle: TextStyle,
    val secondaryTextStyle: TextStyle,
    val pressedScale: Float = .96f,
    val pressDurationMillis: Int = 100,
)
