package smallthingz.ui.animation

import org.junit.Assert.assertEquals
import org.junit.Test
import smallthingz.ui.animation.core.AnimationPlayTime

class AnimationPlayTimeTest {
    @Test fun constantScaleMatchesSourceIncludingFloatRounding() {
        for (scale in floatArrayOf(0.5f, 1f, 2f)) {
            val clock = AnimationPlayTime()
            for (delta in longArrayOf(0L, 16_666_667L, 33_333_335L, 99_999_999L)) {
                assertEquals((delta / scale).toLong(), clock.advance(800L + delta, scale))
            }
        }
    }

    @Test fun timestampsCanWrapThroughLongMinValue() {
        val clock = AnimationPlayTime()
        assertEquals(0L, clock.advance(Long.MAX_VALUE, 1f))
        assertEquals(1L, clock.advance(Long.MIN_VALUE, 1f))
        assertEquals(2L, clock.advance(Long.MIN_VALUE + 2, 2f))
    }

    @Test fun scaleChangesApplyOnlyToNewIntervals() {
        val clock = AnimationPlayTime()
        assertEquals(0L, clock.advance(10L, 1f))
        assertEquals(20L, clock.advance(20L, 0.5f))
        assertEquals(25L, clock.advance(30L, 2f))
        assertEquals(25L, clock.advance(30L, 1f))
        assertEquals(71L, clock.advance(40L, 0f, 71L))
        clock.reset()
        assertEquals(0L, clock.advance(40L, 2f))
        assertEquals(3L, clock.advance(46L, 2f))
    }
}
