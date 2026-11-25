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

    @Test fun flatTranslucentRangeEnclosesFusedAndPremultipliedIntegerSrcOver() {
        for (alpha in 0..255) for (source in 0..255) {
            val fill = (alpha shl 24) or (source * 0x010101)
            // Independent channel endpoints and interior values include black/white exactness.
            for (background in listOf(0, 1, 17, 127, 128, 254, 255)) {
                val surface = textSurfaceRange(-0x1000000 or (background * 0x010101))
                val range = noiseSurfaceRangeOver(surface, fill, 0)
                val low = (range ushr 32).toInt() and 255
                val high = range.toInt() and 255
                val fused = (source * alpha + background * (255 - alpha) + 127) / 255
                // SkPremultiplyARGBInline followed by SkPMSrcOver's 256-scaled destination.
                val premultiplied = (source * alpha + 127) / 255
                val integerSrcOver = (premultiplied + ((background * (256 - alpha)) ushr 8)).coerceAtMost(255)
                assertTrue("Fused alpha=$alpha source=$source background=$background",
                    fused in low..high)
                assertTrue("Premultiplied alpha=$alpha source=$source background=$background",
                    integerSrcOver in low..high)
                if (alpha == 0 || alpha == 255) assertEquals(low, high)
            }
        }
    }

    @Test fun lightIdleFillBoundsObservedHostPixelAndModeledResultsBeforeNoise() {
        val field = 0xfff7f2fa.toInt()
        val fill = 0xbdeaddff.toInt()
        val range = noiseSurfaceRangeOver(textSurfaceRange(field), fill, 0)
        // Modeled arithmetic routes; neither identifies the native host's exact raster path.
        val fused = 0xffede2fe.toInt()
        val premultiplied = 0xffede3fe.toInt()
        // Baseline diagnostic 58240 / grain-diagnostic-1795638651124424252, SDK35 native:
        // flat rebind, idle selected slot 0, local (36, 2); actual matched the Canvas oracle.
        val observedHostPixel = 0xffede2fd.toInt()
        for (pixel in listOf(fused, premultiplied, observedHostPixel)) for (shift in 0..16 step 8) {
            val low = (range ushr 32).toInt() ushr shift and 255
            val high = range.toInt() ushr shift and 255
            assertTrue((pixel ushr shift and 255) in low..high)
        }
        assertEquals(0xffece1fd.toInt(), (range ushr 32).toInt())
        assertEquals(0xffeee3fe.toInt(), range.toInt())
    }

}
