package smallthingz.ui.components

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.reverb.ReverbBrowserPanelReorder
import smallthingz.ui.reverb.ReverbBrowserPanelSettingRow
import smallthingz.ui.reverb.ReverbBrowserPanelSwipe
import smallthingz.ui.reverb.ReverbBrowserPanelTab
import smallthingz.ui.reverb.ReverbBrowserPanelTabAction
import smallthingz.ui.reverb.ReverbBrowserPanelTabs
import smallthingz.ui.reverb.ReverbDesign

@RunWith(AndroidJUnit4::class)
class ReverbBrowserTabOwnershipIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun swipesNeedAnOwnerAndAuthoritativeRetentionRestoresTheSameCard() = mounted { f ->
        lateinit var gesture: ReverbBrowserPanelSwipe
        lateinit var surface: View
        val actions = mutableListOf<Pair<String?, ReverbBrowserPanelTabAction>>()
        main {
            val row = f.row("Alpha")
            gesture = generateSequence(row.parent as? View) { it.parent as? View }
                .filterIsInstance<ReverbBrowserPanelSwipe>().first()
            surface = gesture.getChildAt(0)
            swipe(gesture) { assertEquals("No swipe translation without a close owner", 0f, surface.translationX, 0f) }
        }
        // The ownerless stroke can post a platform click while it stays inside the preview.
        // Deliver that work before a separate gesture installs and uses its action owner.
        instrumentation.waitForIdleSync()
        main {
            assertNull(f.panel.onTabAction)
            f.panel.onTabAction = { key, action -> actions.add(key to action) }
            swipe(gesture) { assertTrue("The owned gesture must move the card", surface.translationX > 0f) }
        }
        await("Tab close request") { actions.isNotEmpty() }
        main {
            val close = "a" to ReverbBrowserPanelTabAction.CLOSE
            assertEquals(listOf(close), actions)
            assertTrue(surface.translationX > 0f)
            f.panel.updateTab(f.alpha.copy(origin = "New preview metadata"))
            assertTrue("Metadata does not answer the pending close", surface.translationX > 0f)
            f.panel.bindTabs("Tabs", f.entries)
            assertSame(surface, gesture.getChildAt(0))
            assertEquals("The authoritative retained key restores the card", 0f, surface.translationX, 0f)
            assertTrue(f.row("Alpha").performClick())
            assertEquals(listOf(close, "a" to ReverbBrowserPanelTabAction.SELECT), actions)
        }
    }

    @Test fun removingFocusedTabsHandsOffToNeighborAndHonorsAnOwnerAcquiredDuringAdmission() = mounted { f ->
        main {
            val alpha = f.row("Alpha")
            val beta = f.row("Beta")
            val gamma = f.row("Gamma")
            assertTrue(alpha.requestFocus())
            f.panel.bindTabs("Tabs", listOf(f.beta, f.gamma))
            assertSame(beta, f.root.findFocus())
            var checked = false
            f.root.probe = {
                f.root.probe = null
                checked = true
                assertTrue(f.sink.requestFocus())
            }
            f.panel.bindTabs("Tabs", listOf(f.gamma))
            assertTrue("The surviving row's input admission must run", checked)
            assertSame(f.sink, f.root.findFocus())
            assertFalse(gamma.hasFocus())
        }
    }

    @Test fun accessibilityReorderPreservesKeyboardAndHonorsTheCallbacksNewAccessibilityOwner() =
        withTouchExploration {
            mounted { f ->
                main {
                    val alpha = f.row("Alpha")
                    val beta = f.row("Beta")
                    assertTrue(f.sink.requestFocus())
                    assertTrue(alpha.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
                    val calls = mutableListOf<Pair<String, String>>()
                    f.panel.onTabMove = { key, target ->
                        calls.add(key to target)
                        assertSame("Reorder must not borrow keyboard focus", f.sink, f.root.findFocus())
                        f.panel.bindTabs("Tabs", listOf(f.beta, f.alpha, f.gamma))
                        assertTrue(beta.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
                    }
                    assertTrue(alpha.performAccessibilityAction(moveLater(alpha), null))
                    assertEquals(listOf("a" to "b"), calls)
                    assertSame(f.sink, f.root.findFocus())
                    assertTrue("The host's explicit accessibility transfer wins", beta.isAccessibilityFocused)
                    assertFalse(alpha.isAccessibilityFocused)

                    // Provider queries are host callbacks. During the next move the provider
                    // invalidates the proposed restore target without taking focus itself.
                    var queried = false
                    val provider = object : AccessibilityNodeProvider() {
                        override fun findFocus(focus: Int): AccessibilityNodeInfo? {
                            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && !queried) {
                                queried = true
                                f.panel.updateTab(f.alpha.copy(enabled = false))
                            }
                            return null
                        }
                    }
                    f.panel.onTabMove = { key, target ->
                        calls.add(key to target)
                        assertTrue(alpha.performAccessibilityAction(
                            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null))
                        f.sink.accessibilityDelegate = object : View.AccessibilityDelegate() {
                            override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProvider = provider
                        }
                    }
                    assertTrue(alpha.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
                    try {
                        assertTrue(alpha.performAccessibilityAction(moveLater(alpha), null))
                        assertEquals(listOf("a" to "b", "a" to "c"), calls)
                        assertTrue("The focus lookup must exercise provider reentry", queried)
                        assertFalse(alpha.isEnabled)
                        assertFalse("A disabled source cannot regain accessibility focus", alpha.isAccessibilityFocused)
                    } finally {
                        f.sink.accessibilityDelegate = null
                    }
                }
            }
        }

    @Test fun metadataKeepsTheOwnedDragAndExistingMoveActionsWhileEligibilityRetiresThem() = mounted { f ->
        main {
            val alpha = f.row("Alpha")
            val calls = mutableListOf<Pair<String, String>>()
            f.panel.onTabMove = { key, target -> calls.add(key to target) }
            val originalAction = moveLater(alpha)
            val reorder = reorder(f.panel)
            val drag = checkNotNull(reorder.begin("a"))
            f.panel.updateTab(f.beta.copy(title = "Updated target"))
            f.panel.updateTab(f.alpha.copy(title = "Updated source"))
            assertEquals("a", reorder.source(drag))
            assertSame(alpha, f.row("Updated source"))
            assertEquals(originalAction, moveLater(alpha))
            assertTrue(alpha.performAccessibilityAction(originalAction, null))
            assertEquals(listOf("a" to "b"), calls)
            val nextDrag = checkNotNull(reorder.begin("a"))
            f.panel.updateTab(f.beta.copy(enabled = false))
            assertNull(reorder.source(nextDrag))
            assertFalse(alpha.performAccessibilityAction(originalAction, null))
            assertEquals(listOf("a" to "b"), calls)
        }
    }

    @Test fun themeRebindRetiresPendingGesturesAndKeepsDeliveredCloseOffscreen() = mounted { f ->
        val actions = mutableListOf<Pair<String?, ReverbBrowserPanelTabAction>>()
        lateinit var gesture: ReverbBrowserPanelSwipe
        lateinit var surface: View
        val context = f.panel.context
        val frameAfterRetirement = CountDownLatch(1)
        main {
            val light = ReverbDesign.theme(context, false)
            val dark = ReverbDesign.theme(context, true)
            val row = f.row("Alpha")
            gesture = generateSequence(row.parent as? View) { it.parent as? View }
                .filterIsInstance<ReverbBrowserPanelSwipe>().first()
            surface = gesture.getChildAt(0)
            f.panel.onTabAction = { key, action -> actions.add(key to action) }
            swipe(gesture, afterDown = { f.panel.bindTheme(dark) }) {
                assertEquals("A pre-theme DOWN cannot take swipe ownership", 0f, surface.translationX, 0f)
            }
            assertTrue(actions.isEmpty())

            f.panel.onTabMove = { _, _ -> throw AssertionError("A stale drag cannot request reorder") }
            val reorder = reorder(f.panel)
            val staleDrag = checkNotNull(reorder.begin("a"))
            assertEquals("a", reorder.source(staleDrag))
            f.panel.bindTheme(light)
            // The card DROP listener resolves the source from this exact lease.
            assertNull("The pre-theme token has no source for a later DROP", reorder.source(staleDrag))

            swipe(gesture) {
                assertTrue("Precondition: this swipe already owns motion", surface.translationX > 0f)
                f.panel.bindTheme(dark)
                assertEquals("Rebind retires the active swipe before CLOSE", 0f, surface.translationX, 0f)
            }
            assertTrue(actions.isEmpty())

            swipe(gesture) { assertTrue(surface.translationX > 0f) }
            assertTrue("The released swipe still draws its exit before CLOSE", surface.translationX > 0f)
            assertTrue("Rebind must precede delivery of CLOSE", actions.isEmpty())
            f.panel.bindTheme(light)
            assertEquals("Rebind retires the released exit before CLOSE", 0f, surface.translationX, 0f)
            gesture.postOnAnimation { frameAfterRetirement.countDown() }
        }
        assertTrue("Expected a frame before admitting a fresh swipe", frameAfterRetirement.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        main {
            assertTrue("The retired exit must not publish CLOSE on the next frame", actions.isEmpty())
            assertEquals("The retired animator cannot move the restored card", 0f, surface.translationX, 0f)
            swipe(gesture) { assertTrue("A fresh gesture remains available", surface.translationX > 0f) }
        }
        await("Close from the fresh swipe") { actions.isNotEmpty() }
        main {
            val close = "a" to ReverbBrowserPanelTabAction.CLOSE
            assertEquals(listOf(close), actions)
            val deliveredTranslation = surface.translationX
            assertTrue(deliveredTranslation > 0f)
            f.panel.bindTheme(ReverbDesign.theme(context, true))
            assertEquals("A delivered close must stay offscreen during paint binding",
                deliveredTranslation, surface.translationX, 0f)
            assertEquals(listOf(close), actions)
            f.panel.bindTabs("Tabs", f.entries)
            assertEquals("An authoritative retained key restores the same card", 0f, surface.translationX, 0f)
        }
    }

    @Test fun themeRebindPreservesPendingAccessibilityRemovalHandoff() = withTouchExploration {
        mounted { f ->
            lateinit var beta: ReverbBrowserPanelSettingRow
            main {
                val alpha = f.row("Alpha")
                beta = f.row("Beta")
                assertTrue(f.sink.requestFocus())
                assertTrue(alpha.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
                f.panel.bindTabs("Tabs", listOf(f.beta, f.gamma))
                assertFalse("The surviving row has not reached its deferred handoff", beta.isAccessibilityFocused)
                f.panel.bindTheme(ReverbDesign.theme(f.panel.context, true))
                assertSame(beta, f.row("Beta"))
                assertSame(f.sink, f.root.findFocus())
            }
            await("Deferred accessibility focus on the surviving tab") { beta.isAccessibilityFocused }
            main { assertSame(f.sink, f.root.findFocus()) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun reorder(panel: ReverbBrowserPanelTabs<String>): ReverbBrowserPanelReorder<String> {
        // Seed and read the local lease deterministically, independent of WindowManager drag pickup.
        val field = ReverbBrowserPanelTabs::class.java.getDeclaredField("reorder").apply { isAccessible = true }
        return field.get(panel) as ReverbBrowserPanelReorder<String>
    }

    @Suppress("DEPRECATION")
    private fun moveLater(row: View): Int {
        val node = checkNotNull(row.createAccessibilityNodeInfo())
        return try {
            node.actionList.single { it.label?.toString() == row.context.getString(R.string.reverb_browser_tab_move_later) }.id
        } finally { node.recycle() }
    }

    private fun swipe(card: View, afterDown: () -> Unit = {}, duringMove: () -> Unit) {
        assertTrue(card.width > 0 && card.height > 0)
        val downTime = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { y = card.height / 2f; pressure = 1f; size = 1f })
        fun send(action: Int, afterDown: Long, x: Float) {
            coords[0].x = x
            val event = MotionEvent.obtain(downTime, downTime + afterDown, action, 1, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { card.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0L, card.width * .2f)
        try {
            afterDown()
            send(MotionEvent.ACTION_MOVE, 16L, card.width * .8f)
            duringMove()
        } catch (failure: Throwable) {
            send(MotionEvent.ACTION_CANCEL, 32L, card.width * .8f)
            throw failure
        }
        send(MotionEvent.ACTION_UP, 32L, card.width * .8f)
    }

    private class Gate(activity: Activity) : LinearLayout(activity), NativeInputGate {
        var probe: (() -> Unit)? = null
        override val acceptsNativeInput: Boolean get() { probe?.invoke(); return true }
    }

    private class Fixture(activity: Activity) {
        val alpha = ReverbBrowserPanelTab("a", "Alpha", null, null, null, true, "Current", "Close Alpha")
        val beta = alpha.copy(key = "b", title = "Beta", current = false, closeLabel = "Close Beta")
        val gamma = beta.copy(key = "c", title = "Gamma", closeLabel = "Close Gamma")
        val entries = listOf(alpha, beta, gamma)
        val panel = ReverbBrowserPanelTabs<String>(activity, ReverbDesign.theme(activity, false), "Tabs", "Back", "New")
        val sink = Button(activity).apply { text = "Host action"; isFocusableInTouchMode = true }
        val root = Gate(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(sink, LinearLayout.LayoutParams(-1, -2))
        }

        fun row(title: String): ReverbBrowserPanelSettingRow =
            descendants(panel).filterIsInstance<ReverbBrowserPanelSettingRow>()
                .single { it.titleView.text.toString() == title }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun mounted(test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var installed: Fixture? = null
        withRetiringTestActivity(activity, root = { installed?.root }, cleanup = {
            installed?.let { it.root.probe = null; it.panel.close() }
        }) {
            val f = main {
                Fixture(activity).also {
                    installed = it
                    it.panel.bindTabs("Tabs", it.entries)
                    activity.setContentView(it.root)
                    for (entry in it.entries) it.row(entry.title).isFocusableInTouchMode = true
                }
            }
            await("Attached tab panel") {
                f.panel.isAttachedToWindow && f.panel.hasWindowFocus() && f.panel.isLaidOut && !f.panel.isLayoutRequested
            }
            test(f)
        }
    }

    private fun withTouchExploration(test: () -> Unit) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info = checkNotNull(automation.serviceInfo)
        val original = info.flags
        val manager = checkNotNull(instrumentation.targetContext.getSystemService(AccessibilityManager::class.java))
        try {
            info.flags = original or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            automation.serviceInfo = info
            await("Accessibility touch exploration") { manager.isTouchExplorationEnabled }
            test()
        } finally {
            info.flags = original
            automation.serviceInfo = info
        }
    }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!main(ready)) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Timed out waiting for $description")
            SystemClock.sleep(20)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
