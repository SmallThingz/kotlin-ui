package smallthingz.ui.components

import android.graphics.Color
import kotlin.math.roundToInt
import smallthingz.ui.text.NativeTypographyRole

/** Appearance for a buffer tab. Selection and capture remain independent inputs. */
data class NativeBufferSegmentStyle(
    val cornerRadiusDp: Float = 16f,
    val horizontalPaddingDp: Int = 12,
    val verticalPaddingDp: Int = 11,
    val glyphSizeDp: Int = 18,
    val glyphGapDp: Int = 7,
    val labelRole: NativeTypographyRole = NativeTypographyRole.LABEL_LARGE,
    val selectedFillAlpha: Float = .74f,
    val selectedDisabledInkAlpha: Float = .62f,
    val idleDisabledInkAlpha: Float = .30f,
    val preferredWidthDp: Int = 112,
) {
    init {
        require(cornerRadiusDp.isFinite() && cornerRadiusDp >= 0f)
        require(horizontalPaddingDp >= 0 && verticalPaddingDp >= 0 && glyphSizeDp >= 0 && glyphGapDp >= 0 &&
            preferredWidthDp >= 0)
        require(selectedFillAlpha in 0f..1f && selectedDisabledInkAlpha in 0f..1f && idleDisabledInkAlpha in 0f..1f)
    }

    fun preferredWidthPx(density: Float): Int = (preferredWidthDp * density).roundToInt()

    fun fill(palette: NativeColorScheme, selected: Boolean, recording: Boolean, filled: Boolean): Int = when {
        selected && recording -> palette.primary
        filled && selected -> palette.tertiary
        selected -> srgbColorWithAlpha(palette.primaryContainer, selectedFillAlpha)
        else -> Color.TRANSPARENT
    }

    fun ink(palette: NativeColorScheme, selected: Boolean, recording: Boolean, enabled: Boolean, filled: Boolean): Int = when {
        selected && recording -> palette.onPrimary
        filled && selected -> palette.onTertiary
        selected && !enabled -> srgbColorWithAlpha(palette.onPrimaryContainer, selectedDisabledInkAlpha)
        selected -> palette.onPrimaryContainer
        enabled -> palette.muted
        else -> srgbColorWithAlpha(palette.muted, idleDisabledInkAlpha)
    }
}
