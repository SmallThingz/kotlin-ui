package smallthingz.ui.components

import smallthingz.ui.graphics.Color
import smallthingz.ui.text.NativeTypographyMetrics
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.unit.sp

/** Geometry and paint for the frequency graph, arc dial and in-place sampling action. */
data class NativeCalibrationRecipe(
    val tickTextSp: Float = 11f,
    val valueTextSp: Float = 12f,
    val pointRadiusDp: Float = 5f,
    val selectedPointRadiusDp: Float = 7f,
    val selectionHaloRadiusDp: Float = 14f,
    val curveStrokeDp: Float = 2f,
    val pointHitRadiusDp: Float = 32f,
    val dialWidthDp: Float = 104f,
    val dialHeightDp: Float = 64f,
    val dialFocusRadiusDp: Float = 12f,
    val dialStrokeDp: Float = 3f,
    val dialBeadRadiusDp: Float = 5f,
    val actionMinTargetDp: Float = 48f,
    val actionSurface: NativeMaterialButtonStyle = NativeMaterialButtonStyle(12, iconSizeDp = 20, iconGapDp = 8),
    val progressOpacity: Float = .22f,
    val actionOpacity: Float = .12f,
    val disabledActionOpacity: Float = .06f,
    val disabledActionContentOpacity: Float = .38f,
    val heldPulseMillis: Long = 900L,
) {
    companion object {
        val Default = NativeCalibrationRecipe()
    }
}

/** Appearance of a grouped tile list and its inline creation editor. */
data class NativeQuickTilesRecipe(
    val surfaceRadiusDp: Float = 20f,
    val sectionHorizontalInsetDp: Int = 16,
    val sectionVerticalInsetDp: Int = 12,
    val sectionGapDp: Int = 8,
    val rowStartInsetDp: Int = 16,
    val rowEndInsetDp: Int = 4,
    val rowVerticalInsetDp: Int = 4,
    val rowLabelInsetDp: Int = 12,
    val iconSizeDp: Int = 32,
    val editorInsetDp: Int = 8,
    val editorGapDp: Int = 4,
    val disabledActionOpacity: Float = .38f,
    val glyphStroke: Float = 1.7f,
    val loadingRowInsetDp: Int = 16,
) {
    init { require(loadingRowInsetDp >= 0) }

    fun actionInk(ink: Int, enabled: Boolean): Int = if (enabled) ink else srgbColorWithAlpha(ink, disabledActionOpacity)

    companion object {
        val Default = NativeQuickTilesRecipe()
    }
}

/** Native caret raster, selection and large value editor typography. */
data class NativeEditorVisualRecipe(
    val cursorWidthDp: Float = 2f,
    val selectionAlpha: Int = 0x66,
    val largeValueText: NativeTypographyMetrics = NativeTypographyMetrics(34f, 40f, 400, -1.1f),
) {
    fun largeValueStyle(base: TextStyle, ink: Int): TextStyle = base.copy(
        color = Color(ink),
        fontSize = largeValueText.fontSizeSp.sp,
        lineHeight = largeValueText.lineHeightSp.sp,
        fontWeight = FontWeight(largeValueText.fontWeight),
        letterSpacing = largeValueText.letterSpacingSp.sp,
    )

    fun selectionColor(accent: Int): Int = (accent and 0x00ffffff) or (selectionAlpha shl 24)

    companion object {
        val Default = NativeEditorVisualRecipe()
    }
}
