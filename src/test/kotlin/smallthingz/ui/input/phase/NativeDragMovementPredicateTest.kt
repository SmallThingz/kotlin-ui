package smallthingz.ui.input.phase

import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.unit.Velocity
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class NativeDragMovementPredicateTest {
    @Test fun draggingPreservesDistancePredicateAtFloatBoundaries() {
        val values = listOf(0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, 1e-25f, 1e-20f,
            0.5f, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN)
        for (x in values) for (y in values) {
            val sink = Sink()
            val router = NativeDragPointerRouter(NativeDragAxis.HORIZONTAL, { 10f }, { 8000f }, { true }, sink)
            val down = change(Offset.Zero, previouslyPressed = false)
            router.dispatch(event(down), NativePointerPass.INITIAL)
            router.dispatch(event(down), NativePointerPass.MAIN)
            sink.deltas.clear()

            val move = change(Offset(x, y), previouslyPressed = true)
            // Freeze the released recognizer's Float arithmetic, independently of the
            // public magnitude helper's improved finite overflow/underflow accuracy.
            val dx = move.delta.x
            val dy = move.delta.y
            val expected = sqrt(dx * dx + dy * dy) != 0f
            router.dispatch(event(move), NativePointerPass.MAIN)
            assertEquals("x=$x y=$y", expected, move.consumed)
            assertEquals("x=$x y=$y", if (expected) 1 else 0, sink.deltas.size)
            if (expected) assertEquals(move.delta, sink.deltas.single())
        }
    }

    @Test fun underflowedMovementIsSkippedWithoutRetiringTheActiveDrag() {
        val sink = Sink()
        val router = NativeDragPointerRouter(NativeDragAxis.HORIZONTAL, { 10f }, { 8000f }, { true }, sink)
        val down = change(Offset.Zero, previouslyPressed = false)
        router.dispatch(event(down), NativePointerPass.INITIAL)
        router.dispatch(event(down), NativePointerPass.MAIN)
        sink.deltas.clear()

        for (position in listOf(Offset(Float.MIN_VALUE, 0f), Offset(0f, Float.MIN_VALUE),
            Offset(1e-25f, -1e-25f))) {
            val tiny = change(position, previouslyPressed = true)
            router.dispatch(event(tiny), NativePointerPass.MAIN)
            assertFalse("Underflowed movement must not consume: $position", tiny.consumed)
            assertTrue(sink.deltas.isEmpty())
        }

        val normal = change(Offset(0.5f, -0.25f), previouslyPressed = true)
        router.dispatch(event(normal), NativePointerPass.MAIN)
        assertTrue(normal.consumed)
        assertEquals(listOf(normal.delta), sink.deltas)
    }

    private fun change(position: Offset, previouslyPressed: Boolean) = NativeDragPointerChange(
        1L, if (previouslyPressed) 10L else 0L, position, Offset.Zero, true, previouslyPressed,
        PointerType.Touch, Offset.Zero,
    )
    private fun event(change: NativeDragPointerChange) = NativeDragPointerEvent(listOf(change), Offset.Zero)

    private class Sink : NativeDragSink {
        val deltas = mutableListOf<Offset>()
        override fun start(position: Offset) = Unit
        override fun delta(physicalDelta: Offset) { deltas.add(physicalDelta) }
        override fun stop(physicalVelocity: Velocity) = Unit
        override fun cancel() = Unit
    }
}
