package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
                // A raw palette now keeps the recipe's grain rather than opting out implicitly.
                assertEquals(if (raw) 1f else .5f, common.recipe.grainIntensity, 0f)
                assertEquals(palette.onSurface, common.palette.title)
                assertEquals(palette.onSurfaceVariant, common.palette.onSurfaceVariant)
                assertEquals(palette.scrim, common.palette.scrim)
                assertEquals(darkenEightPercent(palette.surfaceContainerLow), common.palette.surfaceContainerLow)
                assertEquals(common.palette.surfaceContainerLow, ReverbDesign.modalSurface(palette))
            }
        }
    }

    @Test fun sheetDarkeningPreservesAlphaAndOnlyUsesTheLowSurfaceRole() {
        val sheet = NativeActionSheetRecipe()
        for (alpha in listOf(0, 1, 64, 128, 254, 255)) {
            val palette = NativeColorScheme.fallback(false).copy(
                surfaceContainerLow = (alpha shl 24) or 0x00ff8040,
                surfaceContainerHigh = 0xff123456.toInt(),
            )
            // 255,128,64 at 92% truncate to 234,117,58; alpha is never blended.
            assertEquals((alpha shl 24) or 0x00ea753a, sheet.surfaceColor(palette))
        }
    }

    @Test fun rawPalettesKeepExplicitSheetRecipesAndGrainOptOut() {
        val recipe = NativeActionSheetRecipe(handleHeightDp = 5f, handleVerticalPaddingDp = 9f,
            grainSeed = 42, grainIntensity = .27f)
        val modals = NativeUiAppearance.Default.modals.copy(sheet = recipe)
        val raw = NativeColorScheme.fallback(false)
        assertEquals(recipe, modals.actionSheetStyle(density, resolver, raw).recipe)
        val flat = modals.copy(sheet = recipe.copy(grainSeed = null))
            .actionSheetStyle(density, resolver, raw)
        assertNull(flat.recipe.grainSeed)
        assertEquals(.27f, flat.recipe.grainIntensity, 0f)
        val themed = NativeDefaultColorScheme.resolve(false, 30) { 0xfff3edf4.toInt() }
        assertEquals(recipe.copy(grainIntensity = .5f),
            modals.actionSheetStyle(density, resolver, themed).recipe)
    }

    @Test fun floatingPanelsAndLinksUseTheirOwnRaisedRolesAcrossCommonAndReverb() {
        val floating = NativeUiAppearance.Default.modals.floating
        assertEquals(floating, ReverbDesign.appearance.modals.floating)
        for (dark in listOf(false, true)) {
            val palette = NativeDefaultColorScheme.resolve(dark, 30) { 0xfff3edf4.toInt() }
            val panelFill = floating.surfaceColor(palette)
            val linkFill = floating.link.fill(palette)
            assertEquals(if (dark) 0xff171717.toInt() else palette.surfaceContainerHigh, panelFill)
            assertEquals(if (dark) 0xff1a1a1a.toInt() else palette.surfaceContainerHighest, linkFill)
            assertEquals(linkFill, ReverbDesign.modalLinkSurface(palette))
            assertEquals(palette.onSurface, floating.link.content(palette))
        }
    }

    @Test fun amoledFloatingLiftCompositesOpacityWhileRawSurfacesKeepTheirAlpha() {
        val floating = NativeUiAppearance.Default.modals.floating
        val palette = NativeDefaultColorScheme.resolve(true, 30) { 0xfff3edf4.toInt() }.copy(
            surfaceContainerHigh = 0x40102030,
            surfaceContainerHighest = 0x80203040.toInt(),
        )
        // The 4% sRGB white overlay has alpha byte 10. Source-over raises opacity:
        // round(64 + 10 * (1 - 64/255)) = 71; round(128 + 10 * (1 - 128/255)) = 133.
        assertEquals(71, floating.surfaceColor(palette) ushr 24)
        assertEquals(133, floating.link.fill(palette) ushr 24)
        val raw = palette.copy(surfacePreset = null)
        assertEquals(raw.surfaceContainerHigh, floating.surfaceColor(raw))
        assertEquals(raw.surfaceContainerHighest, floating.link.fill(raw))
    }

    private fun darkenEightPercent(color: Int): Int {
        fun channel(shift: Int): Int = (((color ushr shift) and 255) * .92f).toInt()
        return (color and 0xff000000.toInt()) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }
}
