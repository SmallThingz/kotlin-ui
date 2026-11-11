package smallthingz.ui.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.animation.view.AnimationScaleReader
import smallthingz.ui.interaction.view.RowStateAlpha

class AnimationScaleReaderTest {
    @Test fun readableSupplierKeepsAuthorityAndRemainsLive() {
        var scale = 2f
        var reads = 0
        val reader = AnimationScaleReader({ reads++; scale }, { false })
        for (value in listOf(2f, .5f, 0f, 1f)) {
            scale = value
            assertEquals(value, reader.read(), 0f)
        }
        assertEquals(4, reads)
    }

    @Test fun denialReadsOnceAcrossFramesAndRetriesOnNewTarget() {
        var reads = 0
        var denied = true
        val reader = AnimationScaleReader({
            reads++
            if (denied) throw SecurityException("settings denied")
            .5f
        }, { true })
        repeat(120) { assertEquals(1f, reader.read(), 0f) }
        assertEquals(1, reads)
        reader.retryOnNewTarget()
        assertEquals(1f, reader.read(), 0f)
        assertEquals(2, reads)
        denied = false
        assertEquals(1f, reader.read(), 0f)
        reader.retryOnNewTarget()
        assertEquals(.5f, reader.read(), 0f)
        assertEquals(3, reads)
    }

    @Test fun disabledPlatformWinsDuringDeniedReadWithoutRetryingSettings() {
        var enabled = true
        var reads = 0
        val reader = AnimationScaleReader({ reads++; throw SecurityException() }, { enabled })
        assertEquals(1f, reader.read(), 0f)
        enabled = false
        repeat(60) { assertEquals(0f, reader.read(), 0f) }
        enabled = true
        assertEquals(1f, reader.read(), 0f)
        assertEquals(1, reads)
    }

    @Test fun nonSecurityFailurePropagatesWithoutBecomingCachedDenial() {
        val failure = IllegalStateException("broken supplier")
        var reads = 0
        val reader = AnimationScaleReader({ reads++; throw failure }, { true })
        repeat(2) {
            try { reader.read(); throw AssertionError("Expected original failure") }
            catch (actual: IllegalStateException) { assertSame(failure, actual) }
        }
        assertEquals(2, reads)
    }

    @Test fun denialAfterReadableScaleUsesFallbackThenRecovers() {
        var denied = false
        val reader = AnimationScaleReader({
            if (denied) throw SecurityException()
            2f
        }, { true })
        assertEquals(2f, reader.read(), 0f)
        denied = true
        assertEquals(1f, reader.read(), 0f)
        denied = false
        assertEquals(1f, reader.read(), 0f)
        reader.retryOnNewTarget()
        assertEquals(2f, reader.read(), 0f)
    }

    @Test fun deniedColorTransitionSettlesImmediatelyWhenAnimationsDisable() {
        var enabled = true
        val reader = AnimationScaleReader({ throw SecurityException() }, { enabled })
        val state = ColorTransition(intArrayOf(0xff000000.toInt()))
        val white = 0xffffffff.toInt()
        reader.retryOnNewTarget()
        state.retarget(intArrayOf(white), 0L, reader.read())
        state.frame(40_000_000L, reader.read())
        assertTrue(state.isInterpolating)
        enabled = false
        state.frame(50_000_000L, reader.read())
        assertFalse(state.running)
        assertEquals(white, state.color(0))
    }

    @Test fun deniedHoverStateSettlesAndNextInteractionCanReadZero() {
        var enabled = true
        var denied = true
        var reads = 0
        val reader = AnimationScaleReader({
            reads++
            if (denied) throw SecurityException()
            0f
        }, { enabled })
        val state = RowStateAlpha()
        reader.retryOnNewTarget()
        state.animateTo(.10f, 45)
        state.frame(0L, reader.read())
        state.frame(10_000_000L, reader.read())
        assertTrue(state.running)
        enabled = false
        state.frame(11_000_000L, reader.read())
        assertFalse(state.running)
        assertEquals(.10f, state.value, 0f)
        assertEquals(1, reads)
        denied = false
        enabled = true
        reader.retryOnNewTarget()
        state.animateTo(0f, 15)
        state.frame(12_000_000L, reader.read())
        assertFalse(state.running)
        assertEquals(0f, state.value, 0f)
        assertEquals(2, reads)
    }
}
