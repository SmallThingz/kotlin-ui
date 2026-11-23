package smallthingz.ui.common

import android.view.View
import android.view.ViewConfiguration
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeDragAxis
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativeDragPointerRouter
import smallthingz.ui.input.phase.NativeDragSink
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode

/** A panel-wide vertical drag in the same pointer passes as its child scroll and click controls. */
class NativeModalDragNode(
    override val view: View,
    private val enabled: () -> Boolean,
    startImmediately: () -> Boolean,
    sink: NativeDragSink,
    canStartDrag: (Offset) -> Boolean = { true },
) : NativeViewPhasePointerNode {
    private val configuration = ViewConfiguration.get(view.context)
    private val router = NativeDragPointerRouter(
        NativeDragAxis.VERTICAL,
        { configuration.scaledTouchSlop.toFloat() },
        { configuration.scaledMaximumFlingVelocity.toFloat() },
        startImmediately,
        sink,
        canStartDrag = canStartDrag,
    )
    fun interested(change: NativeDragPointerChange) = enabled() && router.interested(change)
    override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        router.enabled = enabled()
        router.dispatch(event, pass)
    }
    override fun cancel() = router.reset()
}
