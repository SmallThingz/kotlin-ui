package smallthingz.ui.reverb.onboarding

import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode

import android.view.View

class NativePagerDirectionPhaseNode(
    override val view: View,
    private val observer: NativePagerDirectionObserver,
) : NativeViewPhasePointerNode {
    override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (pass == NativePointerPass.INITIAL) observer.initial(event)
    }
    override fun cancel() = observer.cancel()
}
