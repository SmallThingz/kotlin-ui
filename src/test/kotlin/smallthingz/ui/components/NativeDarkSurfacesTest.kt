package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.R

class NativeDarkSurfacesTest {
    private val base = NativeColorScheme.fallback(true)
    private val blue = 0xff6682dc.toInt()

    @Test fun darkSurfaceTransformsDiscardResolvedLightPopupPaint() {
        val light = defaultLight()
        assertEquals(0xffffffff.toInt(), light.popupFill)
        for (style in NativeDarkSurfaces.values()) {
            val actual = light.withDarkSurfaces(style)
            val withoutLightPopup = light.copy(popupFillOverride = null).withDarkSurfaces(style)
            assertEquals("Only the replaced popup policy differs before this fix: $style", withoutLightPopup, actual)
            assertNull(actual.popupFillOverride)
            assertEquals(withoutLightPopup.popupFill, actual.popupFill)
            assertSame(light.surfacePreset, actual.surfacePreset)
            assertEquals(light.primary, actual.primary)
            assertEquals(light.onSurface, actual.onSurface)
        }
        val amoled = light.withDarkSurfaces(NativeDarkSurfaces.AMOLED)
        assertEquals(0xff000000.toInt(), amoled.field)
        assertEquals(0xff0a0a0a.toInt(), amoled.popupFill)
    }

    @Test fun lightResolverPassThroughAndGrainOnlyCopiesKeepResolvedPopupPaint() {
        val light = defaultLight()
        val resolver = NativeColorSchemeResolver { _, _, _ -> light }
            .withDarkSurfaces(NativeDarkSurfaces.AMOLED)
        assertSame(light, resolver.resolve(false, 28) { error("Unused") })
        assertEquals(light.popupFill, resolver.resolve(false, 28) { error("Unused") }.popupFill)
        assertNull(resolver.resolve(true, 28) { error("Unused") }.popupFillOverride)
        val flat = light.copy(surfacePreset = NativeGrainPreset(0f, 0f))
        assertEquals(light.popupFillOverride, flat.popupFillOverride)
        assertEquals(light.popupFill, flat.popupFill)
        assertEquals(light, light.copy(surfacePreset = NativeGrainPreset(.22f, .20f)))
        val material = NativeColorScheme.resolve(false, 28) { error("No fallback resources") }
        assertSame(NativeColorScheme.fallback(false), material)
        assertNull(material.popupFillOverride)
    }

    private fun defaultLight() = NativeDefaultColorScheme.resolve(false, 28) { id ->
        when (id) {
            R.color.native_default_light_background -> 0xfff6f6f6.toInt()
            R.color.native_default_light_foreground -> 0xfffcfcfc.toInt()
            R.color.native_default_light_popup -> 0xffffffff.toInt()
            else -> error("Unexpected resource $id")
        }
    }

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
