package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
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

    @Test fun lightDefaultsShareReverbsFourPercentPageDarkeningAndPreserveOtherMaterialRoles() {
        for (sdk in sdks) {
            val raw = NativeColorScheme.resolve(false, sdk, ::resourceColor)
            val expected = raw.copy(background = darkenPage(raw.background), surface = darkenPage(raw.surface))
            for (appearance in defaultAppearances()) {
                val actual = appearance.colorScheme.resolve(false, sdk, ::resourceColor)
                assertEquals("Default light roles on SDK $sdk", expected, actual.copy(surfacePreset = null))
                if (sdk == 30) {
                    assertEquals(0xfff3edf4.toInt(), actual.background)
                    assertEquals(0xfff3edf4.toInt(), actual.surface)
                }
                assertEquals(NativeGrainPreset(backgroundGrain = 1f, foregroundGrain = .5f), actual.surfacePreset)
                assertNull(actual.surfacePreset!!.borderColor)
                assertNull(actual.surfacePreset.borderWidthDp)
                assertEquals(raw.border, actual.border)
                assertEquals(raw.settingsControls.outline, actual.settingsControls.outline)
                assertEquals(raw.pageControls.outline, actual.pageControls.outline)
                assertPresetPropagates(actual)
            }
        }
    }

    @Test fun lightPageDarkeningChangesOnlyRgbAndPreservesEvenTranslucentResourceAlpha() {
        // SDK 34 reads the page roles directly, allowing alpha preservation to be checked
        // without platform APIs or the opaque neutral-tone synthesis used by SDK 31.
        for (alpha in listOf(0, 1, 127, 254, 255)) {
            val readColor: (Int) -> Int = { id ->
                (resourceColor(id) and 0x00ffffff) or (alpha shl 24)
            }
            val raw = NativeColorScheme.resolve(false, 34, readColor)
            val actual = NativeDefaultColorScheme.resolve(false, 34, readColor)
            assertEquals(alpha, actual.background ushr 24)
            assertEquals(alpha, actual.surface ushr 24)
            assertEquals(raw.copy(background = darkenPage(raw.background), surface = darkenPage(raw.surface)),
                actual.copy(surfacePreset = null))
        }
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

    // Legacy ColorUtils.blendARGB(color, BLACK, .04f) RGB truncation, with original alpha.
    private fun darkenPage(color: Int): Int {
        fun channel(shift: Int): Int = (((color ushr shift) and 255) * .96f).toInt()
        return (color and 0xff000000.toInt()) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }

    // Distinct opaque resource colors expose lost/misrouted dynamic roles without Android resources.
    private fun resourceColor(id: Int): Int = 0xff000000.toInt() or ((id * 0x45d9f3b) and 0x00ffffff)
}
