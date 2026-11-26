package smallthingz.ui.components

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbAppChoice
import smallthingz.ui.reverb.ReverbAppChoicePicker
import smallthingz.ui.reverb.ReverbAppChoiceTrigger
import smallthingz.ui.reverb.ReverbCheckedSettingsRow
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

/** Attached windows exercise shared input/placement; these are not device TalkBack/IME claims. */
@RunWith(AndroidJUnit4::class)
class ReverbAppChoiceIntegrationTest {
    // Test-only opt-in; normal instrumentation retains the platform's real IME behavior.
    private val excludeIme = when (InstrumentationRegistry.getArguments().getString("reverb_semantic_exclude_ime")) {
        null, "false" -> false
        "true" -> true
        else -> error("reverb_semantic_exclude_ime must be true or false")
    }

    @Test fun checkedSettingsRowBindsSilentlyAndRequestsStateThroughOneCheckboxTarget() = withFixture { f ->
        withCheckedRow(f) { control, requests ->
            main {
                val row = control.row
                control.bind("Example app", "Unavailable component", false, true, f.theme)
                control.bind("Example app", "Unavailable component", false, true, f.theme)
                assertTrue(requests.isEmpty())
                val info = row.createAccessibilityNodeInfo()
                assertEquals("android.widget.CheckBox", info.className)
                assertEquals("Example app, Unavailable component", info.contentDescription.toString())
                assertTrue(info.isCheckable)
                assertFalse(info.isChecked)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, control.importantForAccessibility)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    row.getChildAt(0).importantForAccessibility)
                assertTrue(row.requestFocus())
                press(row, KeyEvent.KEYCODE_ENTER)
                assertEquals(listOf(true), requests)
                assertFalse("Activation must wait for owner binding", row.createAccessibilityNodeInfo().isChecked)
                control.bind("Example app", "Unavailable component", true, true, f.theme)
                assertEquals(listOf(true), requests)
                assertTrue(row.createAccessibilityNodeInfo().isChecked)
                assertFalse(row.createAccessibilityNodeInfo().isSelected)
                assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(listOf(true, false), requests)
                assertTrue("Requesting false must not silently change owner state", row.createAccessibilityNodeInfo().isChecked)
                control.bind("Example app", "Unavailable component", true, false, f.theme)
                assertFalse(row.performClick())
                assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                control.bind("Example app", "Unavailable component", true, true, f.theme)
                f.root.allowed = false
                assertFalse(row.performClick())
                assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                f.root.allowed = true
                control.close()
                control.bind("Must remain retired", null, false, true, f.theme)
                assertFalse(row.performClick())
                assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(listOf(true, false), requests)
            }
        }
    }

    @Test fun checkedSettingsRowWrapperDisableRetiresChildAdmissionWithoutChangingSelection() = withFixture { f ->
        withCheckedRow(f) { control, requests ->
            main {
                val row = control.row
                control.bind("Example app", "Component", true, true, f.theme)
                assertTrue(row.requestFocus())
                control.isEnabled = false
                assertFalse(row.isEnabled)
                assertFalse(row.isFocusable)
                val info = row.createAccessibilityNodeInfo()
                assertFalse(info.isEnabled)
                assertTrue(info.isChecked)
                assertFalse(info.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
                press(row, KeyEvent.KEYCODE_ENTER)
                assertFalse(row.performClick())
                assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertTrue(requests.isEmpty())
                control.isEnabled = true
                assertTrue(row.isEnabled)
                assertTrue(row.requestFocus())
                press(row, KeyEvent.KEYCODE_ENTER)
                assertEquals(listOf(false), requests)
                assertTrue(row.createAccessibilityNodeInfo().isChecked)
                control.bind("Example app", "Component", true, false, f.theme)
                control.isEnabled = true
                assertFalse(row.isEnabled)
                assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                control.close()
                control.isEnabled = true
                assertFalse(row.isEnabled)
                assertFalse(row.performClick())
                assertEquals(listOf(false), requests)
            }
        }
    }

    @Test fun checkedSettingsRowWrapperDisableCancelsHeldPointerBeforeReenable() = withFixture { f ->
        withCheckedRow(f) { control, requests ->
            main {
                val row = control.row
                control.bind("Example app", "Component", true, true, f.theme)
                assertTrue(row.requestFocus())
                fun pointer(action: Int, downTime: Long): Boolean {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                        row.width / 2f, row.height / 2f, 0)
                    return try { row.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                val heldDown = SystemClock.uptimeMillis()
                assertTrue(pointer(MotionEvent.ACTION_DOWN, heldDown))
                control.isEnabled = false
                control.isEnabled = true
                pointer(MotionEvent.ACTION_UP, heldDown)
                assertTrue("Re-enabling must not accept the retired pointer", requests.isEmpty())
                assertTrue(row.createAccessibilityNodeInfo().isChecked)
                val freshDown = SystemClock.uptimeMillis()
                assertTrue(pointer(MotionEvent.ACTION_DOWN, freshDown))
                pointer(MotionEvent.ACTION_UP, freshDown)
                assertEquals("A fresh pointer still requests exactly once", listOf(false), requests)
                assertTrue(row.createAccessibilityNodeInfo().isChecked)
            }
        }
    }

    @Test fun checkedSettingsRowRetainsTitleDetailAndGlyphAtNarrowLargeFontRtl() = withFixture(rtl = true) { f ->
        withCheckedRow(f, widthDp = 220) { control, requests ->
            val original = control.row
            main {
                val dark = ReverbDesign.theme(f.activity, true)
                val enlarged = ReverbDesign.windowTheme(Density(dark.density.density, 2f), dark.fontResolver, dark.palette)
                control.bind("An application with a long name", "Unavailable component with a long name",
                    true, true, enlarged)
            }
            await { control.row.isLaidOut && !control.row.isLayoutRequested }
            main {
                val row = control.row
                assertSame(original, row)
                assertEquals(View.GONE, row.icon.visibility)
                assertEquals(View.VISIBLE, row.trailing.visibility)
                assertEquals(View.VISIBLE, row.packageLabel.visibility)
                assertEquals("Unavailable component with a long name", row.packageLabel.getText())
                fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                    row.offsetDescendantRectToMyCoords(view, it)
                }
                val title = bounds(row.label)
                val detail = bounds(row.packageLabel)
                val glyph = bounds(row.trailing)
                for (rect in listOf(title, detail, glyph)) {
                    assertTrue(rect.width() > 0 && rect.height() > 0)
                    assertTrue(rect.left >= 0 && rect.right <= row.width)
                    assertTrue(rect.top >= 0 && rect.bottom <= row.height)
                }
                assertTrue(detail.top >= title.bottom)
                assertTrue("RTL end glyph must not overlap either label", glyph.right <= minOf(title.left, detail.left))
                assertTrue(row.height >= (48 * f.theme.density.density).roundToInt())
                control.bind("Short name", null, false, true, f.theme)
                assertSame(original, control.row)
                assertEquals(View.VISIBLE, row.trailing.visibility)
                assertEquals(View.GONE, row.packageLabel.visibility)
                assertEquals("Short name", row.contentDescription.toString())
                assertFalse(row.createAccessibilityNodeInfo().isChecked)
                assertTrue(requests.isEmpty())
            }
        }
    }

    @Test fun triggerPreservesIdentityIconAndSingleDisabledExplanationAcrossThemeAndRtl() = withFixture { f ->
        main {
            val icon = ColorDrawable(Color.MAGENTA)
            f.trigger.bind(ReverbAppChoice("a", "متصفح طويل", "org.example.browser", icon,
                enabled = false, disabledReason = "Authorization required"), f.theme)
            val row = f.trigger.row
            assertSame(icon, row.icon.drawable)
            assertNull(row.icon.imageTintList)
            assertEquals("Authorization required", row.reason.getText())
            assertEquals(View.VISIBLE, row.reason.visibility)
            assertFalse(row.performClick())
            val info = row.createAccessibilityNodeInfo()
            assertFalse(info.isEnabled)
            assertEquals("android.widget.Spinner", info.className)
            assertEquals(1, info.contentDescription.toString().split("Authorization required").size - 1)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                row.getChildAt(0).importantForAccessibility)
            f.trigger.layoutDirection = View.LAYOUT_DIRECTION_RTL
            f.trigger.bind(ReverbAppChoice("a", "متصفح طويل", "org.example.browser", icon),
                ReverbDesign.theme(f.activity, true))
            assertSame(row, f.trigger.row)
            assertSame(icon, row.icon.drawable)
            assertEquals(View.GONE, row.reason.visibility)
            assertEquals("", row.reason.getText())
            assertFalse(row.contentDescription.toString().contains("Authorization required"))
            assertEquals(0, f.opens)
        }
        await { f.trigger.row.width > 0 && f.trigger.row.icon.left > f.trigger.row.trailing.left }
        main { f.trigger.close(); assertFalse(f.trigger.row.performClick()) }
    }

    @Test fun selectedDisabledChoiceCannotAnswerAndEnabledChoiceReturnsOnlyItsIdOnce() = withFixture { f ->
        val replies = mutableListOf<String>()
        val picker = open(f, listOf(
            ReverbAppChoice("blocked", "Same label", "org.example.blocked", enabled = false, disabledReason = "Unavailable"),
            ReverbAppChoice("allowed", "Same label", "org.example.allowed"),
        ), "blocked", onSelect = { replies += it })
        main {
            val disabled = picker.rows[0]
            val enabled = picker.rows[1]
            assertTrue(disabled.isSelected)
            val info = disabled.createAccessibilityNodeInfo()
            assertEquals("android.widget.RadioButton", info.className)
            assertTrue(info.isCheckable)
            assertTrue(info.isChecked)
            assertFalse(info.isEnabled)
            assertFalse(disabled.performClick())
            assertFalse(disabled.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertFalse(disabled.hasFocus())
            assertTrue(enabled.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf("allowed"), replies)
            assertFalse(picker.window.isShowing)
            assertFalse(enabled.performClick())
            assertFalse(disabled.performClick())
            assertEquals(listOf("allowed"), replies)
        }
    }

    @Test fun revocationAndReentrantRetirementDuringAdmissionCannotCommit() {
        for (retire in listOf(false, true)) withFixture { f ->
            var admitted = true
            var selecting = false
            val replies = mutableListOf<String>()
            lateinit var picker: ReverbAppChoicePicker
            picker = open(f, canSelect = {
                if (selecting && retire) picker.close()
                admitted
            }, onSelect = { replies += it })
            main {
                selecting = true
                if (!retire) admitted = false
                picker.rows.first().performClick()
                admitted = true
                picker.rows.first().performClick()
                assertTrue(replies.isEmpty())
                assertFalse(picker.window.isShowing)
            }
        }
    }

    @Test fun throwingRetirementAdmissionStillDismissesAndUnregistersPicker() = withFixture { f ->
        val picker = open(f, onSelect = { fail("Closed picker selected") })
        main {
            val failure = IllegalStateException("retirement admission")
            val notifyFailure = IllegalStateException("retirement notification")
            var retired = 0
            val notify = picker.onRetired
            picker.onRetired = { retired++; notify?.invoke(); throw notifyFailure }
            f.root.probe = { throw failure }
            try {
                val actual = runCatching { picker.close() }.exceptionOrNull()
                assertSame(failure, actual)
                assertEquals(listOf(notifyFailure), actual?.suppressed?.toList())
                assertFalse(picker.window.isShowing)
                val observing = ReverbAppChoicePicker::class.java.getDeclaredField("observing").apply { isAccessible = true }
                assertFalse(observing.getBoolean(picker))
                assertNull(picker.onRetired)
                assertNull(f.trigger.picker)
                picker.close()
                assertEquals(1, retired)
            } finally { f.root.probe = null }
            assertTrue(picker.rows.none { it.performClick() })
        }
    }

    @Test fun throwingSelectionAdmissionKeepsItsFailureWhenRetirementAlsoThrows() = withFixture { f ->
        val selectionFailure = IllegalStateException("selection admission")
        val cleanupFailure = IllegalStateException("cleanup admission")
        var selecting = false
        val picker = open(f, canSelect = {
            if (selecting) {
                f.root.probe = { throw cleanupFailure }
                throw selectionFailure
            }
            true
        }, onSelect = { fail("Failed admission selected") })
        main {
            selecting = true
            try {
                val actual = runCatching { picker.rows.first().performClick() }.exceptionOrNull()
                assertSame(selectionFailure, actual)
                assertEquals(listOf(cleanupFailure), actual?.suppressed?.toList())
                assertFalse(picker.window.isShowing)
                assertNull(f.trigger.picker)
            } finally { f.root.probe = null; picker.close() }
        }
    }

    @Test fun closedAncestorGateBlocksTriggerAndOpenPickerClickAccessibilityAndKeyInput() = withFixture { f ->
        main {
            f.trigger.row.isFocusableInTouchMode = true
            f.trigger.row.requestFocus()
            f.root.allowed = false
            assertFalse(f.trigger.row.performClick())
            assertFalse(f.trigger.row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            press(f.trigger.row, KeyEvent.KEYCODE_ENTER)
            assertEquals(0, f.opens)
            f.root.allowed = true
        }
        val replies = mutableListOf<String>()
        val picker = open(f, onSelect = { replies += it })
        main {
            val row = picker.rows.first()
            row.isFocusableInTouchMode = true
            row.requestFocus()
            f.root.allowed = false
            assertFalse(row.performClick())
            assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            press(row, KeyEvent.KEYCODE_ENTER)
            assertTrue(replies.isEmpty())
            f.trigger.dismissChoices()
            f.root.allowed = true
            assertFalse(row.performClick())
        }
    }

    @Test fun windowFocusLossRejectsDirectActionsAndRetiresThePendingActivationKey() = withFixture { f ->
        val replies = mutableListOf<String>()
        val picker = open(f, onSelect = { replies += it })
        var covering: Dialog? = null
        try {
            main {
                val row = picker.rows.first()
                row.isFocusableInTouchMode = true
                assertTrue(row.requestFocus())
                row.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                covering = Dialog(f.activity).apply { setContentView(View(f.activity)) }
                showFixture(requireNotNull(covering))
            }
            await { !picker.panel.hasWindowFocus() && covering!!.window!!.decorView.hasWindowFocus() }
            main {
                val row = picker.rows.first()
                assertFalse(row.performClick())
                assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                covering!!.dismiss()
            }
            await { picker.panel.hasWindowFocus() }
            main {
                val row = picker.rows.first()
                row.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                assertTrue(replies.isEmpty())
                press(row, KeyEvent.KEYCODE_ENTER)
                assertEquals(listOf("a"), replies)
            }
        } finally { main { covering?.dismiss() } }
    }

    @Test fun escapeAndAnchorDetachAreSilentAndDismissalReturnsFocus() = withFixture { f ->
        val replies = mutableListOf<String>()
        val picker = open(f, onSelect = { replies += it })
        main { press(picker.panel, KeyEvent.KEYCODE_ESCAPE); assertFalse(picker.window.isShowing) }
        await { f.trigger.hasWindowFocus() && f.trigger.hasFocus() }
        assertTrue(replies.isEmpty())
        val next = open(f, onSelect = { replies += it })
        main {
            f.root.removeView(f.trigger)
            assertFalse(next.window.isShowing)
            assertFalse(next.rows.first().performClick())
            assertTrue(replies.isEmpty())
        }
    }

    @Test fun outsideDismissalIsSilentAndAFreshPointerSelectsOnceAfterReopening() = withFixture { f ->
        val replies = mutableListOf<String>()
        val picker = open(f, onSelect = { replies += it })
        main {
            val time = SystemClock.uptimeMillis()
            val outside = MotionEvent.obtain(time, time, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try { picker.panel.rootView.dispatchTouchEvent(outside) } finally { outside.recycle() }
            assertFalse(picker.window.isShowing)
            assertTrue(replies.isEmpty())
        }
        await { f.trigger.hasWindowFocus() && f.trigger.hasFocus() }
        val next = open(f, onSelect = { replies += it })
        main {
            val row = next.rows.last()
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time + action, action, row.width / 2f, row.height / 2f, 0)
                try { row.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            assertEquals(listOf("b"), replies)
            assertFalse(row.performClick())
            assertEquals(listOf("b"), replies)
        }
    }

    @Test fun narrowRtlPopupFlipsAboveAndRetiresOnDensityChangeWithoutSelection() = withFixture(bottom = true, rtl = true) { f ->
        val replies = mutableListOf<String>()
        val picker = open(f, (0..5).map {
            ReverbAppChoice("$it", "متصفح طويل للاختبار $it", "org.example.very.long.package$it")
        }, "3", onSelect = { replies += it })
        main {
            assertTrue(picker.window.isAboveAnchor)
            assertEquals(picker.panel.height.toFloat(), picker.panel.pivotY, 1f)
            assertEquals(View.LAYOUT_DIRECTION_RTL, picker.rows.first().layoutDirection)
            assertTrue(picker.rows.first().icon.left > picker.rows.first().label.left)
            assertTrue(picker.panel.width <= f.root.width)
            assertTrue(picker.rows.all { it.packageLabel.measuredWidth > 0 })
            val dark = ReverbDesign.theme(f.activity, true)
            picker.bindTheme(dark)
            assertTrue(picker.window.isShowing)
            picker.bindTheme(ReverbDesign.windowTheme(Density(dark.density.density, 1.6f), dark.fontResolver, dark.palette))
            assertFalse(picker.window.isShowing)
            assertTrue(replies.isEmpty())
            f.trigger.bind(ReverbAppChoice("a", "متصفح طويل للاختبار", "org.example.a"),
                ReverbDesign.windowTheme(Density(dark.density.density, 1.6f), dark.fontResolver, dark.palette))
        }
        await { f.trigger.hasWindowFocus() }
        val enlarged = open(f, listOf(ReverbAppChoice("a", "متصفح طويل للاختبار", "org.example.very.long.package")),
            onSelect = { replies += it })
        main {
            assertTrue(enlarged.window.isAboveAnchor)
            assertTrue(enlarged.rows.first().height >= (48 * f.theme.density.density).roundToInt())
            assertTrue(enlarged.rows.first().packageLabel.height > 0)
            assertTrue(replies.isEmpty())
        }
    }

    private fun withCheckedRow(f: Fixture, widthDp: Int = 320,
        block: (ReverbCheckedSettingsRow, MutableList<Boolean>) -> Unit) {
        val requests = mutableListOf<Boolean>()
        lateinit var control: ReverbCheckedSettingsRow
        main {
            control = ReverbCheckedSettingsRow(f.activity, f.theme) { requests += it }
            control.bind("Example app", null, false, true, f.theme)
            f.root.removeView(f.trigger)
            f.root.addView(control, FrameLayout.LayoutParams(
                (widthDp * f.theme.density.density).roundToInt().coerceAtMost(f.root.width), -2, Gravity.TOP or Gravity.START))
        }
        try {
            await { control.row.isLaidOut && control.row.hasWindowFocus() }
            block(control, requests)
        } finally {
            main { control.close(); f.root.removeView(control) }
        }
    }

    private fun open(f: Fixture, choices: List<ReverbAppChoice> = listOf(
        ReverbAppChoice("a", "Browser A", "org.example.a"), ReverbAppChoice("b", "Browser B", "org.example.b")),
        selected: String = "a", canSelect: () -> Boolean = { true }, onSelect: (String) -> Unit): ReverbAppChoicePicker {
        lateinit var result: ReverbAppChoicePicker
        main {
            f.trigger.showChoices("Choose browser", choices, selected, canSelect, onSelect)
            result = requireNotNull(f.trigger.picker)
        }
        await { result.panel.isLaidOut && result.panel.hasWindowFocus() }
        return result
    }

    private class GateLayout(context: android.content.Context) : FrameLayout(context), NativeInputGate {
        var allowed = true
        var probe: (() -> Unit)? = null
        override val acceptsNativeInput: Boolean get() { probe?.invoke(); return allowed }
    }
    private class Fixture(val activity: NativeMenuAccessibilityTestActivity, val root: GateLayout,
        val theme: ReverbTheme) {
        var opens = 0
        val trigger = ReverbAppChoiceTrigger(activity, theme) { opens++ }
    }

    private fun withFixture(bottom: Boolean = false, rtl: Boolean = false, block: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeMenuAccessibilityTestActivity
        lateinit var f: Fixture
        var installed: Fixture? = null
        try {
            main {
                val root = GateLayout(activity)
                f = Fixture(activity, root, ReverbDesign.theme(activity, false))
                installed = f
                root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                f.trigger.bind(ReverbAppChoice("a", "Browser A", "org.example.a"), f.theme)
                root.addView(f.trigger, FrameLayout.LayoutParams(
                    (320 * f.theme.density.density).roundToInt().coerceAtMost(activity.resources.displayMetrics.widthPixels), -2,
                    if (bottom) Gravity.BOTTOM or Gravity.START else Gravity.TOP or Gravity.START))
                activity.setContentView(root)
            }
            await { f.trigger.isLaidOut && f.trigger.hasWindowFocus() }
            block(f)
        } finally {
            main { installed?.trigger?.close(); activity.finish() }
        }
    }

    private fun showFixture(dialog: Dialog) {
        prepareFixtureWindow(dialog)
        dialog.show()
        assertFixtureWindow(dialog)
    }

    private fun prepareFixtureWindow(dialog: Dialog) {
        if (!excludeIme) return
        val window = requireNotNull(dialog.window)
        check(!dialog.isShowing && window.peekDecorView()?.isAttachedToWindow != true) {
            "IME exclusion must be set before the fixture window is attached"
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode((window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        assertFixtureWindow(dialog)
    }

    private fun assertFixtureWindow(dialog: Dialog) {
        if (!excludeIme) return
        val attrs = requireNotNull(dialog.window).attributes
        assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
        assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            attrs.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
    }

    private fun press(view: View, key: Int) {
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Attached app-choice window did not settle")
    }
}
