package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import smallthingz.ui.reverb.menuColors
import org.junit.Test
import smallthingz.ui.graphics.Color
import smallthingz.ui.text.TextStyle

class NativeModalContrastTest {
    @Test fun repositorySurfaceUsesSemanticInkEvenWhenPaletteValuesAlias() {
        val link = NativeFloatingLinkStyle()
        for (dark in listOf(false, true)) {
            val base = NativeColorScheme.fallback(dark)
            for (alias in listOf(base.primary, base.primaryContainer, base.inverseSurface, base.background)) {
                val palette = base.copy(surfaceContainerHighest = alias)
                assertEquals(palette.onSurface, link.content(palette))
                assertEquals(palette.onSurface, link.titleColor(palette, TextStyle()))
            }
        }
    }

    @Test fun explicitRepositoryTypographyColorKeepsCallerOwnership() {
        val custom = 0xff123456.toInt()
        assertEquals(custom, NativeFloatingLinkStyle().titleColor(
            NativeColorScheme.fallback(true), TextStyle(color = Color(custom))))
    }
    @Test fun amoledFloatingForegroundGetsSmallLocalLiftAndRetainsGrain() {
        val palette = NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 30) { error("No resources") }
        val menu = NativeAnchoredMenuRecipe().surfaceColor(palette)
        val lift = (menu and 255) - (palette.raised and 255)
        assertTrue("Small visible neutral lift", lift in 8..11)
        assertEquals(menu, palette.menuColors().raised)
        assertEquals(menu, palette.menuColors().surfaceContainerHigh)
        assertEquals(.5f, palette.menuColors().grainIntensity, 0f)
        assertEquals(palette.background, NativeFloatingPanelRecipe().surfaceColor(palette))
        assertTrue(NativeFloatingLinkStyle().fill(palette) > palette.surfaceContainerHighest)
        assertEquals(0xff000000.toInt(), palette.surface)
        assertEquals(NativeColorScheme.fallback(true).primary, palette.primary)
    }

    @Test fun lightAndNonPresetMenusKeepOriginalFillAndNoGrain() {
        for (dark in listOf(false, true, false)) {
            val palette = NativeColorScheme.fallback(dark)
            assertEquals(palette.raised, NativeAnchoredMenuRecipe().surfaceColor(palette))
            assertEquals(palette.raised, palette.menuColors().raised)
            assertEquals(0f, palette.menuColors().grainIntensity, 0f)
        }
    }

}
