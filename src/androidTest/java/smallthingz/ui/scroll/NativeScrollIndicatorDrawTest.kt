package smallthingz.ui.scroll

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.components.withRetiringTestActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeScrollIndicatorDrawTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val style get() = NativeUiAppearance.Default.scrollIndicator

    @Test fun physicalRightAndDpGeometryHoldInLtrAndRtl() = instrumentation.runOnMainSync {
        for (direction in intArrayOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val mdpi = list(160, direction).apply { adapter = Rows(16) }
            val xhdpi = list(320, direction).apply { adapter = Rows(16) }
            for (list in listOf(mdpi, xhdpi)) {
                list.setPadding(7, 0, 13, 0)
                layout(list, 120, 96)
                assertEquals(direction, list.layoutDirection)
            }
            assertThumb(mdpi, Rect(116, 0, 118, 24))
            assertThumb(xhdpi, Rect(112, 0, 116, 48))
        }
    }

    @Test fun realRowsUpdateTheThumbWhenScrolledResizedAndRebound() = instrumentation.runOnMainSync {
        val list = list(160, View.LAYOUT_DIRECTION_LTR)
        list.adapter = Rows(0)
        layout(list, 120, 96)
        assertThumb(list, null)

        list.adapter = Rows(4)
        layout(list, 120, 96)
        assertThumb(list, null)

        list.adapter = Rows(16)
        layout(list, 120, 96)
        assertThumb(list, Rect(116, 0, 118, 24))
        list.scrollBy(0, 144)
        assertThumb(list, Rect(116, 36, 118, 60))

        val custom = NativeScrollIndicatorStyle(widthDp = 4f, rightInsetDp = 5f, minThumbHeightDp = 30f)
        list.bindScrollIndicator(custom, Color.BLUE)
        assertThumb(list, Rect(111, 33, 115, 63), Color.BLUE)
        list.scrollBy(0, 144)
        assertThumb(list, Rect(111, 66, 115, 96), Color.BLUE)
        list.bindScrollIndicator(null, Color.BLUE)
        assertThumb(list, null)

        list.bindScrollIndicator(style, Color.RED)
        list.scrollBy(0, -288)
        layout(list, 90, 192)
        assertThumb(list, Rect(86, 0, 88, 96))
        layout(list, 90, 384)
        assertThumb(list, null)
    }

    @Test fun indicatorSurvivesHardwareSkipDrawRequests() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: LinearLayout? = null
        withRetiringTestActivity(activity, root = { host }) {
            lateinit var bound: NativeSourceRecyclerView
            lateinit var control: NativeSourceRecyclerView
            val laidOut = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setBackgroundColor(Color.WHITE)
                }
                host = root
                fun addList() = recycler(activity, 160, View.LAYOUT_DIRECTION_LTR).apply {
                    adapter = Rows(16)
                    isFocusable = false
                    defaultFocusHighlightEnabled = false
                    root.addView(this, LinearLayout.LayoutParams(120, 96))
                }
                bound = addList()
                control = addList()
                control.doOnLayout { laidOut.countDown() }
                activity.setContentView(root)
            }
            assertTrue("Hardware fixture did not lay out", laidOut.await(5, TimeUnit.SECONDS))

            instrumentation.runOnMainSync {
                for (list in listOf(bound, control)) {
                    assertTrue(list.isAttachedToWindow && list.isHardwareAccelerated)
                    assertTrue(list.canScrollVertically(1))
                    assertEquals(View.OVER_SCROLL_NEVER, list.overScrollMode)
                    assertNull(list.background)
                    assertNull(list.foreground)
                    assertFalse(list.hasFocus())
                }
                bound.setWillNotDraw(true)
                assertTrue("Fixture must enter draw suppression before binding", bound.willNotDraw())
                bound.bindScrollIndicator(style, Color.RED)
                assertFalse(bound.willNotDraw())
            }
            assertWindowThumb(activity.window, bound, control, Color.RED)

            instrumentation.runOnMainSync {
                // A different tint makes PixelCopy reject a stale pre-removal frame.
                bound.bindScrollIndicator(style, Color.BLUE)
                for (list in listOf(bound, control)) {
                    val decoration = object : RecyclerView.ItemDecoration() {}
                    list.addItemDecoration(decoration)
                    assertEquals(1, list.itemDecorationCount)
                    list.removeItemDecoration(decoration)
                    assertEquals(0, list.itemDecorationCount)
                }
                assertTrue("Unbound RecyclerView must take its stock reset branch", control.willNotDraw())
                assertFalse("Bound thumb must keep hardware drawing enabled", bound.willNotDraw())
            }
            assertWindowThumb(activity.window, bound, control, Color.BLUE)

            instrumentation.runOnMainSync {
                bound.bindScrollIndicator(null, Color.BLUE)
                assertTrue("Unbind restores the latest suppressed draw request", bound.willNotDraw())
            }
            assertWindowThumb(activity.window, bound, control, Color.WHITE)

            instrumentation.runOnMainSync {
                bound.bindScrollIndicator(style, Color.BLUE)
                bound.addItemDecoration(object : RecyclerView.ItemDecoration() {})
                bound.bindScrollIndicator(null, Color.BLUE)
                assertFalse("Unbind honors a later draw request from decoration addition", bound.willNotDraw())
            }
        }
    }

    private fun assertWindowThumb(
        window: Window,
        bound: RecyclerView,
        control: RecyclerView,
        expected: Int,
    ) {
        val source = Rect()
        instrumentation.runOnMainSync {
            val boundLocation = IntArray(2).also(bound::getLocationInWindow)
            val controlLocation = IntArray(2).also(control::getLocationInWindow)
            assertEquals(boundLocation[0], controlLocation[0])
            // Capture the same interior thumb position from each 120×96 mdpi list.
            val x = boundLocation[0] + 117
            source.set(x, boundLocation[1] + 12, x + 1, controlLocation[1] + 13)
        }
        val handler = Handler(Looper.getMainLooper())
        val deadline = SystemClock.uptimeMillis() + 5_000
        var result = PixelCopy.ERROR_SOURCE_NO_DATA
        var boundPixel = Color.TRANSPARENT
        var controlPixel = Color.TRANSPARENT
        do {
            val image = Bitmap.createBitmap(source.width(), source.height(), Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            instrumentation.runOnMainSync {
                PixelCopy.request(window, Rect(source), image, { status ->
                    result = status
                    copied.countDown()
                }, handler)
            }
            assertTrue("PixelCopy did not complete",
                copied.await(maxOf(1L, deadline - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS))
            try {
                if (result == PixelCopy.SUCCESS) {
                    boundPixel = image.getPixel(0, 0)
                    controlPixel = image.getPixel(0, image.height - 1)
                    if (boundPixel == expected && controlPixel == Color.WHITE) return
                }
            } finally {
                image.recycle()
            }
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("Window PixelCopy status", PixelCopy.SUCCESS, result)
        assertEquals("Bound RecyclerView pixel", expected, boundPixel)
        assertEquals("Unbound control pixel", Color.WHITE, controlPixel)
    }

    private fun list(density: Int, direction: Int) = recycler(instrumentation.context, density, direction).apply {
        setBackgroundColor(Color.WHITE)
        bindScrollIndicator(style, Color.RED)
    }

    private fun recycler(base: Context, density: Int, direction: Int): NativeSourceRecyclerView {
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = density
        })
        return NativeSourceRecyclerView(context).apply {
            layoutDirection = direction
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
        }
    }

    private fun layout(list: RecyclerView, width: Int, height: Int) {
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, width, height)
    }

    private fun assertThumb(list: RecyclerView, bounds: Rect?, color: Int = Color.RED) {
        val bitmap = Bitmap.createBitmap(list.width, list.height, Bitmap.Config.ARGB_8888)
        try {
            list.draw(Canvas(bitmap))
            for (y in 0 until list.height) for (x in 0 until list.width) {
                if (bitmap.getPixel(x, y) != Color.WHITE) {
                    assertTrue("Unexpected painted pixel ($x, $y), expected thumb at $bounds",
                        bounds?.contains(x, y) == true)
                }
            }
            if (bounds != null) {
                for (x in bounds.left until bounds.right) {
                    assertEquals(color, bitmap.getPixel(x, bounds.centerY()))
                }
                // The end rows can be partially covered by the rounded, antialiased cap.
                assertTrue(bitmap.getPixel(bounds.centerX(), bounds.top) != Color.WHITE)
                assertTrue(bitmap.getPixel(bounds.centerX(), bounds.bottom - 1) != Color.WHITE)
                if (bounds.width() >= 4) {
                    assertTrue(bitmap.getPixel(bounds.left, bounds.top) != color)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class Row(view: View) : RecyclerView.ViewHolder(view)

    private class Rows(private val count: Int) : RecyclerView.Adapter<Row>() {
        override fun getItemCount() = count
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(View(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24)
        })
        override fun onBindViewHolder(holder: Row, position: Int) = Unit
    }
}
