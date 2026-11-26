package smallthingz.ui.components

import android.content.Context
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Original four-column phone sizing shared by both application pickers. */
internal object NativeApplicationGridSizing {
    const val cellMinWidthDp = 80f
    // Measured at mdpi/default font scale: Arabic/mixed labels need 44dp for two
    // fallback-font lines. Budget 3dp top + 36dp icon + 2dp gap + 44dp text +
    // 3dp bottom = 88dp; retain fallback line spacing and the full icon.
    const val cellMinHeightDp = 88f
    const val maxColumns = 4

    fun columns(widthDp: Float, fontScale: Float): Int {
        val scale = fontScale.coerceAtLeast(1f)
        val fitting = (widthDp / (cellMinWidthDp * scale)).toInt()
        return fitting.coerceIn(1, maxColumns)
    }

    fun heightDp(fontScale: Float): Float = cellMinHeightDp * fontScale.coerceAtLeast(1f)
}

/** Shared geometry and checked-control styling for installed-application grids. */
class NativeApplicationGridRecipe internal constructor(appearance: NativeUiAppearance) {
    val iconSizeDp = 36f
    val iconLabelGapDp = 2f
    val contentInsetDp = 3f
    val horizontalInsetDp = 4f
    // A minimum-width 80dp cell leaves 22dp beside its centered 36dp icon:
    // an 18dp badge and 4dp inset. Place at the physical top-right in both LTR
    // and RTL (not logical END); the badge is decorative, not the checked target.
    val markerSizeDp = 18f
    val markerInsetDp = 4f
    val labelMaxLines = 2
    val unavailableIconAlpha = .55f
    val cellMinWidthDp = NativeApplicationGridSizing.cellMinWidthDp
    val cellMinHeightDp = NativeApplicationGridSizing.cellMinHeightDp
    val maxColumns = NativeApplicationGridSizing.maxColumns
    val cellMarginDp = 0f
    val cornerRadiusDp = appearance.groupSurfaces.settingsChoices.cornerRadiusDp
    val labelTopInsetDp get() = contentInsetDp + iconSizeDp + iconLabelGapDp
    val iconToLabelDp get() = iconSizeDp + iconLabelGapDp

    val choiceStyle = NativeChoiceStyle(
        radiusDp = 0f,
        minHeightDp = cellMinHeightDp,
        textStyle = appearance.typography.nativeTextStyle(NativeTypographyRole.BODY_SMALL),
        horizontalPaddingDp = horizontalInsetDp,
        verticalPaddingDp = contentInsetDp,
        outlined = false,
        retainContentColorsWhenDisabled = true,
    )

    fun heightPx(context: Context): Int = (NativeApplicationGridSizing.heightDp(
        context.resources.configuration.fontScale) * context.resources.displayMetrics.density).roundToInt()

    fun columns(context: Context, widthPx: Int): Int = NativeApplicationGridSizing.columns(
        widthPx / context.resources.displayMetrics.density, context.resources.configuration.fontScale)
}

/** Resolve from the same appearance that owns the page's colors, groups and typography. */
val NativeUiAppearance.applicationGrid: NativeApplicationGridRecipe
    get() = NativeApplicationGridRecipe(this)
