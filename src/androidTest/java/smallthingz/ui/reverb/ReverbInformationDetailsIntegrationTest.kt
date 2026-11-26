package smallthingz.ui.reverb

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeInputGate
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeToggleRow
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.util.closePreservingPrimaryFailure

/** Foreground Android regressions. No provider, grants, network, or package discovery involved. */
@RunWith(AndroidJUnit4::class)
class ReverbInformationDetailsIntegrationTest {
    @Test fun namedSemanticDetailsDoesNotToggleAndRejectsRetiredOrDisabledRows() = fixture { f ->
        main {
            assertFalse(hasLongAction(f.row)) // Backward-compatible default.
            f.list.bindDetails(4, "Show integration details") { key, anchor ->
                assertSame(f.row, anchor)
                f.details += key
            }
            val action = f.row.createAccessibilityNodeInfo()!!.actionList.single {
                it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK
            }
            assertEquals("Show integration details", action.label.toString())
            assertTrue(longAction(f.row))
            assertEquals(listOf("provider"), f.details)
            assertTrue(f.changes.isEmpty())
            assertTrue(f.row.performClick())
            assertEquals(listOf("provider" to true), f.changes)
            f.gate.admitted = false
            assertFalse(longAction(f.row))
            f.gate.admitted = true
            f.list.bind(listOf(f.item.copy(enabled = false)), null)
            assertFalse(hasLongAction(f.row))
            assertFalse(longAction(f.row))
            assertFalse(f.row.performClick())
            assertFalse(f.row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf("provider" to true), f.changes)
            f.list.bind(listOf(f.item), null)
            f.list.bindDetails(4, "Show integration details", null)
            assertFalse(hasLongAction(f.row))
            assertFalse(f.row.performLongClick())
            f.installDetails()
            f.list.bind(emptyList(), "No integrations")
            assertFalse(f.row.performLongClick())
            assertFalse(longAction(f.row))
            f.list.close()
            assertFalse(f.row.performLongClick())
            assertEquals(listOf("provider"), f.details)
        }
    }

    @Test fun platformLongClickSurvivesDetailsBindingAndRemoval() = fixture { f ->
        main {
            var platformClicks = 0
            f.row.setOnLongClickListener { platformClicks++; true }
            assertTrue(f.row.performLongClick())
            f.list.bindDetails(Int.MAX_VALUE, "", null)
            assertTrue(f.row.isLongClickable)
            assertTrue(f.row.performLongClick(0f, 0f))
            f.installDetails()
            assertTrue(f.row.performLongClick())
            assertEquals(listOf("provider"), f.details)
            assertEquals(2, platformClicks)
            f.gate.admitted = false
            assertFalse(f.row.performLongClick())
            assertEquals(listOf("provider"), f.details)
            assertEquals(2, platformClicks)
            f.gate.admitted = true
            f.list.bindDetails(Int.MAX_VALUE, "", null)
            assertTrue(f.row.isLongClickable)
            assertTrue(f.row.performLongClick())
            assertEquals(3, platformClicks)
            assertTrue(f.changes.isEmpty())
        }
    }

