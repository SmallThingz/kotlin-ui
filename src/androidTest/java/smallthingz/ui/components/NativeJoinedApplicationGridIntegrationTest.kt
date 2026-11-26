package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbDesign
import kotlin.math.roundToInt

/** Real Android layout/draw checks; these do not claim an attached application flow. */
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
            repeat(6) { grid.addView(View(context).apply { setBackgroundColor(Color.BLUE) }) }
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
                val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                    if (rtl) width - 1f else 0f, 0f, 0)
                try { assertFalse("Transparent corner cannot activate a child", grid.dispatchTouchEvent(down)) }
                finally { down.recycle() }
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

    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
