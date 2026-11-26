package smallthingz.ui.components

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import smallthingz.ui.components.blob.NativeAudioBlobAppearance
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.scroll.NativeScrollIndicatorStyle
import smallthingz.ui.text.NativeReadoutTypography
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp
import java.util.function.LongSupplier

/** Immutable appearance chosen when constructing the views for a window. */
class NativeUiAppearance(
    val colorScheme: NativeColorSchemeResolver = NativeDefaultColorScheme,
    val typography: NativeTypography = NativeTypography.Default,
    settingsSelectors: NativeSettingsSelectorStyle? = null,
    buttons: NativeButtonRecipes? = null,
    outlinedField: NativeOutlinedFieldRecipe? = null,
    val pairedFields: NativePairedFieldRecipe = NativePairedFieldRecipe(),
    val joinedFields: NativeJoinedFieldRecipe = NativeJoinedFieldRecipe.Default,
    val toolbars: NativeToolbarStyle = NativeToolbarStyle.Default,
    modals: NativeModalRecipes? = null,
    val audioBlob: NativeAudioBlobAppearance = NativeAudioBlobAppearance.Default,
    val editorVisuals: NativeEditorVisualRecipe = NativeEditorVisualRecipe.Default,
    val calibration: NativeCalibrationRecipe = NativeCalibrationRecipe.Default,
    val quickTiles: NativeQuickTilesRecipe = NativeQuickTilesRecipe.Default,
    val durationWheel: NativeDurationWheelStyle = NativeDurationWheelStyle.Default,
    val springFineSeek: NativeSpringFineSeekStyle = NativeSpringFineSeekStyle.Default,
    val discreteSlider: NativeDiscreteSliderStyle = NativeDiscreteSliderStyle.Default,
    val listDecorations: NativeListDecorationStyle = NativeListDecorationStyle.Default,
    val onboardingFooter: NativeOnboardingFooterRecipe = NativeOnboardingFooterRecipe.Default,
    val bufferSegment: NativeBufferSegmentStyle = NativeBufferSegmentStyle(),
    readoutTypography: NativeReadoutTypography? = null,
    statusContent: NativeCenteredStatusStyle? = null,
    val detailCards: NativeDetailCardStyle = NativeDetailCardStyle(),
    val groupSurfaces: NativeGroupSurfaceRecipes = NativeGroupSurfaceRecipes.Default,
    val backdrops: NativeBackdropStyle = NativeBackdropStyle.Default,
    val captureControls: NativeCaptureControlsStyle = NativeCaptureControlsStyle.Default,
    compactField: NativeCompactFieldRecipe? = null,
    val anchoredDropdown: NativeAnchoredDropdownStyle = NativeAnchoredDropdownStyle(),
    val scrollIndicator: NativeScrollIndicatorStyle = NativeScrollIndicatorStyle.Default,
) {
    private val settingsSelectorsOverride = settingsSelectors
    private val buttonsOverride = buttons
    private val outlinedFieldOverride = outlinedField
    private val compactFieldOverride = compactField
    private val modalsOverride = modals
    private val readoutTypographyOverride = readoutTypography
    private val statusContentOverride = statusContent

    val settingsSelectors: NativeSettingsSelectorStyle = settingsSelectors ?: NativeSettingsSelectorStyle(typography)
    val buttons: NativeButtonRecipes = buttons ?: NativeButtonRecipes(labelStyle = typography.buttonLabelStyle,
        explicitLabelStyle = typography[NativeTypographyRole.LABEL_LARGE])
    val outlinedField: NativeOutlinedFieldRecipe = outlinedField ?: NativeOutlinedFieldRecipe(typography = typography)
    val compactField: NativeCompactFieldRecipe = compactField ?: NativeCompactFieldRecipe(typography)
    val modals: NativeModalRecipes = modals ?: NativeModalRecipes(typography)
    internal val floatingWindowRecipe: NativeFloatingPanelRecipe = this.modals.floating.let { recipe ->
        if (recipe.grainSeed == backdrops.windowGrainSeed) recipe
        else recipe.copy(grainSeed = backdrops.windowGrainSeed)
    }
    val readoutTypography: NativeReadoutTypography = readoutTypography ?: NativeReadoutTypography(typography)
    val statusContent: NativeCenteredStatusStyle = statusContent ?: NativeCenteredStatusStyle(typography)

    /** Resolve a sheet using this appearance's window backdrop and the current palette. */
    fun actionSheetStyle(
        density: Density, fontResolver: FontFamily.Resolver, palette: NativeColorScheme,
    ): NativeActionSheetStyle =
        modals.actionSheetStyle(density, fontResolver, palette, backdrops.windowGrainSeed)

    /** Inherited recipes follow [typography]. Pass null for a recipe to restore inheritance. */
    fun copy(
        colorScheme: NativeColorSchemeResolver = this.colorScheme,
        typography: NativeTypography = this.typography,
        settingsSelectors: NativeSettingsSelectorStyle? = settingsSelectorsOverride,
        buttons: NativeButtonRecipes? = buttonsOverride,
        outlinedField: NativeOutlinedFieldRecipe? = outlinedFieldOverride,
        pairedFields: NativePairedFieldRecipe = this.pairedFields,
        joinedFields: NativeJoinedFieldRecipe = this.joinedFields,
        toolbars: NativeToolbarStyle = this.toolbars,
        modals: NativeModalRecipes? = modalsOverride,
        audioBlob: NativeAudioBlobAppearance = this.audioBlob,
        editorVisuals: NativeEditorVisualRecipe = this.editorVisuals,
        calibration: NativeCalibrationRecipe = this.calibration,
        quickTiles: NativeQuickTilesRecipe = this.quickTiles,
        durationWheel: NativeDurationWheelStyle = this.durationWheel,
        springFineSeek: NativeSpringFineSeekStyle = this.springFineSeek,
        discreteSlider: NativeDiscreteSliderStyle = this.discreteSlider,
        listDecorations: NativeListDecorationStyle = this.listDecorations,
        onboardingFooter: NativeOnboardingFooterRecipe = this.onboardingFooter,
        bufferSegment: NativeBufferSegmentStyle = this.bufferSegment,
        readoutTypography: NativeReadoutTypography? = readoutTypographyOverride,
        statusContent: NativeCenteredStatusStyle? = statusContentOverride,
        detailCards: NativeDetailCardStyle = this.detailCards,
        groupSurfaces: NativeGroupSurfaceRecipes = this.groupSurfaces,
        backdrops: NativeBackdropStyle = this.backdrops,
        captureControls: NativeCaptureControlsStyle = this.captureControls,
        compactField: NativeCompactFieldRecipe? = compactFieldOverride,
        anchoredDropdown: NativeAnchoredDropdownStyle = this.anchoredDropdown,
        scrollIndicator: NativeScrollIndicatorStyle = this.scrollIndicator,
    ): NativeUiAppearance = NativeUiAppearance(
        colorScheme = colorScheme,
        typography = typography,
        settingsSelectors = settingsSelectors,
        buttons = buttons,
        outlinedField = outlinedField,
        pairedFields = pairedFields,
        joinedFields = joinedFields,
        toolbars = toolbars,
        modals = modals,
        audioBlob = audioBlob,
        editorVisuals = editorVisuals,
        calibration = calibration,
        quickTiles = quickTiles,
        durationWheel = durationWheel,
        springFineSeek = springFineSeek,
        discreteSlider = discreteSlider,
        listDecorations = listDecorations,
        onboardingFooter = onboardingFooter,
        bufferSegment = bufferSegment,
        readoutTypography = readoutTypography,
        statusContent = statusContent,
        detailCards = detailCards,
        groupSurfaces = groupSurfaces,
        backdrops = backdrops,
        captureControls = captureControls,
        compactField = compactField,
        anchoredDropdown = anchoredDropdown,
        scrollIndicator = scrollIndicator,
    )

    fun resolveColorScheme(context: Context, darkTheme: Boolean): NativeColorScheme =
        colorScheme.resolve(darkTheme, Build.VERSION.SDK_INT) { id -> context.resources.getColor(id, context.theme) }

    companion object {
        val Default = NativeUiAppearance()
    }
}

