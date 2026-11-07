package smallthingz.ui.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothProgressTest {
    @Test fun successivePublicationsRemainSmoothMonotonicAndBelowCompletion() {
        val progress = smoothProgressSpring()
        var previous = 0f
        for (frame in 0..180) {
            if (frame % 12 == 0) progress.retarget((frame / 180f).coerceAtMost(.99f))
            progress.frame(frame * 16_666_667L)
            assertTrue(progress.value >= previous - .000001f)
            assertTrue(progress.value < 1f)
            previous = progress.value
        }
    }

    @Test fun disabledMotionUsesActualTargetWithoutInventingCompletion() {
        val progress = smoothProgressSpring()
        progress.retarget(.73f, 0f)
        progress.frame(1L, 0f)
        assertEquals(.73f, progress.value, 0f)
    }

    @Test fun hiddenSnapRetiresMotionAndNewIdentityStartsAtZero() {
        val progress = smoothProgressSpring()
        progress.retarget(.8f)
        progress.frame(1L)
        progress.frame(100_000_001L)
        progress.stop()
        progress.snapTo(.8f)
        assertTrue(!progress.isRunning)
        progress.snapTo(0f)
        assertEquals(0f, progress.value, 0f)
    }
}
