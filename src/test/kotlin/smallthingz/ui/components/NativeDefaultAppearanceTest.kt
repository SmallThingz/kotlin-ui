package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.roundToInt
import smallthingz.ui.controls.srgbLuminance
import smallthingz.ui.graphics.noiseSurfaceRange
import org.junit.Assert.assertSame
import org.junit.Test
import smallthingz.ui.R
import smallthingz.ui.reverb.ReverbDesign

class NativeDefaultAppearanceTest {
    private val sdks = listOf(30, 31, 34)

    @Test fun constructorDefaultAndCopyShareTheDefaultResolver() {
        defaultAppearances().forEach { appearance ->
            assertSame(NativeDefaultColorScheme, appearance.colorScheme)
        }
    }

    @Test fun everyDefaultEntryUsesNeutralAmoledDepthWithoutChangingMaterialAccentOrContentRoles() {
        for (sdk in sdks) {
            val raw = NativeColorScheme.resolve(true, sdk, ::resourceColor)
            // Pin the existing Reverb policy independently of the resolver being introduced.
            // Whole-scheme equality also protects every accent, fixed, content and feedback role.
            val expected = raw.copy(
                background = gray(0),
                surface = gray(0),
                surfaceDim = gray(0),
                surfaceVariant = gray(16),
                surfaceTint = gray(59),
                surfaceBright = gray(18),
                surfaceContainerLowest = gray(5),
                surfaceContainerLow = gray(10),
                surfaceContainer = gray(12),
                surfaceContainerHigh = gray(14),
                surfaceContainerHighest = gray(17),
                surfacePreset = NativeDarkSurfacePreset.Amoled,
            )
            for (appearance in defaultAppearances()) {
                val actual = appearance.colorScheme.resolve(true, sdk, ::resourceColor)
                assertEquals("Default dark roles on SDK $sdk", expected, actual)
                assertSame(NativeDarkSurfacePreset.Amoled, actual.surfacePreset)
                assertEquals(gray(38), actual.border)
                assertEquals(gray(38), actual.settingsControls.outline)
                assertEquals(1f, actual.surfacePreset!!.borderWidthDp!!, 0f)
                assertEquals(1f, actual.surfacePreset.backgroundGrain, 0f)
                assertEquals(.5f, actual.surfacePreset.foregroundGrain, 0f)
                assertPresetPropagates(actual)
            }
        }
    }

    @Test fun lightDefaultsUseNeutralSurfacesAndPreserveMaterialAccentAndContentRoles() {
        for (sdk in sdks) {
            val raw = NativeColorScheme.resolve(false, sdk, ::resourceColor)
            val backdrop = resourceColor(R.color.native_default_light_background)
            val foreground = resourceColor(R.color.native_default_light_foreground)
            val popup = resourceColor(R.color.native_default_light_popup)
            val expected = raw.copy(
                background = backdrop, surface = backdrop, surfaceDim = backdrop,
                surfaceBright = popup, surfaceVariant = foreground,
                surfaceContainerLowest = popup, surfaceContainerLow = foreground,
                surfaceContainer = foreground, surfaceContainerHigh = foreground,
                surfaceContainerHighest = foreground, popupFillOverride = popup,
            )
            for (appearance in defaultAppearances()) {
                val actual = appearance.colorScheme.resolve(false, sdk, ::resourceColor)
                assertEquals("Default light roles on SDK $sdk", expected, actual.copy(surfacePreset = null))
                assertEquals(NativeGrainPreset(.22f, .20f), actual.surfacePreset)
                assertEquals(.22f, actual.backgroundGrain, 0f)
                assertEquals(.20f, actual.foregroundGrain, 0f)
                assertEquals(popup, actual.popupFill)
                assertNull(actual.surfacePreset!!.borderColor)
                assertNull(actual.surfacePreset.borderWidthDp)
                assertEquals(raw.border, actual.border)
                assertEquals(raw.settingsControls.outline, actual.settingsControls.outline)
                assertEquals(raw.pageControls.outline, actual.pageControls.outline)
                assertPresetPropagates(actual)
            }
        }
    }

