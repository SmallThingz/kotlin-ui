package smallthingz.ui.components

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Retires a captured action-key press without synthesizing an activating key-up.
 * Physical input dispatch is serialized. A callback deliberately dispatching a successor before
 * resuming its own raw platform handler can still intermix leaf state; this host never cancels
 * that successor to repair the old handler.
 */
internal class NativeKeyPressRetirement(
    private val owner: ViewGroup,
    private val content: ViewGroup,
    private val generation: () -> Long,
) {
    private data class Slot(val deviceId: Int, val keyCode: Int)
    private class Press(val slot: Slot, val downTime: Long, val generation: Long, val serial: Long, target: View) {
        val target = WeakReference(target)
        var retired = false
        var forwardingSerial: Long? = null
    }
    private val active = mutableMapOf<Slot, Press>()
    private val retired = mutableMapOf<Slot, Long>()
    // Retain the latest sequence after UP without accumulating a history of lost releases.
    private val latestDownTime = mutableMapOf<Slot, Long>()
    private var serial = 0L
    private var admissionSerial = 0L

    /** Conservative claim; a custom child's actual pointer receiver need not be inferred. */
    fun claimPointerDown() { admissionSerial++ }

    fun dispatch(event: KeyEvent, admitted: () -> Boolean, forward: () -> Boolean): Boolean {
        val freshDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (freshDown && admitted()) admissionSerial++
        val entrySerial = admissionSerial
        if (!confirmKey(event.keyCode)) return if (admitted()) forward() else true
        val slot = Slot(event.deviceId, event.keyCode)
        if (freshDown) latestDownTime[slot] = event.downTime
        else if (latestDownTime[slot]?.let { it != event.downTime } == true) return true
        val retiredDownTime = retired[slot]
        if (retiredDownTime != null) {
            if (freshDown) retired.remove(slot)
            else if (event.downTime == retiredDownTime) {
                // Keep this single tombstone until a fresh DOWN, including duplicate canceled UPs.
                return true
            }
        }
        if (!admitted()) {
            if (event.action == KeyEvent.ACTION_DOWN) retired[slot] = event.downTime
            return true
        }
        val epoch = generation()
        if (freshDown) {
            active.remove(slot)?.let { old -> old.retired = true; clean(old, entrySerial) }
            if (epoch != generation() || entrySerial != admissionSerial || !admitted()) {
                retireEvent(slot, event.downTime, null, entrySerial)
                return true
            }
            val target = content.findFocus()
            if (target != null && actionTarget(target) && epoch == generation() &&
                entrySerial == admissionSerial && admitted()) {
                active[slot] = Press(slot, event.downTime, epoch, ++serial, target)
            }
        }
        val press = active[slot]?.takeIf { it.downTime == event.downTime }
        if (epoch != generation() || entrySerial != admissionSerial || !admitted()) {
            retireEvent(slot, event.downTime, press, entrySerial)
            return true
        }
        val previousLease = press?.forwardingSerial
        if (press != null) press.forwardingSerial = entrySerial
        try {
            return forward()
        } finally {
            try {
                // hide/show from a listener can precede the platform's late onKeyDown effects.
                // Only this in-flight lease can finish across the resulting generation changes.
                if (press?.retired == true) clean(press, entrySerial)
            } finally {
                if (press != null) press.forwardingSerial = previousLease
                if (event.action == KeyEvent.ACTION_UP && active[slot] === press) active.remove(slot)
            }
        }
    }

    private fun retireEvent(slot: Slot, downTime: Long, press: Press?, expectedSerial: Long) {
        // A callback may already own this slot with a newer active or retired sequence.
        if (latestDownTime[slot] == downTime) retired[slot] = downTime
        if (press != null && active[slot] === press) {
            active.remove(slot)
            press.retired = true
            clean(press, expectedSerial)
        }
    }

    fun retire() {
        val retirementSerial = admissionSerial
        val interrupted = active.values.toList()
        active.clear()
        for (press in interrupted) {
            press.retired = true
            retired[press.slot] = press.downTime
        }
        for (press in interrupted) clean(press, retirementSerial)
    }

    private fun clean(press: Press, expectedSerial: Long) {
        val target = press.target.get() ?: return
        fun owned(): Boolean {
            // onCheckIsTextEditor is overridable: all ownership reads must follow that callback.
            if (containsEditor(target)) return false
            val lease = press.forwardingSerial
            val ownsPresentation = if (lease != null) lease == admissionSerial
                else press.generation == generation()
            return press.retired && ownsPresentation && expectedSerial == admissionSerial &&
                content.parent === owner && inside(target) &&
                active.values.none { it.serial > press.serial && it.target.get() === target }
        }
        if (!owned()) return
        // Also cancels pending long-press/tap callbacks. Never cancel an editor's own input.
        target.cancelPendingInputEvents()
        if (owned()) target.isPressed = false
    }

    private fun actionTarget(target: View): Boolean = !containsEditor(target) && inside(target) &&
        (target.isClickable || target.isLongClickable)

    private fun containsEditor(view: View): Boolean {
        if (view is EditText || view.onCheckIsTextEditor() || view is TextView && view.isTextSelectable) return true
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            val child = view.getChildAt(index) ?: continue
            if (containsEditor(child)) return true
        }
        return false
    }

    private fun inside(view: View): Boolean {
        if (content.parent !== owner) return false
        var current: View? = view
        while (current != null) {
            if (current === content) return true
            current = current.parent as? View
        }
        return false
    }

    // KeyEvent.isConfirmKey is hidden; API28 handles these public codes even with modifiers.
    private fun confirmKey(code: Int): Boolean = code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_NUMPAD_ENTER
}
