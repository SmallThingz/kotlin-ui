package smallthingz.ui.inject

import android.graphics.Color
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.text.NativeTypographyRole

/**
 * Named Inject variants, retaining the existing permission/hero/identity presentation.
 * Product-specific dimensions below are migrated from Inject; common roles stay shared aliases.
 */
object InjectPluginVisuals {
    val permissionStyle = InjectDesign.appearance.detailCards.copy(
        markerSizeDp = 28, markerGlyphSizeDp = 24, paddingDp = 14, gapDp = 12,
    )
    val permissionTitleStyle get() = InjectDesign.appearance.typography[NativeTypographyRole.TITLE_MEDIUM]
    val permissionBodyStyle get() = InjectDesign.appearance.typography[NativeTypographyRole.BODY_MEDIUM]
    fun permissionPalette(palette: NativeColorScheme) = palette.copy(primaryContainer = Color.TRANSPARENT)
    fun permissionStatusAlpha(enabled: Boolean, granted: Boolean) = if (enabled || granted) 1f else .45f

    val heroSurface get() = InjectDesign.appearance.groupSurfaces.settingsGroup
    fun heroFill(palette: NativeColorScheme) = palette.primaryContainer
    // Preserve the existing continuous accent surface, rather than inventing a contrasting edge.
    fun heroBorder(palette: NativeColorScheme) = heroFill(palette)
    fun heroContent(palette: NativeColorScheme) = palette.onPrimaryContainer
    const val heroIconSizeDp = 40
    const val heroTextGapDp = 12
    const val heroHorizontalPaddingDp = 14
    const val heroVerticalPaddingDp = 12
    const val heroHeadlineMaxLines = 2

    const val identitySizeDp = 56
    const val identityTitleGapDp = 12
    val identityTitleStyle get() = InjectDesign.appearance.settingsSelectors.sectionTitle
    fun identityFill(palette: NativeColorScheme, buttonSurface: Boolean) =
        if (buttonSurface) InjectDesign.appearance.toolbars.actionFill(palette) else palette.primaryContainer
    fun identityInk(palette: NativeColorScheme, buttonSurface: Boolean) =
        if (buttonSurface) InjectDesign.appearance.toolbars.actionTint(palette, NativeToolbarActionTone.MUTED)
        else palette.onPrimaryContainer
    fun identityOutline(palette: NativeColorScheme) =
        InjectDesign.appearance.toolbars.actionOutline(palette, NativeToolbarActionTone.MUTED)
}

enum class InjectHeadlineKind(val role: NativeTypographyRole) {
    VALUE(NativeTypographyRole.DISPLAY_SMALL),
    SUMMARY(NativeTypographyRole.HEADLINE_SMALL),
}
