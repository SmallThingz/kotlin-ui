package smallthingz.ui.input.phase

import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.unit.Velocity
import org.junit.Assert.*
import org.junit.Test

/** Retained stream ownership, pass ordering and failure cleanup. */
class NativePointerPhaseTreeTest {
    @Test fun cancellationRevokesPathsBeforeReentrantCallbackAndKeepsNewStream() {
        val tree = NativePointerPhaseTree()
        val calls = mutableListOf<String>()
        val fresh = Node("fresh", calls)
        var cancelled = 0
        val old = object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
            override fun cancel() {
                cancelled++
                assertFalse("Old stream must be retired before calling its node", tree.hasPaths)
                tree.cancel()
                tree.add(2, listOf(fresh))
            }
        }
        tree.add(1, listOf(old))
        tree.cancel()
        assertEquals(1, cancelled)
        assertTrue(tree.hasPaths)
        tree.dispatch { _, _ -> event(change(1, 0f, 0f, down = true, id = 2)) }
        assertEquals(listOf("fresh:INITIAL", "fresh:MAIN", "fresh:FINAL"), calls)
        tree.cancel()
        assertFalse(tree.hasPaths)
    }

    @Test fun failingChildCancellationStillRetiresParentAndSiblingExactlyOnce() {
        val tree = NativePointerPhaseTree()
        val calls = mutableListOf<String>()
        val childFailure = IllegalStateException("child")
        val parentFailure = IllegalArgumentException("parent")
        fun node(name: String, error: Throwable?) = object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
            override fun cancel() { calls += name; if (error != null) throw error }
        }
        tree.add(1, listOf(node("parent", parentFailure), node("child", childFailure)))
        tree.add(2, listOf(node("sibling", null)))
        val thrown = runCatching { tree.cancel() }.exceptionOrNull()
        assertSame(childFailure, thrown)
        assertEquals(listOf(parentFailure), childFailure.suppressed.toList())
        assertEquals(listOf("child", "parent", "sibling"), calls)
        assertFalse(tree.hasPaths)
        tree.cancel()
        assertEquals(3, calls.size)
    }

    @Test fun pruningRetiresAllDetachedBranchesBeforeReentrantCancellation() {
        val tree = NativePointerPhaseTree()
        val calls = mutableListOf<String>()
        fun node(name: String, reenter: Boolean) = object : NativePhasePointerNode {
            override val attached = false
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
            override fun cancel() {
                calls += name
                if (reenter) tree.cancel()
            }
        }
        tree.add(1, listOf(node("first", true)))
        tree.add(2, listOf(node("second", false)))
        tree.pruneDetached()
        assertEquals(listOf("first", "second"), calls)
        assertFalse(tree.hasPaths)
    }

    @Test fun failedProjectionDoesNotRetainPreviouslyCachedEvent() {
        val tree = NativePointerPhaseTree()
        val calls = mutableListOf<String>()
        val first = Node("first", calls)
        tree.add(1, listOf(first, Node("second", calls)))
        val failure = IllegalStateException("geometry unavailable")
        assertSame(failure, runCatching {
            tree.dispatch { node, _ ->
                if (node !== first) throw failure
                event(change(0, 0f, 0f, down = true))
            }
        }.exceptionOrNull())
        // Observe retention directly: no dispatch happened, but the first branch was cached.
        val roots = tree.javaClass.getDeclaredField("roots").run { isAccessible = true; get(tree) as List<*> }
        val branch = roots.single()!!
        assertNull(branch.javaClass.getDeclaredField("event").run { isAccessible = true; get(branch) })
        assertTrue(calls.isEmpty())
        tree.cancel()
    }

    @Test fun deferredCancelFailureDoesNotReplaceOriginalDispatchFailure() {
        val tree = NativePointerPhaseTree()
        val dispatchFailure = IllegalStateException("dispatch")
        val cancelFailure = IllegalArgumentException("cancel")
        tree.add(1, listOf(object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
                tree.cancel()
                throw dispatchFailure
            }
            override fun cancel() { throw cancelFailure }
        }))
        assertSame(dispatchFailure, runCatching {
            tree.dispatch { _, _ -> event(change(0, 0f, 0f, down = true)) }
        }.exceptionOrNull())
        assertEquals(listOf(cancelFailure), dispatchFailure.suppressed.toList())
        assertFalse(tree.hasPaths)
    }

    @Test
    fun initialMainFinalFollowSourceOrderWithCancellationDeferredUntilAfterAllPasses() {
        val calls = mutableListOf<String>()
        val tree = NativePointerPhaseTree()
        val parent = Node("parent", calls)
        val child = Node("child", calls) { _, pass ->
            if (pass == NativePointerPass.MAIN) tree.cancel()
        }
        tree.add(1, listOf(parent, child))
        val event = event(change(0, 0f, 0f, down = true))
        tree.dispatch { _, _ -> event }
        assertEquals(listOf(
            "parent:INITIAL", "child:INITIAL", "child:MAIN", "parent:MAIN",
            "parent:FINAL", "child:FINAL", "child:CANCEL", "parent:CANCEL",
        ), calls)
        assertFalse(tree.hasPaths)
    }

    @Test
    fun detachmentDuringInitialSkipsDescendantAndLaterPassesThenCancelsAtNextBoundary() {
        val calls = mutableListOf<String>()
        lateinit var parent: Node
        parent = Node("parent", calls) { _, pass ->
            if (pass == NativePointerPass.INITIAL) parent.attached = false
        }
        val child = Node("child", calls)
        val tree = NativePointerPhaseTree()
        tree.add(1, listOf(parent, child))
        tree.dispatch { _, _ -> event(change(0, 0f, 0f, down = true)) }
        assertEquals(listOf("parent:INITIAL"), calls)
        tree.pruneDetached()
        assertEquals(listOf("parent:INITIAL", "child:CANCEL", "parent:CANCEL"), calls)
        assertFalse(tree.hasPaths)
    }

    @Test
    fun eachPointerRetainsItsOwnChildPathWhileAncestorsReceiveBoth() {
        val tree = NativePointerPhaseTree()
        val calls = mutableListOf<String>()
        val ids = mutableMapOf<String, Set<Long>>()
        val parent = Node("parent", calls) { event, _ -> ids["parent"] = event.changes.mapTo(mutableSetOf()) { it.id } }
        val first = Node("first", calls) { event, _ -> ids["first"] = event.changes.mapTo(mutableSetOf()) { it.id } }
        val second = Node("second", calls) { event, _ -> ids["second"] = event.changes.mapTo(mutableSetOf()) { it.id } }
        tree.add(1, listOf(parent, first))
        tree.add(2, listOf(parent, second))
        val changes = listOf(change(10, 10f, 0f, id = 1), change(10, 20f, 0f, id = 2))
        tree.dispatch { _, retained -> NativeDragPointerEvent(changes.filter { it.id in retained }, Offset.Zero) }
        assertEquals(setOf(1L, 2L), ids["parent"])
        assertEquals(setOf(1L), ids["first"])
        assertEquals(setOf(2L), ids["second"])
        tree.release(setOf(1L))
        calls.clear()
        tree.dispatch { _, retained -> NativeDragPointerEvent(changes.filter { it.id in retained }, Offset.Zero) }
        assertFalse(calls.any { it.startsWith("first:") })
        assertFalse(calls.any { it.endsWith("CANCEL") })
    }

    @Test
    fun rowCanceledByDragDoesNotConsumePagerUpMerelyBecauseItStillHandlesAndroidStream() {
        val stops = mutableListOf<Velocity>()
        val sink = object : NativeDragSink {
            override fun start(position: Offset) = Unit
            override fun delta(physicalDelta: Offset) = Unit
            override fun stop(physicalVelocity: Velocity) { stops += physicalVelocity }
            override fun cancel() = Unit
        }
        val router = NativeDragPointerRouter(NativeDragAxis.HORIZONTAL, { 10f }, { 8000f }, { false }, sink)
        val phase = NativeClickPhaseConsumption()
        var activeClick = false
        var clickCount = 0
        val parent = object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = router.dispatch(event, pass)
            override fun cancel() = router.reset()
        }
        val row = object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
                if (pass == NativePointerPass.MAIN) phase.main(event) { consumed, consume ->
                    val change = event.changes.single()
                    if (change.changedToDown && !consumed) { activeClick = true; consume(false) }
                    else if (change.changedToUp && activeClick && !consumed) {
                        consume(false); clickCount++; activeClick = false
                    }
                    true // Android stream remains handled even AFTER Final canceled the click.
                }
                if (pass == NativePointerPass.FINAL) phase.final(event) { if (it) activeClick = false }
            }
            override fun cancel() { activeClick = false; phase.cancel() }
        }
        val tree = NativePointerPhaseTree()
        tree.add(1, listOf(parent, row))
        for (change in listOf(
            change(0, 0f, 0f, down = true),
            change(10, -20f, 0f),
            change(20, -40f, -20f),
            change(30, -40f, -40f, up = true),
        )) tree.dispatch { _, _ -> event(change) }
        assertFalse(activeClick)
        assertEquals(0, clickCount)
        assertEquals(1, stops.size)
        assertEquals(-2000f, stops.single().x, 0.01f)
    }

    @Test
    fun originalDownExemptionDoesNotExemptLaterConsumedChangesForSamePointer() {
        val phase = NativeClickPhaseConsumption()
        val down = event(change(0, 0f, 0f, down = true))
        phase.main(down) { _, consume -> consume(false); true }
        var cancelled = false
        phase.final(down) { cancelled = it }
        assertFalse(cancelled)
        val moved = event(change(10, 1f, 0f).also { it.consume() })
        phase.final(moved) { cancelled = it }
        assertTrue(cancelled)
    }

    private class Node(
        val name: String,
        private val calls: MutableList<String>,
        private val action: (NativeDragPointerEvent, NativePointerPass) -> Unit = { _, _ -> },
    ) : NativePhasePointerNode {
        override var attached = true
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            calls += "$name:$pass"; action(event, pass)
        }
        override fun cancel() { calls += "$name:CANCEL" }
    }

    private fun event(change: NativeDragPointerChange) = NativeDragPointerEvent(listOf(change), Offset.Zero)
    private fun change(time: Long, x: Float, oldX: Float, down: Boolean = false, up: Boolean = false, id: Long = 1) =
        NativeDragPointerChange(id, time, Offset(x, 0f), Offset(oldX, 0f), !up, !down, PointerType.Touch, Offset(x, 0f))
}
