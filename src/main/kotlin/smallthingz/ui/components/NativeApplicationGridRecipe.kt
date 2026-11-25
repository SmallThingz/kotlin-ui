package smallthingz.ui.components

import android.content.Context
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Shared geometry and checked-control styling for installed-application grids. */
class NativeApplicationGridRecipe internal constructor(appearance: NativeUiAppearance) {
    val iconSizeDp = 40f
    val iconLabelGapDp = 4f
    val contentInsetDp = 6f
    val horizontalInsetDp = 4f
    val markerSizeDp = 18f
    val markerInsetDp = 6f
    val labelMaxLines = 2
    val unavailableIconAlpha = .55f
    val cellMinWidthDp = 96f
    val cellMinHeightDp = 100f
    val maxColumns = 6
    val cellMarginDp = NativeScreenSpacing.CONTROL_GAP_DP / 2f
    val labelTopInsetDp get() = contentInsetDp + iconSizeDp + iconLabelGapDp
    val iconToLabelDp get() = iconSizeDp + iconLabelGapDp

    val choiceStyle = NativeChoiceStyle(
        radiusDp = appearance.groupSurfaces.settingsChoices.cornerRadiusDp,
        minHeightDp = cellMinHeightDp,
        textStyle = appearance.typography.nativeTextStyle(NativeTypographyRole.BODY_SMALL),
        horizontalPaddingDp = horizontalInsetDp,
        verticalPaddingDp = contentInsetDp,
        outlined = true,
        retainContentColorsWhenDisabled = true,
    )

    fun heightPx(context: Context): Int = (cellMinHeightDp * context.resources.displayMetrics.density *
        context.resources.configuration.fontScale.coerceAtLeast(1f)).roundToInt()

    fun columns(context: Context, widthPx: Int): Int =
        (widthPx / context.resources.displayMetrics.density /
            (cellMinWidthDp * context.resources.configuration.fontScale.coerceAtLeast(1f)))
            .toInt().coerceIn(1, maxColumns)
}

/** Resolve from the same appearance that owns the page's colors, groups and typography. */
val NativeUiAppearance.applicationGrid: NativeApplicationGridRecipe
    get() = NativeApplicationGridRecipe(this)
