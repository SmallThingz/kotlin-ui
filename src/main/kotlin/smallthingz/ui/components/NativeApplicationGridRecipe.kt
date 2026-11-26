package smallthingz.ui.components

import android.content.Context
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Shared geometry and checked-control styling for installed-application grids. */
class NativeApplicationGridRecipe internal constructor(appearance: NativeUiAppearance) {
    val iconSizeDp = 36f
    val iconLabelGapDp = 3f
    val contentInsetDp = 4f
    val horizontalInsetDp = 4f
    val markerSizeDp = 18f
    val markerInsetDp = 4f
    val labelMaxLines = 2
    val unavailableIconAlpha = .55f
    val cellMinWidthDp = 80f
    val cellMinHeightDp = 88f
    val maxColumns = 6
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
