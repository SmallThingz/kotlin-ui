package smallthingz.ui.zfm

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeSettingsRow
import smallthingz.ui.components.withRetiringTestActivity
import smallthingz.ui.controls.ControlPalette
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ZfmChooserViewportIntegrationTest {
    @Test fun preferredDestinationWaitsForRealTabWithoutRequestingFocusOrClicking() = withKeyboardChooser {
        main {
            assertTrue(boundary.isInTouchMode)
            assertNull(boundary.findFocus())
            assertTrue(internal.isFocusedByDefault)
            assertFalse(refresh.isFocusedByDefault)
            assertFalse(close.isFocusedByDefault)
        }
        // Goes through ViewRoot's touch-mode/default-focus path, not boundary.dispatchKeyEvent.
        key(KeyEvent.KEYCODE_TAB)
        main {
            assertFalse(boundary.isInTouchMode)
            assertSame(internal, boundary.findFocus())
            assertEquals(0, clicks)
        }
        key(KeyEvent.KEYCODE_TAB)
        main { assertSame(close, boundary.findFocus()) }
        key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
        main { assertSame(internal, boundary.findFocus()); assertEquals(0, clicks) }
        key(KeyEvent.KEYCODE_ENTER)
        main { assertEquals(1, clicks) }
    }

    @Test fun nullPreferenceLeavesNativeManagementEntryUnchanged() = withKeyboardChooser(preferredKey = null) {
        main { assertFalse(internal.isFocusedByDefault); assertNull(boundary.findFocus()) }
        key(KeyEvent.KEYCODE_TAB)
        main { assertSame(refresh, boundary.findFocus()); assertEquals(0, clicks) }
    }

    @Test fun publicationKeepsExistingFocusAndNativeTabShiftTabAndArrowHandling() = withKeyboardChooser {
        instrumentation.setInTouchMode(false)
        main {
            assertTrue(refresh.requestFocus())
            assertTrue(viewport.publish(items.toList(), bottomFirst = false))
            assertSame(refresh, boundary.findFocus())
            assertTrue(close.requestFocus())
            assertTrue(viewport.publish(items, bottomFirst = true))
            assertSame(close, boundary.findFocus())
            assertTrue(refresh.requestFocus())
        }
        key(KeyEvent.KEYCODE_TAB)
        main { assertSame(more, boundary.findFocus()) }
        key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
        main { assertSame(refresh, boundary.findFocus()) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        main {
            assertSame(more, boundary.findFocus())
            assertEquals(View.NO_ID, internal.nextFocusForwardId)
            assertEquals(View.NO_ID, internal.accessibilityTraversalBefore)
            assertEquals(View.NO_ID, internal.accessibilityTraversalAfter)
            assertEquals(0, clicks)
        }
    }

    @Test fun unavailablePreferredRootFallsBackWithoutChangingItsFocusableState() {
        for (unavailable in listOf("disabled", "hidden", "not-focusable", "missing")) {
            withKeyboardChooser {
                main {
                    when (unavailable) {
                        "disabled" -> internal.isEnabled = false
                        "hidden" -> internal.visibility = View.GONE
                        "not-focusable" -> internal.isFocusable = false
                    }
                    assertTrue(viewport.publish(if (unavailable == "missing") items.drop(1) else items))
                    assertFalse(internal.isFocusedByDefault)
                    if (unavailable == "not-focusable") assertFalse(internal.isFocusable)
                    assertNull(boundary.findFocus())
                }
                key(KeyEvent.KEYCODE_TAB)
                main { assertSame(refresh, boundary.findFocus()); assertEquals(0, clicks) }
            }
        }
    }

    @Test fun removalRebindingAndCloseRestoreOnlyFlagsOwnedByTheViewport() = withKeyboardChooser {
        main {
            assertTrue(internal.isFocusedByDefault)
            // An unrelated caller-owned default must not be erased.
            other.isFocusedByDefault = true
            assertTrue(viewport.publish(items.drop(1)))
            assertFalse(internal.isFocusedByDefault)
            assertTrue(other.isFocusedByDefault)
            assertNull(internal.parent)

            val replacement = row("Replacement")
            val replacementItems = listOf(ZfmChooserRoot("internal", replacement), items[1])
            assertTrue(viewport.publish(replacementItems))
            assertTrue(replacement.isFocusedByDefault)
            replacement.bind("Rebound", onClick = { clicks++ })
            assertTrue(viewport.publish(replacementItems.toList()))
            assertTrue(replacement.isFocusedByDefault)
            viewport.close()
            assertFalse(replacement.isFocusedByDefault)
            assertTrue(other.isFocusedByDefault)
            assertNull(boundary.findFocus())
        }
    }

    @Test fun preExistingPreferredFlagSurvivesRemovalAndClose() = withKeyboardChooser(priorDefault = true) {
        main {
            assertTrue(viewport.publish(items.drop(1)))
            assertTrue(internal.isFocusedByDefault)
            assertTrue(viewport.publish(items))
            viewport.close()
            assertTrue(internal.isFocusedByDefault)
        }
    }

    @Test fun detachRestoresHintAndDetachedPublicationDoesNotRearmIt() = withKeyboardChooser {
        main {
            host.removeView(boundary)
            assertFalse(internal.isFocusedByDefault)
            assertTrue(viewport.publish(items))
            assertFalse(internal.isFocusedByDefault)
            host.addView(boundary, FrameLayout.LayoutParams(600, -2))
            assertTrue(internal.isFocusedByDefault)
            assertNull(boundary.findFocus())
            viewport.close()
            assertFalse(internal.isFocusedByDefault)
        }
    }

    @Test fun unchangedPublicationDoesNotReassertCallerClearedHint() = withKeyboardChooser {
        main {
            internal.isFocusedByDefault = false
            assertTrue(viewport.publish(items.toList()))
            assertFalse(internal.isFocusedByDefault)
            viewport.close()
            assertFalse(internal.isFocusedByDefault)
        }
    }

    @Test fun invisibleAncestorDefersInitialPlacementUntilRevealAndAppliesItOnce() = withChooser(hidden = true) {
        layout()
        assertEquals(100, viewport.height)
        assertFalse(viewport.isShown)
        assertEquals(0, viewport.scrollY)
        assertTrue(initialSnapshot().isNotEmpty())

        panel.visibility = View.VISIBLE
        assertTrue(viewport.isShown)
        assertTrue("Reveal must request another layout", boundary.isLayoutRequested)
        layout()
        assertEquals(160, viewport.scrollY)
        assertFalse(viewport.canScrollVertically(1))
        assertTrue(initialSnapshot().isEmpty())

        viewport.scrollTo(0, 0)
        viewport.requestLayout()
        layout()
        assertEquals(0, viewport.scrollY)
    }

    @Test fun inputWhileHiddenRetiresTheSnapshotAndPreventsInitialPlacement() = withChooser(hidden = true) {
        layout()
        assertTrue(initialSnapshot().isNotEmpty())
        viewport.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
        assertTrue(initialSnapshot().isEmpty())

        panel.visibility = View.VISIBLE
        layout()
        assertEquals(0, viewport.scrollY)
        assertTrue(viewport.canScrollVertically(1))
    }

    @Test fun closeDropsTheUncommittedSnapshotWhileTheWindowIsStillAttached() = withChooser(hidden = true) {
        layout()
        assertTrue(initialSnapshot().isNotEmpty())
        viewport.close()
        assertTrue(boundary.isAttachedToWindow)
        assertTrue(initialSnapshot().isEmpty())
    }

    @Test fun refreshDoesNotAnchorARootUnderClippedPadding() = withChooser {
        viewport.clipToPadding = true
        viewport.setPadding(0, 0, 0, 20)
        layout()
        viewport.scrollTo(0, 60)
        // Bottom-first places the new row after the last visible root, moving only clipped roots.
        val next = items.toMutableList().apply {
            add(3, ZfmChooserRoot("new", TestRow(viewport.context)))
        }
        assertTrue(viewport.publish(next))
        assertNotNull(pending())
        layout()
        assertEquals(60, viewport.scrollY)
    }

    @Test fun unchangedPublicationSkipsSnapshotsAndLayoutButManagementResizePreservesTheAnchor() = withChooser {
        layout()
        viewport.scrollTo(0, 60)
        assertEquals(60, viewport.scrollY)
        val initialMeasureCount = rows.sumOf { it.measures }
        assertTrue(initialMeasureCount >= rows.size)

        repeat(3) {
            assertTrue(viewport.publish(items.toList()))
            assertNull(pending())
            assertFalse(boundary.isLayoutRequested)
            assertFalse(viewport.isLayoutRequested)
            layout()
        }
        assertEquals(initialMeasureCount, rows.sumOf { it.measures })

        management.minimumHeight = 40
        assertTrue(viewport.isLayoutRequested)
        assertTrue(viewport.publish(items))
        assertNotNull(pending())
        assertTrue(viewport.publish(items))
        layout()
        assertEquals(80, viewport.scrollY)
        assertNull(pending())
    }

    @Test fun declinedDownThroughTheParentDoesNotBlockLaterAnchoring() = withChooser {
        viewport.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = 20 }
        val footerActions = mutableListOf<Int>()
        val footer = View(viewport.context).apply {
            setOnTouchListener { _, event ->
                footerActions.add(event.actionMasked)
                assertTrue("A reentrant render during DOWN must see the active touch", touchActive())
                management.minimumHeight = 40
                assertTrue(viewport.publish(items))
                assertNull(pending())
                false
            }
        }
        panel.addView(footer, FrameLayout.LayoutParams(-1, 20, Gravity.BOTTOM))
        layout()
        assertEquals(80, viewport.height)
        viewport.scrollTo(0, 60)

        val declinedDownTime = SystemClock.uptimeMillis()
        assertFalse(dispatchTouch(MotionEvent.ACTION_DOWN, 90f, declinedDownTime))
        assertFalse("A declined DOWN must release the touch before any terminal event", touchActive())
        assertFalse(dispatchTouch(MotionEvent.ACTION_UP, 90f, declinedDownTime))
        assertEquals(listOf(MotionEvent.ACTION_DOWN), footerActions)
        layout()
        assertEquals(60, viewport.scrollY)

        management.minimumHeight = 60
        assertTrue(viewport.publish(items))
        assertNotNull(pending())
        layout()
        assertEquals(80, viewport.scrollY)

        val acceptedDownTime = SystemClock.uptimeMillis()
        assertTrue(dispatchTouch(MotionEvent.ACTION_DOWN, 30f, acceptedDownTime))
        assertTrue(touchActive())
        assertTrue(dispatchTouch(MotionEvent.ACTION_CANCEL, 30f, acceptedDownTime))
        assertFalse(touchActive())
    }

    /** Real window/key path; the placement fixture below intentionally blocks descendant focus. */
    private fun withKeyboardChooser(
        preferredKey: String? = "internal",
        priorDefault: Boolean = false,
        test: KeyboardFixture.() -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: KeyboardFixture? = null
        var wasInTouchMode = false
        onMain(instrumentation) { wasInTouchMode = activity.window.decorView.isInTouchMode }
        try {
            instrumentation.setInTouchMode(true)
            withRetiringTestActivity(activity, root = { fixture?.host }, cleanup = { fixture?.viewport?.close() }) {
                val focused = CountDownLatch(1)
                val laidOut = CountDownLatch(1)
                onMain(instrumentation) {
                    val host = object : FrameLayout(activity) {
                        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                            super.onWindowFocusChanged(hasWindowFocus)
                            if (hasWindowFocus) focused.countDown()
                        }
                    }
                    val next = KeyboardFixture(host, preferredKey, priorDefault)
                    fixture = next
                    activity.setContentView(host)
                    host.addView(next.boundary, FrameLayout.LayoutParams(600, -2))
                    next.boundary.doOnLayout { laidOut.countDown() }
                    if (host.hasWindowFocus()) focused.countDown()
                }
                assertTrue("Keyboard chooser window never focused", focused.await(5, TimeUnit.SECONDS))
                assertTrue("Keyboard chooser never laid out", laidOut.await(5, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                val chooser = requireNotNull(fixture)
                chooser.main {
                    assertTrue(boundary.hasWindowFocus())
                    assertTrue(boundary.isInTouchMode)
                    assertNull(host.rootView.findFocus())
                }
                chooser.test()
            }
        } finally {
            instrumentation.setInTouchMode(wasInTouchMode)
        }
    }

    private companion object {
        fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                try { block() } catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
        }
    }

    private class KeyboardFixture(val host: FrameLayout, preferredKey: String?, priorDefault: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val palette = ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
            0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt())
        var clicks = 0
        val refresh = button("Refresh")
        val more = button("More")
        val close = button("Close")
        val internal = row("Internal Storage").apply { isFocusedByDefault = priorDefault }
        val other = row("Root")
        val items = listOf(ZfmChooserRoot("internal", internal), ZfmChooserRoot("root", other))
        private val management = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(refresh, LinearLayout.LayoutParams(-1, 64))
            addView(more, LinearLayout.LayoutParams(-1, 64))
        }
        val viewport = ZfmChooserViewport(host.context, management, 1L, { it == 1L },
            preferredDefaultRootKey = preferredKey)
        private val panel = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            // Keep all candidates visible: these tests isolate entry policy, not scroll placement.
            addView(viewport, LinearLayout.LayoutParams(-1, -2))
            addView(close, LinearLayout.LayoutParams(-1, 64))
        }
        val boundary = viewport.wrapPanel(panel)

        init { assertTrue(viewport.publish(items)) }

        private fun button(label: String) = Button(host.context).apply {
            text = label
            isFocusableInTouchMode = false
            setOnClickListener { clicks++ }
        }

        fun row(label: String) = NativeSettingsRow(host.context, palette).apply {
            bind(label, onClick = { clicks++ })
        }

        fun main(block: KeyboardFixture.() -> Unit) = onMain(instrumentation) { block() }

        fun key(code: Int, modifiers: Int = 0) {
            val now = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, modifiers))
            instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, modifiers))
            instrumentation.waitForIdleSync()
        }
    }

    private fun withChooser(hidden: Boolean = false, test: Fixture.() -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: FrameLayout? = null
        var fixture: Fixture? = null
        withRetiringTestActivity(activity, root = { host }, cleanup = { fixture?.viewport?.close() }) {
            val focused = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val root = object : FrameLayout(activity) {
                    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                        super.onWindowFocusChanged(hasWindowFocus)
                        if (hasWindowFocus) focused.countDown()
                    }
                }.apply { descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
                host = root
                activity.setContentView(root)
                if (root.hasWindowFocus()) focused.countDown()
            }
            assertTrue("Chooser fixture window never focused", focused.await(5, TimeUnit.SECONDS))
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                try {
                    val parent = requireNotNull(host)
                    val chooser = Fixture(parent, hidden).also { fixture = it }
                    parent.addView(chooser.boundary, FrameLayout.LayoutParams(120, 100))
                    chooser.test()
                } catch (error: Throwable) {
                    failure = error
                }
            }
            failure?.let { throw it }
        }
    }

    private class Fixture(private val parent: FrameLayout, hidden: Boolean) {
        private val context = parent.context
        val management = View(context).apply { minimumHeight = 20 }
        val viewport = ZfmChooserViewport(context, management, 1L, { it == 1L })
        val rows = List(6) { TestRow(context) }
        val items = rows.mapIndexed { index, view -> ZfmChooserRoot(index.toString(), view) }
        val panel = FrameLayout(context).apply {
            addView(viewport, FrameLayout.LayoutParams(-1, -1))
            visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        }
        val boundary = viewport.wrapPanel(panel)

        init { assertTrue(viewport.publish(items)) }

        fun layout() {
            boundary.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
            boundary.layout(0, 0, 120, 100)
        }

        fun initialSnapshot() = field(boundary, "initialScrollPositions") as List<*>
        fun pending() = field(viewport, "pending")
        fun touchActive() = field(viewport, "touchActive") as Boolean

        fun dispatchTouch(action: Int, y: Float, downTime: Long): Boolean {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 60f, y, 0)
            return try { parent.dispatchTouchEvent(event) } finally { event.recycle() }
        }

        private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner)
    }

    private class TestRow(context: Context) : View(context) {
        var measures = 0
            private set

        init { minimumHeight = 40 }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measures++
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
