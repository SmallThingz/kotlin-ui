package smallthingz.ui.scroll

import smallthingz.ui.MotionDurationScale
import smallthingz.ui.animation.core.FloatDecayAnimation
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.exponentialDecay
import smallthingz.ui.animation.core.spring
import smallthingz.ui.geometry.Offset
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Proposal tests only: no compilation, execution or actual frame-phase qualification credited. */
class NativeVerticalScrollOwnerTest {
    @Test fun semanticsInitializesScaleBeforeAwaitAndSamplesAgainOnTheFirstFrame() = boundedTest {
        val fixture = Fixture()
        var consumed = 0f
        val scale = TestMotionScale(1f)
        val scroll = launch(scale, start = CoroutineStart.UNDISPATCHED) {
            consumed = fixture.owner.semanticsScrollBy(80f)
        }
        assertTrue(fixture.state.isScrollInProgress)
        assertEquals(0, fixture.state.value)
        fixture.frames.frame(1_000_000_000L) {
            assertEquals(1, scale.reads)
            scale.scaleFactor = 0f
        }
        scroll.join()
        assertEquals(2, scale.reads)
        assertEquals(80f, consumed, .001f)
        assertEquals(80, fixture.state.value)
        assertEquals(1, fixture.frames.delivered)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun semanticsReadsChangedScaleWhenThePendingFrameArrives() = boundedTest {
        val fixture = Fixture()
        val scale = TestMotionScale(1f)
        val scroll = launch(scale, start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.semanticsScrollBy(80f)
        }
        fixture.frames.frame(1_000_000_000L)
        fixture.frames.frame(1_016_666_667L) { scale.scaleFactor = 2f }
        val spring = TargetBasedAnimation(spring<Float>(), Float.VectorConverter, 0f, 80f)
        val halfTimeNanos = 16_666_667L / 2
        assertEquals(spring.getValueFromNanos(halfTimeNanos).roundToInt(), fixture.state.value)
        assertTrue(fixture.state.isScrollInProgress)

        fixture.frames.frame(1_033_333_334L) { scale.scaleFactor = 0f }
        scroll.join()
        assertEquals(80, fixture.state.value)
        assertEquals(3, fixture.frames.delivered)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun zeroInheritedScaleLeavesDirectDragImmediate() = boundedTest {
        val fixture = Fixture()
        launch(TestMotionScale(0f), start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.drag {
                assertTrue(fixture.state.isScrollInProgress)
                assertEquals(-24f, scrollBy(-24f), 0f)
                assertEquals(24, fixture.state.value)
            }
        }.join()
        assertEquals(0, fixture.frames.delivered)
        assertTrue(fixture.preVelocities.isEmpty())
        assertTrue(fixture.postVelocities.isEmpty())
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun zeroScaleAtAnEdgePassesTheWholeVelocityToNestedPostFling() = boundedTest {
        val fixture = Fixture()
        val fling = launch(TestMotionScale(0f), start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.onScrollStopped(1800f)
        }
        fixture.frames.frame(0L)
        fling.join()
        assertEquals(0, fixture.state.value)
        val (consumed, available) = fixture.postVelocities.single()
        assertEquals(0f, consumed, 0f)
        assertEquals(1800f, available, 0f)
        assertFalse(fixture.owner.isFlinging)
    }

    @Test fun flingInitializesScaleBeforeAwaitAndSamplesAgainOnTheFirstFrame() = boundedTest {
        val fixture = Fixture()
        val scale = TestMotionScale(1f)
        val fling = launch(scale, start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.onScrollStopped(-1000f)
        }
        assertTrue(fixture.owner.isFlinging)
        assertEquals(0, fixture.state.value)
        assertTrue(fixture.postVelocities.isEmpty())
        fixture.frames.frame(1_000_000_000L) {
            assertEquals(1, scale.reads)
            scale.scaleFactor = 0f
        }
        fling.join()
        assertEquals(2, scale.reads)
        assertFalse(fling.isCancelled)
        val decay = FloatDecayAnimation(exponentialDecay(), 1000f)
        assertEquals(decay.getValueFromNanos(Long.MAX_VALUE).roundToInt(), fixture.state.value)
        assertEquals(listOf(-1000f), fixture.preVelocities)
        val (consumed, remaining) = fixture.postVelocities.single()
        assertEquals(-decay.getVelocityFromNanos(Long.MAX_VALUE), remaining, .001f)
        assertEquals(-1000f, consumed + remaining, .001f)
        assertEquals(1, fixture.frames.delivered)
        assertFalse(fixture.owner.isFlinging)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun flingSamplesChangedInheritedScaleAndKeepsItForNestedCallbacks() = boundedTest {
        val baseline = Fixture()
        val baselineFling = launch(start = CoroutineStart.UNDISPATCHED) {
            baseline.owner.onScrollStopped(1000f)
        }
        baseline.frames.frame(0L)
        baseline.frames.frame(4_000_000L)
        baselineFling.join()

        val fixture = Fixture()
        val scale = TestMotionScale(2f)
        fixture.pre = {
            assertSame(scale, currentCoroutineContext()[MotionDurationScale])
            assertEquals(2f, scale.scaleFactor, 0f)
            assertFalse(fixture.state.isScrollInProgress)
            assertFalse(fixture.owner.isFlinging)
            0f
        }
        fixture.post = { _, _ ->
            assertSame(scale, currentCoroutineContext()[MotionDurationScale])
            assertEquals(4f, scale.scaleFactor, 0f)
            assertFalse(fixture.state.isScrollInProgress)
            assertFalse(fixture.owner.isFlinging)
            0f
        }
        val fling = launch(scale, start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.onScrollStopped(1000f)
        }
        fixture.frames.frame(0L)
        assertTrue(fixture.owner.isFlinging)
        assertTrue(fixture.postVelocities.isEmpty())
        fixture.frames.frame(16_000_000L) { scale.scaleFactor = 4f }
        fling.join()
        assertFalse(fling.isCancelled)
        assertEquals(listOf(1000f), fixture.preVelocities)
        assertEquals(baseline.postVelocities.single(), fixture.postVelocities.single())
        assertEquals(2, fixture.frames.delivered)
    }

    @Test fun onlyPointerCancelReturnsTheModalAfterANestedDrag() = boundedTest {
        val fixture = Fixture()
        var modalReturns = 0
        val channel = NativeVerticalDragChannel(fixture.owner, this) { modalReturns++ }
        try {
            channel.start(Offset.Zero)
            while (!fixture.state.isScrollInProgress) yield()
            val successorEntered = CompletableDeferred<Unit>()
            val successor = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.owner.drag {
                    successorEntered.complete(Unit)
                    awaitCancellation()
                }
            }
            successorEntered.await()
            successor.cancelAndJoin()

            channel.start(Offset.Zero)
            while (!fixture.state.isScrollInProgress) yield()
            assertEquals(0, modalReturns)
            channel.cancel()
            while (modalReturns == 0) yield()
            assertEquals(1, modalReturns)
            assertTrue(fixture.preVelocities.isEmpty())
            assertTrue(fixture.postVelocities.isEmpty())
        } finally {
            channel.close()
        }
    }

    @Test fun heldUserMutationRejectsDefaultFlingWithoutFinishingTheActiveDrag() = boundedTest {
        val fixture = Fixture()
        val drag = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.drag { awaitCancellation() }
        }
        assertTrue(fixture.state.isScrollInProgress)
        assertTrue(fixture.owner.shouldScrollImmediately)
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(400f) }
        fling.join()
        assertTrue(fling.isCancelled)
        assertEquals(listOf(400f), fixture.preVelocities)
        assertTrue(fixture.postVelocities.isEmpty())
        assertFalse(fixture.owner.isFlinging)
        assertTrue(fixture.state.isScrollInProgress)
        drag.cancelAndJoin()
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun overlappingFlingsKeepTheSuccessorsFlagUntilItsMutationRetires() = boundedTest {
        val fixture = Fixture()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.onScrollStopped(-1000f)
        }
        // Consume the incumbent's request before cancellation, so the next request
        // can only establish actual admission of the successor.
        fixture.frames.awaitRequest()
        assertTrue(fixture.owner.isFlinging)
        assertTrue(fixture.state.isScrollInProgress)

        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.onScrollStopped(-1000f)
        }
        first.join()
        fixture.frames.awaitRequest()
        assertTrue(first.isCancelled)
        assertTrue(second.isActive)
        assertTrue(fixture.owner.isFlinging)
        assertTrue(fixture.state.isScrollInProgress)
        assertTrue(fixture.postVelocities.isEmpty())

        fixture.frames.deliver(0L)
        fixture.frames.frame(16_666_667L)
        assertTrue(fixture.state.value > 0)
        assertTrue(fixture.owner.isFlinging)
        assertTrue(fixture.state.isScrollInProgress)

        second.cancelAndJoin()
        assertFalse(fixture.owner.isFlinging)
        assertFalse(fixture.state.isScrollInProgress)
        assertTrue(fixture.postVelocities.isEmpty())
    }

