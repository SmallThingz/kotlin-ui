package smallthingz.ui.animation.core

import org.junit.Assert.*
import org.junit.Test

class AnimationNumericContractsTest {
    @Test fun vectorEqualityIsReflexiveAndSignedZerosHaveEqualHashes() {
        val pairs = listOf(
            AnimationVector1D(0f) to AnimationVector1D(-0f),
            AnimationVector2D(0f, -0f) to AnimationVector2D(-0f, 0f),
            AnimationVector3D(0f, -0f, 1f) to AnimationVector3D(-0f, 0f, 1f),
            AnimationVector4D(0f, -0f, 1f, 2f) to AnimationVector4D(-0f, 0f, 1f, 2f),
        )
        for ((left, right) in pairs) {
            assertEquals(left, right)
            assertEquals(left.hashCode(), right.hashCode())
            assertTrue(hashSetOf(left).contains(right))
        }
        val vectors = listOf(AnimationVector1D(Float.NaN), AnimationVector2D(Float.NaN, 0f),
            AnimationVector3D(0f, Float.NaN, 0f), AnimationVector4D(0f, 0f, Float.NaN, 0f))
        vectors.forEach { assertTrue(it.equals(it)) }
    }

    @Test fun specsAndEasingsRespectSignedZeroHashContract() {
        val a = SpringSpec<Float>(dampingRatio = 0f)
        val b = SpringSpec<Float>(dampingRatio = -0f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val nan = SpringSpec<Float>(dampingRatio = Float.NaN)
        assertTrue(nan.equals(nan))
        val c = CubicBezierEasing(0f, -0f, 1f, 1f)
        val d = CubicBezierEasing(-0f, 0f, 1f, 1f)
        assertEquals(c, d)
        assertEquals(c.hashCode(), d.hashCode())
    }

    @Test fun durationAndRepeatDurationAddInLongArithmetic() {
        val spec = VectorizedTweenSpec<AnimationVector1D>(Int.MAX_VALUE, 1, LinearEasing)
        val expected = (Int.MAX_VALUE.toLong() + 1L) * MillisToNanos
        val zero = AnimationVector1D(0f)
        assertEquals(expected, spec.getDurationNanos(zero, zero, zero))
        val repeated = VectorizedInfiniteRepeatableSpec(spec)
        assertEquals(expected, repeated.durationNanos)
        assertEquals(0f, repeated.getValueFromNanos(expected, zero, AnimationVector1D(1f), zero).value, 0f)
    }

    @Test fun directInfiniteRepeatRejectsZeroDurationTweenAndSnap() {
        val animations = listOf<VectorizedDurationBasedAnimationSpec<AnimationVector1D>>(
            VectorizedTweenSpec(durationMillis = 0),
            VectorizedSnapSpec(delayMillis = 0),
        )
        for (animation in animations) {
            try {
                VectorizedInfiniteRepeatableSpec(animation)
                fail("Accepted a zero-duration infinite animation")
            } catch (expected: IllegalArgumentException) {
                assertEquals(
                    "Animation to be infinitely repeated cannot have a 0-duration",
                    expected.message,
                )
            }
        }
    }

    @Test fun genericStateCopyOwnsItsVelocity() {
        data class Value(val value: Float)
        val converter = TwoWayConverter<Value, AnimationVector1D>({ AnimationVector1D(it.value) }, { Value(it.value) })
        val source = AnimationState(converter, Value(0f), AnimationVector1D(3f))
        val copy = source.copy()
        assertNotSame(source.velocityVector, copy.velocityVector)
        source.velocityVector.value = 8f
        assertEquals(3f, copy.velocityVector.value, 0f)
    }

    @Test fun targetAnimationDoesNotConvertInitialValueTwiceForZeroVelocity() {
        var conversions = 0
        val converter = TwoWayConverter<Float, AnimationVector1D>(
            { conversions++; AnimationVector1D(it) }, { it.value },
        )
        val animation = TargetBasedAnimation(tween<Float>(100), converter, 2f, 4f)
        assertEquals(2, conversions)
        assertEquals(2f, animation.getValueFromNanos(0), 0f)
    }
    @Test fun cubicBoundsReadOnlyTheirRequestedRootSlice() {
        val expected = computeCubicVerticalBounds(0f, 2f, -1f, 1f, FloatArray(5), 0)
        val roots = FloatArray(9) { Float.NaN }
        val actual = computeCubicVerticalBounds(0f, 2f, -1f, 1f, roots, 2)
        assertEquals(expected, actual)
        assertTrue(roots[0].isNaN())
        assertTrue(roots[1].isNaN())
        assertTrue(roots[7].isNaN())
        assertTrue(roots[8].isNaN())
    }

}
