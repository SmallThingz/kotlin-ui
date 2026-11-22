package smallthingz.ui.components

import android.graphics.Rect
import android.view.View
import kotlin.math.roundToInt

/** The edge used for a modal's layout and vertical transition. */
enum class NativeDialogEdge {
    TOP, BOTTOM;

    internal fun orientTopOffset(value: Float): Float = if (this == TOP) value else -value

    companion object {
        /** With no visible trigger, present from the bottom of the host window. */
        fun fromTrigger(trigger: View?): NativeDialogEdge {
            if (trigger == null || !trigger.isAttachedToWindow || !trigger.isShown || trigger.width <= 0 || trigger.height <= 0) return BOTTOM
            val root = trigger.rootView
            val location = IntArray(2)
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            root.getLocationOnScreen(location)
            if (!visible.intersect(location[0], location[1], location[0] + root.width, location[1] + root.height)) {
                return BOTTOM
            }
            trigger.getLocationOnScreen(location)
            val centerX = location[0] + trigger.width / 2
            val centerY = location[1] + trigger.height / 2
            if (!visible.contains(centerX, centerY)) return BOTTOM
            return nativeDialogEntryEdge(visible.top, visible.bottom, centerY,
                (160f * trigger.resources.displayMetrics.density).roundToInt())
        }
    }
}

/** Top-third placement for short windows, bounded to 160dp on taller windows. */
internal fun nativeDialogEntryEdge(top: Int, bottom: Int, triggerCenter: Int, topBandLimit: Int): NativeDialogEdge {
    val band = minOf((bottom - top) / 3, topBandLimit)
    return if (band > 0 && triggerCenter >= top && triggerCenter < top + band) NativeDialogEdge.TOP
    else NativeDialogEdge.BOTTOM
}
