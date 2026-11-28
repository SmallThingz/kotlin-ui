package smallthingz.ui.components

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Public controlled-choice contract in an attached window; no renderer or device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeChoiceSelectActionHostTest {
    @Test fun repeatedSelectKeepsCheckedRadioAndDoesNotRepeatOwnerCallback() = withChoices { _, choices, requests ->
        val first = checkNotNull(choices.choiceView("a"))
        val second = checkNotNull(choices.choiceView("b"))
        assertTrue(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        assertEquals("a", choices.selectedId)
        assertEquals(listOf("a"), requests)
        assertSelectedRadio(first)

        assertTrue(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        assertEquals("a", choices.selectedId)
        assertEquals(listOf("a"), requests)
        assertSelectedRadio(first)

        assertTrue(second.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        assertEquals("b", choices.selectedId)
        assertEquals(listOf("a", "b"), requests)
        assertFalse(first.isChecked)
        assertSelectedRadio(second)
    }

    @Test fun pointerAndAccessibilityClickStillToggleOffForTextOnlyAnswer() = withChoices { root, choices, requests ->
        val first = checkNotNull(choices.choiceView("a"))
        tap(root, first)
        assertEquals("a", choices.selectedId)
        assertTrue(first.isChecked)
        tap(root, first)
        assertNull(choices.selectedId)
        assertFalse(first.isChecked)
        assertEquals(listOf("a", "a"), requests)

        repeat(2) { assertTrue(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)) }
        assertNull(choices.selectedId)
        assertFalse(first.isChecked)
        assertEquals(listOf("a", "a", "a", "a"), requests)
    }

    @Test fun selectedRadioStillRejectsDisabledAndClosedActions() = withChoices { _, choices, requests ->
        val first = checkNotNull(choices.choiceView("a"))
        assertTrue(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        choices.isEnabled = false
        assertFalse(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        assertEquals("a", choices.selectedId)
        assertEquals(listOf("a"), requests)
        choices.isEnabled = true
        assertTrue(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        assertEquals(listOf("a"), requests)

        choices.close()
        assertFalse(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        assertFalse(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals("a", choices.selectedId)
        assertEquals(listOf("a"), requests)
    }

    @Test fun enabledChipsRespectRejectingAncestorBeforeSelectedNoOp() = withChoices { root, choices, requests ->
        val first = checkNotNull(choices.choiceView("a"))
        val second = checkNotNull(choices.choiceView("b"))
        assertTrue(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        root.acceptsNativeInput = false
        assertTrue(root.isEnabled && choices.isEnabled)
        for (chip in listOf(first, second)) {
            assertTrue(chip.isEnabled)
            assertTrue(chip.isAttachedToWindow && chip.isShown && chip.hasWindowFocus())
            assertFalse(chip.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
            assertFalse(chip.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        }
        assertEquals("a", choices.selectedId)
        assertTrue(first.isChecked)
        assertFalse(second.isChecked)
        assertEquals(listOf("a"), requests)

        root.acceptsNativeInput = true
        assertTrue(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        assertEquals(listOf("a"), requests)
        assertTrue(second.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        assertEquals("b", choices.selectedId)
        assertEquals(listOf("a", "b"), requests)
    }

    private fun assertSelectedRadio(chip: NativeChip) {
        assertTrue(chip.isChecked)
        val node = chip.createAccessibilityNodeInfo()
        try {
            assertEquals("android.widget.RadioButton", node.className.toString())
            assertTrue(node.isCheckable)
            assertTrue(node.isChecked)
            assertTrue(node.isSelected)
            assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SELECT })
        } finally { node.recycle() }
    }

    private class GateHost(context: Context) : FrameLayout(context), NativeInputGate {
        override var acceptsNativeInput = true
    }

    private fun withChoices(block: (GateHost, NativeSegmentedChoice, MutableList<String>) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = GateHost(activity)
        val choices = NativeSegmentedChoice(activity, NativeColorScheme.fallback(false).settingsControls,
            NativeChoiceStyle(animateSelection = false), maxColumns = 1, fixedColumns = 1,
            activateOnFocusNavigation = false)
        try {
            val options = listOf(NativeChoice("a", "A", semantics = NativeChoiceSemantics.RADIO),
                NativeChoice("b", "B", semantics = NativeChoiceSemantics.RADIO))
            val requests = mutableListOf<String>()
            choices.bind(options, null)
            choices.onSelectionRequest = { id ->
                requests += id
                choices.bind(options, if (choices.selectedId == id) null else id)
                true
            }
            root.addView(choices, FrameLayout.LayoutParams(320, FrameLayout.LayoutParams.WRAP_CONTENT))
            activity.setContentView(root)
            controller.visible().windowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idleFor(16, MILLISECONDS)
            root.measure(exact(360), exact(640))
            root.layout(0, 0, 360, 640)
            for (id in listOf("a", "b")) {
                val chip = checkNotNull(choices.choiceView(id))
                assertTrue(chip.isAttachedToWindow)
                assertTrue(chip.isShown)
                assertTrue(chip.hasWindowFocus())
                assertTrue(chip.width > 0 && chip.height >= 48)
            }
            block(root, choices, requests)
        } finally {
            try { choices.close() } finally { controller.pause().stop().destroy() }
        }
    }

    private fun tap(root: FrameLayout, chip: NativeChip) {
        val bounds = Rect(0, 0, chip.width, chip.height)
        root.offsetDescendantRectToMyCoords(chip, bounds)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
            shadowOf(Looper.getMainLooper()).idleFor(16, MILLISECONDS)
        }
        shadowOf(Looper.getMainLooper()).idleFor(100, MILLISECONDS)
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
