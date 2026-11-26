package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
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
                val expectedGrain = palette.surfacePreset?.foregroundGrain ?: common.sheet.grainIntensity
                assertEquals(expectedGrain, reverbSheet.recipe.grainIntensity, 0f)
            }
        }
    }

    @Test fun nonzeroFloatingElevationStillUsesBackgroundAndPreservesMenuAndLinkLift() {
        val floating = NativeFloatingPanelRecipe(surfaceElevationDp = 6f)
        val amoled = NativeDefaultColorScheme.resolve(true, 30) { 0xfff4eaf5.toInt() }
        assertEquals(amoled.background, floating.surfaceColor(amoled))
        assertEquals(0xff171717.toInt(), NativeAnchoredMenuRecipe().surfaceColor(amoled))
        assertEquals(0xff1a1a1a.toInt(), floating.link.fill(amoled))

        val raw = NativeColorScheme.fallback(false).copy(
            background = 0xffeadfc7.toInt(),
            surfaceContainerHigh = 0xff45617b.toInt(),
        )
        assertEquals(raw.background, floating.surfaceColor(raw))
        assertEquals(raw.surfaceContainerHigh, NativeAnchoredMenuRecipe().surfaceColor(raw))
        assertEquals(raw.surfaceContainerHighest, floating.link.fill(raw))
    }
}
