package smallthingz.ui.components

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** System-window injection at the painted Share label: never calls View.performClick directly. */
@RunWith(AndroidJUnit4::class)
class NativeMenuWindowHitTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun paintedShareHitsOnlyShareAfterPointerRelocationAndReturningToFallback() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var popup: NativePopupMenu<Int>? = null
        var runtime: NativeModalWindowRuntime? = null
        lateinit var root: FrameLayout
        lateinit var anchor: View
        var shares = 0
        var renameState = false
        var otherActions = 0
        var dismissed = 0
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                root = FrameLayout(activity)
                anchor = View(activity)
                root.addView(anchor, FrameLayout.LayoutParams(180, 72).apply {
                    leftMargin = 24; topMargin = 96
                })
                activity.setContentView(root)
            }
            awaitDraw(anchor)
            instrumentation.runOnMainSync {
                val clock = NativeModalWindowRuntime(activity).also { runtime = it; it.setStarted(true) }
                val colors = NativeMenuColors(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(), 0xff305080.toInt())
                val icons = object : NativeMenuIcons<Int> {
                    override val check = 0
                    override fun create(context: Context, icon: Int, color: Int) = View(context)
                    override fun bind(view: View, icon: Int, color: Int) = Unit
                }
                popup = NativePopupMenu(anchor, Density(1f), TextStyle.Default, createFontFamilyResolver(activity),
                    colors, NativeVerticalScrollState(), clock.frames, clock.scope, icons,
                    onDismissRequest = { dismissed++ }, onTerminal = {})
                requireNotNull(popup).bindActions(listOf(
                    NativeMenuAction("Rename", 0, colors.ink, colors.ink) { renameState = true },
                    NativeMenuAction("Info", 1, colors.ink, colors.ink) { otherActions++ },
                    NativeMenuAction("Share", 2, colors.ink, colors.ink) { shares++ },
                    NativeMenuAction("Trim", 3, colors.ink, colors.ink) { otherActions++ },
                    NativeMenuAction("Delete", 4, colors.ink, colors.ink) { otherActions++ },
                    NativeMenuAction("Multi-select", 5, colors.ink, colors.ink) { otherActions++ },
                ), colors)
                requireNotNull(popup).setOrigin(NativeMenuOrigin(137f, 31f))
                requireNotNull(popup).show()
            }
            val menu = requireNotNull(popup)
            awaitDraw(menu, expanded = true)
            injectShare(menu)
            instrumentation.runOnMainSync {
                assertEquals(1, shares); assertFalse(renameState); assertEquals(0, otherActions)
                // Move the same origin close to the opposite viewport corner. Reuse the popup
                // and its retained row endpoints, which must not retain the old window location.
                root.scrollTo(5, 7)
                anchor.pivotX = 0f; anchor.pivotY = 0f
                anchor.scaleX = .9f; anchor.scaleY = 1.1f
                anchor.translationX = (root.width - anchor.right - 12).toFloat()
                anchor.translationY = (root.height - anchor.bottom - 12).toFloat()
                menu.setOrigin(NativeMenuOrigin(170f, 65f))
                menu.requestLayout()
            }
            awaitDraw(menu, expanded = true)
            injectShare(menu)
            instrumentation.runOnMainSync {
                assertEquals(2, shares); assertFalse(renameState); assertEquals(0, otherActions)
                menu.setOrigin(null) // Keyboard/accessibility placement must clear prior pointer origin.
                menu.requestLayout()
            }
            awaitDraw(menu, expanded = true)
            injectShare(menu)
            instrumentation.runOnMainSync {
                assertEquals(3, shares); assertFalse(renameState)
                assertEquals(0, otherActions); assertEquals(0, dismissed)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanup: Throwable? = null
            instrumentation.runOnMainSync {
                for (close in listOf<() -> Unit>({ popup?.close() }, { runtime?.close() }, { activity.finish() })) {
                    try { close() } catch (error: Throwable) {
                        val previous = failure ?: cleanup
                        if (previous != null) previous.addSuppressed(error) else cleanup = error
                    }
                }
            }
            if (failure == null) cleanup?.let { throw it }
        }
    }

    private fun injectShare(menu: NativePopupMenu<Int>) {
        val screen = IntArray(2)
        var x = 0f; var y = 0f
        instrumentation.runOnMainSync {
            val panel = menu.getChildAt(0) as NativeAnchoredMenuPanel
            assertEquals(1f, panel.scaleX, 0f)
            val content = panel.getChildAt(0) as NativeMenuContent<*>
            val label = content.column.rows[2].getChildAt(0)
            assertTrue(label.isShown)
            assertTrue(label.width > 0 && label.height > 0)
            label.getLocationOnScreen(screen)
            x = screen[0] + label.width / 2f
            y = screen[1] + label.height / 2f
        }
        val downTime = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitDraw(view: View, expanded: Boolean = false) {
        val drawn = CountDownLatch(1)
        val observer = arrayOfNulls<ViewTreeObserver>(1)
        val listener = ViewTreeObserver.OnPreDrawListener {
            val panel = if (expanded) (view as NativePopupMenu<*>).getChildAt(0) as NativeAnchoredMenuPanel else null
            if (view.isLaidOut && view.width > 0 && (panel == null || (panel.scaleX == 1f && panel.alpha == 1f))) {
                drawn.countDown()
            }
            true
        }
        instrumentation.runOnMainSync {
            observer[0] = view.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
            view.invalidate()
        }
        try { assertTrue("Owned popup did not draw in time", drawn.await(5, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { observer[0]?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) } }
    }
}