    @Test fun lightGrainKeepsNearWhiteSurfacesAndReadableInkAcrossConservativeRasterBounds() {
        val colors = NativeDefaultColorScheme.resolve(false, 28, ::lightResourceColor)
        // Use the renderer's conservative bounds, including interpolation and raster rounding,
        // rather than testing only the untextured token or treating samples as opaque gray.
        val page = noiseSurfaceRange(colors.background, (colors.backgroundGrain * 255f).roundToInt())
        val field = noiseSurfaceRange(colors.field, (colors.foregroundGrain * 255f).roundToInt())
        val popup = noiseSurfaceRange(colors.popupFill, (colors.popupGrain * 255f).roundToInt())
        assertEquals(gray(242), (page ushr 32).toInt())
        assertEquals(gray(247), page.toInt())
        assertEquals(gray(248), (field ushr 32).toInt())
        assertEquals(gray(253), field.toInt())
        assertEquals(gray(251), (popup ushr 32).toInt())
        assertEquals(gray(255), popup.toInt())
        assertTrue(srgbLuminance(colors.background) < srgbLuminance(colors.field))
        assertTrue(srgbLuminance(colors.field) < srgbLuminance(colors.popupFill))
        for (range in listOf(page, field, popup)) {
            for (surface in listOf((range ushr 32).toInt(), range.toInt())) {
                assertTrue(contrast(colors.ink, surface) >= 4.5)
                assertTrue(contrast(colors.muted, surface) >= 4.5)
                assertTrue(contrast(colors.primary, surface) >= 4.5)
                assertTrue(contrast(colors.outline, surface) >= 3.0)
            }
        }
        // A recording tab is elevated, not selected: its Material accent/ink path is untouched.
        val tabs = NativeBufferSegmentStyle()
        assertEquals(gray(255), tabs.fill(colors, false, true, false))
        assertEquals(colors.muted, tabs.ink(colors, false, true, true, false))
        assertEquals(colors.primary, tabs.fill(colors, true, true, false))
        assertTrue(contrast(colors.primary, colors.popupFill) >= 4.5)
        assertEquals(colors.onPrimary, tabs.ink(colors, true, true, true, false))
    }

