package smallthingz.ui.components

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import java.time.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer

/** Real row callbacks only; no application operations, popup, or synthetic input admission. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSettingsRowAdmissionHostTest {
    private lateinit var oldFrameDelay: Duration
    private var oldPaused = false

    @Before fun finiteFrameClock() {
        oldFrameDelay = ShadowChoreographer.getFrameDelay()
        oldPaused = ShadowChoreographer.isPaused()
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ShadowChoreographer.setPaused(true)
    }

    @After fun restoreFrameClock() {
        ShadowChoreographer.setFrameDelay(oldFrameDelay)
        ShadowChoreographer.setPaused(oldPaused)
    }

    @Test fun pageLeaseRefusesRealClickAndLongActionsThenReleaseRestoresThem() = withRow { row, _ ->
        var clicks = 0
        var longs = 0
        row.bind("Fixture", "Detail", onClick = { clicks++ })
        row.setOnLongClickListener { longs++; true }
        row.isSelected = true
        val description = row.contentDescription.toString()
        val node = row.createAccessibilityNodeInfo()
        try {
            assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK })
        } finally { node.recycle() }
        fun actions(expected: Boolean) {
            assertEquals(expected, row.performClick())
            // View reports a handled semantic action independently of performClick's result.
            // Actual callback counts below, plus direct return values, prove effect admission.
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(expected, row.performLongClick())
            assertEquals(expected, row.performLongClick(11f, 13f))
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        }
        val lease = checkNotNull(NativePageTransition.InputRoot.blockFrom(row))
        try {
            actions(false)
            assertEquals(0, clicks)
            assertEquals(0, longs)
            assertTrue(row.isSelected)
            assertEquals(description, row.contentDescription.toString())
        } finally { lease.close() }
        actions(true)
        assertEquals(2, clicks)
        assertEquals(3, longs)
        assertTrue(row.isSelected)
        assertEquals(description, row.contentDescription.toString())
    }

    @Test fun genericInputGateStillRefusesBothActionsWithoutAPageLease() = withRow { row, gate ->
        var clicks = 0
        var longs = 0
        row.bind("Fixture", onClick = { clicks++ })
        row.setOnLongClickListener { longs++; true }
        gate.acceptsNativeInput = false
        assertFalse(row.performClick())
        // The platform acknowledges the action even when the guarded effect is refused.
        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertFalse(row.performLongClick())
        assertFalse(row.performLongClick(11f, 13f))
        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        assertEquals(0, clicks)
        assertEquals(0, longs)
        gate.acceptsNativeInput = true
        assertTrue(row.performClick())
        assertTrue(row.performLongClick(11f, 13f))
        assertEquals(1, clicks)
        assertEquals(1, longs)
    }

    @Test fun longOnlyRowNeedsBothOwnersToAdmitButNoPrimaryAction() = withRow { row, gate ->
        var longs = 0
        row.bind("Long only")
        row.setOnLongClickListener { longs++; true }
        assertFalse(row.isClickable)
        val lease = checkNotNull(NativePageTransition.InputRoot.blockFrom(row))
        gate.acceptsNativeInput = false
        try {
            assertFalse(row.performLongClick(11f, 13f))
            gate.acceptsNativeInput = true
            assertFalse(row.performLongClick())
            assertEquals(0, longs)
        } finally { lease.close() }
        assertTrue(row.performLongClick(11f, 13f))
        assertEquals(1, longs)
        assertFalse(row.isClickable)
    }

    @Test fun admittedLongFallbackKeepsCoordinatesAndBlockedFallbackHasNoEffects() = withRow { row, _ ->
        var declinedLongs = 0
        row.bind("Context action")
        row.setOnLongClickListener { declinedLongs++; false }
        val lease = checkNotNull(NativePageTransition.InputRoot.blockFrom(row))
        try {
            assertFalse(row.performLongClick(11f, 13f))
            assertEquals(0, declinedLongs)
            assertTrue(row.contextPoints.isEmpty())
            assertEquals(0, row.contextMenus)
        } finally { lease.close() }
        assertTrue(row.performLongClick(11f, 13f))
        assertEquals(listOf(11f to 13f), row.contextPoints)
        assertTrue(row.performLongClick())
        assertEquals("Coordinate overload must restore the ordinary no-coordinate fallback", 1, row.contextMenus)
        assertEquals(2, declinedLongs)
    }

    private class GateHost(context: Context) : FrameLayout(context), NativeInputGate {
        override var acceptsNativeInput = true
    }

    /** Observe the platform fallback boundary without creating a real context menu. */
    private class Row(context: Context) : NativeSettingsRow(context, NativeColorScheme.fallback(false).settingsControls) {
        val contextPoints = mutableListOf<Pair<Float, Float>>()
        var contextMenus = 0
        override fun showContextMenu(x: Float, y: Float): Boolean {
            contextPoints += x to y
            return true
        }
        override fun showContextMenu(): Boolean { contextMenus++; return true }
    }

    private fun withRow(block: (Row, GateHost) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = NativePageTransition.InputRoot(activity)
        val gate = GateHost(activity)
        val row = Row(activity)
        root.addView(gate, FrameLayout.LayoutParams(-1, -1))
        gate.addView(row, FrameLayout.LayoutParams(320, 88))
        activity.setContentView(root)
        try {
            controller.visible()
            shadowOf(Looper.getMainLooper()).idleFor(16, MILLISECONDS)
            assertTrue(row.isAttachedToWindow)
            controller.windowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idleFor(16, MILLISECONDS)
            root.measure(exact(360), exact(640))
            root.layout(0, 0, 360, 640)
            assertTrue(row.isShown)
            assertTrue(row.hasWindowFocus())
            block(row, gate)
        } finally { controller.pause().stop().destroy() }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
