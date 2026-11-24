package smallthingz.ui.common

import org.junit.Assert.*
import org.junit.Test
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeDragAxis
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePhasePointerNode
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativePointerPhaseTree
import smallthingz.ui.input.pointer.PointerType

class NativeAxisDragLifecycleTest {
    @Test fun terminalMainDisablesNodeSkippingFinalThenFreshPathStartsOnItsFirstDown() {
        for (axis in NativeDragAxis.entries) {
            var admitted = true
            var finalUps = 0
            val h = Harness(axis) { admitted = false }
            val tree = NativePointerPhaseTree()
            val node = object : NativePhasePointerNode {
                override val attached get() = admitted
                override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
                    if (pass == NativePointerPass.FINAL && event.changes.all { it.changedToUp }) finalUps++
                    h.drag.dispatch(event, pass)
                }
                override fun cancel() = h.drag.cancel()
            }
            fun routed(vararg changes: NativeDragPointerChange) {
                val event = event(*changes)
                tree.dispatch { _, ids -> event.copy(changes = event.changes.filter { it.id in ids }) }
            }
            tree.add(1, listOf(node))
            routed(change(1, 0f, down = true))
            routed(change(1, 15f))
            routed(change(1, 15f, 15f, up = true))
            tree.release(setOf(1L))
            assertFalse(tree.hasPaths)
            assertEquals(0, finalUps) // Actual phase-tree attachment gate skipped terminal FINAL.
            assertEquals(1, h.ends)
            admitted = true
            tree.add(2, listOf(node))
            routed(change(2, 0f, down = true, consumed = true))
            routed(change(2, -14f))
            assertEquals(2, h.starts)
            assertEquals(listOf(5f, -4f), h.deltas)
            assertEquals(1, h.ends)
            assertEquals(0, h.cancels)
            routed(change(2, -14f, -14f, up = true))
            tree.release(setOf(2L))
            assertEquals(2, h.ends)
            assertEquals(0, h.cancels)
        }
    }

    @Test fun heldTrackedPointerAndSameLogicalIdDownCannotRestartWaitingPath() {
        val h = waiting()
        h.frame(change(1, 4f, 4f), change(2, 0f, down = true))
        h.frame(change(1, 4f, 4f), change(2, 30f))
        h.frame(change(1, 0f, down = true)) // Even an inconsistent DOWN cannot reuse the old ID.
        h.frame(change(1, 30f))
        assertEquals(0, h.starts)
        assertEquals(0, h.ends)
        assertEquals(0, h.cancels)
    }

    @Test fun absentTrackedIdDoesNotAllowRestartWhileAnotherRoutedPointerIsHeld() {
        val h = waiting()
        h.frame(change(2, 5f, 5f), change(3, 0f, down = true))
        h.frame(change(2, 5f, 5f), change(3, 30f))
        assertEquals(0, h.starts)
        h.frame(change(2, 5f, 5f, up = true), change(3, 30f, 30f, up = true))
        h.frame(change(4, 0f, down = true))
        h.frame(change(4, 14f))
        assertEquals(1, h.starts) // Normal FINAL-all-up admission remains intact.
        assertEquals(listOf(4f), h.deltas)
    }

    @Test fun emptyMainAndFreshDownInInitialOrFinalDoNotAdmitAGesture() {
        for (pass in listOf(NativePointerPass.INITIAL, NativePointerPass.FINAL)) {
            val h = waiting()
            h.drag.dispatch(event(), NativePointerPass.MAIN)
            h.drag.dispatch(event(change(2, 0f, down = true)), pass)
            h.frame(change(2, 30f))
            assertEquals(0, h.starts)
            h.drag.dispatch(event(change(3, 0f, down = true)), NativePointerPass.MAIN)
            h.frame(change(3, 14f))
            assertEquals(1, h.starts)
            assertEquals(listOf(4f), h.deltas)
            assertEquals(0, h.ends)
            assertEquals(0, h.cancels)
        }
    }

    private fun waiting() = Harness(NativeDragAxis.VERTICAL).also {
        it.frame(change(1, 0f, down = true))
        it.frame(change(1, 4f, consumed = true))
    }

    private class Harness(axis: NativeDragAxis, end: () -> Unit = {}) {
        var starts = 0
        var ends = 0
        var cancels = 0
        val deltas = mutableListOf<Float>()
        val drag = NativeAxisDrag(axis, { 10f }, { starts++ }, { deltas += it },
            { ends++; end() }, { cancels++ })
        fun frame(vararg changes: NativeDragPointerChange) {
            val event = event(*changes)
            NativePointerPass.entries.forEach { drag.dispatch(event, it) }
        }
    }

    private companion object {
        fun event(vararg changes: NativeDragPointerChange) = NativeDragPointerEvent(changes.toList(), Offset.Zero)
        fun change(id: Long, value: Float, previous: Float = 0f, down: Boolean = false,
                   up: Boolean = false, consumed: Boolean = false) =
            NativeDragPointerChange(id, 0L, Offset(value, value), Offset(previous, previous),
                !up, !down, PointerType.Touch, Offset(value, value)).also { if (consumed) it.consume() }
    }
}