    @Test fun elevatedWhitePopupIsAValueAndGenericMaterialLiftRemainsTheDefault() {
        val default = NativeDefaultColorScheme.resolve(false, 28, ::lightResourceColor)
        val explicitMaterial = NativeColorScheme.fallback(false).copy(surfaceContainerHigh = gray(252))
        val explicitGrain = explicitMaterial.copy(surfacePreset = NativeGrainPreset(.22f, .20f))
        assertEquals(gray(255), default.popupFill)
        val equal = default.copy(surfacePreset = NativeGrainPreset(.22f, .20f))
        assertEquals(default, equal)
        assertEquals(default.hashCode(), equal.hashCode())
        assertEquals(default.popupFill, equal.popupFill)
        val genericOptOut = default.copy(popupFillOverride = null)
        assertNotEquals(default, genericOptOut)
        assertEquals(explicitMaterial.popupFill, genericOptOut.popupFill)
        assertTrue(srgbLuminance(explicitMaterial.popupFill) < srgbLuminance(explicitMaterial.field))
        assertEquals(explicitMaterial.popupFill, explicitGrain.popupFill)
        val dark = NativeDefaultColorScheme.resolve(true, 28) { error("No resource read") }
        val previousDark = NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 28) { error("No resource read") }
        assertEquals(previousDark, dark)
        assertEquals(previousDark.popupFill, dark.popupFill)
    }

    private fun lightResourceColor(id: Int): Int = when (id) {
        R.color.native_default_light_background -> gray(246)
        R.color.native_default_light_foreground -> gray(252)
        R.color.native_default_light_popup -> gray(255)
        else -> error("Unexpected light paint resource $id")
    }

    private fun contrast(first: Int, second: Int): Double {
        val a = srgbLuminance(first)
        val b = srgbLuminance(second)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }

    @Test fun presetsAreReusedWhileEachResolutionReadsTheCurrentDynamicPalette() {
        val lightPreset = NativeDefaultColorScheme.resolve(false, 30, ::resourceColor).surfacePreset
        for (dark in listOf(false, true)) {
            for (sdk in sdks) {
                val first = NativeDefaultColorScheme.resolve(dark, sdk, ::resourceColor)
                val second = NativeDefaultColorScheme.resolve(dark, sdk) { id ->
                    resourceColor(id) xor 0x00ffffff
                }
                assertSame(if (dark) NativeDarkSurfacePreset.Amoled else lightPreset, first.surfacePreset)
                assertSame(first.surfacePreset, second.surfacePreset)
                if (sdk >= 31) assertNotEquals("Dynamic accents must refresh on SDK $sdk", first.primary, second.primary)
            }
        }
    }

    @Test fun materialGrayTintedAndCustomResolversRemainExplicitOverridesAfterCopy() {
        val customLight = NativeColorScheme.fallback(false).copy(primary = 0xff123456.toInt())
        val customDark = NativeColorScheme.fallback(true).copy(background = 0xff234567.toInt())
        val custom = NativeColorSchemeResolver { dark, _, _ -> if (dark) customDark else customLight }
        val overrides = listOf(
            NativeColorScheme,
            NativeColorScheme.withDarkSurfaces(NativeDarkSurfaces.GRAY),
            NativeColorScheme.withDarkSurfaces(NativeDarkSurfaces.TINTED, tint = 0xff6682dc.toInt()),
            custom,
        )
        for (resolver in overrides) {
            val constructed = NativeUiAppearance(colorScheme = resolver)
            val copied = NativeUiAppearance.Default.copy(colorScheme = resolver)
            val appearances = listOf(constructed, constructed.copy(), copied,
                copied.copy(toolbars = copied.toolbars.copy(compactHeightDp = 61f)))
            for (appearance in appearances) {
                assertSame(resolver, appearance.colorScheme)
                for (dark in listOf(false, true)) {
                    for (sdk in sdks) {
                        val expected = resolver.resolve(dark, sdk, ::resourceColor)
                        val actual = appearance.colorScheme.resolve(dark, sdk, ::resourceColor)
                        assertEquals("Explicit override on SDK $sdk, dark=$dark", expected, actual)
                        assertNull(actual.surfacePreset)
                        if (resolver === custom) assertSame(if (dark) customDark else customLight, actual)
                    }
                }
            }
        }
    }

    @Test fun commonDefaultsMatchReverbInBothModesAndRetainTheExistingDarkPreset() {
        val previousPolicy = NativeDarkSurfacePreset.Amoled.colorScheme()
        for (sdk in sdks) {
            for (dark in listOf(false, true)) {
                val common = NativeUiAppearance.Default.colorScheme.resolve(dark, sdk, ::resourceColor)
                if (dark) assertEquals(previousPolicy.resolve(true, sdk, ::resourceColor), common)
                assertEquals("Common/Reverb parity on SDK $sdk, dark=$dark", common,
                    ReverbDesign.appearance.colorScheme.resolve(dark, sdk, ::resourceColor))
            }
        }
    }

    private fun defaultAppearances(): List<NativeUiAppearance> = listOf(
        NativeUiAppearance(),
        NativeUiAppearance.Default,
        NativeUiAppearance.Default.copy(),
        NativeUiAppearance.Default.copy(toolbars = NativeUiAppearance.Default.toolbars.copy(compactHeightDp = 61f)),
    )

    private fun assertPresetPropagates(colors: NativeColorScheme) {
        assertSame(colors.surfacePreset, colors.settingsControls.surfacePreset)
        assertSame(colors.surfacePreset, colors.pageControls.surfacePreset)
        assertSame(colors.surfacePreset, colors.buttonPalette.surfacePreset)
    }

    private fun gray(level: Int): Int = 0xff000000.toInt() or (level * 0x010101)

    // Distinct opaque resource colors expose lost/misrouted dynamic roles without Android resources.
    private fun resourceColor(id: Int): Int = 0xff000000.toInt() or ((id * 0x45d9f3b) and 0x00ffffff)
}