private fun sectionTitleStyle(typography: NativeTypography): TextStyle = typography.defaultTextStyle.copy(
    fontSize = 19.sp,
    lineHeight = 26.sp,
    fontWeight = FontWeight.Medium,
)

private val defaultSectionTitle = sectionTitleStyle(NativeTypography.Default)

/** Grouped Settings presentation for Theme and app-defined selections. */
data class NativeSettingsSelectorStyle(
    val segmented: NativeSegmentedStyle = NativeSegmentedStyle.Default,
    val sectionTitle: TextStyle = defaultSectionTitle,
    val retentionSection: NativeRetentionSectionLayout = NativeRetentionSectionLayout(),
    val recordingSection: NativeRecordingSectionLayout = NativeRecordingSectionLayout(),
) {
    internal constructor(typography: NativeTypography) : this(sectionTitle = sectionTitleStyle(typography))

    /** Shared grouped surface for composed selectors; retains the recipe's palette and grain ownership. */
    fun createSegmentedPresentation(
        context: Context,
        onPaintedSurfaceChanged: (View) -> Unit = {},
    ): NativeSegmentedPresentation =
        GroupedSegmentedPresentation(context, segmented.surfaceRadiusDp, onPaintedSurfaceChanged)

    fun createSegmentedControl(
        context: Context,
        palette: ControlPalette,
        density: Float = context.resources.displayMetrics.density,
        inputAllowed: () -> Boolean = { true },
        onPaintedSurfaceChanged: (View) -> Unit = {},
    ): NativeSegmentedControl = NativeSegmentedControl(context, palette, segmented, density, inputAllowed,
        createSegmentedPresentation(context, onPaintedSurfaceChanged))

    /** [titlePaintedSurface] samples the actual title underlay, encoded as [smallthingz.ui.controls.textSurfaceRange]. */
    fun createThemeSelector(
        context: Context,
        palette: ControlPalette,
        selected: NativeThemeMode,
        textDensity: Density,
        fontResolver: FontFamily.Resolver,
        inputAllowed: () -> Boolean = { true },
        onPaintedSurfaceChanged: (View) -> Unit = {},
        titlePaintedSurface: ((View) -> Long)? = null,
        onSelectionRequest: (NativeThemeMode) -> Boolean,
    ): NativeThemeSelector = NativeThemeSelector(context, palette, selected, palette.content,
        SettingsThemePresentation(context, this, palette, textDensity, fontResolver,
            onPaintedSurfaceChanged, titlePaintedSurface), textDensity.density, inputAllowed, segmented, onSelectionRequest)

    /**
     * The same localized Theme row inside a host that already owns the section's side gutters.
     * Only the row's outer horizontal inset is omitted; choice text/icon padding is unchanged.
     * Use [createThemeSelector] for a full-width section whose row must own those gutters.
     */
    fun createThemeSelectorInInsetSection(
        context: Context,
        palette: ControlPalette,
        selected: NativeThemeMode,
        textDensity: Density,
        fontResolver: FontFamily.Resolver,
        inputAllowed: () -> Boolean = { true },
        onPaintedSurfaceChanged: (View) -> Unit = {},
        titlePaintedSurface: ((View) -> Long)? = null,
        onSelectionRequest: (NativeThemeMode) -> Boolean,
    ): NativeThemeSelector = NativeThemeSelector(context, palette, selected, palette.content,
        SettingsThemePresentation(context, this, palette, textDensity, fontResolver,
            onPaintedSurfaceChanged, titlePaintedSurface), textDensity.density, inputAllowed, segmented,
        onSelectionRequest, sectionInsetByHost = true)
}

