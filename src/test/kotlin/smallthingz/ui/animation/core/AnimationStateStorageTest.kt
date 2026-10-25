package smallthingz.ui.animation.core

import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationStateStorageTest {
    private data class Value(val number: Float)
    private val converter = TwoWayConverter<Value, AnimationVector1D>(
        { AnimationVector1D(it.number) }, { Value(it.value) },
    )

    @Test fun equalAssignmentsKeepTheExistingValueReference() {
        val first = Value(1f)
        val equal = Value(1f)
        val different = Value(2f)
        val state = AnimationState(converter, first)
        val scope = AnimationScope(first, converter, AnimationVector1D(0f), 0L, different, 0L, true) {}
        state.value = equal
        scope.value = equal
        assertSame(first, state.value)
        assertSame(first, scope.value)
        state.value = different
        scope.value = different
        assertSame(different, state.value)
        assertSame(different, scope.value)
        assertSame(different, scope.toAnimationState().value)
    }

    @Test fun genericFloatEqualityPreservesSignedZeroAndNanPayloadBehavior() {
        val firstNan = Float.fromBits(0x7fc00001)
        val secondNan = Float.fromBits(0x7fc00002)
        val state = AnimationState(0f)
        val scope = AnimationScope(0f, Float.VectorConverter, AnimationVector1D(0f), 0L, 1f, 0L, true) {}
        for (next in listOf(-0f, 0f, firstNan, secondNan)) {
            state.value = next
            scope.value = next
            // Generic boxed Float equality distinguishes signed zero but treats NaNs as equal.
            val expected = if (next.isNaN()) firstNan.toRawBits() else next.toRawBits()
            assertEquals(expected, state.value.toRawBits())
            assertEquals(expected, scope.value.toRawBits())
        }
    }

    @Test fun snapRetainsEqualValueAndTargetReferences() = runBlocking {
        val first = Value(1f)
        val animated = Animatable(first, converter)
        val exposed = animated.asState()
        animated.snapTo(Value(1f))
        assertSame(first, animated.value)
        assertSame(first, animated.targetValue)
        val next = Value(2f)
        animated.snapTo(next)
        assertSame(next, animated.value)
        assertSame(next, animated.targetValue)
        assertSame(exposed, animated.asState())
        assertSame(next, exposed.value)
        assertFalse(animated.isRunning)
    }

    @Test fun cancellationClearsRunningStateAndAllowsANewMutation() = runBlocking {
        val awaitingFrame = CompletableDeferred<Unit>()
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                awaitingFrame.complete(Unit)
                awaitCancellation()
            }
        }
        val animated = Animatable(0f)
        val animation = launch(clock, start = CoroutineStart.UNDISPATCHED) { animated.animateTo(10f) }
        try {
            awaitingFrame.await()
            assertTrue(animated.isRunning)
            assertEquals(10f, animated.targetValue, 0f)
            animation.cancelAndJoin()
            assertFalse(animated.isRunning)
            assertEquals(0f, animated.velocity, 0f)
            animated.snapTo(3f)
            assertEquals(3f, animated.value, 0f)
            assertEquals(3f, animated.targetValue, 0f)
        } finally {
            animation.cancelAndJoin()
        }
    }
}
