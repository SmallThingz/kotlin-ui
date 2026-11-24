package smallthingz.ui.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.controls.textSurfaceRange

class NoiseSurfaceRangeTest {
    @Test fun halfStrengthBoundsEncloseEveryReleasedGrainAlphaAndChannel() {
        for (strength in listOf(0, 128, 255)) for (channel in 0..255) {
            val fill = -0x1000000 or (channel * 0x010101)
            val range = noiseSurfaceRange(fill, strength)
            val low = (range ushr 32).toInt() and 255
            val high = range.toInt() and 255
            for (alpha in 5..17) for (grain in listOf(0, 255)) {
                val effective = alpha * strength / 255.0
                val painted = (channel * (255 - effective) + grain * effective) / 255
                assertTrue(painted >= low && painted <= high)
            }
        }
        assertEquals(textSurfaceRange(-0x1000000, 0xff090909.toInt()), noiseSurfaceRange(-0x1000000, 128))
    }

    @Test fun transparentPaintIsUnknownAndDisabledNoiseIsExactlyFlat() {
        assertEquals(0L, noiseSurfaceRange(0x80112233.toInt(), 128))
        assertEquals(textSurfaceRange(0xff123456.toInt()), noiseSurfaceRange(0xff123456.toInt(), 0))
        assertEquals(0L, noiseSurfaceRangeOver(0L, -1, 128))
    }

    @Test fun transparentNestedFillDoesNotAddGrainAndOpaqueFillReplacesUnderlay() {
        val underlay = noiseSurfaceRange(0xff101010.toInt(), 128)
        assertEquals(underlay, noiseSurfaceRangeOver(underlay, 0, 0))
        assertEquals(noiseSurfaceRange(0xffabcdef.toInt(), 128),
            noiseSurfaceRangeOver(underlay, 0xffabcdef.toInt(), 128))
    }
}
