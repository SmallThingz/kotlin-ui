package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
                val palette = base.copy(surfaceContainerHigh = alias)
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
    @Test fun amoledPopupKeepsSmallLiftAndSharedForegroundGrain() {
        val palette = NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 30) { error("No resources") }
        val menu = NativeAnchoredMenuRecipe().surfaceColor(palette)
        val lift = (menu and 255) - (palette.raised and 255)
        assertTrue("Small visible neutral lift", lift in 8..11)
        assertEquals(menu, palette.menuColors().raised)
        assertEquals(menu, palette.menuColors().surfaceContainerHigh)
        assertEquals(.5f, palette.menuColors().grainIntensity, 0f)
        assertEquals(palette.background, NativeFloatingPanelRecipe().surfaceColor(palette))
        assertEquals(palette.raised, NativeFloatingLinkStyle().fill(palette))
        assertEquals(0xff000000.toInt(), palette.surface)
        assertEquals(NativeColorScheme.fallback(true).primary, palette.primary)
    }

    @Test fun nonAmoledPopupOffsetsTheNeutralFillWithoutChangingGrain() {
        val recipe = NativeAnchoredMenuRecipe.Default
        for (dark in listOf(false, true)) {
            val raw = NativeColorScheme.fallback(dark)
            val gray = raw.copy(surfacePreset = NativeDarkSurfacePreset.Amoled.copy(style = NativeDarkSurfaces.GRAY))
            val tinted = raw.copy(surfacePreset = NativeDarkSurfacePreset.Amoled.copy(style = NativeDarkSurfaces.TINTED))
            for (palette in listOf(raw, gray, tinted)) {
                val fill = recipe.surfaceColor(palette)
                assertNotEquals(palette.raised, fill)
                assertNotEquals(palette.background, fill)
                assertEquals(fill, palette.menuColors().raised)
                assertEquals(palette.surfacePreset?.foregroundGrain ?: 0f, palette.menuColors().grainIntensity, 0f)
            }
        }
        val raw = NativeColorScheme.fallback(false)
        assertEquals(0xff0a0a0a.toInt(), recipe.surfaceColor(raw.copy(surfaceContainerHigh = 0xff000000.toInt())))
        assertEquals(0xfff5f5f5.toInt(), recipe.surfaceColor(raw.copy(surfaceContainerHigh = 0xffffffff.toInt())))
    }

}
