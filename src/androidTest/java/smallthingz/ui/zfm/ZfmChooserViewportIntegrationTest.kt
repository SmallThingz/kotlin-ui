package smallthingz.ui.zfm

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.withRetiringTestActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ZfmChooserViewportIntegrationTest {
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
