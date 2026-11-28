package smallthingz.ui.components

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
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

/** Software/native-graphics foreground pixels, not a GPU ripple or physical focus qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeProgressSwitchIndicationHostTest {
    private lateinit var frameDelay: Duration
    private var vsyncPaused = false

    @Before fun finiteFrameClock() {
        frameDelay = ShadowChoreographer.getFrameDelay()
        vsyncPaused = ShadowChoreographer.isPaused()
        // Match the existing SDK35 host fixtures: rescheduled vsync must be in the future.
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ShadowChoreographer.setPaused(true)
    }

    @After fun restoreFrameClock() {
        ShadowChoreographer.setFrameDelay(frameDelay)
        ShadowChoreographer.setPaused(vsyncPaused)
    }

    @Test fun focusedStandaloneInkIsRoundedAfterResizeAndKeepsFullSemanticTarget() = withRow { row, values ->
        assertTrue(row.requestFocusFromTouch())
        settleInk(row)
        assertTrue(row.isFocused)
        assertFalse(row.defaultFocusHighlightEnabled)
        assertRoundedInk(row)
        layout(row, 96, 64)
        assertRoundedInk(row)

        val node = checkNotNull(row.createAccessibilityNodeInfo())
        assertTrue(node.isFocusable)
        assertTrue(node.isCheckable)
        assertFalse(node.isChecked)
        assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        val bounds = Rect().also(node::getBoundsInScreen)
        assertEquals(96, bounds.width())
        assertEquals(64, bounds.height())
        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf(true), values)
        (row as NativeProgressSwitch).bindProgress(false, false, null)
        row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals("Disabled activation remains refused", listOf(true), values)
    }

    @Test fun hoverUsesTheSameRoundedForegroundWithoutToggling() = withRow { row, values ->
        event(MotionEvent.ACTION_HOVER_ENTER, row.width / 2f, row.height / 2f) { row.onHoverEvent(it) }
        settleInk(row)
        assertRoundedInk(row)
        assertTrue(values.isEmpty())
        event(MotionEvent.ACTION_HOVER_EXIT, row.width / 2f, row.height / 2f) { row.onHoverEvent(it) }
        settleInk(row)
        foreground(row).useBitmap { assertEquals(0, Color.alpha(it.getPixel(it.width / 2, it.height / 2))) }
    }

    @Test fun pressedInkIsRoundedButCornerTapStillUsesTheFullTarget() = withRow { row, values ->
        assertTrue(row.width >= 48 && row.height >= 48)
        val down = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, 1f, 1f, down) { assertTrue(row.onTouchEvent(it)) }
        settleInk(row)
        assertRoundedInk(row)
        assertTrue("A press does not dispatch before release", values.isEmpty())
        event(MotionEvent.ACTION_UP, 1f, 1f, down) { assertTrue(row.onTouchEvent(it)) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Visually clipped corner remains an ordinary target", listOf(true), values)
    }

    @Test fun ordinaryGroupedToggleKeepsItsRectangularFocusIndication() = withRow(standalone = false) { row, values ->
        assertTrue(row.requestFocusFromTouch())
        settleInk(row)
        foreground(row).useBitmap {
            assertTrue(Color.alpha(it.getPixel(1, 1)) > 0)
            assertTrue(Color.alpha(it.getPixel(it.width / 2, it.height / 2)) > 0)
        }
        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf(true), values)
    }

    private fun withRow(standalone: Boolean = true, check: (NativeToggleRow, MutableList<Boolean>) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = activity.get()
        val scheme = NativeColorScheme.fallback(true)
        val values = mutableListOf<Boolean>()
        val row = if (standalone) NativeProgressSwitch(context, scheme, "Fixture switch", { values += it })
            .apply { bindProgress(false, true, null) }
        else NativeToggleRow(context, View(context), null, NativeMaterialSwitchView(context, scheme),
            onChanged = { values += it }).apply { bindToggle(false, true, scheme) }
        val parking = View(context).apply { isFocusable = true; isFocusableInTouchMode = true }
        val root = FrameLayout(context).apply {
            if (standalone) addView(row, FrameLayout.LayoutParams(64, 48))
            else addView(NativeGroupLayout(context).apply {
                addRow(row, NativeGroupLayout.LayoutParams(160, 64))
            }, FrameLayout.LayoutParams(160, 64))
            addView(parking, FrameLayout.LayoutParams(1, 1).apply { topMargin = 200 })
        }
        val scale = ValueAnimator.getDurationScale()
        setScale(1f)
        try {
            context.setContentView(root)
            activity.visible()
            // Paused 16ms vsync defers the first traversal; focus dispatch requires its ViewRoot.
            shadowOf(Looper.getMainLooper()).idleFor(16, MILLISECONDS)
            assertTrue("Real control must attach before window focus", row.isAttachedToWindow)
            activity.windowFocusChanged(true)
            // SDK35 queues focus on ViewRoot's Handler; the next traversal releases its message.
            shadowOf(Looper.getMainLooper()).idleFor(16, MILLISECONDS)
            assertTrue("Real window must own focus", row.hasWindowFocus())
            layout(root, 360, 640)
            assertTrue(parking.requestFocus())
            settleInk(row)
            assertTrue(row.isAttachedToWindow)
            assertFalse(row.isFocused)
            check(row, values)
        } finally {
            row.close()
            activity.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            setScale(scale)
        }
    }

    private fun settleInk(row: View) {
        shadowOf(Looper.getMainLooper()).idleFor(120, MILLISECONDS) // Deliver accepted interactions/delayed press.
        foreground(row).recycle() // Start the real RippleDrawable's software draw timeline if pressed.
        shadowOf(Looper.getMainLooper()).idleFor(300, MILLISECONDS)
    }

    private fun assertRoundedInk(row: View) {
        foreground(row).useBitmap { bitmap ->
            for ((x, y) in listOf(1 to 1, bitmap.width - 2 to 1,
                1 to bitmap.height - 2, bitmap.width - 2 to bitmap.height - 2)) {
                assertEquals("No square indication at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
            assertTrue("Focus/hover/press remains visible", Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 0)
        }
        // Causal control: the very same live indication still paints the formerly rectangular corner.
        Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888).useBitmap {
            row.foreground.draw(Canvas(it))
            assertTrue("The rounded assertion must exercise actual ink", Color.alpha(it.getPixel(1, 1)) > 0)
        }
    }

    private fun foreground(row: View): Bitmap =
        Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888).also { row.onDrawForeground(Canvas(it)) }

    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun event(action: Int, x: Float, y: Float, down: Long = SystemClock.uptimeMillis(), block: (MotionEvent) -> Unit) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { block(event) } finally { event.recycle() }
    }

    private fun setScale(value: Float) = ValueAnimator::class.java
        .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).apply { isAccessible = true }
        .invoke(null, value)
}
