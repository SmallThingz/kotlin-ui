package smallthingz.ui.components

import android.content.Context
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Pure sizing shared by both app pickers; one extra column without jumping to six on phones. */
internal object NativeApplicationGridSizing {
    const val cellMinWidthDp = 64f
    // Measured at mdpi/default font scale: Arabic/mixed labels need 44dp for two
    // fallback-font lines. Budget 3dp top + 32dp icon + 2dp gap + 44dp text +
    // 3dp bottom = 84dp; retain fallback line spacing and the full icon.
    const val cellMinHeightDp = 84f
    const val maxColumns = 7
    private const val preferredCellWidthDp = 80f

    fun columns(widthDp: Float, fontScale: Float): Int {
        val scale = fontScale.coerceAtLeast(1f)
        val desired = (widthDp / (preferredCellWidthDp * scale)).toInt() + 1
        val fitting = (widthDp / (cellMinWidthDp * scale)).toInt()
        return minOf(desired, fitting).coerceIn(1, maxColumns)
    }

    fun heightDp(fontScale: Float): Float = cellMinHeightDp * fontScale.coerceAtLeast(1f)
}

/** Shared geometry and checked-control styling for installed-application grids. */
class NativeApplicationGridRecipe internal constructor(appearance: NativeUiAppearance) {
    val iconSizeDp = 32f
    val iconLabelGapDp = 2f
    val contentInsetDp = 3f
    val horizontalInsetDp = 3f
    // A 64dp cell leaves 16dp beside its centered 32dp icon: 14dp badge +
    // 1dp end inset + 1dp clear space. This is decorative, not the checked target.
    val markerSizeDp = 14f
    val markerInsetDp = 1f
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
