package smallthingz.ui.platform

import android.os.Handler
import android.view.Choreographer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the production clock/dispatcher with explicit host queues, not an Android runtime. */
class AndroidUiFrameClockHostTest {
    @Test fun cancelledCallbackAlreadyTakenFromQueueDoesNotInvokeUserCode() = runBlocking {
        val choreographer = Choreographer()
        val clock = AndroidUiFrameClock(choreographer)
        var calls = 0
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            clock.withFrameNanos { calls++ }
        }
        val ready = choreographer.takeFrame()
        assertEquals(1, ready.size)
        request.cancel()
        ready.single().doFrame(17L)
        request.join()
        assertEquals(0, calls)
        assertTrue(request.isCancelled)
    }

    @Test fun cancellationOfLaterWaiterInActualDispatcherBatchSkipsOnlyThatWaiter() = runBlocking {
        val choreographer = Choreographer()
        val dispatcher = dispatcher(choreographer)
        val calls = mutableListOf<String>()
        lateinit var second: Job
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.frameClock.withFrameNanos {
                calls += "first"
                second.cancel()
            }
        }
        second = launch(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.frameClock.withFrameNanos { calls += "cancelled" }
        }
        val third = launch(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.frameClock.withFrameNanos { calls += "third" }
        }
        assertEquals(1, choreographer.pendingCount())
        choreographer.frame(23L)
        first.join(); second.join(); third.join()
        assertEquals(listOf("first", "third"), calls)
        assertTrue(second.isCancelled)
    }

    @Test fun liveCallbackReturnsExactFrameTimeAndItsResult() = runBlocking {
        val choreographer = Choreographer()
        val clock = AndroidUiFrameClock(choreographer)
        var calls = 0
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            clock.withFrameNanos { time -> calls++; time + 7L }
        }
        choreographer.frame(31L)
        assertEquals(38L, result.await())
        assertEquals(1, calls)
        assertEquals(0, choreographer.pendingCount())
    }

    @Test fun throwingCallbackDeliversFailureWithoutAbortingRemainingBatch() = runBlocking {
        val choreographer = Choreographer()
        val dispatcher = dispatcher(choreographer)
        val expected = IllegalArgumentException("callback fixture")
        var failure: Throwable? = null
        var laterRan = false
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                dispatcher.frameClock.withFrameNanos { throw expected }
            } catch (error: IllegalArgumentException) {
                failure = error
            }
        }
        val later = launch(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.frameClock.withFrameNanos { laterRan = true }
        }
        choreographer.frame(41L)
        first.join(); later.join()
        assertSame(expected, failure)
        assertTrue(laterRan)
    }

    @Test fun ordinaryPendingCancellationStillRemovesCallback() = runBlocking {
        val choreographer = Choreographer()
        val clock = AndroidUiFrameClock(choreographer)
        var called = false
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            clock.withFrameNanos { called = true }
        }
        request.cancelAndJoin()
        assertEquals(0, choreographer.pendingCount())
        choreographer.frame(53L)
        assertFalse(called)
    }

    private fun dispatcher(choreographer: Choreographer): AndroidUiDispatcher =
        AndroidUiDispatcher::class.java.getDeclaredConstructor(Choreographer::class.java, Handler::class.java)
            .apply { isAccessible = true }
            .newInstance(choreographer, Handler())
}
