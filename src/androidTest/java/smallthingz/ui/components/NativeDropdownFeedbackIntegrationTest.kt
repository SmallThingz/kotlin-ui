package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.common.DropdownDismissPolicy
import smallthingz.ui.common.DropdownField
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.common.DropdownSelectionPolicy

/** Attached production Views and dispatched events. Numeric layout/state evidence, not pixel or
 * physical input-routing/performance qualification. Every main-thread failure returns to JUnit. */
@RunWith(AndroidJUnit4::class)
class NativeDropdownFeedbackIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private class Fixture(val activity: Activity, val host: FrameLayout, val group: DropdownGroup,
        val control: DropdownField<Int>, val content: DropdownContent<Int>, val deliveries: MutableList<Int>) {
        lateinit var pane: ViewGroup
        lateinit var wheel: ViewGroup
        var selected: ((Int) -> Unit)? = null
        fun opened() {
            pane = host.getChildAt(1) as ViewGroup
            wheel = pane.getChildAt(1) as ViewGroup
        }
    }

    private fun scenario(dark: Boolean = false, initial: Int = 20, count: Int = 80,
        policy: DropdownSelectionPolicy = DropdownSelectionPolicy.EXPLICIT,
        dismiss: DropdownDismissPolicy? = null, settleMillis: Long = 0, collapseMillis: Long = 0,
        block: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        try {
            main {
                val appearance = NativeUiAppearance.Default.copy(anchoredDropdown =
                    NativeUiAppearance.Default.anchoredDropdown.copy(expandMillis = 0,
                        settleMillis = settleMillis, collapseMillis = collapseMillis))
                val theme = ControlTheme.resolve(activity, dark, appearance)
                val host = FrameLayout(activity)
                val group = if (dismiss == null) DropdownGroup(theme, { host }, { true }, policy)
                    else DropdownGroup(theme, { host }, { true }, policy, dismiss)
                val content = DropdownContent("Feedback choices", initial, (0 until count).toList()) { "Choice $it" }
                val deliveries = ArrayList<Int>()
                val control = group.field(activity, content) { value ->
                    deliveries += value
                    fixture?.selected?.invoke(value)
                }
                fixture = Fixture(activity, host, group, control, content, deliveries)
                val density = activity.resources.displayMetrics.density
                val row = (64 * density).roundToInt()
                host.addView(control.view, FrameLayout.LayoutParams((280 * density).roundToInt(), row, Gravity.CENTER))
                activity.setContentView(FrameLayout(activity).apply {
                    addView(host, FrameLayout.LayoutParams(-1, row * 4, Gravity.CENTER))
                })
            }
            val f = checkNotNull(fixture)
            await("Attached field not ready") { f.control.view.isAttachedToWindow &&
                f.control.view.height > 0 && activity.hasWindowFocus() }
            main { f.group.open(f.control, f.content); assertTrue(f.group.isExpanded); f.opened() }
            frames(f.host, 2)
            main { assertTrue(f.wheel.isAttachedToWindow); assertTrue(f.wheel.width > 0) }
            block(f)
        } finally {
            main {
                try { fixture?.group?.close() }
                finally { try { fixture?.control?.close() } finally { activity.finish() } }
            }
        }
    }

    @Test fun linearEndsKeepOriginalLabelsAndBoundedMountedRowsInLightAndDark() {
        for (dark in listOf(false, true)) for (initial in listOf(0, 39)) {
            scenario(dark, initial, count = 40) { f -> main {
                assertEquals(initial.toFloat(), position(f), 0f)
                assertRows(f)
                val action = if (initial == 0) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                assertFalse("No wrapping beyond a real endpoint", f.wheel.performAccessibilityAction(action, null))
                assertEquals(initial.toFloat(), position(f), 0f)
                val inward = if (initial == 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                assertTrue(f.wheel.performAccessibilityAction(inward, null))
                assertEquals((if (initial == 0) 1 else 38).toFloat(), position(f), 0f)
                assertRows(f)
                assertTrue(f.deliveries.isEmpty())
            } }
        }
    }

    @Test fun heldOptionFeedbackEndsOnUpCancelAndSlopInBothThemes() {
        for (dark in listOf(false, true)) scenario(dark) { f ->
            // Hold on the actual selected row. The parent owns touch routing, not row.performClick.
            for (terminal in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                lateinit var row: NativeDropdown<*>
                var down = 0L
                main {
                    row = slotRow(f)
                    down = SystemClock.uptimeMillis()
                    touch(f.wheel, MotionEvent.ACTION_DOWN, down, f.wheel.width / 2f, slotY(f))
                    assertTrue("DOWN must start the actual row ripple", pressed(row))
                    assertTrue(f.deliveries.isEmpty())
                }
                frames(f.host, 2)
                main {
                    assertTrue("Feedback must remain held before terminal input", pressed(row))
                    val x = if (terminal == MotionEvent.ACTION_MOVE)
                        f.wheel.width / 2f + (field(f.wheel, "slop") as Int) * 2 else f.wheel.width / 2f
                    touch(f.wheel, terminal, down, x, slotY(f))
                    assertFalse("Held press must end on UP/CANCEL/slop", pressed(row))
                    if (terminal == MotionEvent.ACTION_MOVE) {
                        touch(f.wheel, MotionEvent.ACTION_UP, down, x, slotY(f))
                        assertTrue("Horizontal slop must not activate the row", f.group.isExpanded)
                    }
                    assertTrue("Unchanged initial option must not redeliver", f.deliveries.isEmpty())
                }
            }
            await("UP should retire the unchanged selected option") { !f.group.isExpanded }
            lateinit var retiredRow: NativeDropdown<*>
            main {
                f.group.open(f.control, f.content); f.opened()
                retiredRow = slotRow(f)
                touch(f.wheel, MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis(), f.wheel.width / 2f, slotY(f))
                assertTrue(pressed(retiredRow))
                f.group.dismiss()
                assertFalse(retiredRow.isAttachedToWindow)
                assertFalse(pressed(retiredRow))
                f.group.open(f.control, f.content); f.opened()
                assertNotSame(retiredRow, slotRow(f))
                assertFalse(pressed(slotRow(f)))
                assertTrue(f.deliveries.isEmpty())
            }
            frames(f.host, 2)
            main { assertTrue(f.group.isExpanded); assertFalse(pressed(slotRow(f))) }
        }
    }

    @Test fun outsideHostDispatchCommitsOnlyCompletedStationaryTap() = scenario(initial = 0) { f ->
        var down = 0L
        var x = 0f
        var y = 0f
        main {
            // Browse once: EXPLICIT has an uncommitted candidate. The list's upper end leaves
            // an outside point within the wheel's horizontal span, above its revealed rows.
            browse(f, 1)
            x = f.wheel.left + f.wheel.width / 2f
            y = (field(session(f), "anchorY") as Int) - f.control.view.height - 2f
            assertTrue("Fixture must offer an outside point inside the pane", y > 0f)
            down = SystemClock.uptimeMillis()
            touch(f.pane, MotionEvent.ACTION_DOWN, down, x, y)
            assertTrue("Outside DOWN cannot commit", f.deliveries.isEmpty())
            assertTrue(f.group.isExpanded)
            touch(f.pane, MotionEvent.ACTION_CANCEL, down, x, y)
            assertTrue(f.deliveries.isEmpty()); assertTrue(f.group.isExpanded)
            down = SystemClock.uptimeMillis()
            touch(f.pane, MotionEvent.ACTION_DOWN, down, x, y)
            val movedY = (y + f.control.view.height).coerceAtMost(f.pane.height - 1f)
            touch(f.pane, MotionEvent.ACTION_MOVE, down, x, movedY)
            touch(f.pane, MotionEvent.ACTION_UP, down, x, movedY)
            assertTrue("Outside drag crossing the popup is not a click", f.deliveries.isEmpty())
            assertTrue(f.group.isExpanded)
        }
        frames(f.host, 2)
        main {
            assertTrue("Cancelled/slop outside input must remain noncommitting after dispatch", f.deliveries.isEmpty())
            assertTrue(f.group.isExpanded)
            down = SystemClock.uptimeMillis()
            touch(f.pane, MotionEvent.ACTION_DOWN, down, x, y)
            touch(f.pane, MotionEvent.ACTION_UP, down, x, y)
        }
        await("Outside click did not retire") { !f.group.isExpanded }
        main { assertEquals(listOf(1), f.deliveries) }
    }

    @Test fun defaultCommitAndExplicitCancelPropagateThroughCommonGroup() {
        for (policy in DropdownSelectionPolicy.values()) for (dismiss in listOf(null, DropdownDismissPolicy.CANCEL)) {
            scenario(policy = policy, dismiss = dismiss) { f ->
                main { browse(f, 1) }
                // Even duration-zero ValueAnimator publishes from its later end callback.
                await("Browse did not complete") { position(f) == 21f &&
                    ((field(session(f), "animator") as? android.animation.ValueAnimator)?.isRunning != true) }
                if (policy == DropdownSelectionPolicy.PROGRESSIVE)
                    await("Progressive browse did not publish") { f.deliveries == listOf(21) }
                main {
                    if (policy == DropdownSelectionPolicy.EXPLICIT) assertTrue(f.deliveries.isEmpty())
                    outsideTap(f)
                }
                await("Outside policy did not retire popup") { !f.group.isExpanded }
                main {
                    val expected = if (policy == DropdownSelectionPolicy.PROGRESSIVE || dismiss == null)
                        listOf(21) else emptyList()
                    assertEquals(expected, f.deliveries)
                }
            }
        }
        for (dismiss in listOf(null, DropdownDismissPolicy.CANCEL)) scenario(dismiss = dismiss) { f ->
            main { browse(f, 1) }
            await("Explicit browse did not complete") { position(f) == 21f }
            main {
                val now = SystemClock.uptimeMillis()
                f.pane.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
                f.pane.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0,
                    0, -1, 0, KeyEvent.FLAG_CANCELED))
                assertTrue("Cancelled Back cannot commit or dismiss", f.group.isExpanded)
                assertTrue(f.deliveries.isEmpty())
                f.pane.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                f.pane.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            }
            await("Back did not retire popup") { !f.group.isExpanded }
            main { assertEquals(if (dismiss == null) listOf(21) else emptyList<Int>(), f.deliveries) }
        }
        for (dismiss in listOf(null, DropdownDismissPolicy.CANCEL)) scenario(dismiss = dismiss) { f ->
            main {
                browse(f, 1)
                assertTrue(f.wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null))
            }
            await("Accessibility dismissal did not retire popup") { !f.group.isExpanded }
            main { assertEquals(if (dismiss == null) listOf(21) else emptyList<Int>(), f.deliveries) }
        }
    }

    @Test fun unknownUntouchedChoiceAndLifecycleRetirementNeverInventCommit() {
        for (count in listOf(1, 40)) scenario(initial = -1, count = count) { f ->
            main { outsideTap(f) }
            await("Unknown-selection dismissal did not retire") { !f.group.isExpanded }
            main { assertTrue("Unknown untouched selection has no candidate", f.deliveries.isEmpty()) }
        }
        for (retirement in 0..3) scenario { f ->
            var siblingSawBlur = false
            main {
                browse(f, 1)
                when (retirement) {
                    0 -> f.group.dismiss()
                    1 -> f.control.close()
                    2 -> f.host.removeView(f.control.view)
                    else -> {
                        // A sibling after the popup exposes mutation of Android's cached child
                        // traversal if blur synchronously removes the popup from its parent.
                        f.host.addView(object : View(f.activity) {
                            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                                super.onWindowFocusChanged(hasWindowFocus)
                                if (!hasWindowFocus) siblingSawBlur = true
                            }
                        }, FrameLayout.LayoutParams(1, 1))
                        f.host.dispatchWindowFocusChanged(false)
                        assertTrue("Focus traversal must reach the sibling after the popup", siblingSawBlur)
                    }
                }
                assertTrue("Lifecycle retirement cannot publish", f.deliveries.isEmpty())
            }
            await("Queued retirement did not dispose popup") { !f.group.isExpanded && !f.wheel.isAttachedToWindow }
            main { assertTrue("No late lifecycle commit", f.deliveries.isEmpty()) }
        }
        scenario { f ->
            main {
                browse(f, 1)
                f.pane.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                f.pane.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                assertTrue("Escape explicitly cancels", f.deliveries.isEmpty())
            }
            await("Escape did not retire popup") { !f.group.isExpanded }
            main { assertTrue(f.deliveries.isEmpty()) }
        }
    }

    @Test fun wholeHostDetachVisitsTrailingSiblingAndRetiresBeforeSafeReopen() = scenario { f ->
        lateinit var parent: ViewGroup
        lateinit var trailing: View
        lateinit var oldPane: ViewGroup
        lateinit var oldWheel: ViewGroup
        lateinit var oldSession: Any
        lateinit var originalObserver: ViewTreeObserver
        lateinit var staleRetirement: Runnable
        var detachVisits = 0
        main {
            browse(f, 1) // Leave an uncommitted EXPLICIT candidate to expose accidental delivery.
            oldPane = f.pane
            oldWheel = f.wheel
            oldSession = session(f)
            originalObserver = f.control.view.viewTreeObserver
            assertTrue(originalObserver.isAlive)
            assertSame("Store the exact observer which owns the guard", originalObserver,
                field(oldSession, "geometryObserver"))
            staleRetirement = field(oldSession, "retireAfterDetach") as Runnable
            parent = f.host.parent as ViewGroup
            trailing = object : View(f.activity) {
                override fun onDetachedFromWindow() {
                    super.onDetachedFromWindow()
                    detachVisits++
                }
            }
            f.host.addView(trailing, FrameLayout.LayoutParams(1, 1))
            assertTrue(trailing.isAttachedToWindow)
            assertSame(oldPane, f.host.getChildAt(1))
            assertSame(trailing, f.host.getChildAt(2))
            // Exercise Android's whole-host cached child-array traversal, not remove(anchor).
            parent.removeView(f.host)
            assertEquals("Detach traversal must visit the sibling after the popup", 1, detachVisits)
            assertFalse(f.host.isAttachedToWindow)
            assertFalse(oldWheel.isAttachedToWindow)
            assertTrue("Input must retire during traversal", field(oldSession, "retiring") as Boolean)
            assertFalse("Structural disposal must be posted", field(oldSession, "disposed") as Boolean)
            assertSame("Popup removal must wait until child traversal returns", f.host, oldPane.parent)
            assertTrue(f.deliveries.isEmpty())
        }
        await("Posted whole-host retirement did not finish") {
            field(oldSession, "disposed") as Boolean && !f.group.isExpanded && oldPane.parent == null
        }
        main {
            assertEquals(1, detachVisits)
            assertTrue("Detached candidate must not publish", f.deliveries.isEmpty())
            assertNull("Retirement must clear the stored observer owner", field(oldSession, "geometryObserver"))
            assertTrue("The original window observer remains live after subtree removal", originalObserver.isAlive)
            val observed = field(oldSession, "observed") as IntArray
            val sentinel = intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE)
            sentinel.copyInto(observed)
            originalObserver.dispatchOnPreDraw()
            assertArrayEquals("Retired guard must be absent from the original observer", sentinel, observed)
            f.host.removeView(trailing)
            parent.addView(f.host, f.host.layoutParams)
        }
        await("Reattached field not ready") {
            f.control.view.isAttachedToWindow && !f.host.isLayoutRequested && f.activity.hasWindowFocus()
        }
        main {
            f.group.open(f.control, f.content)
            assertTrue(f.group.isExpanded)
            f.opened()
            assertNotSame(oldPane, f.pane)
            assertNotSame(oldWheel, f.wheel)
            // Redeliver the captured old callback after replacement, not a fabricated close.
            staleRetirement.run()
            assertTrue(f.group.isExpanded)
            assertTrue(f.wheel.isAttachedToWindow)
            assertEquals(20f, position(f), 0f)
            assertTrue(f.deliveries.isEmpty())
        }
        frames(f.host, 2)
        main {
            assertEquals(1, detachVisits)
            assertTrue(f.group.isExpanded)
            assertTrue(f.wheel.isAttachedToWindow)
            assertRows(f)
            assertTrue("No late old-session publication", f.deliveries.isEmpty())
        }
    }

    @Test fun escapeBeforeProgressivePublishCannotCommitCancelledCandidateAfterReopen() =
        scenario(policy = DropdownSelectionPolicy.PROGRESSIVE, settleMillis = 160, collapseMillis = 160) { f ->
            main {
                // One dispatch turn: browse has not reached its animator-end publication.
                browse(f, 1)
                assertTrue(f.deliveries.isEmpty())
                f.pane.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                f.pane.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                assertTrue(field(session(f), "closing") as Boolean)
                val now = SystemClock.uptimeMillis()
                touch(f.wheel, MotionEvent.ACTION_DOWN, now, f.wheel.width / 2f, slotY(f))
                touch(f.wheel, MotionEvent.ACTION_UP, now, f.wheel.width / 2f, slotY(f))
                assertFalse("Valid closing tap must reopen", field(session(f), "closing") as Boolean)
            }
            await("Cancelled candidate's replacement motion did not finish") {
                position(f) == 20f && (field(session(f), "reveal") as Float) == 1f &&
                    ((field(session(f), "animator") as? android.animation.ValueAnimator)?.isRunning != true)
            }
            main { assertTrue(f.deliveries.isEmpty()); outsideTap(f) }
            await("Final outside dismissal did not retire") { !f.group.isExpanded }
            main { assertTrue("Cancelled candidate cannot resurface", f.deliveries.isEmpty()) }
        }

    @Test fun outsideCommitCallbackReopensWithoutOldOwnerClearingReplacement() = scenario { f ->
        lateinit var oldWheel: ViewGroup
        main {
            browse(f, 1)
            oldWheel = f.wheel
            f.selected = { value ->
                f.selected = null
                f.group.dismiss()
                val replacement = f.content.copy(selected = value)
                f.control.bind(replacement)
                f.group.open(f.control, replacement)
                f.opened()
            }
            outsideTap(f)
        }
        await("Outside callback did not establish replacement owner") { f.wheel !== oldWheel }
        main {
            assertEquals(listOf(21), f.deliveries)
            assertFalse(oldWheel.isAttachedToWindow)
            assertTrue(f.group.isExpanded)
        }
        frames(f.host, 3)
        main {
            assertTrue(f.wheel.isAttachedToWindow)
            assertTrue(f.group.isExpanded)
            assertEquals(21f, position(f), 0f)
            assertRows(f)
            assertEquals(listOf(21), f.deliveries)
        }
    }

    @Test fun overflowCueTouchAndAccessibilityStepOnceWithinDisjointBounds() {
        for (dark in listOf(false, true)) scenario(dark) { f ->
            lateinit var upper: NativeDropdownOverflowCue
            lateinit var lower: NativeDropdownOverflowCue
            var down = 0L
            main {
                val cues = children(f.wheel).filterIsInstance<NativeDropdownOverflowCue>().sortedBy { it.top }
                assertEquals(2, cues.size)
                upper = cues[0]; lower = cues[1]
                assertCue(f, upper); assertCue(f, lower)
                assertTrue("Cue targets must leave an option lane", upper.bottom < lower.top)
                for (cue in cues) {
                    assertFalse(cue.contains(f.wheel.width / 2f, slotY(f)))
                    val node = cue.createAccessibilityNodeInfo()
                    try {
                        assertEquals("android.widget.Button", node.className.toString())
                        assertTrue(node.isClickable); assertTrue(node.isEnabled)
                        assertFalse(node.contentDescription.isNullOrBlank())
                        assertTrue(node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
                    } finally { node.recycle() }
                }
                // A near-edge cue miss stays within slop but must not fall through to its option.
                assertTrue((field(f.wheel, "slop") as Int) >= 2)
                down = SystemClock.uptimeMillis()
                val missY = lower.y + lower.height / 2f
                touch(f.wheel, MotionEvent.ACTION_DOWN, down, lower.right - 1f, missY)
                touch(f.wheel, MotionEvent.ACTION_UP, down, lower.right + 1f, missY)
                assertFalse(lower.isPressed)
                assertEquals(20f, position(f), 0f)
                assertTrue(f.group.isExpanded); assertTrue(f.deliveries.isEmpty())
                down = SystemClock.uptimeMillis()
                touch(f.wheel, MotionEvent.ACTION_DOWN, down, lower.x + lower.width / 2f, lower.y + lower.height / 2f)
                assertTrue(lower.isPressed)
                assertEquals(20f, position(f), 0f)
            }
            frames(f.host, 2)
            main {
                touch(f.wheel, MotionEvent.ACTION_UP, down, lower.x + lower.width / 2f, lower.y + lower.height / 2f)
                assertFalse(lower.isPressed)
                assertEquals(21f, position(f), 0f)
                assertTrue(f.group.isExpanded); assertTrue(f.deliveries.isEmpty())
                assertTrue(upper.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(20f, position(f), 0f)
                assertTrue(f.group.isExpanded); assertTrue(f.deliveries.isEmpty())
                // Reach an actual list edge; the retained cue must lose pointer and a11y admission.
                repeat(20) { browse(f, -1) }
                assertEquals(0f, position(f), 0f)
                assertNotEquals(View.VISIBLE, upper.visibility)
                assertFalse(upper.isEnabled); assertFalse(upper.isClickable)
                assertFalse(upper.performClick())
                assertFalse(upper.contains(upper.x + upper.width / 2f, upper.y + upper.height / 2f))
                assertRows(f)
            }
        }
    }

    @Test fun quantizedNativeFlingStaysMonotoneThroughItsFinalFrameAndRetirement() {
        for (direction in listOf(-1, 1)) scenario(initial = 40, count = 100, settleMillis = 160) { f ->
            val samples = ArrayList<Float>()
            var release = 0f
            var target = 0
            lateinit var observer: ViewTreeObserver.OnPreDrawListener
            lateinit var oldWheel: ViewGroup
            lateinit var observedTree: ViewTreeObserver
            lateinit var native: NativeDropdownFlingTiming
            main {
                oldWheel = f.wheel
                observedTree = oldWheel.viewTreeObserver
                observer = ViewTreeObserver.OnPreDrawListener {
                    samples += position(f)
                    true
                }
                observedTree.addOnPreDrawListener(observer)
                swipe(f, direction)
                release = position(f)
                assertTrue("Real native fling must start", field(f.wheel, "flinging") as Boolean)
                native = field(f.wheel, "flingTiming") as NativeDropdownFlingTiming
                target = (field(f.wheel, "flingPath") as NativeDropdownFlingPath).targetRow
                assertTrue(target in 0 until f.content.options.size)
                assertTrue((target - release) * direction > 0f)
                samples += release
            }
            try {
                await("Native fling did not finish") { !(field(f.wheel, "flinging") as Boolean) }
                // Observe beyond the previous settle duration to catch a late reverse/correction.
                val deadline = SystemClock.uptimeMillis() + 250
                while (SystemClock.uptimeMillis() < deadline) frames(f.host, 1)
                main {
                    samples += position(f)
                    assertTrue("Need actual intermediate motion observations", samples.distinct().size > 2)
                    for ((a, b) in samples.zipWithNext()) {
                        assertTrue("Reverse motion $a -> $b target=$target", (b - a) * direction >= -0.0001f)
                    }
                    for (sample in samples) assertTrue("Overshoot $sample target=$target",
                        sample >= minOf(release, target.toFloat()) - .0001f &&
                            sample <= maxOf(release, target.toFloat()) + .0001f)
                    assertEquals(target.toFloat(), position(f), 0f)
                    assertRows(f)
                    assertTrue(f.deliveries.isEmpty())
                    assertTrue(native.isFinished)
                    val animator = field(session(f), "animator") as? android.animation.ValueAnimator
                    assertFalse("No terminal settle animator", animator?.isRunning == true)
                    f.group.dismiss()
                    assertFalse(oldWheel.isAttachedToWindow)
                    assertNull(field(oldWheel, "flingTiming"))
                    (field(oldWheel, "flingFrame") as Runnable).run()
                    assertEquals(target.toFloat(), position(f), 0f)
                    assertTrue(f.deliveries.isEmpty())
                }
            } finally {
                main { if (observedTree.isAlive) observedTree.removeOnPreDrawListener(observer) }
            }
        }
    }

    private fun assertCue(f: Fixture, cue: NativeDropdownOverflowCue) {
        assertEquals(View.VISIBLE, cue.visibility)
        assertTrue(cue.width > 0 && cue.height > 0)
        assertTrue(cue.left >= 0 && cue.right <= f.wheel.width)
        assertTrue(cue.top >= (field(session(f), "safeTop") as Int))
        assertTrue(cue.bottom <= (field(session(f), "safeBottom") as Int))
        assertTrue(cue.contains(cue.x + cue.width / 2f, cue.y + cue.height / 2f))
    }

    private fun assertRows(f: Fixture) {
        val rows = children(f.wheel).filterIsInstance<NativeDropdown<*>>().sortedBy { it.translationY }
        assertTrue(rows.isNotEmpty())
        val s = session(f)
        val anchor = field(s, "anchorY") as Int
        val top = field(s, "safeTop") as Int
        val bottom = field(s, "safeBottom") as Int
        val height = f.control.view.height
        val limit = ceil((bottom - top).toDouble() / height).toInt() + 4
        assertTrue("Virtualization must remain bounded", rows.size <= minOf(f.content.options.size, limit))
        val indices = rows.map { row ->
            val i = checkNotNull(row.optionAccessibilityLabel).toString().removePrefix("Choice ").toInt()
            assertEquals("Original option index must determine physical row order",
                anchor + (i - position(f)) * height, row.translationY, .01f)
            i
        }
        assertEquals(indices.distinct(), indices)
        assertTrue(indices.zipWithNext().all { (a, b) -> b == a + 1 })
        for (i in f.content.options.indices) {
            val y = anchor + (i - position(f)) * height
            if (y < bottom && y + height > top) assertTrue("Missing visible option $i", i in indices)
        }
    }

    private fun browse(f: Fixture, direction: Int) {
        assertTrue(f.wheel.performAccessibilityAction(if (direction > 0)
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
    }

    private fun outsideTap(f: Fixture) {
        val now = SystemClock.uptimeMillis()
        // Outside the centered wheel but within the actual overlay, routed to its scrim.
        assertTrue(f.wheel.left > 2)
        touch(f.pane, MotionEvent.ACTION_DOWN, now, 1f, f.pane.height / 2f)
        touch(f.pane, MotionEvent.ACTION_UP, now, 1f, f.pane.height / 2f)
    }

    private fun swipe(f: Fixture, direction: Int) {
        val now = SystemClock.uptimeMillis()
        val down = now - 64
        val start = slotY(f)
        touch(f.wheel, MotionEvent.ACTION_DOWN, down, f.wheel.width / 2f, start, down)
        for (n in 1..3) touch(f.wheel, MotionEvent.ACTION_MOVE, down, f.wheel.width / 2f,
            start - direction * f.control.view.height * n / 4f, down + n * 16)
        touch(f.wheel, MotionEvent.ACTION_UP, down, f.wheel.width / 2f,
            start - direction * f.control.view.height, now)
    }

    private fun touch(target: View, action: Int, down: Long, x: Float, y: Float,
        time: Long = SystemClock.uptimeMillis()) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("$action not handled by ${target.javaClass.simpleName}", target.dispatchTouchEvent(event)) }
        finally { event.recycle() }
    }

    private fun pressed(row: View): Boolean {
        val indication = checkNotNull(field(row, "indication"))
        val ripple = field(indication, "ripple") as? Drawable ?: return false
        return android.R.attr.state_pressed in ripple.state
    }
    private fun slotRow(f: Fixture) = children(f.wheel).filterIsInstance<NativeDropdown<*>>().single {
        abs(it.translationY - (field(session(f), "anchorY") as Int)) < .5f
    }
    private fun children(group: ViewGroup) = (0 until group.childCount).map(group::getChildAt)
    private fun session(f: Fixture): Any = checkNotNull(field(f.wheel, "this\$0"))
    private fun position(f: Fixture) = field(session(f), "position") as Float
    private fun slotY(f: Fixture) = (field(session(f), "anchorY") as Int) + f.control.view.height / 2f
    private fun field(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(owner) }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        error("Missing field $name on ${owner.javaClass.name}")
    }
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun frames(view: View, count: Int) {
        repeat(count) {
            val latch = CountDownLatch(1)
            main { view.postOnAnimation { latch.countDown() }; view.invalidate() }
            assertTrue("Attached frame callback timed out", latch.await(2, TimeUnit.SECONDS))
        }
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (main(predicate)) return
            SystemClock.sleep(16)
        }
        fail(message)
    }
}
