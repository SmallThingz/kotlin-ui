/*
 * Source-shaped DragGestureNode queue for the existing vertical mutation owner.
 * Copyright 2022-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0.
 */
package smallthingz.ui.scroll

import smallthingz.ui.input.phase.NativeDragSink

import smallthingz.ui.geometry.Offset
import smallthingz.ui.unit.Velocity
import smallthingz.ui.scroll.NativeVerticalScrollOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One attached vertical input node. No detector, View scrolling or alternate mutation state. */
internal class NativeVerticalDragChannel(
    private val owner: NativeVerticalScrollOwner,
    scope: CoroutineScope,
    private val onPointerDragCancelled: (() -> Unit)?,
) : NativeDragSink, AutoCloseable {
    private val nodeJob = SupervisorJob(scope.coroutineContext[Job])
    private val nodeScope = CoroutineScope(scope.coroutineContext + nodeJob)
    private sealed interface Event {
        data object Start : Event
        data class Delta(val value: Offset) : Event
        data class Stop(val velocity: Velocity) : Event
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
                    owner.drag {
                        while (event !is Event.Stop && event !is Event.Cancel) {
                            (event as? Event.Delta)?.let { scrollBy(it.value.y) }
                            event = events.receive()
                        }
                    }
                    val release = event as? Event.Stop
                    // Released ScrollableNode exits UserInput drag before launching its fling.
                    if (release == null && onPointerDragCancelled != null) {
                        onPointerDragCancelled.invoke()
                    } else {
                        nodeScope.launch { owner.onScrollStopped(release?.velocity?.y ?: 0f) }
                    }
                } catch (_: CancellationException) {
                    // A superseding mutation may already own the modal. Settling either way
                    // would mistake that interruption for the prior pointer's terminal event.
                    if (isActive && onPointerDragCancelled == null) nodeScope.launch { owner.onScrollStopped(0f) }
                }
            }
        }
        events.trySend(Event.Start)
    }
    override fun delta(physicalDelta: Offset) { events.trySend(Event.Delta(physicalDelta)) }
    override fun stop(physicalVelocity: Velocity) { events.trySend(Event.Stop(physicalVelocity)) }
    override fun cancel() { events.trySend(Event.Cancel) }
    override fun close() {
        nodeJob.cancel()
        listener = null
        events.close()
    }
}
