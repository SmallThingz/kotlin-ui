package smallthingz.ui.components

import org.junit.Assert.*
import org.junit.Test

class NativeBufferSegmentStyleTest {
    @Test fun offPageRecordingUsesPopupWithoutTakingSelectionOrSelectedInk() {
        val style = NativeBufferSegmentStyle()
        for (dark in listOf(false, true, false)) {
            val palette = NativeDefaultColorScheme.resolve(dark, 30) { 0xfff3edf4.toInt() }
            for (filled in listOf(false, true)) {
                val fill = style.fill(palette, selected = false, recording = true, filled = filled)
                val popup = NativeAnchoredMenuRecipe.Default.colors(palette)
                assertEquals(255, fill ushr 24)
                assertEquals(popup.raised, fill)
                assertEquals(popup.grainIntensity, palette.foregroundGrain, 0f)
                assertNotEquals(palette.primary, fill)
                assertEquals(palette.muted, style.ink(palette, false, true, true, filled))
                assertEquals(style.ink(palette, false, false, false, filled),
                    style.ink(palette, false, true, false, filled))
                assertEquals(0, style.fill(palette, false, false, filled))
            }
            assertEquals(palette.primary, style.fill(palette, true, true, false))
            assertEquals(palette.onPrimary, style.ink(palette, true, true, true, false))
            assertEquals(palette.tertiary, style.fill(palette, true, false, true))
            assertEquals(srgbColorWithAlpha(palette.primaryContainer, .74f), style.fill(palette, true, false, false))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun inactiveRecordingOpacityRejectsInvalidValues() {
        NativeBufferSegmentStyle(inactiveRecordingFillAlpha = Float.NaN)
    }
}
