package smallthingz.ui.components

import org.junit.Assert.*
import org.junit.Test
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb

class NativeAcknowledgedStatusTintTest {
    @Test fun semanticDefaultFollowsCopiedInkAndPresetRemoval() {
        val changedInk = 0xFF123456.toInt()
        val light = NativeColorScheme.fallback(false)
        assertNull(light.acknowledgedStatusTintOverride)
        assertEquals(light.ink, light.acknowledgedStatusTint)
        val copied = light.copy(onSurface = changedInk)
        assertEquals(changedInk, copied.acknowledgedStatusTint)
        val dark = copied.copy(surfacePreset = NativeDarkSurfacePreset.Amoled)
        assertNull("Default white is not materialized into an override", dark.acknowledgedStatusTintOverride)
        assertEquals(Color.White.toArgb(), dark.acknowledgedStatusTint)
        assertEquals(changedInk, dark.copy(surfacePreset = null).acknowledgedStatusTint)
        assertEquals(light.ink, light.acknowledgedStatusTint)
    }

    @Test fun explicitThemeTintSurvivesPresetWrappingAndResetsToCurrentDefault() {
        val explicit = 0xFFABCDEF.toInt()
        val changedInk = 0xFF123456.toInt()
        val customBase = NativeColorSchemeResolver { dark, _, _ ->
            NativeColorScheme.fallback(dark).copy(acknowledgedStatusTintOverride = explicit)
        }
        val wrapped = NativeDarkSurfacePreset.Amoled.colorScheme(customBase).resolve(true, 28) {
            error("Fallback palette must not read system resources")
        }
        assertSame(NativeDarkSurfacePreset.Amoled, wrapped.surfacePreset)
        assertEquals(explicit, wrapped.acknowledgedStatusTint)
        assertEquals(explicit, wrapped.copy(onSurface = changedInk).acknowledgedStatusTint)
        assertEquals(explicit, wrapped.copy(surfacePreset = null).acknowledgedStatusTint)
        assertEquals(Color.White.toArgb(), wrapped.copy(acknowledgedStatusTintOverride = null).acknowledgedStatusTint)
        assertEquals(changedInk, wrapped.copy(onSurface = changedInk, surfacePreset = null,
            acknowledgedStatusTintOverride = null).acknowledgedStatusTint)
        // Zero is an explicit transparent color, not a sentinel for the default.
        assertEquals(0, wrapped.copy(acknowledgedStatusTintOverride = 0).acknowledgedStatusTint)
    }
}
