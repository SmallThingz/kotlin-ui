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

    @Test fun floatingPanelsUseWindowBackgroundWhileLinksKeepTheirRaisedRole() {
        val floating = NativeUiAppearance.Default.modals.floating
        assertEquals(floating, ReverbDesign.appearance.modals.floating)
        for (dark in listOf(false, true)) {
            val palette = NativeDefaultColorScheme.resolve(dark, 30) { 0xfff3edf4.toInt() }
            val panelFill = floating.surfaceColor(palette)
            val linkFill = floating.link.fill(palette)
            assertEquals(palette.background, panelFill)
            assertEquals(if (dark) 0xff1a1a1a.toInt() else palette.surfaceContainerHighest, linkFill)
            assertEquals(linkFill, ReverbDesign.modalLinkSurface(palette))
            assertEquals(palette.onSurface, floating.link.content(palette))
        }
    }

    @Test fun floatingBackgroundPreservesAlphaWhileAmoledLinksKeepTheirLift() {
        val floating = NativeUiAppearance.Default.modals.floating
        val palette = NativeDefaultColorScheme.resolve(true, 30) { 0xfff3edf4.toInt() }.copy(
            background = 0x30102030,
            surfaceContainerHigh = 0x40102030,
            surfaceContainerHighest = 0x80203040.toInt(),
        )
        // The panel preserves background alpha48. Links retain their own 4% white lift:
        // round(128 + 10 * (1 - 128/255)) = 133.
        assertEquals(48, floating.surfaceColor(palette) ushr 24)
        assertEquals(133, floating.link.fill(palette) ushr 24)
        val raw = palette.copy(surfacePreset = null)
        assertEquals(raw.background, floating.surfaceColor(raw))
        assertEquals(raw.surfaceContainerHighest, floating.link.fill(raw))
    }

}