data class NativeRetentionSectionLayout(
    val sideInsetDp: Int = DEFAULT_SETTINGS_SECTION_HORIZONTAL_INSET_DP,
    val inlineGapDp: Int = 0,
    val stackedGapDp: Int = 8,
    val preferredInlineWidthDp: Int = 126,
    val bodyGapDp: Int = 14,
    val trailingInsetDp: Int = 12,
)

data class NativeRecordingSectionLayout(
    val sideInsetDp: Int = DEFAULT_SETTINGS_SECTION_HORIZONTAL_INSET_DP,
    val titleTopInsetDp: Int = 12,
    val bodyGapDp: Int = 12,
    val warningHorizontalInsetDp: Int = 14,
    val warningVerticalInsetDp: Int = 8,
)

private class GroupedSegmentedPresentation(
    context: Context,
    radiusDp: Float,
    onPaintedSurfaceChanged: (View) -> Unit,
) : NativeSegmentedPresentation {
    override val surface = NativeGroupLayout(context, radiusDp, onPaintedSurfaceChanged = onPaintedSurfaceChanged)

    override fun setContent(content: View) = surface.addRow(content,
        NativeGroupLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

    override fun bindSurface(palette: ControlPalette) {
        surface.setSurfacePreset(palette.surfacePreset)
        surface.setPalette(palette.surface, palette.outline)
    }
    override val paintedSurfaceColor: Int get() = surface.paintedFillColor
    override val paintedTextSurface: Long get() = surface.paintedTextSurface
}

internal class SettingsThemePresentation(
    context: Context,
    private val style: NativeSettingsSelectorStyle,
    palette: ControlPalette,
    textDensity: Density,
    fontResolver: FontFamily.Resolver,
    onPaintedSurfaceChanged: (View) -> Unit,
    titlePaintedSurface: ((View) -> Long)?,
) : NativeThemePresentation,
    NativeSegmentedPresentation by GroupedSegmentedPresentation(context, style.segmented.surfaceRadiusDp, onPaintedSurfaceChanged) {
    override val title = ParagraphView(context, style.sectionTitle, textDensity, fontResolver, palette.content).apply {
        isAccessibilityHeading = true
    }

    init {
        if (titlePaintedSurface != null) title.paintedTextSurface = LongSupplier { titlePaintedSurface(title) }
    }

    fun bindTypography(textDensity: Density, fontResolver: FontFamily.Resolver) =
        title.setTypography(style.sectionTitle, textDensity, fontResolver)

    override fun bindTitle(titleText: String, palette: ControlPalette, titleColor: Int) {
        title.setText(titleText)
        title.animateTextColor(titleColor)
    }
}
