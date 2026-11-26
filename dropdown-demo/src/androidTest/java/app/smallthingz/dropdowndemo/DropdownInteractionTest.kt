package app.smallthingz.dropdowndemo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.app.Activity
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Canvas
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
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.DropdownField
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.email.EmailDropdownGroup
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeAnchoredDropdown
import smallthingz.ui.components.NativeDropdownContent
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
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

    @Test fun emailChoicesShowOnlyOptionLabelsAndMarkTheCommittedValue() = withActivity { activity ->
        val label = "Avatar type"
        val choices = listOf("Monogram", "Icon", "Image")
        var current = NativeDropdownContent(label, choices.first(), choices) { it }
        val commits = mutableListOf<String>()
        lateinit var group: DropdownGroup
        lateinit var field: DropdownField<String>
        instrumentation.runOnMainSync {
            val host = activity.findViewById<FrameLayout>(android.R.id.content)
            group = EmailDropdownGroup(ControlTheme.resolve(activity, false), { host }, { true })
            field = group.field(activity, current) { selected ->
                commits += selected
                current = current.copy(selected = selected)
                field.bind(current)
            }
            host.addView(field.view, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER))
        }
        fun openChoices() {
            instrumentation.runOnMainSync { assertTrue(field.view.performClick()) }
            await("Email choices not mounted") {
                choices.all { option -> nodes().any { it.contentDescription == option && it.isVisibleToUser } }
            }
        }
        fun assertPresentation(expected: String) {
            instrumentation.runOnMainSync {
                val wheel = checkNotNull(popupPane(label)).getChildAt(1) as ViewGroup
                val rows = (0 until wheel.childCount).map(wheel::getChildAt).filterIsInstance<NativeDropdown<*>>()
                assertEquals(choices.toSet(), rows.map { it.createAccessibilityNodeInfo().contentDescription.toString() }.toSet())
                for (row in rows) {
                    val name = row.createAccessibilityNodeInfo().contentDescription.toString()
                    val pageTitle = row.getChildAt(0)
                    val option = row.getChildAt(1) as ParagraphView
                    assertFalse(pageTitle.isShown)
                    assertTrue(option.isShown)
                    assertEquals(name, option.getText())
                    assertEquals(name == expected, row.createAccessibilityNodeInfo().isSelected)

                    // Render the trailing glyph itself, independent of wheel position or focus.
                    val mark = row.getChildAt(2)
                    val bitmap = Bitmap.createBitmap(mark.width, mark.height, Bitmap.Config.ARGB_8888)
                    try {
                        mark.draw(Canvas(bitmap))
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val opaque = pixels.filter { it ushr 24 == 255 }.toSet()
                        if (name == expected) {
                            assertTrue("Selected option must paint its circle and contrasting check", opaque.size >= 2)
                        } else {
                            assertTrue("Other options must have no trailing glyph", pixels.all { it == 0 })
                        }
                    } finally { bitmap.recycle() }
                }
            }
        }
        try {
            await("Email field not laid out") {
                var ready = false
                instrumentation.runOnMainSync { ready = field.view.isShown && field.view.width > 0 && field.view.height > 0 }
                ready
            }
            openChoices()
            assertPresentation("Monogram")
            guardInput()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            assertPresentation("Monogram")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
            await("Email popup did not cancel") { wheel() == null }
            assertTrue(commits.isEmpty())
            openChoices()
            assertTrue(nodes().single { it.contentDescription == "Icon" && it.isClickable }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Email popup did not commit") { wheel() == null }
            assertEquals(listOf("Icon"), commits)
            openChoices()
            assertPresentation("Icon")
        } finally {
            instrumentation.runOnMainSync {
                group.close()
                field.close()
                (field.view.parent as? ViewGroup)?.removeView(field.view)
            }
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

    @Test fun topEdgeKeepsThreeOptionsInOriginalOrderAndStopsAtBothEnds() = withActivity {
        val label = "Top edge"
        val options = listOf("One", "Two", "Three")
        val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val slot = open(it, label)
        fun optionRows() = nodes().filter { row ->
            row.isClickable && row.isVisibleToUser && row.className?.toString() == "android.widget.Button" &&
                row.contentDescription.toString() in options
        }
        val initialLabels = optionRows().map { it.contentDescription.toString() }
        assertTrue(initialLabels.isNotEmpty())
        assertEquals(options.takeLast(initialLabels.size), initialLabels)
        assertEquals(slot, selectedBounds("Three"))
        assertFalse(checkNotNull(wheel()).performAction(forward))
        step(backward, label, "Two")
        assertEquals(slot, selectedBounds("Two"))
        step(backward, label, "One")
        assertEquals(slot, selectedBounds("One"))
        assertFalse(checkNotNull(wheel()).performAction(backward))
        await("Three original choices did not expand") { optionRows().size == options.size }
        val rows = optionRows()
        assertEquals(options, rows.map { it.contentDescription.toString() })
        assertTrue(rows.single { it.contentDescription.toString() == "Two" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitClosed("Two")
    }

    @Test fun sampleRatesKeepTheAnchorSlotFromTheFirstToTheLastChoice() = withActivity {
        val label = "Sample rate"
        val rates = listOf(8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000, 176400, 192000)
        val slot = open(it, label, measureFirstDraw = true)
        val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val initial = rates.indexOf(44100)
        assertEquals(slot, selectedBounds("${rates[initial]} Hz"))
        for (index in (initial - 1) downTo 0) {
            val value = "${rates[index]} Hz"
            step(backward, label, value)
            assertEquals(value, slot, selectedBounds(value))
        }
        assertFalse(checkNotNull(wheel()).performAction(backward))
        for (index in 1..rates.lastIndex) {
            val value = "${rates[index]} Hz"
            step(forward, label, value)
            assertEquals(value, slot, selectedBounds(value))
        }
        assertFalse(checkNotNull(wheel()).performAction(forward))
        tap(slot)
        awaitClosed("${rates.last()} Hz")
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
            controller = NativeAnchoredDropdown(activity, host, ControlTheme.resolve(activity, true, appearance)
                .withTypography(Density(resources.displayMetrics.density, resources.configuration.fontScale), createFontFamilyResolver(activity)))
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

    @Test fun rapidTapsReverseAnInFlightAnimationBeforeItReachesItsEndpoint() = measureReversalWindows(
        "narrow-reveal-progress-windows-v1", listOf(.30f to .40f, .75f to .85f, .97f to 1f))

    // Sample one real state in each disjoint reveal-progress bin. These are eased visual
    // progress bins, not exact phases or fractions of elapsed animation time.
    @Test fun sampledRevealProgressBinsReverseWithin75ms() = measureReversalWindows(
        "sampled-reveal-progress-bins-v2", listOf(.10f to .50f, .50f to .90f, .90f to 1f))

    private fun measureReversalWindows(protocol: String, windows: List<Pair<Float, Float>>) = withActivity {
        report("Reversal protocol=$protocol windows=$windows; sampled-state coverage only")
        val label = "Sample format"
        val slot = open(it, label, measureFirstDraw = true)
        val full = NativeUiAppearance.Default.anchoredDropdown.scrimOpacity
        await("Initial expansion did not finish") { scrimAlpha(label) == full }
        // The initial performClick bypasses the trigger's touch/press path. Exercise it once
        // before measuring warm reversals; this fixed close/open cycle is not a failed-case retry.
        guardInput()
        instrumentation.runOnMainSync { dispatchWindowTap(it, slot) }
        awaitClosed("16-bit PCM")
        guardInput()
        instrumentation.runOnMainSync { dispatchWindowTap(it, slot) }
        await("Primed wheel did not fully expand") { scrimAlpha(label) == full }
        report("Window-dispatch priming complete: one untimed close/open cycle; one attempt per measured case")
        // A skipped window fails immediately; no adaptive bounds or repeated attempts.
        for ((phase, phaseEndExclusive) in windows) {
            for (opening in listOf(false, true)) {
                if (opening) {
                    tap(slot)
                    awaitClosed("16-bit PCM")
                }
                report(measureFrameReversal(it, label, slot, full, phase, phaseEndExclusive, opening))
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

    private fun verifyWindowInputOwnership(activity: Activity) {
        val decor = activity.window.decorView
        check(decor.isAttachedToWindow && decor.isShown && activity.hasWindowFocus() && focusLostAt.get() == null) {
            "Demo window $demoWindowId lost input ownership during window dispatch/observation"
        }
    }

    /** Main-thread dispatch, with UP time recorded before delivering it to the real window. */
    private fun dispatchWindowTap(activity: Activity, slot: Rect): Long {
        verifyWindowInputOwnership(activity)
        val origin = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
        val x = slot.exactCenterX() - origin[0]
        val y = slot.exactCenterY() - origin[1]
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int, time: Long) {
            val event = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { check(activity.dispatchTouchEvent(event)) { "Owned demo window rejected touch action $action" } }
            finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN, down)
        verifyWindowInputOwnership(activity)
        val up = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_UP, up)
        return up
    }

    private data class ReversalFrame(val observedMs: Long, val frameNanos: Long, val alpha: Float, val phase: Float)

    /** Measures the real control's frame response, independently of system input transport. */
    private fun measureFrameReversal(activity: Activity, label: String, slot: Rect, full: Float,
                                     phase: Float, phaseEndExclusive: Float, opening: Boolean): String {
        // This verifies the external window authority before scheduling Main callbacks. Each local
        // dispatch also checks the original Activity still owns focus. No input is injected here.
        guardInput()
        val complete = CountDownLatch(1)
        var choreographer: Choreographer? = null
        var pane: ViewGroup? = null
        var observerPostedAt = 0L
        var firstUp = 0L
        var firstDispatchReturnedAt = 0L
        var before = Float.NaN
        var reverseUp: Long? = null
        var result: Result<String>? = null
        val trace = ArrayList<ReversalFrame>()
        fun progress(alpha: Float) = if (opening) alpha / full else 1f - alpha / full
        fun evidence() = "direct_window_dispatch opening=$opening phase=$phase phaseEndExclusive=$phaseEndExclusive " +
            "actualPhase=${progress(before)} observerPostedMs=$observerPostedAt firstUpMs=$firstUp " +
            "firstDispatchReturnedMs=$firstDispatchReturnedAt acceptedUpMs=$reverseUp samples=$trace"
        val observer = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                try {
                    verifyWindowInputOwnership(activity)
                    val current = popupPane(label)
                    check(current != null && pane === current) {
                        "Original pane disappeared or was replaced before a successful observation: ${evidence()}"
                    }
                    val alpha = current.getChildAt(0).alpha
                    val observed = SystemClock.uptimeMillis()
                    val observedPhase = progress(alpha)
                    trace += ReversalFrame(observed, frameTimeNanos, alpha, observedPhase)
                    val up = reverseUp
                    if (up == null) {
                        check(observedPhase < phaseEndExclusive) {
                            "Phase coverage missed: required [$phase,$phaseEndExclusive), observed=$observedPhase; ${evidence()}"
                        }
                        check(if (opening) alpha < full else alpha > 0f) {
                            "No in-flight frame before the old endpoint: ${evidence()}"
                        }
                        if (observedPhase >= phase && alpha > 0f && alpha < full) {
                            before = alpha
                            reverseUp = dispatchWindowTap(activity, slot)
                        }
                    } else {
                        val elapsed = observed - up
                        fun timing() = "${evidence()} frameObservedMs=$observed frameNanos=$frameTimeNanos " +
                            "interTapMs=${up - firstUp} reversalMs=$elapsed"
                        check(if (opening) alpha < full else alpha > 0f) { "Reached the old endpoint: ${timing()}" }
                        check(elapsed <= 75) { "No reversing frame within 75ms of delivered UP: ${timing()}" }
                        if (if (opening) alpha < before else alpha > before) {
                            result = Result.success(timing())
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
                // Arm observation before input starts animation or creates/layouts a new pane.
                // This does not force frame delivery/order; a skipped phase still fails above.
                choreographer = Choreographer.getInstance().also {
                    observerPostedAt = SystemClock.uptimeMillis()
                    it.postFrameCallback(observer)
                }
                firstUp = dispatchWindowTap(activity, slot)
                firstDispatchReturnedAt = SystemClock.uptimeMillis()
                pane = checkNotNull(popupPane(label)) { "Starting tap did not establish the in-flight pane" }
                check(opening || pane === previous) { "Starting collapse replaced its pane" }
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
