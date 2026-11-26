package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NativeGroupSurfaceRecipesTest {
    private val colors = NativeColorScheme.fallback(false).copy(
        surface = 0xff101112.toInt(), surfaceContainerLow = 0xff202122.toInt(),
        surfaceContainerHigh = 0xff303132.toInt(), surfaceContainerHighest = 0xff404142.toInt(),
        onSurface = 0xff505152.toInt(), outlineVariant = 0xff606162.toInt(), primary = 0xff707172.toInt(),
        primaryContainer = 0xff808182.toInt(),
    )
    private val defaults = NativeUiAppearance.Default.groupSurfaces

    @Test fun groupedSettingsUseTheSubtlePerimeterAndTheirOwnRadii() {
        for ((recipe, radius) in listOf(
            defaults.settingsGroup to 20f, defaults.settingsChoices to 16f,
            defaults.settingsWarning to 14f, defaults.rangeControls to 28f,
        )) {
            assertEquals(radius, recipe.cornerRadiusDp, 0f)
            assertEquals(colors.surfaceContainerHigh, recipe.fillColor(colors))
            assertEquals(0x24505152, recipe.borderColor(colors))
        }
    }

    @Test fun borderlessSurfacesShareForegroundAndKeepSemanticSelectionFill() {
        for ((recipe, radius, fill) in listOf(
            Triple(defaults.emptyLibrary, 28f, colors.surfaceContainerHigh),
            Triple(defaults.recordingInfoRow, 14f, colors.surfaceContainerHigh),
            Triple(defaults.rangeBufferPill, 16f, 0x52808182),
        )) {
            assertEquals(radius, recipe.cornerRadiusDp, 0f)
            assertEquals(fill, recipe.fillColor(colors))
            assertEquals(0, recipe.borderColor(colors))
        }
        assertEquals(10f, defaults.rangeTimeBubble.cornerRadiusDp, 0f)
        assertEquals(1f, defaults.rangeTimeBubble.activeElevationDp, 0f)
    }

    @Test fun feedbackPreservesSemanticToneWhenRolesAliasTheBackdrop() {
        val recipe = defaults.feedback
        assertEquals(18f, recipe.cornerRadiusDp, 0f)
        for ((tone, fill, ink) in listOf(
            Triple(NativeFeedbackTone.INFO, colors.surfaceContainerHigh, colors.onSurface),
            Triple(NativeFeedbackTone.SUCCESS, colors.primaryContainer, colors.onPrimaryContainer),
            Triple(NativeFeedbackTone.ERROR, colors.errorContainer, colors.onErrorContainer),
        )) {
            assertEquals(fill, recipe.fillColor(colors, tone))
            assertEquals(ink, recipe.inkColor(colors, tone))
            assertEquals(fill, recipe.fillColor(colors.copy(surface = fill), tone))
        }
    }

    @Test fun recordingRowsKeepExactPaintTargetsAndAllowAppearanceOverrides() {
        val recipe = defaults.recordingRow
        assertEquals(16f, recipe.surface.cornerRadiusDp, 0f)
        assertEquals(NativeRecordingRowPaint(colors.field, 0x24505152, colors.raised, colors.ink, 1f),
            recipe.target(colors, selected = false))
        assertEquals(NativeRecordingRowPaint(0x8f808182.toInt(), 0x7a707172, 0x2e707172, colors.primary, 1f),
            recipe.target(colors, selected = true))

        val alternate = defaults.copy(recordingRow = recipe.copy(
            surface = NativeGroupSurfaceRecipe(8f, NativeGroupFill.HIGHEST, outlined = false),
            selectedFillAlpha = .25f,
        ))
        val appearance = NativeUiAppearance.Default.copy(groupSurfaces = alternate).copy()
        assertSame(alternate, appearance.groupSurfaces)
        val row = appearance.groupSurfaces.recordingRow
        assertEquals(8f, row.surface.cornerRadiusDp, 0f)
        assertEquals(colors.surfaceContainerHigh, row.target(colors, selected = false).background)
        assertEquals(0x40808182, row.target(colors, selected = true).background)
        assertEquals(0, row.target(colors, selected = true).border)
    }
}