    @Test fun equalPriorityHostsOfTheSameRetainedPageRetireTheOldMutation() = boundedTest {
        val first = Fixture()
        val second = Fixture(first.state)
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            first.owner.drag { awaitCancellation() }
        }
        second.owner.drag { scrollBy(-12f) }
        old.join()
        assertTrue(old.isCancelled)
        assertEquals(12, first.state.value)
        assertFalse(first.state.isScrollInProgress)
        assertFalse(second.owner.shouldScrollImmediately)
    }

    @Test fun userDragCancelsAnActualFlingJobWithoutInventingPostFling() = boundedTest {
        val fixture = Fixture()
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(-1000f) }
        fixture.frames.frame(0L)
        fixture.frames.frame(16_666_667L)
        assertTrue(fixture.owner.isFlinging)
        assertTrue(fixture.state.isScrollInProgress)
        fixture.owner.drag { scrollBy(-4f) }
        fling.join()
        assertTrue(fling.isCancelled)
        assertTrue(fixture.postVelocities.isEmpty())
        assertFalse(fixture.owner.isFlinging)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun suspendedAncestorPreFlingIsCancelledBeforeLocalMutation() = boundedTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        fixture.pre = { entered.complete(Unit); awaitCancellation() }
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(500f) }
        entered.await()
        assertFalse(fixture.state.isScrollInProgress)
        assertFalse(fixture.owner.isFlinging)
        fling.cancelAndJoin()
        assertTrue(fixture.postVelocities.isEmpty())
        assertEquals(0, fixture.frames.delivered)
    }

    @Test fun partialNestedConsumptionReceivesCurrentVelocityAndCorrectPhysicalSign() = boundedTest {
        val fixture = Fixture()
        fixture.pre = { 200f }
        fixture.post = { _, available -> available / 2f }
        // At offset zero, a downward physical fling cannot consume its local negative offset.
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(1000f) }
        fixture.frames.frame(1_000_000_000L)
        fixture.frames.frame(1_016_666_667L)
        fling.join()
        assertFalse(fling.isCancelled)
        assertEquals(listOf(1000f), fixture.preVelocities)
        val (consumed, left) = fixture.postVelocities.single()
        assertEquals(800f, consumed + left, .0001f)
        assertTrue(left > 0f && left < 800f)
        assertTrue(consumed > 0f)
        assertEquals(0, fixture.state.value)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun detachedNodeLocalCancellationStillHandsVelocityToParent() = boundedTest {
        val fixture = Fixture()
        fixture.attached = false
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(-1000f) }
        fixture.frames.frame(0L) // Source allows the zero sample even after node detachment.
        fixture.frames.frame(16_666_667L)
        fling.join()
        assertFalse(fling.isCancelled) // The local exception did not cancel the Job.
        val (consumed, left) = fixture.postVelocities.single()
        assertEquals(-1000f, consumed + left, .0001f)
        assertTrue(left < 0f && left > -1000f)
        assertEquals(0, fixture.state.value)
        assertFalse(fixture.owner.isFlinging)
    }

    @Test fun zeroVelocityTerminalStillRunsBothNestedCallbacksWithoutAFrame() = boundedTest {
        val fixture = Fixture()
        fixture.owner.onScrollStopped(0f)
        assertEquals(listOf(0f), fixture.preVelocities)
        assertEquals(listOf(0f to 0f), fixture.postVelocities)
        assertEquals(0, fixture.frames.delivered)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun suspendedPostFlingDoesNotKeepLocalMutationBusy() = boundedTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        fixture.post = { _, _ -> entered.complete(Unit); awaitCancellation() }
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(1f) }
        entered.await()
        assertFalse(fixture.state.isScrollInProgress)
        assertFalse(fixture.owner.isFlinging)
        fixture.owner.drag { scrollBy(-3f) }
        assertEquals(3, fixture.state.value)
        fling.cancelAndJoin()
    }

    @Test fun pageKeyCapturesItsViewportAtAdmissionAndLeavesUnsupportedEventsAlone() = boundedTest {
        val fixture = Fixture()
        assertTrue(fixture.owner.pageKey(true, true, false, true, this))
        assertEquals(0, fixture.state.value) // Source event returns before the launched mutation.
        fixture.state.measured(50, 500)
        yield()
        assertEquals(80, fixture.state.value) // Captured old viewport, not the later 50 px viewport.
        assertFalse(fixture.owner.pageKey(true, false, false, true, this))
        assertFalse(fixture.owner.pageKey(true, true, true, true, this))
        assertFalse(fixture.owner.pageKey(true, true, false, false, this))
        yield()
        assertEquals(80, fixture.state.value)
    }

    @Test fun descendantRawScrollCannotCompeteWithHeldMutationAndPreservesDirectionFlags() = boundedTest {
        val fixture = Fixture()
        fixture.owner.drag {
            scrollBy(-20f)
            assertEquals(0f, fixture.owner.onNestedPostScroll(10f, true), 0f)
        }
        assertTrue(fixture.state.lastScrolledForward)
        assertEquals(10f, fixture.owner.onNestedPostScroll(10f, true), 0f)
        assertEquals(10, fixture.state.value)
        assertTrue(fixture.state.lastScrolledForward) // Raw backward scroll does not rewrite flags.
        assertEquals(0f, fixture.owner.onNestedPostScroll(10f, false), 0f)
    }

    private fun boundedTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000L) {
            try {
                block()
            } finally {
                coroutineContext[Job]!!.children.forEach { it.cancel() }
            }
        }
    }

    private class Fixture(val state: NativeVerticalScrollState = NativeVerticalScrollState()) {
        val frames = ControlledFrames()
        var attached = true
        var pre: suspend (Float) -> Float = { 0f }
        var post: suspend (Float, Float) -> Float = { _, _ -> 0f }
        val preVelocities = mutableListOf<Float>()
        val postVelocities = mutableListOf<Pair<Float, Float>>()
        init { state.measured(80, 500) }
        private val engine = NativeVerticalScrollEngine(state, object : NativeVerticalScrollHost {
            override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun positionChanged(value: Int) = Unit
        })
        val owner = NativeVerticalScrollOwner(
            engine, exponentialDecay(), frames,
            object : NativeVerticalFlingHost {
                override suspend fun preFling(availableY: Float): Float {
                    preVelocities += availableY
                    return pre(availableY)
                }
                override suspend fun postFling(consumedY: Float, availableY: Float): Float {
                    postVelocities += consumedY to availableY
                    return post(consumedY, availableY)
                }
                override fun isAttached() = attached
            },
        )
    }

    private class TestMotionScale(initialFactor: Float) : MotionDurationScale {
        var reads = 0
            private set
        override var scaleFactor = initialFactor
            get() {
                reads++
                return field
            }
    }

    /** Controlled callback phase, with no wall-clock sleeps or Android frame scheduler. */
    private class ControlledFrames : MonotonicFrameClock {
        private val requests = Channel<Unit>(Channel.UNLIMITED)
        private val times = Channel<Long>(Channel.UNLIMITED)
        var delivered = 0
            private set
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            requests.send(Unit)
            val time = times.receive()
            delivered++
            return onFrame(time)
        }
        suspend fun awaitRequest() {
            requests.receive()
        }
        suspend fun deliver(time: Long) {
            times.send(time)
            yield()
        }
        suspend fun frame(time: Long, beforeDelivery: () -> Unit = {}) {
            awaitRequest()
            beforeDelivery()
            deliver(time)
        }
    }
}
