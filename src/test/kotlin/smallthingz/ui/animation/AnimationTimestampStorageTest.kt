package smallthingz.ui.animation

import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.LinearEasing
import smallthingz.ui.animation.core.TwoWayConverter
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.tween
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationTimestampStorageTest {
    private val origins = longArrayOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)

    private fun animated() = AnimatedValue(0f, Float.VectorConverter,
        tween<Float>(100, easing = LinearEasing), { AnimationVector1D(0f) })

    private fun floatBits(value: Float) = value.toRawBits()

    @Test fun animatedFramesAndInterruptedRestartsAcceptEveryClockOrigin() {
        fun trace(origin: Long): List<Any> {
            val value = animated()
            val result = ArrayList<Any>()
            fun sample(offset: Long, scale: Float = 1f) {
                value.frame(origin + offset, scale)
                result.add(floatBits(value.value)); result.add(floatBits(value.target)); result.add(value.running)
            }
            value.target(1f, 1f)
            sample(0L); sample(23_000_000L)
            value.target(-.5f, 1f)
            sample(23_000_000L); sample(53_000_000L, .5f)
            value.stop()
            value.target(-.5f, 1f)
            sample(71_000_000L); sample(121_000_000L)
            value.snap(.25f)
            value.target(.75f, 1f)
            sample(122_000_000L); sample(123_000_000L, 0f)
            return result
        }
        val expected = trace(0L)
        for (origin in origins) assertEquals(expected, trace(origin))
    }

    @Test fun springFramesAndMomentumAcceptEveryClockOrigin() {
        fun trace(origin: Long): List<Any> {
            val value = SpringValue.float(0f)
            val result = ArrayList<Any>()
            fun sample(offset: Long, scale: Float = 1f) {
                value.frame(origin + offset, scale)
                result.add(floatBits(value.value)); result.add(floatBits(value.targetValue)); result.add(value.isRunning)
            }
            value.retarget(1f)
            sample(0L); sample(17_000_000L); sample(43_000_000L)
            value.retarget(-.25f)
            sample(43_000_000L); sample(76_000_000L, 2f)
            value.stop()
            value.retarget(.5f)
            sample(80_000_000L); sample(100_000_000L)
            value.snapTo(.125f)
            value.retarget(.75f)
            sample(120_000_000L); sample(121_000_000L, 0f)
            return result
        }
        val expected = trace(0L)
        for (origin in origins) assertEquals(expected, trace(origin))
    }

    @Test fun retargetingBeforeAnyFrameStillWaitsForItsFirstClock() {
        for (origin in origins) {
            val value = animated()
            value.target(1f, 1f)
            value.target(.5f, 1f)
            assertEquals(floatBits(0f), floatBits(value.value))
            assertTrue(value.running)
            value.frame(origin, 1f)
            assertEquals(floatBits(0f), floatBits(value.value))
            value.frame(origin + 50_000_000L, 1f)
            assertEquals(floatBits(.25f), floatBits(value.value))
            val spring = SpringValue.float(0f)
            spring.retarget(1f); spring.retarget(.5f)
            assertEquals(floatBits(0f), floatBits(spring.value))
            spring.frame(origin)
            assertEquals(floatBits(0f), floatBits(spring.value))
        }
    }

    @Test fun zeroScaleCompletionClearsClockOwnershipBeforeTheNextAnimation() {
        for (origin in origins) {
            val value = animated()
            value.target(1f, 1f); value.frame(origin, 0f)
            assertFalse(value.running)
            assertEquals(floatBits(1f), floatBits(value.value))
            value.target(0f, 1f)
            value.frame(origin + 5_000_000L, 1f)
            assertEquals(floatBits(1f), floatBits(value.value))
            value.frame(origin + 55_000_000L, 1f)
            assertEquals(floatBits(.5f), floatBits(value.value))
        }
    }

    @Test fun retargetKeepsTheFrameSnapshotWhenAConverterStopsItsOwner() {
        var stopDuringConversion = false
        lateinit var value: AnimatedValue<Float, AnimationVector1D>
        val converter = TwoWayConverter<Float, AnimationVector1D>(
            { AnimationVector1D(it) },
            {
                if (stopDuringConversion) {
                    stopDuringConversion = false
                    value.stop()
                }
                it.value
            },
        )
        value = AnimatedValue(0f, converter, tween<Float>(100, easing = LinearEasing),
            { AnimationVector1D(0f) })
        val control = animated()
        value.target(1f, 1f); control.target(1f, 1f)
        value.frame(-10_000_000L, 1f); control.frame(-10_000_000L, 1f)
        value.frame(20_000_000L, 1f); control.frame(20_000_000L, 1f)
        stopDuringConversion = true
        value.target(-.5f, 1f); control.target(-.5f, 1f)
        assertEquals(floatBits(control.value), floatBits(value.value))
        value.frame(70_000_000L, 1f); control.frame(70_000_000L, 1f)
        assertEquals(floatBits(control.value), floatBits(value.value))
        assertEquals(control.running, value.running)
    }
}
