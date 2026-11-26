package smallthingz.ui.components

import android.view.View
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Appearance of the compact, selection and title toolbars in a native window. */
data class NativeToolbarStyle(
    val compactHeightDp: Float = 66f,
    val compactEdgeInsetDp: Float = 14f,
    val selectionEdgeInsetDp: Float = 16f,
    val selectionTitleGapDp: Float = 16f,
    val selectionTitleRole: NativeTypographyRole = NativeTypographyRole.TITLE_MEDIUM,
    val centeredHeightDp: Float = 64f,
    val centeredPaddingDp: Float = 4f,
    val centeredTitleStartDp: Float = 12f,
    val centeredTitleRole: NativeTypographyRole = NativeTypographyRole.TITLE_LARGE,
    val action: NativeToolbarActionStyle = NativeToolbarActionStyle(),
    val grainSeed: Int? = NoiseDrawable.SEED_TOP_BAR,
    val alertOutlineAlpha: Float = .55f,
    val hintAlpha: Float = .18f,
    val hintDurationMillis: Int = 600,
    val selectionBackFade: Float = .18f,
    val selectionBackShrink: Float = .015f,
    val leadingTitleGapDp: Float = 8f,
    val leadingTitleGlyphDp: Float = 24f,
    val leadingTitleRole: NativeTypographyRole = NativeTypographyRole.TITLE_LARGE,
) {
    fun compactHeightPx(density: Float): Int = (compactHeightDp * density).roundToInt()

    fun backgroundColor(palette: NativeColorScheme): Int = palette.background
    fun actionFill(palette: NativeColorScheme): Int = palette.field
    fun contentColor(palette: NativeColorScheme): Int = palette.ink

    fun actionTint(palette: NativeColorScheme, tone: NativeToolbarActionTone): Int = when (tone) {
        NativeToolbarActionTone.MUTED -> palette.muted
        NativeToolbarActionTone.NEUTRAL -> contentColor(palette)
        NativeToolbarActionTone.DESTRUCTIVE, NativeToolbarActionTone.ALERT -> palette.error
    }

    fun actionOutline(palette: NativeColorScheme, tone: NativeToolbarActionTone): Int =
        if (tone == NativeToolbarActionTone.ALERT) srgbColorWithAlpha(palette.error, alertOutlineAlpha) else palette.border

    fun applySelectionBackPreview(view: View, progress: Float, direction: Float) {
        val fraction = progress.coerceIn(0f, 1f)
        view.translationX = direction * view.width * fraction
        view.alpha = 1f - selectionBackFade * fraction
        val scale = 1f - selectionBackShrink * fraction
        view.scaleX = scale
        view.scaleY = scale
    }

    companion object {
        val Default = NativeToolbarStyle()
    }
}

enum class NativeToolbarActionTone { MUTED, NEUTRAL, DESTRUCTIVE, ALERT }

/** Visible icon, rounded action and independently admitted minimum touch target. */
data class NativeToolbarActionStyle(
    val sizeDp: Float = 46f,
    val glyphDp: Float = 23f,
    val radiusDp: Float = 15f,
    val disabledAlpha: Float = .38f,
) {
    val minimumTargetDp: Float get() = 48f
    internal val selectionSizeDp: Float get() = maxOf(sizeDp, minimumTargetDp)

    companion object {
        val Default = NativeToolbarActionStyle()
    }
}
