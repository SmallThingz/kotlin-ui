package smallthingz.ui.components

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

/** Attached windows exercise shared input/placement; these are not device TalkBack/IME claims. */
@RunWith(AndroidJUnit4::class)
class ReverbAppChoiceIntegrationTest {
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
                covering = Dialog(f.activity).apply { setContentView(View(f.activity)); show() }
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
        override val acceptsNativeInput get() = allowed
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

    private fun press(view: View, key: Int) {
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
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
