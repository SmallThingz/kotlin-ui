package smallthingz.ui.animation.core

import smallthingz.ui.animation.CapturedFrameClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AnimationFailureCleanupTest {
    @Test(timeout = 10_000) fun firstCallbackFailureClearsPublishedScope() = stateFailure(1, false)
    @Test(timeout = 10_000) fun subsequentCallbackFailureClearsScope() = stateFailure(2, false)
    @Test(timeout = 10_000) fun firstFrameSpecFailureClearsScope() = stateFailure(1, true)
    @Test(timeout = 10_000) fun subsequentFrameSpecFailureClearsScope() = stateFailure(2, true)
    @Test(timeout = 10_000) fun firstCallbackCancellationClearsPublishedScope() = stateFailure(1, false, true)

    private fun stateFailure(frame: Int, specFailure: Boolean, cancellation: Boolean = false) = runBlocking {
        val clock = CapturedFrameClock()
        val state = AnimationState(0f)
        val expected = if (cancellation) CancellationException("callback") else IllegalStateException("sample")
        val animation = SampleAnimation(if (specFailure) frame + 1 else -1, expected)
        var callbacks = 0
        var captured: AnimationScope<Float, AnimationVector1D>? = null
        var failure: Throwable? = null
        val job = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            failure = runCatching {
                state.animate(animation) {
                    captured = this
                    callbacks++
                    if (!specFailure && callbacks == frame) throw expected
                }
            }.exceptionOrNull()
        }
        try {
            repeat(frame) { clock.frame(it * 10_000_000L); yield() }
            job.join()
            assertOriginalFailure(expected, failure)
            assertFalse(state.isRunning)
            assertNull(state.activeScope)
            captured?.let { assertFalse(it.isRunning) }
            // A later mutation can acquire ownership and finish normally.
            state.animate(SampleAnimation(), startTimeNanos = 7L) { cancelAnimation() }
            assertFalse(state.isRunning)
            assertNull(state.activeScope)
        } finally { job.cancelAndJoin() }
    }

    @Test(timeout = 10_000) fun cancellationOfCoroutineDuringFirstFrameClearsPublishedScope() = runBlocking {
        val clock = CapturedFrameClock()
        val state = AnimationState(0f)
        var captured: AnimationScope<Float, AnimationVector1D>? = null
        lateinit var job: Job
        job = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            state.animate(SampleAnimation()) {
                captured = this
                job.cancel()
            }
        }
        try {
            clock.frame(0L)
            job.join()
            assertFalse(state.isRunning)
            assertFalse(captured!!.isRunning)
            assertNull(state.activeScope)
            assertEquals(0, clock.pendingCount)
        } finally { job.cancelAndJoin() }
    }

    @Test(timeout = 10_000) fun olderCancellationAndFinallyCannotClearSameTimestampSuccessor() = runBlocking {
        val ownerScope = this
        val clock = CapturedFrameClock()
        val state = AnimationState(0f)
        var successor: Job? = null
        var successorScope: AnimationScope<Float, AnimationVector1D>? = null
        val older = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            state.animate(SampleAnimation(), startTimeNanos = 5L) {
                successor = ownerScope.launch(clock, start = CoroutineStart.UNDISPATCHED) {
                    state.animate(SampleAnimation(), startTimeNanos = 5L) { successorScope = this }
                }
                cancelAnimation()
            }
        }
        try {
            older.join()
            assertTrue(state.isRunning)
            assertSame(successorScope, state.activeScope)
            assertTrue(successorScope!!.isRunning)
            successor!!.cancelAndJoin()
            assertFalse(state.isRunning)
            assertNull(state.activeScope)
        } finally { older.cancelAndJoin(); successor?.cancelAndJoin() }
    }

    @Test(timeout = 10_000) fun animatableReplacementStartsAfterCancelledOwnerCleanup() = runBlocking {
        val clock = CapturedFrameClock()
        val animated = Animatable(0f)
        val first = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            animated.animateTo(10f, tween(100), initialVelocity = 4f)
        }
        var replacement: Job? = null
        val replacementStarted = CompletableDeferred<Unit>()
        try {
            clock.frame(0L); yield()
            replacement = launch(clock, start = CoroutineStart.UNDISPATCHED) {
                animated.animateTo(20f, tween(100), initialVelocity = 2f) {
                    replacementStarted.complete(Unit)
                }
            }
            first.join()
            // Joining the cancelled owner does not dispatch the successor resumed by mutex unlock.
            // Observe entry through its real first-frame callback before testing successor state.
            replacementStarted.await()
            assertTrue(first.isCompleted)
            assertTrue(animated.isRunning)
            assertEquals(20f, animated.targetValue, 0f)
            clock.frame(10_000_000L); yield()
            assertTrue(animated.internalState.isRunning)
            assertNotNull(animated.internalState.activeScope)
            replacement.cancelAndJoin()
            assertFalse(animated.isRunning)
            assertFalse(animated.internalState.isRunning)
            assertEquals(0f, animated.velocity, 0f)
        } finally { first.cancelAndJoin(); replacement?.cancelAndJoin() }
    }

    @Test(timeout = 10_000) fun animatableCallbackFailureResetsVelocityAndAllowsRetry() = animatableFailure(false)
    @Test(timeout = 10_000) fun animatableSpecFailureResetsVelocityAndAllowsRetry() = animatableFailure(true)

    private fun animatableFailure(specFailure: Boolean) = runBlocking {
        val clock = CapturedFrameClock()
        val animated = Animatable(0f)
        val expected = IllegalStateException("animation failure")
        var samples = 0
        val failingSpec = object : FiniteAnimationSpec<Float> {
            override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<Float, V>): VectorizedFiniteAnimationSpec<V> {
                val delegate = tween<Float>(100, easing = LinearEasing).vectorize(converter)
                return object : VectorizedFiniteAnimationSpec<V> by delegate {
                    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
                        if (++samples == 2) throw expected
                        return delegate.getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
                    }
                }
            }
        }
        var failure: Throwable? = null
        val job = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            failure = runCatching {
                animated.animateTo(10f, if (specFailure) failingSpec else tween(100), initialVelocity = 4f) {
                    if (!specFailure) throw expected
                }
            }.exceptionOrNull()
        }
        try {
            clock.frame(0L); yield(); job.join()
            assertOriginalFailure(expected, failure)
            assertFalse(animated.isRunning)
            assertFalse(animated.internalState.isRunning)
            assertNull(animated.internalState.activeScope)
            assertEquals(0f, animated.velocity, 0f)
            val retry = launch(clock, start = CoroutineStart.UNDISPATCHED) { animated.animateTo(3f, snap()) }
            clock.frame(1L); retry.join()
            assertEquals(3f, animated.value, 0f)
            assertFalse(animated.isRunning)
            assertFalse(animated.internalState.isRunning)
        } finally { job.cancelAndJoin() }
    }

    private fun assertOriginalFailure(expected: Throwable, actual: Throwable?) {
        var recovered = checkNotNull(actual) { "Expected the animation failure to propagate" }
        repeat(8) {
            assertEquals(expected.javaClass, recovered.javaClass)
            assertEquals(expected.message, recovered.message)
            if (recovered === expected) return
            // With -ea, kotlinx.coroutines 1.9.0 copies recoverable exceptions at suspend boundaries.
            // Accept only that marked wrapper and retain exact original identity through its cause.
            assertTrue("Unexpected exception wrapper: ${recovered.stackTrace.contentToString()}",
                recovered.stackTrace.any { frame -> frame.className == "_COROUTINE._BOUNDARY" })
            recovered = checkNotNull(recovered.cause) { "Recovery wrapper lost the original exception" }
        }
        fail("Exception cause chain did not preserve the original instance")
    }

    private class SampleAnimation(
        private val failOnSample: Int = -1,
        private val failure: Throwable = IllegalStateException("sample"),
    ) : Animation<Float, AnimationVector1D> {
        private var samples = 0
        override val durationNanos = 100_000_000L
        override val typeConverter = Float.VectorConverter
        override val targetValue = 10f
        override val isInfinite = false
        override fun getValueFromNanos(playTimeNanos: Long): Float {
            if (++samples == failOnSample) throw failure
            return playTimeNanos.toFloat()
        }
        override fun getVelocityVectorFromNanos(playTimeNanos: Long) = AnimationVector1D(3f)
    }
}
