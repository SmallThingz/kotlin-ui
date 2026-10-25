package smallthingz.ui.animation

import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.AnimationVector2D
import smallthingz.ui.animation.core.LinearEasing
import smallthingz.ui.animation.core.SpringSpec
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.spring
import smallthingz.ui.animation.core.tween
import smallthingz.ui.graphics.Color
import smallthingz.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class AnimatedValueTest {
    @Test fun stoppedUnfinishedTweenCanResumeTheSameTarget() {
        val value = AnimatedValue(0f, Float.VectorConverter, tween<Float>(100, easing = LinearEasing),
            { AnimationVector1D(0f) })
        value.target(1f, 1f)
        value.frame(0L, 1f)
        value.frame(40_000_000L, 1f)
        assertEquals(.4f, value.value, 0f)
        value.stop()
        value.target(1f, 1f)
        assertTrue(value.running)
        assertEquals(.4f, value.value, 0f)
        value.frame(80_000_000L, 1f)
        value.frame(180_000_000L, 1f)
        assertEquals(1f, value.value, 0f)
        assertFalse(value.running)
    }

    @Test fun springEqualTargetDoesNotRestartAStoppedOwner() {
        val value = SpringValue.float(0f)
        value.retarget(1f)
        value.frame(0L)
        value.frame(40_000_000L)
        val delivered = value.value
        value.stop()
        assertFalse(value.retarget(1f))
        value.frame(1_000_000_000L)
        assertEquals(delivered, value.value, 0f)
        assertFalse(value.isRunning)
    }

    @Test fun typedSizeInterruptionStartsFromTheDeliveredPose() {
        val value = AnimatedValue(IntSize(20, 40), IntSize.VectorConverter,
            tween<IntSize>(100, easing = LinearEasing), { AnimationVector2D(0f, 0f) })
        value.target(IntSize(81, 123), 1f)
        value.frame(0L, 1f)
        value.frame(37_000_000L, 1f)
        val delivered = value.value
        value.target(IntSize(1, 5), 1f)
        assertEquals(delivered, value.value)
        value.frame(37_000_000L, 1f)
        assertEquals(delivered, value.value)
        value.frame(137_000_000L, 1f)
        assertEquals(IntSize(1, 5), value.value)
        assertFalse(value.running)
    }

    @Test fun interruptedTransitionUsesSpringAndRawMomentum() {
        val converter = Float.VectorConverter
        val tween = tween<Float>(100, easing = LinearEasing)
        val interruption = spring<Float>(visibilityThreshold = .01f)
        val value = TransitionValue(0f, converter, interruption, { AnimationVector1D(0f) })
        value.target(1f, tween, 0L)
        value.frame(40_000_000L, false)
        val original = TargetBasedAnimation(tween, converter, 0f, 1f)
        val delivered = original.getValueFromNanos(40_000_000L)
        val velocity = original.getVelocityVectorFromNanos(40_000_000L)
        val expected = TargetBasedAnimation(interruption, converter, delivered, -1f,
            initialVelocityVector = velocity)
        value.target(-1f, tween, 40_000_000L)
        value.frame(57_000_000L, false)
        assertEquals(expected.getValueFromNanos(17_000_000L), value.value)
        assertEquals(-1f, value.target, 0f)
    }

    @Test fun reducedMotionPublishesExactTypedTargets() {
        val spring = SpringValue.color(Color(0xff2848a0))
        val target = Color(0xffa08028)
        spring.retarget(target)
        spring.frame(32L, 0f)
        assertEquals(target, spring.value)
        assertFalse(spring.isRunning)
        val animated = AnimatedValue(1f, Float.VectorConverter, tween<Float>(100), { AnimationVector1D(0f) })
        animated.target(.25f, 0f)
        animated.frame(99L, 0f)
        assertEquals(.25f, animated.value, 0f)
        assertFalse(animated.running)
        val transition = TransitionValue(0f, Float.VectorConverter, SpringSpec<Float>(),
            { AnimationVector1D(0f) })
        transition.target(2f, tween<Float>(100), 500L)
        transition.frame(0L, true)
        assertEquals(2f, transition.value, 0f)
        assertFalse(transition.running)
    }
}
