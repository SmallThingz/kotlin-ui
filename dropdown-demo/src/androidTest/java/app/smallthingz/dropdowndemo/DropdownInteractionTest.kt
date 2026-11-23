package app.smallthingz.dropdowndemo

import android.content.Intent
import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises public Android accessibility actions against the real library popup window. */
@RunWith(AndroidJUnit4::class)
class DropdownInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun all(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { all(node.getChild(it)) }
    }
    private fun nodes() = all(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow)
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= until) fail("$message\n" + nodes().joinToString("\n") {
                "${it.className} text=${it.text} desc=${it.contentDescription} selected=${it.isSelected} clickable=${it.isClickable} visible=${it.isVisibleToUser} children=${it.childCount} scroll=${it.isScrollable} bounds=${Rect().also(it::getBoundsInScreen)}"
            })
            SystemClock.sleep(40)
        }
    }
    private fun open(label: String): Rect {
        val text = nodes().first { it.text?.toString() == label }
        var target = text
        while (!target.isClickable) target = checkNotNull(target.parent)
        val bounds = Rect().also(target::getBoundsInScreen)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Popup did not open") { wheel() != null }
        await("Popup options did not become accessible") { nodes().any { it.isSelected && it.isClickable } }
        return bounds
    }
    private fun wheel() = nodes().firstOrNull { it.className?.toString() == "android.widget.NumberPicker" }
    private fun selected(label: String) = nodes().any { it.contentDescription?.toString() == label && it.isSelected }
    private fun selectedBounds(label: String) = Rect().also { box ->
        nodes().single { it.contentDescription?.toString() == label && it.isSelected }.getBoundsInScreen(box)
    }
    private fun step(action: Int) {
        assertTrue(checkNotNull(wheel()).performAction(action))
        SystemClock.sleep(300)
    }
    private fun withActivity(block: () -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            await("Demo not visible") { nodes().any { it.packageName?.toString() == "app.smallthingz.dropdowndemo" } }
            block()
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun scrollSelectsAndTappingAnotherOptionCommitsAndCollapses() = withActivity {
        val slot = open("Sample format")
        assertTrue(selected("16-bit PCM"))
        assertEquals(slot, selectedBounds("16-bit PCM"))
        step(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        assertTrue(selected("24-bit PCM"))
        assertEquals(slot, selectedBounds("24-bit PCM"))
        val target = nodes().single { it.contentDescription?.toString() == "32-bit float" && it.isClickable }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Tapped option did not collapse") { wheel() == null }
        assertTrue(nodes().any { it.text?.toString() == "32-bit float" })
    }

    @Test fun topWrapHasThreeUniqueOptionsAndStopsAtItsFiniteEnd() = withActivity {
        open("Top edge")
        await("Three choices did not expand") { nodes().count { it.isClickable && it.className?.toString() == "android.widget.Button" } == 3 }
        val labels = nodes().filter { it.isClickable && it.className?.toString() == "android.widget.Button" }.mapNotNull { it.contentDescription?.toString() }
        assertEquals(listOf("Three", "One", "Two"), labels)
        assertTrue(selected("Three"))
        step(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        assertTrue(selected("One"))
        step(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        assertTrue(selected("Two"))
        assertFalse(checkNotNull(wheel()).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        assertTrue(nodes().single { it.contentDescription?.toString() == "Two" && it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Current slot did not close") { wheel() == null }
        assertTrue(nodes().any { it.text?.toString() == "Two" })
    }

    @Test fun tappingAnchorDuringCollapseReopensTheSameWheelWithTheTappedChoice() = withActivity {
        val slot = open("Sample format")
        val window = checkNotNull(wheel()).windowId
        val option = Rect()
        await("Second option did not expand") {
            nodes().firstOrNull { it.contentDescription?.toString() == "24-bit PCM" && it.isClickable }
                ?.also { it.getBoundsInScreen(option) } != null
        }
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun tap(bounds: Rect) {
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, false)) } finally { event.recycle() }
            }
        }
        tap(option)
        tap(slot)
        await("Second tap did not reopen the selected choice at the anchor") {
            wheel()?.windowId == window && selected("24-bit PCM") && selectedBounds("24-bit PCM") == slot
        }
        SystemClock.sleep(350)
        assertEquals(window, checkNotNull(wheel()).windowId)
        tap(slot)
        await("Reopened wheel did not close") { wheel() == null }
        assertTrue(nodes().any { it.text?.toString() == "24-bit PCM" })
    }

    @Test fun oneDragCrossesMultipleRowsAndSnapsToTheOriginalSlot() = withActivity {
        val slot = open("Sample format")
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                slot.exactCenterX(), slot.exactCenterY() - slot.height() * 2.2f * fraction, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        pointer(MotionEvent.ACTION_DOWN, 0f)
        for (i in 1..10) { SystemClock.sleep(25); pointer(MotionEvent.ACTION_MOVE, i / 10f) }
        pointer(MotionEvent.ACTION_UP, 1f)
        await("A single drag did not select the third row") { selected("32-bit float") && selectedBounds("32-bit float") == slot }
        assertNotNull(wheel())
        assertTrue(nodes().single { it.contentDescription?.toString() == "32-bit float" && it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Dragged choice did not collapse") { wheel() == null }
        assertTrue(nodes().any { it.text?.toString() == "32-bit float" })
    }
}
