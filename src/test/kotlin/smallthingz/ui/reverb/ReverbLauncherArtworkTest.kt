package smallthingz.ui.reverb

import org.junit.Assert.*
import org.junit.Test

class ReverbLauncherArtworkTest {
    private val control = ReverbLauncherArtworkBounds(34f / 108f, 26f / 108f, 74f / 108f, 82f / 108f)

    @Test fun canonicalControlInkFillsHeightAndIsCenteredWithoutChangingAspect() {
        val fit = requireNotNull(launcherArtworkTransform(control, 108, 108, 48, 48))
        assertEquals(48f / 56f, fit.scale, .00001f)
        assertEquals(24f, 54f * fit.scale + fit.translateX, .00001f)
        assertEquals(24f, 54f * fit.scale + fit.translateY, .00001f)
        assertEquals(0f, 26f * fit.scale + fit.translateY, .00001f)
        assertEquals(48f, 82f * fit.scale + fit.translateY, .00001f)
        assertTrue("Canonical ink is larger than full-viewport FIT_CENTER", fit.scale > 48f / 108f)
    }

    @Test fun rectangularAndAsymmetricInkFitsAndCentersBothAxes() {
        val b = ReverbLauncherArtworkBounds(.1f, .2f, .9f, .6f)
        val fit = requireNotNull(launcherArtworkTransform(b, 200, 100, 48, 48))
        assertEquals(.3f, fit.scale, .00001f)
        assertEquals(24f, 100f * fit.scale + fit.translateX, .00001f)
        assertEquals(24f, 40f * fit.scale + fit.translateY, .00001f)
        assertEquals(0f, 20f * fit.scale + fit.translateX, .00001f)
        assertEquals(48f, 180f * fit.scale + fit.translateX, .00001f)
    }

    @Test fun densityAndSizeChangesDoNotRetainTheOldMatrix() {
        for (size in listOf(48, 126, 64, 48)) {
            val fit = requireNotNull(launcherArtworkTransform(control, 284, 284, size, size))
            assertEquals(size / 2f, 284f * .5f * fit.scale + fit.translateX, .0001f)
            assertEquals(size / 2f, 284f * .5f * fit.scale + fit.translateY, .0001f)
            assertEquals(size.toFloat(), (control.bottom - control.top) * 284f * fit.scale, .0001f)
        }
    }

    @Test fun fullBoundsReduceToOrdinaryUniformFit() {
        val fit = requireNotNull(launcherArtworkTransform(ReverbLauncherArtworkBounds(0f, 0f, 1f, 1f), 100, 200, 48, 48))
        assertEquals(.24f, fit.scale, .00001f)
        assertEquals(12f, fit.translateX, .00001f)
        assertEquals(0f, fit.translateY, .00001f)
    }

    @Test fun unlaidOutOrNonIntrinsicDrawablesHaveNoTransform() {
        assertNull(launcherArtworkTransform(control, 108, 108, 0, 48))
        assertNull(launcherArtworkTransform(control, 108, 108, 48, 0))
        assertNull(launcherArtworkTransform(control, -1, 108, 48, 48))
        assertNull(launcherArtworkTransform(ReverbLauncherArtworkBounds(0f, 0f, Float.MIN_VALUE, Float.MIN_VALUE), 1, 1, 48, 48))
    }

    @Test fun invalidAndNonfiniteBoundsAreRejected() {
        for (values in listOf(floatArrayOf(Float.NaN, 0f, 1f, 1f), floatArrayOf(0f, 0f, Float.POSITIVE_INFINITY, 1f),
            floatArrayOf(-.1f, 0f, 1f, 1f), floatArrayOf(0f, 0f, 1.1f, 1f),
            floatArrayOf(.5f, 0f, .5f, 1f), floatArrayOf(0f, .8f, 1f, .2f))) {
            try { ReverbLauncherArtworkBounds(values[0], values[1], values[2], values[3]); fail("Invalid bounds accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
