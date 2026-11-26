package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.reverb.reverbActionSheetStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.text.font.FontSynthesis
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.font.ResolvedTypeface
import smallthingz.ui.unit.Density

class NativeSharedModalPolicyTest {
    private val density = Density(1.5f)
    private val resolver = object : FontFamily.Resolver {
        override fun resolve(fontFamily: FontFamily?, fontWeight: FontWeight,
            fontStyle: FontStyle, fontSynthesis: FontSynthesis): ResolvedTypeface =
            error("Constructing a modal style must not resolve platform fonts")
    }

    @Test fun commonAndReverbSheetHostsShareFillHandleGrainAndTextRoles() {
        val modals = NativeUiAppearance.Default.modals
        for (dark in listOf(false, true)) {
            for (raw in listOf(false, true)) {
                val palette = if (raw) NativeColorScheme.fallback(dark)
                    else NativeDefaultColorScheme.resolve(dark, 30) { 0xfff3edf4.toInt() }
                val common = modals.actionSheetStyle(density, resolver, palette)
                val reverb = reverbActionSheetStyle(ReverbDesign.windowTheme(density, resolver, palette))
                assertEquals(common, reverb)
                assertSame(density, common.textDensity)
                assertSame(resolver, common.sharedFontResolver)
                assertEquals(2f, common.recipe.handleHeightDp, 0f)
                assertEquals(16f, common.recipe.handleVerticalPaddingDp, 0f)
                assertEquals(NoiseDrawable.SEED_BACKGROUND, common.recipe.grainSeed)
                assertEquals(palette.surfacePreset?.backgroundGrain ?: 1f, common.recipe.grainIntensity, 0f)
                assertEquals(palette.onSurface, common.palette.title)
                assertEquals(palette.onSurfaceVariant, common.palette.onSurfaceVariant)
                assertEquals(palette.scrim, common.palette.scrim)
                assertEquals(palette.background, common.palette.surfaceContainerLow)
                assertEquals(common.palette.surfaceContainerLow, ReverbDesign.modalSurface(palette))
            }
        }
    }

    @Test fun sheetUsesTheWindowBackgroundWithoutChangingItsAlpha() {
        val sheet = NativeActionSheetRecipe()
        for (alpha in listOf(0, 1, 64, 128, 254, 255)) {
            val palette = NativeColorScheme.fallback(false).copy(
                background = (alpha shl 24) or 0x00ff8040,
                surfaceContainerLow = 0xffabcdef.toInt(),
                surfaceContainerHigh = 0xff123456.toInt(),
            )
            // Modal fill follows the window background; foreground/link roles stay independent.
            assertEquals((alpha shl 24) or 0x00ff8040, sheet.surfaceColor(palette))
        }
    }

    @Test fun themeBackdropSeedFlowsThroughSheetAndFloatingWindowRecipes() {
        val palette = NativeColorScheme.fallback(false)
        for (seed in listOf(null, 61, NoiseDrawable.SEED_BACKGROUND)) {
            val appearance = NativeUiAppearance.Default.copy(backdrops = NativeBackdropStyle(windowGrainSeed = seed))
            val theme = ReverbTheme(density, resolver, palette, appearance)
            val style = appearance.actionSheetStyle(density, resolver, palette)
            assertEquals(seed, style.recipe.grainSeed)
            assertEquals(style, reverbActionSheetStyle(theme))
            assertEquals(seed, appearance.floatingWindowRecipe.grainSeed)
        }
    }

    @Test fun themeOwnsSheetGrainAndGeometryCopiesKeepResolvedPaint() {
        val recipe = NativeActionSheetRecipe(handleHeightDp = 5f, handleVerticalPaddingDp = 9f,
            grainSeed = 42, grainIntensity = .27f)
        val modals = NativeUiAppearance.Default.modals.copy(sheet = recipe)
        val raw = NativeColorScheme.fallback(false)
        val themed = raw.copy(surfacePreset = NativeGrainPreset(backgroundGrain = .75f, foregroundGrain = .25f))
        val style = modals.actionSheetStyle(density, resolver, themed)
        assertEquals(recipe.copy(grainSeed = NoiseDrawable.SEED_BACKGROUND, grainIntensity = .75f), style.recipe)
        val geometry = style.copy(recipe = recipe.copy(handleHeightDp = 7f, grainSeed = null))
        assertEquals(7f, geometry.recipe.handleHeightDp, 0f)
        assertEquals(style.recipe.grainSeed, geometry.recipe.grainSeed)
        assertEquals(style.recipe.grainIntensity, geometry.recipe.grainIntensity, 0f)
        val flat = themed.copy(surfacePreset = NativeGrainPreset(backgroundGrain = 0f, foregroundGrain = .25f))
        assertEquals(0f, modals.actionSheetStyle(density, resolver, flat).recipe.grainIntensity, 0f)
    }

    @Test fun floatingPanelsUseWindowBackgroundWhileLinksUseSharedForeground() {
        val floating = NativeUiAppearance.Default.modals.floating
        assertEquals(floating, ReverbDesign.appearance.modals.floating)
        for (dark in listOf(false, true)) {
            val palette = NativeDefaultColorScheme.resolve(dark, 30) { 0xfff3edf4.toInt() }
            val panelFill = floating.surfaceColor(palette)
            val linkFill = floating.link.fill(palette)
            assertEquals(palette.background, panelFill)
            assertEquals(palette.surfaceContainerHigh, linkFill)
            assertEquals(linkFill, ReverbDesign.modalLinkSurface(palette))
            assertEquals(palette.onSurface, floating.link.content(palette))
        }
    }

    @Test fun floatingBackgroundAndCanonicalLinkPreserveAlpha() {
        val floating = NativeUiAppearance.Default.modals.floating
        val palette = NativeDefaultColorScheme.resolve(true, 30) { 0xfff3edf4.toInt() }.copy(
            background = 0x30102030,
            surfaceContainerHigh = 0x40102030,
            surfaceContainerHighest = 0x80203040.toInt(),
        )
        assertEquals(palette.background, floating.surfaceColor(palette))
        assertEquals(palette.surfaceContainerHigh, floating.link.fill(palette))
        assertEquals(48, floating.surfaceColor(palette) ushr 24)
        assertEquals(64, floating.link.fill(palette) ushr 24)
        val raw = palette.copy(surfacePreset = null)
        assertEquals(raw.background, floating.surfaceColor(raw))
        assertEquals(raw.surfaceContainerHigh, floating.link.fill(raw))
    }
}
