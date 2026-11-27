package smallthingz.ui.gallery

import android.content.Context
import smallthingz.ui.components.NativeChoiceColor
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.components.NativeMaterialButtonStyle
import smallthingz.ui.components.nativeTextStyle
import smallthingz.ui.components.srgbColorWithAlpha
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.reverbActionSheetStyle
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import smallthingz.ui.text.NativeTypographyRole

/** Gallery uses the shared appearance; product marks and content remain Gallery-owned. */
object GalleryDesign {
    val appearance = ReverbDesign.appearance

    /** Secondary viewer browsing keeps the existing compact native target in every pose.
     * This metric does not opt the primary action row into icon-only presentation.
     */
    val viewerFilmstripHeightDp = appearance.toolbars.action.minimumTargetDp.coerceAtLeast(48f)

    /** Quieter destination paint, derived from the incoming page palette on every bind.
     * Replace selection alpha (143/255); keep shared ink, focus and all target geometry.
     */
    fun navigationPalette(palette: ControlPalette): ControlPalette =
        palette.copy(selection = srgbColorWithAlpha(palette.selection, .56f))

    val actionTextStyle = appearance.typography[NativeTypographyRole.LABEL_LARGE]
    val libraryHeadingTextStyle = appearance.typography[NativeTypographyRole.TITLE_MEDIUM]
    val libraryHeadingButtonStyle = NativeMaterialButtonStyle(
        horizontalPaddingDp = appearance.toolbars.centeredPaddingDp.toInt(),
        verticalPaddingDp = 2, minimumWidthDp = 48, minimumHeightDp = 48,
        iconSizeDp = appearance.toolbars.leadingTitleGlyphDp.toInt(),
        iconGapDp = appearance.toolbars.leadingTitleGapDp.toInt(),
    )
    val libraryActionButtonStyle = NativeMaterialButtonStyle(
        horizontalPaddingDp = 0, verticalPaddingDp = 0,
        minimumWidthDp = 48, minimumHeightDp = 48,
        iconSizeDp = appearance.toolbars.action.glyphDp.toInt(),
        radiusDp = appearance.toolbars.action.radiusDp, showLabel = false,
    )
    /** Secondary library tools retain the existing labelled 48dp targets. */
    val libraryUtilityActionRecipe = appearance.buttons.neutralText.copy(
        surface = NativeMaterialButtonStyle(horizontalPaddingDp = 6, minimumWidthDp = 48,
            minimumHeightDp = 48, iconSizeDp = 20, iconGapDp = 6, showLabel = true),
        labelStyle = appearance.typography[NativeTypographyRole.LABEL_MEDIUM],
    )
    val dialogChoiceTextStyle = appearance.typography[appearance.modals.floating.titleRole]
    val settingsTitleTextStyle = NativeTypography(mapOf(
        NativeTypographyRole.TITLE_MEDIUM to appearance.settingsSelectors.sectionTitle,
    )).nativeTextStyle(NativeTypographyRole.TITLE_MEDIUM)
    val settingsChoiceStyle = appearance.settingsSelectors.segmented.choiceStyle
    // Keep Gallery's existing full-height choice targets while sharing the complete selector.
    val settingsSelectorStyle = appearance.settingsSelectors.let { settings ->
        settings.copy(segmented = settings.segmented.copy(
            choiceStyle = settings.segmented.choiceStyle.copy(
                minHeightDp = maxOf(settings.segmented.choiceStyle.minHeightDp,
                    appearance.toolbars.action.minimumTargetDp),
                focusedSelectedOutline = NativeChoiceColor(NativeTextRole.ON_ACCENT),
            ),
        ))
    }
    val settingsGroup = appearance.groupSurfaces.settingsGroup

    /** Keep the shared modal treatment; Settings has no separate bottom action row. */
    fun settingsSheetStyle(density: Density, resolver: FontFamily.Resolver, colors: NativeColorScheme) =
        reverbActionSheetStyle(ReverbDesign.windowTheme(density, resolver, colors)).let {
            it.copy(recipe = it.recipe.copy(actionsGapDp = 0f))
        }

    fun palette(context: Context, dark: Boolean): NativeColorScheme =
        appearance.resolveColorScheme(context, dark)

    private val textStyles = NativeTypographyRole.entries.map { appearance.typography.nativeTextStyle(it) }

    /** Framework text adapters retain the shared role's SP units, leading and font settings. */
    fun textStyle(role: NativeTypographyRole): NativeTextStyle = textStyles[role.ordinal]
}
