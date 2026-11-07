package smallthingz.ui.components

import android.view.View

/** An owning component can retire input without repainting or replacing retained controls. */
interface NativeInputGate {
    val acceptsNativeInput: Boolean
}

internal fun View.acceptsNativeInput(): Boolean {
    var owner: View? = this
    while (owner != null) {
        if (owner is NativeInputGate && !owner.acceptsNativeInput) return false
        owner = owner.parent as? View
    }
    return true
}
