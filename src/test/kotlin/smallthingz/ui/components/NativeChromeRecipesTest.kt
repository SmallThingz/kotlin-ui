package smallthingz.ui.components

import androidx.core.graphics.Insets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeChromeRecipesTest {
    @Test fun compactHeightUsesTheSameDensityRoundingForBothToolbarModes() {
        val released = NativeToolbarStyle.Default
        val alternate = released.copy(compactHeightDp = 58f)
        assertEquals(173, released.compactHeightPx(2.625f))
        assertEquals(152, alternate.compactHeightPx(2.625f))
    }

    @Test fun customCenteredTitleInsetConstrainsPlacementBeforeRtlMirroring() {
        val style = NativeToolbarStyle.Default.copy(centeredTitleStartDp = 44f)
        val ltr = centeredTopBarPlacement(301, 64, 8, 48, 240, 31, 4, 48,
            1f, false, style.centeredTitleStartDp)
        val rtl = centeredTopBarPlacement(301, 64, 8, 48, 240, 31, 4, 48,
            1f, true, style.centeredTitleStartDp)
        assertEquals(44, ltr.titleX)
        assertEquals(17, rtl.titleX)
        assertEquals(ltr.titleY, rtl.titleY)
    }

    @Test fun leadingTitleKeepsFractionalInsetsAndVisualActionOffset() {
        val style = NativeToolbarStyle.Default
        val metrics = style.leadingTitleMetrics(1.31f)
        val insets = Insets.of(3, 10, 5, 2)
        assertEquals(86, metrics.height)
        assertEquals(60, metrics.actionSize)
        assertEquals(24f, style.leadingTitleGlyphDp, 0f)
        assertEquals(285, metrics.titleWidth(400, insets))
        val ltr = metrics.place(400, insets, false, 60, 60, 100, 32, ::placement)
        val rtl = metrics.place(400, insets, true, 60, 60, 100, 32, ::placement)
        assertEquals(listOf(22, 24, 91, 37), ltr)
        assertEquals(listOf(318, 24, 207, 37), rtl)
    }

    @Test fun leadingTitleFollowsTheAlternateToolbarRecipe() {
        val original = NativeUiAppearance.Default
        val style = original.toolbars.copy(compactHeightDp = 60f, compactEdgeInsetDp = 10f,
            action = original.toolbars.action.copy(sizeDp = 50f), leadingTitleGapDp = 12f)
        val metrics = original.copy(toolbars = style).toolbars.leadingTitleMetrics(1f)
        assertEquals(60, metrics.height)
        assertEquals(218, metrics.titleWidth(300, Insets.NONE))
        assertEquals(listOf(10, 5, 72, 14), metrics.place(300, Insets.NONE, false, 50, 50, 100, 32, ::placement))
    }

    private fun placement(actionX: Int, actionY: Int, titleX: Int, titleY: Int) =
        listOf(actionX, actionY, titleX, titleY)

    @Test fun floatingClipLimitsConfiguredRadiusByWidthAndHeight() {
        val recipe = NativeFloatingPanelRecipe.Default.copy(cornerRadiusDp = 40f)
        assertEquals(80f, recipe.cornerRadiusPx(2f, 300, 130), 0f)
        assertEquals(75f, recipe.cornerRadiusPx(2f, 150, 130), 0f)
        assertEquals(50f, recipe.cornerRadiusPx(2f, 300, 50), 0f)
    }

    @Test fun floatingLinkColorKeepsSurfaceRoleWhenColorsAlias() {
        val style = NativeFloatingPanelRecipe.Default.link
        val palette = NativeColorScheme.fallback(false)
        val variantAlias = palette.copy(surfaceContainerHighest = palette.surfaceVariant)
        assertEquals(variantAlias.onSurface, style.content(variantAlias))
        val primaryAlias = variantAlias.copy(primary = palette.surfaceVariant)
        assertEquals(primaryAlias.onSurface, style.content(primaryAlias))
    }

    @Test fun permissionDialogRecipeRetainsWindowOverridesAndRejectsInvalidGeometry() {
        val defaults = NativeUiAppearance.Default
        val base = defaults.modals.permissionDialog
        assertEquals(360, base.maxWidthDp)
        assertEquals(56, base.actionMinimumHeightDp)
        assertNull(base.dimAmount)
        val alternate = base.copy(noiseSeed = null, dimAmount = 0f, windowAnimations = 0)
        val changed = defaults.copy(modals = defaults.modals.copy(permissionDialog = alternate))
        assertSame(alternate, changed.copy().modals.permissionDialog)
        assertEquals(1f, checkNotNull(base.copy(dimAmount = 1f).dimAmount), 0f)
        for (invalid in floatArrayOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { base.copy(dimAmount = invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { base.copy(maxWidthDp = 0) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(actionMinimumHeightDp = 47) }
    }

    @Test fun layeredMarkCacheKeepsTheFractionalFillBoundsRounding() {
        val bounds = nativeLayeredMarkBounds(108f, 1f, 55, 10)
        assertEquals(55.000004f, bounds.drawWidth, 0f)
        assertEquals(56, bounds.cacheWidth)
        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
    }
}
