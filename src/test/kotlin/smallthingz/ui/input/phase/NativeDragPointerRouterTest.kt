package smallthingz.ui.input.phase

import org.junit.Assert.*
import org.junit.Test
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.unit.Velocity

class NativeDragPointerRouterTest {
    @Test fun resetRevokesActiveGestureBeforeReentrantCancellation() {
        val fixture = Fixture()
        fixture.send(down = true)
        fixture.onCancel = { fixture.router.reset() }
        fixture.router.reset()
        assertEquals(1, fixture.cancellations)
        fixture.router.reset()
        assertEquals(1, fixture.cancellations)
    }

    @Test fun cancellationCallbackCanAdmitReplacementGestureWithoutLosingItsTracker() {
        val fixture = Fixture()
        fixture.send(down = true)
        fixture.onCancel = { fixture.send(down = true) }
        fixture.router.reset()
        fixture.send(x = 12f)
        assertEquals(2, fixture.starts)
        assertEquals(Offset(12f, 0f), fixture.deltas.last())
        fixture.onCancel = {}
        fixture.router.reset()
        assertEquals(2, fixture.cancellations)
    }

    @Test fun throwingCancellationDoesNotRemainActive() {
        val fixture = Fixture()
        fixture.send(down = true)
        val failure = IllegalStateException("cancel")
        fixture.onCancel = { throw failure }
        assertSame(failure, runCatching { fixture.router.reset() }.exceptionOrNull())
        fixture.router.reset()
        assertEquals(1, fixture.cancellations)
        fixture.onCancel = {}
        fixture.send(down = true)
        fixture.send(x = 8f)
        assertEquals(Offset(8f, 0f), fixture.deltas.last())
        fixture.router.reset()
    }

    @Test fun normalUpThenResetDoesNotCancelTheCompletedDrag() {
        val fixture = Fixture()
        fixture.send(down = true)
        fixture.send(x = 12f)
        fixture.send(x = 12f, up = true)
        fixture.router.reset()
        fixture.router.reset()
        assertEquals(1, fixture.stops)
        assertEquals(0, fixture.cancellations)
    }

    @Test fun stopCallbackCanResetWithoutCancellingItsCompletedDrag() {
        val fixture = Fixture()
        fixture.send(down = true)
        fixture.onStop = { fixture.router.reset() }
        fixture.send(up = true)
        assertEquals(1, fixture.stops)
        assertEquals(0, fixture.cancellations)
        fixture.router.reset()
        assertEquals(0, fixture.cancellations)
    }

    @Test fun throwingStopRetiresTheDragBeforeFailureEscapes() {
        val fixture = Fixture()
        fixture.send(down = true)
        val failure = IllegalStateException("stop")
        fixture.onStop = { throw failure }
        assertSame(failure, runCatching { fixture.send(up = true) }.exceptionOrNull())
        val delivered = fixture.deltas.size
        fixture.send(x = 8f) // No fresh DOWN: the failed terminal cannot leave DRAGGING active.
        assertEquals(delivered, fixture.deltas.size)
        fixture.router.reset()
        assertEquals(0, fixture.cancellations)
        fixture.onStop = {}
        fixture.send(down = true)
        fixture.send(x = 12f)
        fixture.send(x = 12f, up = true)
        assertEquals(2, fixture.starts)
        assertEquals(2, fixture.stops)
        assertEquals(Offset(12f, 0f), fixture.deltas.last())
        fixture.router.reset()
        assertEquals(0, fixture.cancellations)
    }

    @Test fun consumedFinalUpRetiresBeforeReentrantCancellation() {
        val fixture = Fixture()
        fixture.send(down = true)
        fixture.onCancel = {
            // Fail finitely on the old implementation instead of recursing to stack overflow.
            if (fixture.cancellations == 1) fixture.router.reset()
        }
        fixture.send(up = true, consumed = true)
        fixture.router.reset()
        assertEquals(0, fixture.stops)
        assertEquals(1, fixture.cancellations)
    }

    @Test fun throwingConsumedUpCancellationDoesNotRemainActive() {
        val fixture = Fixture()
        fixture.send(down = true)
        val failure = IllegalStateException("final up cancel")
        fixture.onCancel = { throw failure }
        assertSame(failure, runCatching { fixture.send(up = true, consumed = true) }.exceptionOrNull())
        fixture.onCancel = {}
        fixture.router.reset()
        assertEquals(1, fixture.cancellations)
        fixture.send(down = true)
        fixture.send(x = 8f)
        assertEquals(Offset(8f, 0f), fixture.deltas.last())
        fixture.router.reset()
        assertEquals(2, fixture.cancellations)
    }

    private class Fixture {
        var onCancel: () -> Unit = {}
        var onStop: () -> Unit = {}
        var stops = 0
        var cancellations = 0
        var starts = 0
        val deltas = mutableListOf<Offset>()
        val router = NativeDragPointerRouter(
            NativeDragAxis.HORIZONTAL, { 10f }, { 8000f }, { true },
            object : NativeDragSink {
                override fun start(position: Offset) { starts++ }
                override fun delta(physicalDelta: Offset) { deltas += physicalDelta }
                override fun stop(physicalVelocity: Velocity) { stops++; onStop() }
                override fun cancel() { cancellations++; onCancel() }
            },
        )
        private var id = 0L
        private var time = 0L
        private var previous = Offset.Zero
        fun send(x: Float = 0f, down: Boolean = false, up: Boolean = false, consumed: Boolean = false) {
            val position = Offset(x, 0f)
            if (down) { id++; previous = position }
            val event = NativeDragPointerEvent(listOf(NativeDragPointerChange(
                id, time++, position, previous, !up, !down, PointerType.Touch, position,
                consumption = NativePointerConsumption(consumed),
            )), Offset.Zero)
            previous = position
            NativePointerPass.entries.forEach { router.dispatch(event, it) }
        }
    }
}
