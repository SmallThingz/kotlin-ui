package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
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
    @Test fun selectedChildBackgroundsMeetAtSharpInnerTurnInBothDirections() = main {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).also {
            it.fontScale = 1f
            it.densityDpi = 160
        })
        val recipe = ReverbDesign.appearance.applicationGrid
        // Stable, distinct colors while retaining the real NativeChip stateful selection drawable.
        val palette = ReverbDesign.theme(context, true).palette.copy(
            primary = Color.BLUE, onSurface = Color.BLACK,
            surfaceContainerHigh = Color.WHITE, surfacePreset = null)
        for (width in listOf(328, 388)) for (rtl in listOf(false, true)) {
            val grid = NativeJoinedApplicationGridLayout(context, palette, recipe)
            grid.columnCount = recipe.columns(context, width)
            assertEquals(5, grid.columnCount)
            grid.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            // Above-left, above-right and below-left of the inner turn are actual selected controls.
            val children = List(7) { index ->
                NativeChip(context, palette.settingsControls, recipe.choiceStyle.copy(animateSelection = false)).apply {
                    bind("", index in setOf(1, 2, 6))
                    background.jumpToCurrentState()
                    grid.addView(this)
                }
            }
            val row = recipe.heightPx(context)
            grid.measure(exact(width), exact(row * 2)); grid.layout(0, 0, width, row * 2)
            val bitmap = Bitmap.createBitmap(width, row * 2, Bitmap.Config.ARGB_8888)
            try {
                grid.draw(Canvas(bitmap))
                fun pixel(x: Int, y: Int) = bitmap.getPixel(if (rtl) width - 1 - x else x, y)
                val step = width * 2 / grid.columnCount
                assertEquals("No old curved fill extends into the missing cell", 0,
                    Color.alpha(pixel(step + 2, row + 2)))
                assertEquals("Missing cells remain transparent", 0,
                    Color.alpha(pixel(width * 4 / 5, row + row / 2)))
                for ((dx, dy) in listOf(-4 to -4, 4 to -4, -4 to 4)) {
                    assertEquals("Selected child fill reaches the inner turn ($dx, $dy)",
                        Color.BLUE, pixel(step + dx, row + dy))
                }
                assertEquals("Unselected cells keep the shared background", Color.WHITE, pixel(20, row / 2))
                assertEquals("Outer corner is clipped", 0, Color.alpha(pixel(0, 0)))
                assertEquals("Rounded outside corner retains its interior", Color.BLUE,
                    pixel(step - recipe.cornerRadiusDp.roundToInt(), row * 2 - recipe.cornerRadiusDp.roundToInt()))
                assertTrue(kotlin.math.abs(width / 5 - grid.getChildAt(6).width) <= 1)
                for ((x, y) in listOf(0f to 0f, (step + 2f) to (row + 2f))) {
                    val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                        if (rtl) width - 1f - x else x, y, 0)
                    try { assertFalse("Transparent corner cannot activate a child", grid.dispatchTouchEvent(down)) }
                    finally { down.recycle() }
                }
            } finally {
                bitmap.recycle()
                children.forEach { it.close() }
            }
        }
    }

    @Test fun recipeKeepsTwoLineNamesAndTouchTargetsAtLargeFontInBothDirections() = main {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val recipe = ReverbDesign.appearance.applicationGrid
        for (font in listOf(1f, 2f)) for (width in listOf(328, 388)) for (rtl in listOf(false, true)) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).also {
                it.fontScale = font
                it.densityDpi = 160
            })
            val palette = ReverbDesign.theme(context, false).palette
            val grid = NativeJoinedApplicationGridLayout(context, palette, recipe)
            grid.columnCount = recipe.columns(context, width)
            assertEquals(if (font == 1f) 5 else if (width == 328) 2 else 3, grid.columnCount)
            grid.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            val chip = NativeChip(context, palette.settingsControls, recipe.choiceStyle).apply {
                gravity = Gravity.CENTER
                minLines = recipe.labelMaxLines
                maxLines = recipe.labelMaxLines
                bind("App\nName", true)
                setPadding(recipe.horizontalInsetDp.roundToInt(), recipe.labelTopInsetDp.roundToInt(),
                    recipe.horizontalInsetDp.roundToInt(), recipe.contentInsetDp.roundToInt())
            }
            grid.addView(chip)
            try {
                grid.measure(exact(width), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                grid.layout(0, 0, width, grid.measuredHeight)
                assertEquals(if (font == 1f) 76 else 152, chip.height)
                assertTrue(chip.width >= 48 && chip.height >= 48)
                assertEquals(2, chip.layout.lineCount)
                assertTrue("Both name lines fit below the reserved icon area",
                    chip.layout.height <= chip.height - chip.paddingTop - chip.paddingBottom)
                assertEquals(if (rtl) width else chip.width, chip.right)
            } finally { chip.close() }
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
