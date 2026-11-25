/*
 * Source-derived from Material3 1.4.0 SheetDefaults/AnchoredDraggable and foundation 1.11.2.
 * Copyright The Android Open Source Project. Apache-2.0.
 */
package smallthingz.ui.components

import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource

import android.view.View
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.DEFAULT_MINIMUM_TOUCH_TARGET_DP
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.unit.Velocity
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativeDragSink
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Actual sheet ancestor for caller-owned native verticalScroll bodies; no second scroll owner. */
// The state always receives positive motion toward the hidden anchor.
internal fun NativeDialogEdge.sheetMotionY(y: Float): Float =
    if (this == NativeDialogEdge.TOP) -y else y

internal class NativeActionSheetNestedScroll(
    private val state: NativeActionSheetState,
    private val settleToDismiss: (Float) -> Unit,
    override val onPointerDragCancelled: () -> Unit,
    private val entryEdge: NativeDialogEdge = NativeDialogEdge.BOTTOM,
) : NativeVerticalNestedHost {
    override fun preScroll(availableY: Float, source: NativeVerticalScrollSource): Float {
        val motion = entryEdge.sheetMotionY(availableY)
        return if (motion < 0 && source == NativeVerticalScrollSource.USER_INPUT) {
            entryEdge.sheetMotionY(state.dispatchRawDelta(motion))
        } else 0f
    }
    override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource): Float {
        if (source != NativeVerticalScrollSource.USER_INPUT) return 0f
        return entryEdge.sheetMotionY(state.dispatchRawDelta(entryEdge.sheetMotionY(availableY)))
    }
    override suspend fun preFling(availableY: Float): Float {
        val motion = entryEdge.sheetMotionY(availableY)
        if (motion < 0 && state.requireOffset() > checkNotNull(state.anchors).minimum) {
            settleToDismiss(motion)
            return availableY
        }
        return 0f
    }
    override suspend fun postFling(consumedY: Float, availableY: Float): Float {
        settleToDismiss(entryEdge.sheetMotionY(availableY))
        return availableY
    }
}

/** Queue ordering matches DragGestureNode: exit the UserInput mutation before onDragStopped. */
internal class NativeActionSheetDragChannel(
    private val state: NativeActionSheetState,
    scope: CoroutineScope,
    private val settleToDismiss: (Float) -> Unit,
    private val returnAfterCancelledDrag: () -> Unit,
    private val entryEdge: NativeDialogEdge = NativeDialogEdge.BOTTOM,
) : NativeDragSink, AutoCloseable {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val nodeScope = CoroutineScope(scope.coroutineContext + job)
    private sealed interface Event {
        data object Start : Event
        data class Delta(val value: Float) : Event
        data class Stop(val velocity: Float) : Event
        data object Cancel : Event
    }
    private val events = Channel<Event>(Channel.UNLIMITED)
    private var listener: Job? = null
    override fun start(position: Offset) {
        if (listener == null) listener = nodeScope.launch {
            while (isActive) {
                var event = events.receive()
                if (event !is Event.Start) continue
                try {
                    state.drag {
                        while (event !is Event.Stop && event !is Event.Cancel) {
                            (event as? Event.Delta)?.let { dragTo(state.newOffsetForDelta(it.value)) }
                            event = events.receive()
                        }
                    }
                    val release = event as? Event.Stop
                    if (release != null) settleToDismiss(release.velocity) else returnAfterCancelledDrag()
                } catch (_: CancellationException) {
                    // A superseding mutation keeps its own target. No release was accepted here.
                }
            }
        }
        events.trySend(Event.Start)
    }
    override fun delta(physicalDelta: Offset) { events.trySend(Event.Delta(entryEdge.sheetMotionY(physicalDelta.y))) }
    override fun stop(physicalVelocity: Velocity) { events.trySend(Event.Stop(entryEdge.sheetMotionY(physicalVelocity.y))) }
    override fun cancel() { events.trySend(Event.Cancel) }
    override fun close() { job.cancel(); listener = null; events.close() }
}

/** Source detectTapGestures(onTap) for the scrim: no ripple, keyboard click node or long press. */
internal class NativeSheetScrimTapNode(
    override val view: View,
    private val density: () -> Float,
    private val action: () -> Unit,
) : NativeViewPhasePointerNode {
    private var waiting = false
    private var ignoreFinalDown = false
    override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (pass == NativePointerPass.INITIAL || event.changes.isEmpty()) return
        if (pass == NativePointerPass.MAIN) {
            ignoreFinalDown = false
            if (!waiting) {
                if (event.changes.all { it.changedToDown && !it.consumed }) {
                    event.changes.forEach { it.consume() }
                    waiting = true; ignoreFinalDown = true
                }
                return
            }
            // waitForUpOrCancellation checks all-up before consumed/out-of-bounds.
            if (event.changes.all { it.changedToUp && !it.consumed }) {
                event.changes.forEach { it.consume() }
                waiting = false
                action()
            } else if (event.changes.any { it.consumed || outside(it) }) waiting = false
        } else if (waiting && !ignoreFinalDown && event.changes.any { it.consumed }) waiting = false
    }
    private fun outside(change: NativeDragPointerChange): Boolean {
        val min = DEFAULT_MINIMUM_TOUCH_TARGET_DP * density()
        val x = if (change.type == PointerType.Touch) max(0f, min-view.width)*.5f else 0f
        val y = if (change.type == PointerType.Touch) max(0f, min-view.height)*.5f else 0f
        return change.position.x < -x || change.position.x > view.width+x ||
            change.position.y < -y || change.position.y > view.height+y
    }
    override fun cancel() { waiting = false; ignoreFinalDown = false }
}

internal fun interface NativeMinimumTouchTarget {
    fun get(): Size
}

/** Explicit real-View hit node; caller supplies released clip and modifier order, never guessed. */
internal class NativeRetainedViewHitLayout(
    private val view: View,
    private val geometry: NativeWindowPointerCoordinates,
    private val minimum: NativeMinimumTouchTarget,
    override val nodes: List<NativeViewPhasePointerNode>,
    private val children: () -> List<NativeViewPointerHitLayout> = { emptyList() },
    private val clip: (Offset) -> Boolean = { true },
) : NativeViewPointerHitLayout {
    override val placed get() = view.isAttachedToWindow && view.isLaidOut && view.visibility == View.VISIBLE
    override val size get() = Size(view.width.toFloat(), view.height.toFloat())
    override val minimumTouchTarget get() = minimum.get()
    override val childrenInHitOrder get() = children()
    override fun screenToLocal(screen: Offset) = geometry.coordinates(view).local(screen)
    override fun withinLayerBounds(local: Offset) = clip(local)
}
