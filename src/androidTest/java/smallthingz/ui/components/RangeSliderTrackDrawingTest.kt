package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.ControlPalette

@RunWith(AndroidJUnit4::class)
class RangeSliderTrackDrawingTest {
    private val palette = ControlPalette(
        Color.WHITE, Color.WHITE, Color.BLACK, Color.GRAY,
        Color.BLUE, Color.WHITE, Color.GREEN, Color.GRAY,
    )
    private val style = NativeSliderStyle(
        steps = 2,
        trackHeightDp = 12f,
        thumbTrackGapDp = 0f,
        showStopIndicator = false,
        colors = NativeSliderColors(activeTickColor = Color.YELLOW, disabledActiveTrackColor = Color.RED),
    )

    @Test
    fun pinsUseReachableGridInsteadOfStyleStepsAndMirrorInRtl() = onMain { owner ->
        // 3, 7, 11, 15, 19 are reachable. The declared max of 22 is off-grid.
        val track = RangeSliderTrack(owner, style, palette, RangeSliderGrid(3, 22, 4, 0))
        draw(track, lowerX = 20f, upperX = 220f) { bitmap ->
            for (x in listOf(70, 120, 170)) assertEquals(Color.YELLOW, bitmap.getPixel(x, 48))
            assertEquals(Color.BLUE, bitmap.getPixel(87, 48))
        }
        draw(track, lowerX = 220f, upperX = 20f) { bitmap ->
            for (x in listOf(70, 120, 170)) assertEquals(Color.YELLOW, bitmap.getPixel(x, 48))
            assertEquals(Color.BLUE, bitmap.getPixel(153, 48))
        }
    }

    @Test
    fun fullIntGridDrawsSampledPinsAndZeroTickSizeHidesThem() = onMain { owner ->
        val track = RangeSliderTrack(owner, style.copy(steps = 0), palette,
            RangeSliderGrid(Int.MIN_VALUE, Int.MAX_VALUE, 1, 0))
        draw(track) { bitmap ->
            assertTrue((40..200).any { bitmap.getPixel(it, 48) == Color.YELLOW })
        }
        track.applyStyle(style.copy(tickSizeDp = 0f))
        draw(track) { bitmap ->
            assertTrue((40..200).all { bitmap.getPixel(it, 48) == Color.BLUE })
        }
    }

    @Test
    fun publicAppearanceChangesRepaintTheSameRangeAndThumbs() = onMain { owner ->
        val plainStyle = style.copy(tickSizeDp = 0f)
        val slider = NativeRangeSlider(owner.context, palette, 0, 100, 10, 20, 80,
            "Lower", "Upper", plainStyle)
        val lowerThumb = slider.getChildAt(0)
        val upperThumb = slider.getChildAt(1)

        fun checkDrawing(expectedColor: Int) {
            val width = 400
            val height = 96
            slider.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            slider.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                slider.draw(Canvas(bitmap))
                assertEquals(expectedColor, bitmap.getPixel(width / 2, height / 2))
            } finally {
                bitmap.recycle()
            }
            assertEquals(2, slider.childCount)
            assertSame(lowerThumb, slider.getChildAt(0))
            assertSame(upperThumb, slider.getChildAt(1))
            assertEquals(20, slider.lower)
            assertEquals(80, slider.upper)
        }

        checkDrawing(Color.BLUE)
        slider.applyPalette(palette.copy(accent = Color.MAGENTA))
        checkDrawing(Color.MAGENTA)
        slider.applyStyle(plainStyle.copy(colors = plainStyle.colors.copy(activeTrackColor = Color.CYAN)))
        checkDrawing(Color.CYAN)
        slider.isEnabled = false
        checkDrawing(Color.RED)
        slider.isEnabled = true
        checkDrawing(Color.CYAN)
    }

    private fun onMain(action: (View) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { action(View(instrumentation.targetContext)) }
    }

    private fun draw(
        track: RangeSliderTrack,
        lowerX: Float = 20f,
        upperX: Float = 220f,
        check: (Bitmap) -> Unit,
    ) {
        val bitmap = Bitmap.createBitmap(240, 96, Bitmap.Config.ARGB_8888)
        try {
            track.draw(Canvas(bitmap), 20f, 220f, 48f, lowerX, upperX, false, false)
            check(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
