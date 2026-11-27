package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import smallthingz.ui.R
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.reverbActionSheetStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.text.font.FontSynthesis
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.font.ResolvedTypeface
import smallthingz.ui.unit.Density

class NativeModalDefaultSurfaceTest {
    private val density = Density(1.5f)
    private val resolver = object : FontFamily.Resolver {
        override fun resolve(fontFamily: FontFamily?, fontWeight: FontWeight,
            fontStyle: FontStyle, fontSynthesis: FontSynthesis): ResolvedTypeface =
            error("Resolving modal defaults must not load platform fonts")
    }

    @Test fun commonAndReverbDefaultModalsUseTheBackgroundRole() {
        val common = NativeUiAppearance.Default.modals
        for (dark in listOf(false, true)) {
            val resolved = NativeDefaultColorScheme.resolve(dark, 30) { 0xfff4eaf5.toInt() }
            val custom = resolved.copy(
                background = if (dark) 0xff192439.toInt() else 0xffeadfc7.toInt(),
                surface = 0xff733869.toInt(),
                surfaceContainerLow = 0xff26465b.toInt(),
                surfaceContainerHigh = 0xff677f42.toInt(),
                surfacePreset = null,
            )
            for (palette in listOf(resolved, custom)) {
                val expectedFill = palette.background
                val theme = ReverbDesign.windowTheme(density, resolver, palette)
                val sheet = common.actionSheetStyle(density, resolver, palette)
                val reverbSheet = reverbActionSheetStyle(theme)
                assertEquals(expectedFill, sheet.palette.surfaceContainerLow)
                assertEquals(expectedFill, reverbSheet.palette.surfaceContainerLow)
                assertEquals(expectedFill, ReverbDesign.modalSurface(palette))
                assertEquals(expectedFill, common.floating.surfaceColor(palette))
                assertEquals(expectedFill, theme.appearance.modals.floating.surfaceColor(palette))
                assertEquals(palette.onSurface, reverbSheet.palette.title)
                assertEquals(NoiseDrawable.SEED_BACKGROUND, reverbSheet.recipe.grainSeed)
                val expectedGrain = palette.surfacePreset?.backgroundGrain ?: 1f
                assertEquals(expectedGrain, reverbSheet.recipe.grainIntensity, 0f)
            }
        }
    }

    @Test fun defaultPopupIsDistinctFromBothOtherMaterialsAndUsesForegroundGrain() {
        val recipe = NativeAnchoredMenuRecipe.Default
        for (dark in listOf(false, true)) {
            val palette = NativeDefaultColorScheme.resolve(dark, 30) { id ->
                when (id) {
                    R.color.native_default_light_background -> 0xfff6f6f6.toInt()
                    R.color.native_default_light_foreground -> 0xfffcfcfc.toInt()
                    R.color.native_default_light_popup -> 0xffffffff.toInt()
                    else -> error("Unexpected resource $id")
                }
            }
            val fill = recipe.surfaceColor(palette)
            assertNotEquals(palette.background, fill)
            assertNotEquals(palette.surfaceContainerHigh, fill)
            assertEquals(fill, recipe.colors(palette).raised)
            assertEquals(if (dark) .5f else .20f, recipe.colors(palette).grainIntensity, 0f)
            assertEquals(fill, NativeBufferSegmentStyle().fill(palette, selected = false, recording = true, filled = false))
        }
    }

    @Test fun popupKeepsItsLiftAndOrdinaryLinkUsesCanonicalForeground() {
        val floating = NativeFloatingPanelRecipe(surfaceElevationDp = 6f)
        val amoled = NativeDefaultColorScheme.resolve(true, 30) { 0xfff4eaf5.toInt() }
        assertEquals(amoled.background, floating.surfaceColor(amoled))
        assertEquals(0xff171717.toInt(), NativeAnchoredMenuRecipe().surfaceColor(amoled))
        assertEquals(0xff0e0e0e.toInt(), floating.link.fill(amoled))

        val raw = NativeColorScheme.fallback(false).copy(
            background = 0xffeadfc7.toInt(),
            surfaceContainerHigh = 0xff45617b.toInt(),
        )
        assertEquals(raw.background, floating.surfaceColor(raw))
        assertNotEquals(raw.surfaceContainerHigh, NativeAnchoredMenuRecipe().surfaceColor(raw))
        assertEquals(raw.surfaceContainerHigh, floating.link.fill(raw))
    }
}
