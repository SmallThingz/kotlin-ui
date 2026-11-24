package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.input.phase.NativeClickPhaseHost
import smallthingz.ui.input.phase.NativePointerLayerShape
import smallthingz.ui.reverb.*
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.util.closePreservingPrimaryFailure

/** Real occurrence controllers with a recording menu host: observes the request boundary, not a copied gesture model. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ReverbIncidentPointerOriginIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun bodyAndCheckboxPhasePressesAnchorAtDownAndSemanticAndKeyboardResetToFallback() = withFixture { f ->
        main {
            val body = f.body
            body.pivotX = 0f; body.pivotY = 0f
            body.scaleX = 1.25f; body.translationX = 9f; body.translationY = 7f
            val offset = f.layoutOffset(body)
            f.phase(body, MotionEvent.ACTION_DOWN, 100f, 12f)
            // Later motion must not replace the admitted DOWN; no MotionEvent is retained.
            f.phase(body, MotionEvent.ACTION_MOVE, 104f, 14f, deep = true)
            f.phase(body, MotionEvent.ACTION_UP, 104f, 14f)
            val first = f.shown.single()
            assertSame(f.card, first.anchor)
            assertEquals(NativeMenuOrigin(offset.first + 134f, offset.second + 19f), first.origin)
            assertEquals(0, f.activations)
            assertTrue(body.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            assertNull("Semantic action must not reuse the preceding pointer", f.shown.last().origin)
            assertEquals(2, f.shown.size)
            // Restore geometry before checking the separately transformed checkbox coordinates.
            body.scaleX = 1f; body.translationX = 0f; body.translationY = 0f
            val check = f.checkbox
            val checkOffset = f.layoutOffset(check)
            // Raw corner pixels can be outside this density-scaled rounded target.
            val downX = check.width / 2f
            val downY = check.height / 2f
            val moveX = downX + check.width / 16f
            val moveY = downY + check.height / 16f
            val shape = check as NativePointerLayerShape
            assertTrue("Measured checkbox DOWN is inside its shape", shape.containsPointerLayer(downX, downY))
            assertTrue("Distinct checkbox MOVE stays inside its shape", shape.containsPointerLayer(moveX, moveY))
            assertTrue("Checkbox controller admits DOWN", f.phase(check, MotionEvent.ACTION_DOWN, downX, downY))
            f.phase(check, MotionEvent.ACTION_MOVE, moveX, moveY, deep = true)
            f.phase(check, MotionEvent.ACTION_UP, moveX, moveY)
            assertEquals("Checkbox emitted a new request", 3, f.shown.size)
            assertEquals(NativeMenuOrigin(checkOffset.first + downX, checkOffset.second + downY), f.shown.last().origin)
            val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(body.onKeyDown(key.keyCode, key))
        }
        // The real controller timer runs on Main; never block it while waiting for a key long-press.
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 80)
        instrumentation.waitForIdleSync()
        main {
            val key = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(f.body.onKeyUp(key.keyCode, key))
            assertEquals(4, f.shown.size)
            assertNull("Keyboard long-press uses the ordinary row anchor", f.shown.last().origin)
            assertEquals(0, f.activations)
        }
    }

    @Test fun canceledReboundAndSelectionModePressesCannotPublishOrReuseOldMenuActions() = withFixture { f ->
        main {
            f.phase(f.body, MotionEvent.ACTION_DOWN, 100f, 12f)
            f.phase(f.body, MotionEvent.ACTION_CANCEL, 100f, 12f)
            f.phase(f.body, MotionEvent.ACTION_MOVE, 100f, 12f, deep = true)
            assertTrue(f.shown.isEmpty())
            f.phase(f.body, MotionEvent.ACTION_DOWN, 100f, 12f)
            f.bind(Row("replacement"))
            f.phase(f.body, MotionEvent.ACTION_MOVE, 100f, 12f, deep = true)
            assertTrue(f.shown.isEmpty())
            f.phase(f.body, MotionEvent.ACTION_DOWN, 120f, 15f)
            f.phase(f.body, MotionEvent.ACTION_MOVE, 120f, 15f, deep = true)
            f.phase(f.body, MotionEvent.ACTION_UP, 120f, 15f)
            val request = f.shown.single()
            request.copy.invoke()
            assertEquals(listOf("replacement"), f.copied)
            f.bind(Row("replacement"), selecting = true)
            request.copy.invoke()
            assertEquals("Retired popup callback is inert", listOf("replacement"), f.copied)
            assertTrue(f.released.contains(request.identity))
            f.phase(f.body, MotionEvent.ACTION_DOWN, 120f, 15f)
            f.phase(f.body, MotionEvent.ACTION_MOVE, 120f, 15f, deep = true)
            f.phase(f.body, MotionEvent.ACTION_CANCEL, 120f, 15f)
            assertFalse(f.body.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            assertEquals(1, f.shown.size)
        }
    }

    private data class Row(override val key: String) : ReverbIncidentOccurrence, ReverbIncidentTarget {
        override val target get() = this
        override val acknowledged = false
        override val timeLabel = "12:01:02 · 0:10"
        override val processLabel = "PID 42"
        override val liveEnd: ReverbIncidentLiveEnd? = null
        override fun matches(reference: ReverbIncidentTarget) = reference is Row && key == reference.key
    }

    private class Fixture(val activity: Activity) {
        private val theme = ReverbDesign.theme(activity, false)
        private val design = ReverbDesign.appearance
        private val appearance = IncidentCardAppearance(theme.palette,
            design.typography[NativeTypographyRole.BODY_MEDIUM], design.typography[NativeTypographyRole.BODY_SMALL],
            theme.density, theme.fontResolver, design.groupSurfaces.recordingRow)
        val shown = mutableListOf<IncidentCardMenuRequest>()
        val released = mutableListOf<IncidentCardMenuIdentity>()
        val copied = mutableListOf<String>()
        var activations = 0
        private var current = Row("original")
        private var selecting = false
        private val actions = ReverbIncidentActions({}, {}, {}, { copied += (it as Row).key })
        val card = ReverbIncidentCardView(activity, appearance, object : IncidentCardMenuHost {
            override fun show(request: IncidentCardMenuRequest) { shown += request }
            override fun update(request: IncidentCardMenuRequest) = Unit
            override fun release(identity: IncidentCardMenuIdentity) { released += identity }
        }, texts, object : IncidentCardAuthority {
            override fun activate(row: ReverbIncidentOccurrence, showOptions: () -> Boolean): Boolean {
                activations++
                return true
            }
            override fun menuTarget(row: ReverbIncidentOccurrence, requiresMutation: Boolean) =
                current.takeIf { !selecting && it.matches(row.target) }
        }).apply { pointerInputRoot = activity.window.decorView; parentInterestedInDown = { true } }
        private val accessibility = IncidentFixtureAccessibility(card)
        fun closeAccessibility() = accessibility.close()
        val body: View get() = descendants(card).first { it is NativeClickPhaseHost }
        val checkbox: View get() = descendants(body).drop(1).first { it is NativeClickPhaseHost }
        fun bind(row: Row = current, selecting: Boolean = false) {
            current = row
            this.selecting = selecting
            card.bind(row, true, false.takeIf { selecting }, actions)
        }
        fun place() {
            val width = (400 * activity.resources.displayMetrics.density).toInt()
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST))
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
            accessibility.refresh()
        }
        // Independent expected offsets for translation-free parents; the test adds the known body transform separately.
        fun layoutOffset(view: View): Pair<Float, Float> {
            var x = 0f; var y = 0f
            var child = view
            while (child !== card) {
                val parent = child.parent as View
                x += child.left - parent.scrollX
                y += child.top - parent.scrollY
                child = parent
            }
            return x to y
        }
        fun phase(view: View, action: Int, x: Float, y: Float, deep: Boolean = false): Boolean {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            return try {
                (view as NativeClickPhaseHost).nativeClickEndpoint.touch(event, false, {}, true,
                    if (deep) MotionEvent.CLASSIFICATION_DEEP_PRESS else 0)
            } finally { event.recycle() }
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var runtime: NativeModalWindowRuntime? = null
        var fixture: Fixture? = null
        var failure: Throwable? = null
        try {
            main {
                runtime = NativeModalWindowRuntime(activity).also { it.setStarted(true) }
                fixture = Fixture(activity).also {
                    it.bind()
                    activity.setContentView(it.card)
                }
            }
            instrumentation.waitForIdleSync()
            main { checkNotNull(fixture).place() }
            test(checkNotNull(fixture))
        } catch (error: Throwable) { failure = error }
        finally {
            main {
                failure = closePreservingPrimaryFailure(failure) { fixture?.closeAccessibility() }
                failure = closePreservingPrimaryFailure(failure) { fixture?.card?.unbind() }
                failure = closePreservingPrimaryFailure(failure) { runtime?.close() }
                failure = closePreservingPrimaryFailure(failure) { activity.finish() }
            }
        }
        failure?.let { throw it }
    }
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    companion object {
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        private val texts = ReverbIncidentTexts(
            "Incidents", "Back", "Clear selection", { "$it selected" }, "Multi-select", "Select all", "Deselect all",
            "Delete selected", "Clear all", "Clear incidents?", "Cancel", "No incidents", "Nothing to show",
            "Loading", "Unavailable", "Stale", "Select", "Deselect", "Mark checked", "Mark unchecked", "Options", "Copy", "Delete",
        )
    }
}
