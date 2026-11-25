package smallthingz.ui.components

import org.junit.Assert.*
import org.junit.Test

class NativeBufferSegmentStyleTest {
    @Test fun inactiveCaptureHasSubtleAccentWithoutSelectedInkOrFilledState() {
        val style = NativeBufferSegmentStyle()
        for (dark in listOf(false, true, false)) {
            val palette = NativeDarkSurfacePreset.Amoled.colorScheme().resolve(dark, 30) { error("No resources") }
            for (filled in listOf(false, true)) {
                val fill = style.fill(palette, selected = false, recording = true, filled = filled)
                assertEquals(31, fill ushr 24)
                assertEquals(palette.primary and 0xffffff, fill and 0xffffff)
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
