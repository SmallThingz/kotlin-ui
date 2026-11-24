package smallthingz.ui.input.phase

import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size

/** Actual root/source wiring; attachment is a node test double, not a window-admission test. */
class PhasePointerRootCancellationTest {
    @Test fun throwingCancellationRetiresPressedSourceBeforeNextMove() = onMain {
        val fixture = Fixture()
        val failure = IllegalStateException("cancel")
        fixture.onCancel = { throw failure }
        fixture.send(MotionEvent.ACTION_DOWN)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            fixture.send(MotionEvent.ACTION_CANCEL)
        })
        fixture.onCancel = {}
        fixture.send(MotionEvent.ACTION_MOVE)
        assertEquals(listOf(false, false), fixture.previousPresses)
        fixture.send(MotionEvent.ACTION_CANCEL)
    }

    @Test fun streamAdmittedDuringCancellationKeepsItsPressedSource() = onMain {
        val fixture = Fixture()
        fixture.send(MotionEvent.ACTION_DOWN)
        fixture.onCancel = {
            fixture.onCancel = {}
            fixture.send(MotionEvent.ACTION_DOWN)
        }
        fixture.send(MotionEvent.ACTION_CANCEL)
        fixture.send(MotionEvent.ACTION_MOVE)
        assertEquals(listOf(false, false, true), fixture.previousPresses)
        fixture.send(MotionEvent.ACTION_CANCEL)
    }

    @Test fun partialBindFailureUnbindsItsOwnerAndPreservesTheBindError() = onMain {
        val fixture = MotionFixture()
        val bindFailure = IllegalStateException("partial bind")
        val cleanupFailure = IllegalArgumentException("partial unbind")
        fixture.nodes[1].bindFailure = bindFailure
        fixture.nodes[1].unbindFailure = cleanupFailure
        assertSame(bindFailure, runCatching { fixture.send() }.exceptionOrNull())
        assertEquals(listOf(cleanupFailure), bindFailure.suppressed.toList())
        assertEquals(listOf(1, 1, 0), fixture.nodes.map { it.binds })
        assertEquals(listOf(1, 1, 0), fixture.nodes.map { it.unbinds })
        fixture.assertReleased()
    }

    @Test fun unbindFailuresStillReleaseEverySiblingAndKeepTheFirstError() = onMain {
        val fixture = MotionFixture()
        val first = IllegalStateException("first unbind")
        val second = IllegalArgumentException("second unbind")
        fixture.nodes[0].unbindFailure = first
        fixture.nodes[1].unbindFailure = second
        assertSame(first, runCatching { fixture.send() }.exceptionOrNull())
        assertEquals(listOf(second), first.suppressed.toList())
        assertEquals(listOf(1, 1, 1), fixture.nodes.map { it.unbinds })
        fixture.assertReleased()
    }

    @Test fun dispatchFailureStaysPrimaryAcrossEveryMotionUnbind() = onMain {
        val fixture = MotionFixture()
        val dispatchFailure = IllegalStateException("dispatch")
        val first = IllegalArgumentException("first unbind")
        val second = UnsupportedOperationException("second unbind")
        fixture.nodes[0].dispatchFailure = dispatchFailure
        fixture.nodes[0].unbindFailure = first
        fixture.nodes[1].unbindFailure = second
        assertSame(dispatchFailure, runCatching { fixture.send() }.exceptionOrNull())
        assertEquals(listOf(first, second), dispatchFailure.suppressed.toList())
        assertEquals(listOf(1, 1, 1), fixture.nodes.map { it.unbinds })
        fixture.assertReleased()
    }

    @Test fun successfulDispatchKeepsMotionForAllPassesThenUnbindsOnce() = onMain {
        val fixture = MotionFixture()
        fixture.send()
        assertEquals(listOf(1, 1, 1), fixture.nodes.map { it.binds })
        assertEquals(listOf(1, 1, 1), fixture.nodes.map { it.unbinds })
        fixture.nodes.forEach { assertEquals(NativePointerPass.entries.toList(), it.passes) }
        fixture.assertReleased()
    }

    /** Public binder fault injection; no nested dispatch or real-window admission claim. */
    private class MotionFixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nodes = List(3) { MotionNode(View(context)) }
        private val layout = object : NativeViewPointerHitLayout {
            override val placed = true
            override val size = Size(100f, 100f)
            override val minimumTouchTarget = size
            override val nodes: List<NativeViewPhasePointerNode> = this@MotionFixture.nodes
            override val childrenInHitOrder = emptyList<NativeViewPointerHitLayout>()
            override fun screenToLocal(screen: Offset) = screen
            override fun withinLayerBounds(local: Offset) = true
        }
        private val root = PhasePointerRoot(context, { it },
            { NativePointerCoordinates(Matrix(), Matrix(), Offset.Zero) }, layout, {})

        fun send() {
            val event = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
            try {
                assertTrue(root.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun assertReleased() {
            nodes.forEach {
                assertNull(it.motion)
                assertNull(it.coordinates)
                assertEquals(0, it.mask)
            }
        }
    }

    private class MotionNode(override val view: View) : NativeViewPhasePointerNode {
        override val attached = true
        var motion: MotionEvent? = null
        var coordinates: NativePointerCoordinates? = null
        var mask = 0
        var binds = 0
        var unbinds = 0
        var bindFailure: Throwable? = null
        var unbindFailure: Throwable? = null
        var dispatchFailure: Throwable? = null
        val passes = mutableListOf<NativePointerPass>()

        override fun bindMotion(event: MotionEvent?, coordinates: NativePointerCoordinates?, androidPointerMask: Int) {
            motion = event
            this.coordinates = coordinates
            mask = androidPointerMask
            if (event != null) {
                binds++
                bindFailure?.let { throw it }
            } else {
                unbinds++
                unbindFailure?.let { throw it }
            }
        }

        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            assertEquals(MotionEvent.ACTION_DOWN, checkNotNull(motion).actionMasked)
            assertTrue(coordinates != null)
            assertEquals(1, mask)
            passes += pass
            dispatchFailure?.let { throw it }
        }

        override fun cancel() = Unit
    }

    private fun onMain(action: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousPresses = mutableListOf<Boolean>()
        var onCancel: () -> Unit = {}
        private val node = object : NativeViewPhasePointerNode {
            override val view = View(context)
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
                if (pass == NativePointerPass.INITIAL) {
                    previousPresses += event.changes.single().previouslyPressed
                }
            }
            override fun cancel() = onCancel()
        }
        private val layout = object : NativeViewPointerHitLayout {
            override val placed = true
            override val size = Size(100f, 100f)
            override val minimumTouchTarget = size
            override val nodes = listOf(node)
            override val childrenInHitOrder = emptyList<NativeViewPointerHitLayout>()
            override fun screenToLocal(screen: Offset) = screen
            override fun withinLayerBounds(local: Offset) = true
        }
        private val root = PhasePointerRoot(
            context, { it }, { NativePointerCoordinates(Matrix(), Matrix(), Offset.Zero) },
            layout, {},
        )
        private var time = 1L
        fun send(action: Int) {
            val event = MotionEvent.obtain(1, time++, action, 20f, 20f, 0)
            try {
                root.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
