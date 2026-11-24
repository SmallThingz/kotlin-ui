package app.smallthingz.dropdowndemo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.app.Activity
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeAnchoredDropdown
import smallthingz.ui.components.NativeDropdownContent
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises Android accessibility, touch and key input against the library dropdown. */
@RunWith(AndroidJUnit4::class)
class DropdownInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private lateinit var activityUnderTest: Activity
    private var demoWindowId = -1
    private val focusLostAt = AtomicReference<Long?>()
    private fun report(message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
    }
    private fun all(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null || node.windowId != demoWindowId || node.packageName?.toString() != instrumentation.targetContext.packageName) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { all(node.getChild(it)) }
    }
    private fun demoWindow() = automation.windows.firstOrNull { it.id == demoWindowId }
    private fun nodes(): List<AccessibilityNodeInfo> {
        assertNull("Demo window $demoWindowId lost focus during this run (uptime ms)", focusLostAt.get())
        return all(demoWindow()?.root)
    }
    private fun guardInput() {
        var focused = false
        instrumentation.runOnMainSync { focused = activityUnderTest.hasWindowFocus() }
        val window = demoWindow()
        assertTrue("Input withheld: demo window=$demoWindowId decorFocused=$focused windowFocused=${window?.isFocused} active=${window?.isActive} focusLostAt=${focusLostAt.get()}",
            focused && window?.isFocused == true && window.isActive && focusLostAt.get() == null)
    }
    private fun await(message: String, detail: () -> String = { "" }, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= until) fail("$message ${detail()} demoWindow=$demoWindowId focused=${demoWindow()?.isFocused} active=${demoWindow()?.isActive}\n" + nodes().joinToString("\n") {
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
            info.flags = (originalFlags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()) or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activityUnderTest = activity
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (!focused) focusLostAt.compareAndSet(null, SystemClock.uptimeMillis())
            }
            try {
                await("Demo window did not gain focus") {
                    var ready = false
                    instrumentation.runOnMainSync {
                        val decor = activity.window.decorView
                        demoWindowId = decor.createAccessibilityNodeInfo().windowId
                        ready = decor.hasWindowFocus() && demoWindowId >= 0
                        if (ready) decor.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                    }
                    ready
                }
                await("Demo window not visible to accessibility") { nodes().isNotEmpty() }
                block(activity)
            } finally { instrumentation.runOnMainSync {
                val observer = activity.window.decorView.viewTreeObserver
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
                activity.finish()
            } }
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
        guardInput()
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
        guardInput()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitClosed("16-bit PCM")
        var restored: View? = null
        instrumentation.runOnMainSync { restored = activity.currentFocus }
        assertSame(prior, restored)
    }

    @Test fun throwingDragSelectionRetiresOverlayAndPreservesPrimaryFailure() = withActivity { activity ->
        val anchor = focusTrigger(activity, "Top edge")
        val label = "Callback failure test"
        val primary = IllegalStateException("Selection failed")
        val cleanup = IllegalArgumentException("Terminal callback failed")
        val selectedValues = mutableListOf<String>()
        var terminalCalls = 0
        var mounted = false
        lateinit var controller: NativeAnchoredDropdown
        instrumentation.runOnMainSync {
            val appearance = NativeUiAppearance.Default
            val host = activity.findViewById<FrameLayout>(android.R.id.content)
            val resources = activity.resources
            controller = NativeAnchoredDropdown(activity, host, appearance, appearance.resolveColorScheme(activity, true),
                Density(resources.displayMetrics.density, resources.configuration.fontScale), createFontFamilyResolver(activity))
            val options = NativeDropdownContent(label, "First", listOf("First", "Second")) { it }
            mounted = controller.open(anchor, options, onTerminal = { terminalCalls++; throw cleanup }) { value ->
                selectedValues += value
                throw primary
            }
        }
        assertTrue(mounted)
        var closeFailure: Throwable? = null
        try {
            await("Failure fixture did not finish opening") { scrimAlpha(label) == NativeUiAppearance.Default.anchoredDropdown.scrimOpacity }
            var thrown: Throwable? = null
            var remainingPane: View? = null
            var restoredFocus: View? = null
            instrumentation.runOnMainSync {
                val pane = checkNotNull(popupPane(label))
                val paneOrigin = IntArray(2).also(pane::getLocationOnScreen)
                val anchorOrigin = IntArray(2).also(anchor::getLocationOnScreen)
                val x = anchorOrigin[0] - paneOrigin[0] + anchor.width / 2f
                val y = anchorOrigin[1] - paneOrigin[1] + anchor.height / 2f
                val down = SystemClock.uptimeMillis()
                fun dispatch(action: Int, eventY: Float) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, eventY, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { pane.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                // Catch the caller's deliberate exception on the same stack as normal touch dispatch.
                thrown = runCatching {
                    dispatch(MotionEvent.ACTION_DOWN, y)
                    dispatch(MotionEvent.ACTION_MOVE, y - anchor.height)
                }.exceptionOrNull()
                remainingPane = popupPane(label)
                restoredFocus = activity.currentFocus
            }
            assertSame(primary, thrown)
            assertEquals(listOf(cleanup), primary.suppressed.toList())
            assertEquals(listOf("Second"), selectedValues)
            assertEquals(1, terminalCalls)
            assertNull(remainingPane)
            assertSame(anchor, restoredFocus)
            awaitClosed("Three")
        } finally {
            instrumentation.runOnMainSync { closeFailure = runCatching { controller.close() }.exceptionOrNull() }
        }
        assertNull("Closing a retired session invoked cleanup again", closeFailure)
        assertEquals(1, terminalCalls)
    }

    @Test fun backDuringHeldDragRetiresTheOldPointerStream() = withActivity { activity ->
        val label = "Sample format"
        val slot = open(activity, label)
        await("Wheel did not fully expand") { scrimAlpha(label) == NativeUiAppearance.Default.anchoredDropdown.scrimOpacity }
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, offsetRows: Float) {
            guardInput()
            val y = slot.exactCenterY() - slot.height() * offsetRows
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, slot.exactCenterX(), y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        pointer(MotionEvent.ACTION_DOWN, 0f)
        try {
            pointer(MotionEvent.ACTION_MOVE, 1f)
            await("Held drag did not select its first crossed row") { selectedBounds("24-bit PCM") == slot }
            guardInput()
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
        return pane(activityUnderTest.window.decorView)
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
                report(measureFrameReversal(it, label, slot, full, phase, opening))
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

    /** Measures the real control's frame response, independently of system input transport. */
    private fun measureFrameReversal(activity: Activity, label: String, slot: Rect, full: Float,
                                     phase: Float, opening: Boolean): String {
        // This verifies the external window authority before scheduling Main callbacks. Each local
        // dispatch also checks the original Activity still owns focus. No input is injected here.
        guardInput()
        val decor = activity.window.decorView
        val complete = CountDownLatch(1)
        var choreographer: Choreographer? = null
        var pane: ViewGroup? = null
        var firstUp = 0L
        var before = Float.NaN
        var reverseUp: Long? = null
        var result: Result<String>? = null
        val trace = ArrayList<Float>()
        fun progress(alpha: Float) = if (opening) alpha / full else 1f - alpha / full
        fun verifyFocus() {
            check(decor.isAttachedToWindow && decor.isShown && activity.hasWindowFocus() && focusLostAt.get() == null) {
                "Demo window $demoWindowId lost input ownership during the frame measurement"
            }
        }
        fun dispatchTap(): Long {
            verifyFocus()
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            val x = slot.exactCenterX() - origin[0]
            val y = slot.exactCenterY() - origin[1]
            val down = SystemClock.uptimeMillis()
            fun dispatch(action: Int, time: Long) {
                val event = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(activity.dispatchTouchEvent(event)) { "Owned demo window rejected touch action $action" } }
                finally { event.recycle() }
            }
            dispatch(MotionEvent.ACTION_DOWN, down)
            verifyFocus()
            val up = SystemClock.uptimeMillis()
            dispatch(MotionEvent.ACTION_UP, up)
            return up
        }
        val observer = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                try {
                    verifyFocus()
                    val current = popupPane(label)
                    check(current != null && pane === current) {
                        "Original pane disappeared or was replaced: opening=$opening phase=$phase alpha=$trace"
                    }
                    val alpha = current.getChildAt(0).alpha
                    val up = reverseUp
                    if (up == null) {
                        check(if (opening) alpha < full else alpha > 0f) {
                            "No in-flight frame for opening=$opening phase=$phase before the old endpoint"
                        }
                        if (progress(alpha) >= phase && alpha > 0f && alpha < full) {
                            before = alpha
                            trace += alpha
                            reverseUp = dispatchTap()
                        }
                    } else {
                        trace += alpha
                        val observed = SystemClock.uptimeMillis()
                        val elapsed = observed - up
                        val timing = "direct_window_dispatch opening=$opening phase=$phase actualPhase=${progress(before)} " +
                            "firstUpMs=$firstUp acceptedUpMs=$up frameObservedMs=$observed frameNanos=$frameTimeNanos " +
                            "interTapMs=${up - firstUp} reversalMs=$elapsed alpha=$trace"
                        check(if (opening) alpha < full else alpha > 0f) { "Reached the old endpoint: $timing" }
                        check(elapsed <= 75) { "No reversing frame within 75ms of delivered UP: $timing" }
                        if (if (opening) alpha < before else alpha > before) {
                            result = Result.success(timing)
                            complete.countDown()
                            return
                        }
                    }
                    checkNotNull(choreographer).postFrameCallback(this)
                } catch (failure: Throwable) {
                    result = Result.failure(failure)
                    complete.countDown()
                }
            }
        }
        try {
            instrumentation.runOnMainSync {
                val previous = popupPane(label)
                check(opening == (previous == null)) { "Unexpected pane state at start of frame measurement" }
                firstUp = dispatchTap()
                pane = checkNotNull(popupPane(label)) { "Starting tap did not establish the in-flight pane" }
                check(opening || pane === previous) { "Starting collapse replaced its pane" }
                choreographer = Choreographer.getInstance().also { it.postFrameCallback(observer) }
            }
            check(complete.await(5, TimeUnit.SECONDS)) { "No terminal frame observation: opening=$opening phase=$phase" }
            return checkNotNull(result).getOrThrow()
        } finally {
            instrumentation.runOnMainSync { choreographer?.removeFrameCallback(observer) }
        }
    }

    @Test fun oneDragCrossesMultipleRowsAndSnapsToTheOriginalSlot() = withActivity {
        val slot = open(it, "Sample format")
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, fraction: Float) {
            guardInput()
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
