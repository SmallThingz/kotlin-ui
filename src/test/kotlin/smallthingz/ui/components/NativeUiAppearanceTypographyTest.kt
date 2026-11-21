package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import smallthingz.ui.components.blob.NativeAudioBlobAppearance
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.unit.sp

class NativeUiAppearanceTypographyTest {
    private val baseline = NativeUiAppearance.Default
    private val body = NativeTypographyRole.BODY_LARGE
    private val label = NativeTypographyRole.LABEL_LARGE
    private val supporting = NativeTypographyRole.BODY_SMALL
    private val changed = NativeTypography(mapOf(
        body to baseline.typography[body].copy(fontSize = 25.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
        label to baseline.typography[label].copy(fontSize = 23.sp),
        supporting to baseline.typography[supporting].copy(fontSize = 19.sp),
    ))

    @Test fun constructorAndCopyDeriveEveryImplicitTextRecipeFromTheChosenTypography() {
        assertEquals(NativeTextStyle(sizeSp = 20f, lineHeightSp = 28f), baseline.statusContent.text)
        assertInheritedTypography(NativeUiAppearance(typography = changed))
        assertInheritedTypography(baseline.copy(typography = changed))
    }

    @Test fun copyRetainsExplicitRecipesAndCanRestoreInheritance() {
        val selectors = baseline.settingsSelectors.copy(sectionTitle = baseline.settingsSelectors.sectionTitle.copy(fontSize = 33.sp))
        val buttons = NativeButtonRecipes(labelStyle = baseline.typography.buttonLabelStyle.copy(fontSize = 31.sp))
        val field = NativeOutlinedFieldRecipe(cornerRadiusDp = 11f)
        val modals = baseline.modals.copy(sheet = baseline.modals.sheet.copy(radiusDp = 13f))
        val readouts = baseline.readoutTypography.copy()
        val status = baseline.statusContent.copy(paddingDp = 30f)
        val explicit = baseline.copy(settingsSelectors = selectors, buttons = buttons, outlinedField = field,
            modals = modals, readoutTypography = readouts, statusContent = status)
        val copied = explicit.copy(typography = changed)
        assertSame(selectors, copied.settingsSelectors)
        assertSame(buttons, copied.buttons)
        assertSame(field, copied.outlinedField)
        assertSame(modals, copied.modals)
        assertSame(readouts, copied.readoutTypography)
        assertSame(status, copied.statusContent)
        assertSame(baseline.pairedFields, copied.pairedFields)
        assertInheritedTypography(copied.copy(settingsSelectors = null, buttons = null, outlinedField = null,
            modals = null, readoutTypography = null, statusContent = null))
    }

    @Test fun outlinedFieldCopyRecalculatesBothTextRoles() {
        val field = baseline.outlinedField.copy(typography = changed)
        assertSame(changed.defaultTextStyle, field.textStyle)
        assertEquals(changed.defaultTextStyle.merge(changed[supporting]), field.supportingTextStyle)
    }

    @Test fun copyingTypographyRetainsIndependentComponentStyles() {
        val toolbars = baseline.toolbars.copy()
        val audioBlob = NativeAudioBlobAppearance(baseline.audioBlob::resolve)
        val editorVisuals = baseline.editorVisuals.copy()
        val calibration = baseline.calibration.copy()
        val quickTiles = baseline.quickTiles.copy()
        val durationWheel = baseline.durationWheel.copy()
        val springFineSeek = baseline.springFineSeek.copy()
        val discreteSlider = baseline.discreteSlider.copy()
        val listDecorations = baseline.listDecorations.copy()
        val onboardingFooter = baseline.onboardingFooter.copy()
        val bufferSegment = baseline.bufferSegment.copy()
        val joinedFields = baseline.joinedFields.copy()
        val detailCards = baseline.detailCards.copy()
        val styled = baseline.copy(toolbars = toolbars, audioBlob = audioBlob, editorVisuals = editorVisuals,
            calibration = calibration, quickTiles = quickTiles, durationWheel = durationWheel,
            springFineSeek = springFineSeek, discreteSlider = discreteSlider,
            listDecorations = listDecorations, onboardingFooter = onboardingFooter, bufferSegment = bufferSegment,
            joinedFields = joinedFields, detailCards = detailCards)
        val copied = styled.copy(typography = changed)
        assertSame(toolbars, copied.toolbars)
        assertSame(audioBlob, copied.audioBlob)
        assertSame(editorVisuals, copied.editorVisuals)
        assertSame(calibration, copied.calibration)
        assertSame(quickTiles, copied.quickTiles)
        assertSame(durationWheel, copied.durationWheel)
        assertSame(springFineSeek, copied.springFineSeek)
        assertSame(discreteSlider, copied.discreteSlider)
        assertSame(listDecorations, copied.listDecorations)
        assertSame(onboardingFooter, copied.onboardingFooter)
        assertSame(bufferSegment, copied.bufferSegment)
        assertSame(joinedFields, copied.joinedFields)
        assertSame(detailCards, copied.detailCards)
    }

    private fun assertInheritedTypography(value: NativeUiAppearance) {
        assertSame(changed, value.typography)
        assertEquals(changed.defaultTextStyle.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
            value.settingsSelectors.sectionTitle)
        val buttons = value.buttons
        listOf(buttons.filled, buttons.text, buttons.neutralText, buttons.destructive, buttons.destructiveText,
            buttons.tonal, buttons.footerBack, buttons.footerContinue, buttons.raisedText,
            buttons.raisedIcon, buttons.largeRaisedIcon).forEach { button ->
            assertSame(changed.buttonLabelStyle, button.labelStyle)
        }
        assertSame(changed[label], buttons.rangeExport.button.labelStyle)
        assertSame(changed[label], buttons.trimActions.cancel.labelStyle)
        assertSame(changed[label], buttons.trimActions.save.button.labelStyle)
        assertEquals(changed.defaultTextStyle.copy(fontSize = 14.sp, lineHeight = 20.sp), value.readoutTypography.retentionLabel)
        assertEquals(changed.defaultTextStyle.copy(fontSize = 13.sp, lineHeight = 20.sp), value.readoutTypography.retentionEstimate)
        assertEquals(changed[label].copy(letterSpacing = 0.04.sp), value.readoutTypography.libraryHeader)
        assertEquals(changed[supporting].copy(fontFamily = FontFamily.Monospace), value.readoutTypography.playerTimestamp)
        assertSame(changed.defaultTextStyle, value.outlinedField.textStyle)
        assertEquals(changed.defaultTextStyle.merge(changed[supporting]), value.outlinedField.supportingTextStyle)
        assertSame(changed, value.modals.typography)
        assertEquals(NativeTextStyle(sizeSp = 20f, lineHeightSp = 28f, weight = FontWeight.Medium.weight,
            fontFamily = "monospace"), value.statusContent.text)
    }
}
