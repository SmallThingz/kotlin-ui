/*
 * Color lookup follows AndroidX Material3 1.4.0 ColorScheme.kt.
 * Copyright 2021 The Android Open Source Project. Licensed under the Apache License,
 * Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
 */
package smallthingz.ui.components

import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import kotlin.math.ln
import kotlin.math.min

/** Appearance of layered native windows. Content and callbacks are bound by the caller. */
data class NativeModalRecipes(
    val typography: NativeTypography = NativeTypography.Default,
    val sheet: NativeActionSheetRecipe = NativeActionSheetRecipe(),
    val floating: NativeFloatingPanelRecipe = NativeFloatingPanelRecipe(),
    val statusBar: NativeStatusBarStyle = NativeStatusBarStyle(),
    val anchoredPanel: NativeAnchoredMenuRecipe = NativeAnchoredMenuRecipe.Default,
    val permissionDialog: NativePermissionDialogRecipe = NativePermissionDialogRecipe(),
) {
    fun actionSheetStyle(density: Density, resolver: FontFamily.Resolver, palette: NativeColorScheme): NativeActionSheetStyle =
        NativeActionSheetStyle(density, resolver,
            NativeActionSheetColors(sheet.surfaceColor(palette), palette.onSurface, palette.onSurfaceVariant,
                palette.scrim, palette.inverseSurface, palette.inverseOnSurface, palette.onSurface,
                palette.border, palette.modalEdgeWidthDp()),
            typography[sheet.titleRole], typography[sheet.tooltipRole],
            sheet.copy(grainIntensity = palette.surfacePreset?.foregroundGrain ?: sheet.grainIntensity))
}

data class NativeStatusBarStyle(
    val darkIcons: Boolean = false,
    val grainSeed: Int? = NoiseDrawable.SEED_TOP_BAR,
) {
    fun create(palette: NativeColorScheme): NativeStatusBarProtection =
        resolve(palette).createProtection()

    internal fun resolve(
        palette: NativeColorScheme,
        preset: NativeSurfacePreset? = palette.surfacePreset,
    ) = NativeStatusBarAppearance(palette.surface, grainSeed, preset?.backgroundGrain ?: 1f, darkIcons)
}

internal data class NativeStatusBarAppearance(
    val surface: Int,
    val grainSeed: Int?,
    val backgroundGrain: Float,
    val darkIcons: Boolean,
) {
    fun createProtection() = NativeStatusBarProtection(NoiseDrawable(surface, grainSeed).apply {
        setNoiseIntensity(backgroundGrain)
    }, this)
}

data class NativeActionSheetRecipe(
    val radiusDp: Float = 28f,
    val horizontalPaddingDp: Float = 24f,
    val bottomPaddingDp: Float = 24f,
    val bodyGapDp: Float = 12f,
    /** Default clearance above actions matches the shared footer's visual bottom space. */
    val actionsGapDp: Float = NativeMergedActionLayout.ACTION_FOOTER_BOTTOM_PADDING_DP,
    val maxWidthDp: Float = 640f,
    val shadowDp: Float = 8f,
    val scrimAlpha: Float = .32f,
    val grainSeed: Int? = NoiseDrawable.SEED_BACKGROUND,
    val titleRole: NativeTypographyRole = NativeTypographyRole.TITLE_LARGE,
    val tooltipRole: NativeTypographyRole = NativeTypographyRole.BODY_SMALL,
    val handleHeightDp: Float = 2f,
    val handleVerticalPaddingDp: Float = 16f,
    /** Content clearance after the handle; also the outer top inset for an inline editor. */
    val topPaddingDp: Float = NativeMergedActionLayout.ACTION_FOOTER_BOTTOM_PADDING_DP,
    val grainIntensity: Float = 1f,
) {
    /** Use the same fill as the normal app background. */
    fun surfaceColor(palette: NativeColorScheme): Int = palette.background
}

/** Top-origin panel with a retained rounded viewport and themed brand/detail content. */
data class NativeFloatingPanelRecipe(
    val cornerRadiusDp: Float = 32f,
    /** Retained for constructor compatibility. Fill follows the window theme background. */
    val surfaceElevationDp: Float = 6f,
    val shadowDp: Float = 8f,
    val grainSeed: Int? = NoiseDrawable.SEED_BACKGROUND,
    val horizontalPaddingDp: Float = 24f,
    val topPaddingDp: Float = 12f,
    val bottomPaddingDp: Float = 26f,
    val headingGapDp: Float = 14f,
    val summaryGapDp: Float = 2f,
    val linkGapDp: Float = 18f,
    val errorGapDp: Float = 12f,
    val brandSizeDp: Float = 104f,
    val actionSizeDp: Float = 48f,
    val headlineRole: NativeTypographyRole = NativeTypographyRole.HEADLINE_SMALL,
    val titleRole: NativeTypographyRole = NativeTypographyRole.TITLE_SMALL,
    val summaryRole: NativeTypographyRole = NativeTypographyRole.BODY_MEDIUM,
    val detailRole: NativeTypographyRole = NativeTypographyRole.BODY_SMALL,
    val link: NativeFloatingLinkStyle = NativeFloatingLinkStyle(),
    val error: NativeFloatingErrorStyle = NativeFloatingErrorStyle(),
) {
    fun surfaceColor(palette: NativeColorScheme): Int = palette.background

    internal fun cornerRadiusPx(density: Float, width: Int, height: Int): Float =
        min(cornerRadiusDp * density, min(width / 2f, height.toFloat()))

    companion object {
        internal val Default = NativeFloatingPanelRecipe()
    }
}

data class NativeFloatingLinkStyle(
    val radiusDp: Float = 20f,
    val elevationDp: Float = 6f,
    val horizontalPaddingDp: Float = 18f,
    val verticalPaddingDp: Float = 15f,
    val glyphSizeDp: Float = 24f,
    val textGapDp: Float = 14f,
    val minimumHeightDp: Float = 48f,
) {
    fun fill(palette: NativeColorScheme): Int =
        raisedModalColor(modalTonalColor(palette.surfaceContainerHighest, palette, elevationDp), palette)

    // This is a surface role, even when its numeric value aliases an accent/container role.
    fun content(palette: NativeColorScheme): Int = palette.onSurface

    fun titleColor(palette: NativeColorScheme, style: TextStyle): Int =
        if (style.color != Color.Unspecified) style.color.toArgb() else content(palette)
}

data class NativeFloatingErrorStyle(
    val radiusDp: Float = 18f,
    val elevationDp: Float = 8f,
    val startPaddingDp: Float = 16f,
    val endPaddingDp: Float = 8f,
    val verticalPaddingDp: Float = 8f,
) {
    fun fill(palette: NativeColorScheme): Int =
        modalTonalColor(palette.errorContainer, palette, elevationDp)
}

private fun modalTonalColor(color: Int, palette: NativeColorScheme, elevationDp: Float): Int {
    if (color != palette.surface || elevationDp == 0f) return color
    val alpha = ((4.5f * ln(elevationDp + 1f)) + 2f) / 100f
    return Color(palette.surfaceTint).copy(alpha = alpha).compositeOver(Color(palette.surface)).toArgb()
}

/** A small neutral lift for floating foregrounds; page blacks and Material accents stay owned by the palette. */
internal fun raisedModalColor(color: Int, palette: NativeColorScheme): Int =
    if ((palette.surfacePreset as? NativeDarkSurfacePreset)?.style == NativeDarkSurfaces.AMOLED)
        Color.White.copy(alpha = .04f).compositeOver(Color(color)).toArgb()
    else color
