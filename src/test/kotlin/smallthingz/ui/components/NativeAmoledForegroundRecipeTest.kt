package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NativeAmoledForegroundRecipeTest {
    @Test fun exporterRecipeMatchesTheActualDefaultControlPalette() {
        val colors = NativeDefaultColorScheme.resolve(true, 28) { error("Unexpected platform color read") }
        assertEquals(colors.pageControls.raised, NativeDarkSurfaceRecipe.amoledRaisedColor)
        assertEquals(checkNotNull(colors.pageControls.surfacePreset).foregroundGrain,
            NativeDarkSurfaceRecipe.amoledForegroundGrain, 0f)
        assertEquals(NativeDarkSurfacePreset.Amoled.foregroundLightness,
            NativeDarkSurfaceRecipe.amoledForegroundLightness, 0f)
    }

    @Test fun extractingTheIdentityRecipeLeavesCustomAndLightSchemesOwnedByTheirResolver() {
        val light = NativeColorScheme.fallback(false)
        val dark = NativeColorScheme.fallback(true)
        val base = NativeColorSchemeResolver { isDark, _, _ -> if (isDark) dark else light }
        for (style in NativeDarkSurfaces.entries) {
            val resolver = base.withDarkSurfaces(style, foregroundLightness = 0f)
            assertSame(light, resolver.resolve(false, 28) { error("Unexpected resource read") })
            val result = resolver.resolve(true, 28) { error("Unexpected resource read") }
            assertEquals(0xff000000.toInt(), result.pageControls.raised)
            assertEquals(dark.primary, result.primary)
            assertEquals(dark.onSurface, result.onSurface)
        }
    }
}