    @Test fun pointerAndKeyboardLongPressConsumeUpWithoutToggle() = fixture { f ->
        main { f.installDetails(); touch(f.row, MotionEvent.ACTION_DOWN) }
        awaitLongPress()
        main {
            touch(f.row, MotionEvent.ACTION_UP)
            assertEquals(listOf("provider"), f.details)
            assertTrue(f.changes.isEmpty())
            f.row.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        awaitLongPress()
        main {
            f.row.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            assertEquals(listOf("provider", "provider"), f.details)
            assertTrue(f.changes.isEmpty())
            touch(f.row, MotionEvent.ACTION_DOWN)
            touch(f.row, MotionEvent.ACTION_UP)
            assertEquals(listOf("provider" to true), f.changes)
        }
    }

    @Test fun pendingGesturesRetireOnCopyRebindCallbackReplacementCancelAndFocusLoss() = fixture { f ->
        var sheet: ReverbInformationDetailsSheet? = null
        try {
        main {
            f.installDetails()
            touch(f.row, MotionEvent.ACTION_DOWN)
            f.list.bind(listOf(f.item.copy(detail = "Updated description")), null)
        }
        awaitLongPress()
        main {
            touch(f.row, MotionEvent.ACTION_UP)
            assertTrue(f.details.isEmpty())
            assertTrue(f.changes.isEmpty())
            touch(f.row, MotionEvent.ACTION_DOWN)
            f.list.bindDetails(4, "New details action") { key, _ -> f.details += "new:$key" }
        }
        awaitLongPress()
        main {
            touch(f.row, MotionEvent.ACTION_UP)
            assertTrue(f.details.isEmpty())
            assertTrue(longAction(f.row))
            assertEquals(listOf("new:provider"), f.details)
            touch(f.row, MotionEvent.ACTION_DOWN)
            touch(f.row, MotionEvent.ACTION_CANCEL)
            f.row.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            // A real shared sheet transfers window focus through the installed observer.
            sheet = ReverbInformationDetailsSheet(f.row, f.theme, "Details", "Full text", "Close") {}
            sheet!!.show()
        }
        awaitLongPress()
        main {
            // Anchor focus loss must cancel the row's pending key, not dismiss its own sheet.
            assertFalse(f.row.hasWindowFocus())
            sheet!!.close()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        main {
            assertTrue(f.row.hasWindowFocus())
            touch(f.row, MotionEvent.ACTION_UP)
            f.row.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            assertEquals(listOf("new:provider"), f.details)
            assertTrue(f.changes.isEmpty())
            touch(f.row, MotionEvent.ACTION_DOWN)
            f.list.close()
        }
        awaitLongPress()
        main {
            touch(f.row, MotionEvent.ACTION_UP)
            assertFalse(longAction(f.row))
            assertEquals(listOf("new:provider"), f.details)
            assertTrue(f.changes.isEmpty())
        }
        } finally { main { sheet?.close() } }
    }

    @Test fun previewUsesFourVisualLinesAndKeepsFullBackingText() = fixture { f ->
        main {
            val full = "A\nA\nA\nA\nA\nFinal retained description"
            f.list.bind(listOf(f.item.copy(detail = full)), null)
            val summary = descendants(f.row).filterIsInstance<ParagraphView>().single { it.getText() == full }
            val width = View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            summary.measure(width, height)
            val unlimitedHeight = summary.measuredHeight
            f.installDetails()
            summary.measure(width, height)
            val fourLines = f.theme.paragraph(f.activity, "A\nA\nA\nA", NativeTypographyRole.BODY_SMALL)
            fourLines.measure(width, height)
            assertEquals(fourLines.measuredHeight, summary.measuredHeight)
            assertTrue(unlimitedHeight > summary.measuredHeight)
            assertEquals(full, summary.getText())
            f.list.bindDetails(Int.MAX_VALUE, "", null)
            summary.measure(width, height)
            assertEquals(unlimitedHeight, summary.measuredHeight)
            assertEquals(full, summary.getText())
        }
    }

    @Test fun unshownDetailsSheetOwnerCloseIsSilentAndIdempotent() = fixture { f ->
        main {
            var dismissed = 0
            val sheet = ReverbInformationDetailsSheet(f.row, f.theme, "Provider",
                "Full retained description\n\nPackage: example.provider", "Close") { dismissed++ }
            sheet.close()
            sheet.close()
            assertEquals(0, dismissed)
        }
    }

    @Test fun repeatedShowKeepsTheSameDetailsWindowUntilUserClosesIt() = fixture { f ->
        var dismissals = 0
        val sheet = main { ReverbInformationDetailsSheet(f.row, f.theme, "Details", "Full text", "Close") { dismissals++ } }
        try {
            val dialog = main {
                sheet.show()
                ReverbInformationDetailsSheet::class.java.getDeclaredField("dialog")
                    .apply { isAccessible = true }.get(sheet) as NativeActionSheet
            }
            val deadline = SystemClock.uptimeMillis() + 5_000L
            while (SystemClock.uptimeMillis() < deadline &&
                !main { dialog.canAcceptSensitiveAction && !f.row.hasWindowFocus() }) {
                SystemClock.sleep(16)
            }
            main {
                assertTrue("Details window did not take input ownership", dialog.canAcceptSensitiveAction)
                assertFalse(f.row.hasWindowFocus())
                val close = descendants(dialog.contentView).filterIsInstance<NativeMaterialButtonView>().single()
                sheet.show()
                assertTrue(dialog.isShowing && dialog.canAcceptSensitiveAction)
                assertTrue(close.isAttachedToWindow && close.isShown)
                assertTrue(dialog.contentContext.accessibility.sourceNode(close).visibleToUser)
                assertEquals(0, dismissals)
                assertTrue(close.performClick())
                assertFalse(dialog.isShowing)
                assertEquals(1, dismissals)
                close.performClick()
                sheet.close()
                assertEquals(1, dismissals)
            }
        } finally { main { sheet.close() } }
    }

    private class Gate(activity: Activity) : FrameLayout(activity), NativeInputGate {
        var admitted = true
        override val acceptsNativeInput get() = admitted
    }

    private class Fixture(val activity: Activity, val gate: Gate) {
        val theme = ReverbDesign.theme(activity, false)
        val details = mutableListOf<String>()
        val changes = mutableListOf<Pair<String, Boolean>>()
        val item = ReverbInformationToggle("provider", "Provider", "Description", false, true)
        val list = ReverbInformationToggleList<String>(activity, theme) { key, enabled -> changes += key to enabled }
        lateinit var row: NativeToggleRow
        fun installDetails() = list.bindDetails(4, "Show integration details") { key, _ -> details += key }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        var failure: Throwable? = null
        try {
            main {
                val gate = Gate(activity)
                val f = Fixture(activity, gate)
                fixture = f
                f.list.bind(listOf(f.item), null)
                gate.addView(f.list.view)
                activity.setContentView(gate)
                f.row = descendants(f.list.view).filterIsInstance<NativeToggleRow>().single()
            }
            instrumentation.waitForIdleSync()
            main { assertTrue(requireNotNull(fixture).row.hasWindowFocus()) }
            test(requireNotNull(fixture))
        } catch (error: Throwable) {
            failure = error
        } finally {
            failure = closePreservingPrimaryFailure(failure) { main { fixture?.list?.close() } }
            failure = closePreservingPrimaryFailure(failure) { main(activity::finish) }
        }
        failure?.let { throw it }
    }

    private fun longAction(row: View) = row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null)
    private fun hasLongAction(row: View) = row.createAccessibilityNodeInfo()?.actionList?.any {
        it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK
    } == true

    private fun touch(row: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, row.width / 2f, row.height / 2f, 0)
        try { row.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun awaitLongPress() {
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
