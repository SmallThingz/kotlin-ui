package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDarkSurfacesTest {
    private val base = NativeColorScheme.fallback(true)
    private val blue = 0xff6682dc.toInt()

    @Test fun amoledKeepsSurfaceFillsAndElevationOverlayBlack() {
        val colors = base.withDarkSurfaces(NativeDarkSurfaces.AMOLED)
        val black = 0xff000000.toInt()
        assertTrue(listOf(colors.background, colors.surface, colors.surfaceTint,
            colors.surfaceBright, colors.surfaceDim, colors.surfaceVariant, colors.surfaceContainer,
            colors.surfaceContainerLow, colors.surfaceContainerLowest, colors.surfaceContainerHigh,
            colors.surfaceContainerHighest).all { it == black })
        assertEquals(base.primary, colors.primary)
        assertEquals(base.onSurface, colors.onSurface)
    }

    @Test fun grayAndTintedKeepDepthWhileOnlyTintedRespondsToSaturation() {
        fun isGray(color: Int): Boolean = (color ushr 16 and 255) == (color ushr 8 and 255) &&
            (color ushr 8 and 255) == (color and 255)
        val gray = base.withDarkSurfaces(NativeDarkSurfaces.GRAY, blue, 1f)
        val noTint = base.withDarkSurfaces(NativeDarkSurfaces.TINTED, blue, 0f)
        val tinted = base.withDarkSurfaces(NativeDarkSurfaces.TINTED, blue, 0.45f)
        assertTrue(isGray(gray.surface) && isGray(gray.surfaceContainerHigh) && isGray(noTint.surface))
        assertTrue(tinted.surface < gray.surface && tinted.surface < tinted.surfaceContainerHigh)
        assertTrue((tinted.surface and 255) > (tinted.surface ushr 16 and 255))
    }

    @Test fun resolverPreservesLightScheme() {
        val light = NativeColorScheme.fallback(false)
        val resolver = NativeColorSchemeResolver { dark, _, _ -> if (dark) base else light }
            .withDarkSurfaces(NativeDarkSurfaces.AMOLED)
        assertSame(light, resolver.resolve(false, 30) { error("Unused") })
        assertEquals(0xff000000.toInt(), resolver.resolve(true, 30) { error("Unused") }.surface)
    }

    @Test fun independentLightnessPreservesTheOtherLayerAndMaterialRoles() {
        val black = 0xff000000.toInt()
        for (style in listOf(NativeDarkSurfaces.TINTED, NativeDarkSurfaces.AMOLED)) {
            val pageOnly = base.withDarkSurfaces(style, backgroundLightness = 1f, foregroundLightness = 0f)
            assertTrue(pageOnly.surface != black)
            assertEquals(black, pageOnly.field)
            assertEquals(black, pageOnly.raised)
            assertEquals(black, pageOnly.surfaceTint)

            val foregroundOnly = base.withDarkSurfaces(style, backgroundLightness = 0f, foregroundLightness = 1f)
            assertEquals(black, foregroundOnly.background)
            assertEquals(black, foregroundOnly.surface)
            assertEquals(black, foregroundOnly.surfaceDim)
            assertTrue(foregroundOnly.surfaceContainerLow > black)
            assertTrue(foregroundOnly.surfaceContainerHigh > foregroundOnly.surfaceContainerLow)
            assertEquals(foregroundOnly.surfaceContainerHigh, foregroundOnly.field)
            assertEquals(foregroundOnly.field, foregroundOnly.raised)
            assertEquals(base.primary, foregroundOnly.primary)
            assertEquals(base.onSurface, foregroundOnly.onSurface)
        }
    }

    @Test fun defaultTintedSlidersPreserveTheOriginalPreset() {
        val default = base.withDarkSurfaces(NativeDarkSurfaces.TINTED)
        assertEquals(default, base.withDarkSurfaces(NativeDarkSurfaces.TINTED,
            backgroundLightness = .32f, foregroundLightness = .75f))
    }

    @Test fun amoledPresetMatchesTheSelectedSurfaceSettings() {
        val preset = NativeDarkSurfacePreset.Amoled
        val colors = preset.colorScheme().resolve(true, 30) { error("Unused") }
        assertEquals(0xff000000.toInt(), colors.surface)
        assertEquals(0xff0e0e0e.toInt(), colors.raised)
        assertEquals(base.primary, colors.primary)
        assertEquals(1f, preset.backgroundGrain, 0f)
        assertEquals(.5f, preset.foregroundGrain, 0f)
        assertEquals(1f, preset.borderWidthDp, 0f)
        assertEquals(0xff262626.toInt(), preset.borderColor)
    }
    @Test fun presetTravelsThroughEveryControlPaletteButLeavesLightResolutionUntouched() {
        val light = NativeColorScheme.fallback(false)
        val resolver = NativeDarkSurfacePreset.Amoled.colorScheme(
            NativeColorSchemeResolver { dark, _, _ -> if (dark) base else light })
        val dark = resolver.resolve(true, 30) { error("Unused") }
        assertSame(NativeDarkSurfacePreset.Amoled, dark.surfacePreset)
        assertSame(dark.surfacePreset, dark.settingsControls.surfacePreset)
        assertSame(dark.surfacePreset, dark.pageControls.surfacePreset)
        assertSame(dark.surfacePreset, dark.buttonPalette.surfacePreset)
        assertEquals(NativeDarkSurfacePreset.Amoled.borderColor, dark.border)
        assertSame(light, resolver.resolve(false, 30) { error("Unused") })
        assertEquals(null, light.pageControls.surfacePreset)
        assertEquals(null, light.buttonPalette.surfacePreset)
    }

}
