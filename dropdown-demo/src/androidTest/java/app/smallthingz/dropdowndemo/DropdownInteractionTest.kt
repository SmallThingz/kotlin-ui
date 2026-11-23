package app.smallthingz.dropdowndemo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.app.Activity
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeUiAppearance
import java.util.concurrent.atomic.AtomicReference

/** Exercises Android accessibility, touch and key input against the library dropdown. */
@RunWith(AndroidJUnit4::class)
class DropdownInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun report(message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
    }
    private fun all(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { all(node.getChild(it)) }
    }
    private fun nodes() = all(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow)
    private fun await(message: String, detail: () -> String = { "" }, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= until) fail("$message ${detail()}\n" + nodes().joinToString("\n") {
                "window=${it.windowId} ${it.className} text=${it.text} desc=${it.contentDescription} selected=${it.isSelected} clickable=${it.isClickable} visible=${it.isVisibleToUser} children=${it.childCount} scroll=${it.isScrollable} bounds=${Rect().also(it::getBoundsInScreen)}"
            })
            SystemClock.sleep(40)
        }
    }
    private fun open(activity: Activity, label: String, measureFirstDraw: Boolean = false): Rect {
        var target: AccessibilityNodeInfo? = null
        var trigger: NativeDropdown<*>? = null
        var previous: Rect? = null
        var matches = 0
        var diagnostic = ""
        await("Field $label did not stabilize", { diagnostic }) {
            var physical: Rect? = null
            var focused = false
            instrumentation.runOnMainSync {
                val decor = activity.window.decorView
                focused = decor.hasWindowFocus()
                val icons = ArrayList<View>()
                decor.findViewsWithText(icons, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                val field = icons.mapNotNull { it.parent as? NativeDropdown<*> }.singleOrNull()
                trigger = field
                if (field != null && field.isLaidOut && !field.isLayoutRequested) {
                    val xy = IntArray(2).also(field::getLocationOnScreen)
                    physical = Rect(xy[0], xy[1], xy[0] + field.width, xy[1] + field.height)
                }
            }
            var node = nodes().firstOrNull { it.text?.toString() == label }
            while (node != null && !node.isClickable) node = node.parent
            target = node
            val observed = node?.takeIf { it.refresh() }?.let { Rect().also(it::getBoundsInScreen) }
            matches = if (focused && observed != null && observed == physical) {
                if (observed == previous) matches + 1 else 1
            } else 0
            previous = observed
            diagnostic = "focus=$focused view=$physical accessibility=$observed consecutiveMatches=$matches"
            matches >= 3
        }
        val bounds = checkNotNull(previous)
        val drawTiming = AtomicReference<String>()
        if (measureFirstDraw) {
            instrumentation.runOnMainSync {
                val start = SystemClock.elapsedRealtimeNanos()
                assertTrue(checkNotNull(trigger).performClick())
                val returned = SystemClock.elapsedRealtimeNanos()
                val pane = checkNotNull(popupPane(label))
                pane.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                    var recorded = false
                    override fun onDraw() {
                        if (recorded) return
                        recorded = true
                        val drawn = SystemClock.elapsedRealtimeNanos()
                        drawTiming.set("$label performClickMs=${(returned - start) / 1e6} firstDrawMs=${(drawn - start) / 1e6}")
                        pane.post { if (pane.viewTreeObserver.isAlive) pane.viewTreeObserver.removeOnDrawListener(this) }
                    }
                })
            }
        } else {
            assertTrue(checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        await("Popup did not open") { wheel() != null }
        await("Popup options did not become accessible") { nodes().any { it.isSelected && it.isClickable } }
        if (measureFirstDraw) {
            await("Dropdown did not draw") { drawTiming.get() != null }
            report(drawTiming.get())
        }
        return bounds
    }
    private fun wheel(tree: List<AccessibilityNodeInfo> = nodes()) = tree.firstOrNull { it.className?.toString() == "android.widget.NumberPicker" }
    private fun selected(label: String) = nodes().any { it.contentDescription?.toString() == label && it.isSelected }
    private fun selectedBounds(label: String, tree: List<AccessibilityNodeInfo> = nodes()) =
        tree.singleOrNull { it.contentDescription?.toString() == label && it.isSelected }?.let { Rect().also(it::getBoundsInScreen) }
    private fun awaitClosed(value: String) {
        await("Popup did not close with $value displayed") {
            val tree = nodes()
            wheel(tree) == null && tree.any { it.text?.toString() == value }
        }
    }
    private fun step(action: Int, paneLabel: String, expected: String) {
        val started = SystemClock.uptimeMillis()
        assertTrue(checkNotNull(wheel()).performAction(action))
        var liveAt: Long? = null
        var liveLabel: CharSequence? = null
        await("Accessibility scroll did not select $expected", { "liveSelection=$liveLabel firstObservedMs=$liveAt" }) {
            instrumentation.runOnMainSync {
                val wheelView = popupPane(paneLabel)?.getChildAt(1) as? ViewGroup
                if (wheelView != null) {
                    val row = (0 until wheelView.childCount).map(wheelView::getChildAt).singleOrNull { it.isSelected }
                    liveLabel = row?.createAccessibilityNodeInfo()?.contentDescription
                    if (liveAt == null && liveLabel == expected) liveAt = SystemClock.uptimeMillis() - started
                }
            }
            selected(expected)
        }
        report("$paneLabel scroll to $expected liveObservedMs=$liveAt accessibilityObservedMs=${SystemClock.uptimeMillis() - started}")
    }
    private fun withActivity(block: (Activity) -> Unit) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info = checkNotNull(automation.serviceInfo)
        val originalFlags = info.flags
        try {
            info.flags = originalFlags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
            automation.serviceInfo = info
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            try {
                await("Demo not visible") { nodes().any { it.packageName?.toString() == "app.smallthingz.dropdowndemo" } }
                block(activity)
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        } finally {
            info.flags = originalFlags
            automation.serviceInfo = info
        }
    }

    @Test fun scrollSelectsAndTappingAnotherOptionCommitsAndCollapses() = withActivity {
        val slot = open(it, "Sample format")
        assertTrue(selected("16-bit PCM"))
        assertEquals(slot, selectedBounds("16-bit PCM"))
        step(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Sample format", "24-bit PCM")
        assertTrue(selected("24-bit PCM"))
        assertEquals(slot, selectedBounds("24-bit PCM"))
        val target = nodes().single { it.contentDescription?.toString() == "32-bit float" && it.isClickable }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitClosed("32-bit float")
    }

    @Test fun topWrapHasThreeUniqueOptionsAndStopsAtItsFiniteEnd() = withActivity {
        open(it, "Top edge")
        await("Three choices did not expand") { nodes().count { it.isClickable && it.className?.toString() == "android.widget.Button" } == 3 }
        val labels = nodes().filter { it.isClickable && it.className?.toString() == "android.widget.Button" }.mapNotNull { it.contentDescription?.toString() }
        assertEquals(listOf("Three", "One", "Two"), labels)
        assertTrue(selected("Three"))
        step(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Top edge", "One")
        assertTrue(selected("One"))
        step(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Top edge", "Two")
        assertTrue(selected("Two"))
        assertFalse(checkNotNull(wheel()).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        assertTrue(nodes().single { it.contentDescription?.toString() == "Two" && it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitClosed("Two")
    }

    @Test fun sampleRatesKeepTheAnchorSlotFromTheFirstToTheLastChoice() = withActivity {
        val label = "Sample rate"
        val rates = listOf(8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000, 176400, 192000)
        val slot = open(it, label, measureFirstDraw = true)
        val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var index = rates.indexOf(44100)
        var reverseSteps = 0
        assertEquals(slot, selectedBounds("${rates[index]} Hz"))
        // A viewport near an edge can rotate the finite order. Discover its first item through the wheel.
        while (checkNotNull(wheel()).actionList.any { action -> action.id == backward }) {
            assertTrue("Wheel never reached its first sample rate", ++reverseSteps < rates.size)
            index = Math.floorMod(index - 1, rates.size)
            val value = "${rates[index]} Hz"
            step(backward, label, value)
            assertEquals(value, slot, selectedBounds(value))
        }
        assertFalse(checkNotNull(wheel()).performAction(backward))
        repeat(rates.lastIndex) {
            index = (index + 1) % rates.size
            val value = "${rates[index]} Hz"
            step(forward, label, value)
            assertEquals(value, slot, selectedBounds(value))
        }
        assertFalse(checkNotNull(wheel()).performAction(forward))
        tap(slot)
        awaitClosed("${rates[index]} Hz")
    }

    private fun tap(bounds: Rect) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, false)) } finally { event.recycle() }
        }
    }

    @Test fun tappingAnchorDuringCollapseReopensTheSameWheelWithTheTappedChoice() = withActivity {
        val label = "Sample format"
        val slot = open(it, label)
        var originalPane: ViewGroup? = null
        instrumentation.runOnMainSync { originalPane = popupPane(label) }
        assertNotNull(originalPane)
        val option = Rect()
        await("Second option did not expand") {
            nodes().firstOrNull { it.contentDescription?.toString() == "24-bit PCM" && it.isClickable }
                ?.also { it.getBoundsInScreen(option) } != null
        }
        tap(option)
        tap(slot)
        await("Second tap did not reopen the selected choice at $slot") {
            val tree = nodes()
            wheel(tree) != null && selectedBounds("24-bit PCM", tree) == slot
        }
        SystemClock.sleep(350)
        var survivingPane: ViewGroup? = null
        instrumentation.runOnMainSync { survivingPane = popupPane(label) }
        assertSame(originalPane, survivingPane)
        tap(slot)
        awaitClosed("24-bit PCM")
    }

    private fun focusTrigger(activity: Activity, label: String): View {
        var prior: View? = null
        instrumentation.runOnMainSync {
            val icons = ArrayList<View>()
            activity.window.decorView.findViewsWithText(icons, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            val trigger = icons.mapNotNull { it.parent as? NativeDropdown<*> }.single()
            trigger.requestFocusFromTouch()
            prior = activity.currentFocus
        }
        assertTrue(prior is NativeDropdown<*>)
        return checkNotNull(prior)
    }

    @Test fun backDismissesTheModalAndRestoresKeyboardFocus() = withActivity { activity ->
        val label = "Sample format"
        val prior = focusTrigger(activity, label)
        open(activity, label)
        assertFalse("Background labels remained accessible", nodes().any { it.text?.toString() == "Channels" })
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitClosed("16-bit PCM")
        var restored: View? = null
        instrumentation.runOnMainSync { restored = activity.currentFocus }
        assertSame(prior, restored)
    }

    @Test fun backDuringHeldDragRetiresTheOldPointerStream() = withActivity { activity ->
        val label = "Sample format"
        val slot = open(activity, label)
        await("Wheel did not fully expand") { scrimAlpha(label) == NativeUiAppearance.Default.anchoredDropdown.scrimOpacity }
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, offsetRows: Float) {
            val y = slot.exactCenterY() - slot.height() * offsetRows
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, slot.exactCenterX(), y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        pointer(MotionEvent.ACTION_DOWN, 0f)
        try {
            pointer(MotionEvent.ACTION_MOVE, 1f)
            await("Held drag did not select its first crossed row") { selectedBounds("24-bit PCM") == slot }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            pointer(MotionEvent.ACTION_MOVE, 2f)
        } finally { pointer(MotionEvent.ACTION_UP, 2f) }
        awaitClosed("24-bit PCM")
    }

    @Test fun shorterOverlayHostDismissesWheelWithoutMovingItsAnchor() = withActivity { activity ->
        val label = "Top edge"
        val anchor = focusTrigger(activity, label)
        val slot = open(activity, label)
        var oldHeight = 0
        lateinit var host: ViewGroup
        instrumentation.runOnMainSync {
            host = checkNotNull(popupPane(label)).parent as ViewGroup
            oldHeight = host.height - host.paddingTop - host.paddingBottom
            host.setPadding(host.paddingLeft, host.paddingTop, host.paddingRight, host.paddingBottom + slot.height())
        }
        awaitClosed("Three")
        var restoredFocus: View? = null
        var resizedHeight = 0
        val resizedSlot = Rect()
        instrumentation.runOnMainSync {
            resizedHeight = host.height - host.paddingTop - host.paddingBottom
            val xy = IntArray(2).also(anchor::getLocationOnScreen)
            resizedSlot.set(xy[0], xy[1], xy[0] + anchor.width, xy[1] + anchor.height)
            restoredFocus = activity.currentFocus
        }
        assertEquals(oldHeight - slot.height(), resizedHeight)
        assertEquals(slot, resizedSlot)
        assertSame(anchor, restoredFocus)
    }

    private fun popupPane(label: String): ViewGroup? {
        fun pane(view: View): ViewGroup? {
            if (view !is ViewGroup) return null
            if (view.accessibilityPaneTitle == label) return view
            for (i in 0 until view.childCount) pane(view.getChildAt(i))?.let { return it }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::pane)
    }

    private fun scrimAlpha(label: String): Float {
        var alpha = 0f
        instrumentation.runOnMainSync {
            alpha = popupPane(label)?.getChildAt(0)?.alpha ?: 0f
        }
        return alpha
    }

    @Test fun rapidTapsReverseAnInFlightAnimationBeforeItReachesItsEndpoint() = withActivity {
        val label = "Sample format"
        val slot = open(it, label, measureFirstDraw = true)
        val full = NativeUiAppearance.Default.anchoredDropdown.scrimOpacity
        await("Initial expansion did not finish") { scrimAlpha(label) == full }
        for (phase in listOf(.30f, .75f, .97f)) {
            for (opening in listOf(false, true)) {
                if (opening) {
                    tap(slot)
                    awaitClosed("16-bit PCM")
                }
                val started = SystemClock.uptimeMillis()
                tap(slot)
                val startDeadline = started + 1000
                fun progress(alpha: Float) = if (opening) alpha / full else 1f - alpha / full
                var alpha = scrimAlpha(label)
                while (progress(alpha) < phase && SystemClock.uptimeMillis() < startDeadline) {
                    SystemClock.sleep(4)
                    alpha = scrimAlpha(label)
                }
                val before = alpha
                val trace = mutableListOf(before)
                assertTrue("No in-flight sample at phase=$phase opening=$opening alpha=$before",
                    progress(before) >= phase && before > 0f && before < full)
                val tapped = SystemClock.uptimeMillis()
                tap(slot)
                var reversed = false
                while (!reversed && SystemClock.uptimeMillis() - tapped < 75) {
                    alpha = scrimAlpha(label)
                    trace += alpha
                    assertTrue("Reached old endpoint before reversing: opening=$opening phase=$phase alpha=$trace",
                        if (opening) alpha < full else alpha > 0f)
                    reversed = if (opening) alpha < before else alpha > before
                    if (!reversed) SystemClock.sleep(4)
                }
                val elapsed = SystemClock.uptimeMillis() - tapped
                val timing = "opening=$opening phase=$phase actualPhase=${progress(before)} interTapMs=${tapped - started} reversalMs=$elapsed alpha=$trace"
                report(timing)
                assertTrue(timing, reversed && elapsed <= 75)
                if (opening) {
                    awaitClosed("16-bit PCM")
                    tap(slot)
                }
                await("Reopened wheel did not fully expand") { scrimAlpha(label) == full }
            }
        }
        tap(slot)
        awaitClosed("16-bit PCM")
    }

    @Test fun oneDragCrossesMultipleRowsAndSnapsToTheOriginalSlot() = withActivity {
        val slot = open(it, "Sample format")
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                slot.exactCenterX(), slot.exactCenterY() - slot.height() * 2.2f * fraction, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        pointer(MotionEvent.ACTION_DOWN, 0f)
        for (i in 1..10) { SystemClock.sleep(25); pointer(MotionEvent.ACTION_MOVE, i / 10f) }
        pointer(MotionEvent.ACTION_UP, 1f)
        await("A single drag did not select the third row at $slot") { selectedBounds("32-bit float") == slot }
        assertNotNull(wheel())
        assertTrue(nodes().single { it.contentDescription?.toString() == "32-bit float" && it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitClosed("32-bit float")
    }
}
