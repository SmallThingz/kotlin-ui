package smallthingz.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.ReverbDesign
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Real Android layout, paint and touch checks with an attached recycled grid. */
@RunWith(AndroidJUnit4::class)
class NativeJoinedApplicationGridIntegrationTest {
    @Test fun shortRowPaintsConcaveJoinAndLeavesMissingCellsEmptyInBothDirections() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recipe = ReverbDesign.appearance.applicationGrid
        val d = context.resources.displayMetrics.density
        for (rtl in listOf(false, true)) {
            val grid = NativeJoinedApplicationGridLayout(context, ReverbDesign.theme(context, true).palette, recipe)
            grid.columnCount = 4
            grid.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            repeat(6) {
                grid.addView(View(context).apply {
                    setBackgroundColor(Color.BLUE)
                    setOnClickListener {}
                })
            }
            val width = (320 * d).roundToInt()
            val row = recipe.heightPx(context)
            grid.measure(exact(width), exact(row * 2)); grid.layout(0, 0, width, row * 2)
            val bitmap = Bitmap.createBitmap(width, row * 2, Bitmap.Config.ARGB_8888)
            try {
                grid.draw(Canvas(bitmap))
                fun pixel(x: Float, y: Int) = bitmap.getPixel(
                    (if (rtl) width - 1 - (x * d).roundToInt() else (x * d).roundToInt()).coerceIn(0, width - 1), y)
                assertEquals("Missing cells remain transparent", 0, Color.alpha(pixel(240f, row + row / 2)))
                assertEquals("The inward join is painted beyond the rectangular cells", 255,
                    Color.alpha(pixel(162f, row + (2 * d).roundToInt())))
                assertEquals("Occupied last row remains visible", Color.BLUE, pixel(40f, row + row / 2))
                assertEquals("Outer corner is clipped", 0, Color.alpha(pixel(0f, 0)))
                assertTrue(kotlin.math.abs(width / 4 - grid.getChildAt(5).width) <= 1)
                assertFalse("Transparent corner cannot activate an otherwise clickable child",
                    touch(grid, MotionEvent.ACTION_DOWN, if (rtl) width - 1f else 0f, 0f))
                val first = grid.getChildAt(0)
                tap(grid, (first.left + first.right) / 2f, row / 2f)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun reflowRetainsCellsAndDoesNotStretchThePartialRow() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recipe = ReverbDesign.appearance.applicationGrid
        val grid = NativeJoinedApplicationGridLayout(context, ReverbDesign.theme(context, false).palette, recipe)
        val children = List(6) { View(context) }; children.forEach(grid::addView)
        for (columns in listOf(4, 2, 1, 4)) {
            grid.columnCount = columns
            grid.measure(exact(320), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            grid.layout(0, 0, 320, grid.measuredHeight)
            children.forEachIndexed { index, child ->
                assertSame(child, grid.getChildAt(index))
                assertTrue(kotlin.math.abs(320 / columns - child.width) <= 1)
                assertEquals(index / columns * recipe.heightPx(context), child.top)
            }
        }
    }

    @Test fun recyclerKeepsHeaderAndScrolledPartialRowClickable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val laidOut = CountDownLatch(1)
        val clicked = CountDownLatch(1)
        val clicks = mutableListOf<Int>()
        var grid: InvalidationProbe? = null
        withRetiringTestActivity(activity, root = { grid }) {
            main {
                val theme = ReverbDesign.theme(activity, true)
                val recipe = theme.appearance.applicationGrid
                val rowHeight = recipe.heightPx(activity)
                val headerHeight = rowHeight / 2
                val manager = GridLayoutManager(activity, 4).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int) = if (position == 0) 4 else 1
                    }
                }
                val list = InvalidationProbe(activity, theme.palette, recipe).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    headerItemCount = 1
                    itemAnimator = null
                    overScrollMode = View.OVER_SCROLL_NEVER
                    layoutManager = manager
                    adapter = AppRows(headerHeight, rowHeight) { position ->
                        clicks += position
                        if (position == 9) clicked.countDown()
                    }
                    addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                        if (right > left && bottom > top) laidOut.countDown()
                    }
                }
                grid = list
                activity.setContentView(FrameLayout(activity).apply {
                    addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerHeight + rowHeight))
                })
            }
            assertTrue("Joined grid did not lay out", laidOut.await(5, TimeUnit.SECONDS))
            main {
                val list = checkNotNull(grid)
                val manager = list.layoutManager as GridLayoutManager
                val header = checkNotNull(manager.findViewByPosition(0))
                val first = checkNotNull(manager.findViewByPosition(1))
                assertEquals(list.width, header.width)
                assertEquals(header.bottom, first.top)
                assertTrue(first.top > 0)
                assertEquals(Color.MAGENTA, pixel(list, list.width - 1, header.height / 2))

                // Send the same notification NoiseTexture emits on completion, without relying on
                // whether the process-wide shader was warmed by another test.
                val noise = list.grain()
                assertSame(list, noise.callback)
                val before = list.grainInvalidations
                noise.invalidateSelf()
                assertEquals(before + 1, list.grainInvalidations)

                assertTrue(list.canScrollVertically(1))
                list.scrollBy(0, first.height * 3)
                assertFalse(list.canScrollVertically(1))
                val partial = checkNotNull(manager.findViewByPosition(9))
                assertTrue(partial.top >= 0 && partial.bottom <= list.height)
                val x = (partial.left + partial.right) / 2
                val y = (partial.top + partial.bottom) / 2
                assertEquals(Color.BLUE, pixel(list, x, y))
                assertEquals(Color.TRANSPARENT, pixel(list, list.width * 3 / 4, y))
                val node = partial.createAccessibilityNodeInfo()
                try {
                    assertEquals("App 9", node.contentDescription)
                    assertTrue(node.isClickable)
                    assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                } finally { node.recycle() }
                tap(list, x.toFloat(), y.toFloat())
            }
            assertTrue("The visible partial-row cell did not click", clicked.await(5, TimeUnit.SECONDS))
            main {
                assertEquals(listOf(9), clicks)
                val list = checkNotNull(grid)
                (list.parent as ViewGroup).removeView(list)
                assertNull(list.grain().callback)
            }
        }
    }

    private class AppRows(private val headerHeight: Int, private val rowHeight: Int,
        private val clicked: (Int) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 11

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(View(parent.context)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            holder.itemView.apply {
                layoutParams = GridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    if (position == 0) headerHeight else rowHeight)
                setBackgroundColor(if (position == 0) Color.MAGENTA else Color.BLUE)
                contentDescription = if (position == 0) "Search header" else "App $position"
                isFocusable = true
                setOnClickListener { clicked(position) }
            }
        }
    }

    private class InvalidationProbe(context: Context, palette: NativeColorScheme, recipe: NativeApplicationGridRecipe)
        : NativeJoinedApplicationRecyclerView(context, palette, recipe) {
        var grainInvalidations = 0
            private set

        fun grain(): NoiseDrawable = NativeJoinedApplicationRecyclerView::class.java.getDeclaredField("fill")
            .apply { isAccessible = true }.get(this) as NoiseDrawable

        override fun invalidateDrawable(who: Drawable) {
            if (who is NoiseDrawable && verifyDrawable(who)) grainInvalidations++
            super.invalidateDrawable(who)
        }
    }

    private fun pixel(view: View, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            bitmap.getPixel(x, y)
        } finally { bitmap.recycle() }
    }

    private fun touch(view: View, action: Int, x: Float, y: Float, down: Long = SystemClock.uptimeMillis()): Boolean {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun tap(view: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        assertTrue("Interior DOWN must be admitted", touch(view, MotionEvent.ACTION_DOWN, x, y, down))
        assertTrue("Interior UP must be delivered", touch(view, MotionEvent.ACTION_UP, x, y, down))
    }

    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)

    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
