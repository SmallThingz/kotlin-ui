package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.DisplayMetrics
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

    @Test
    fun legacySingleSliderRetainsEnabledAndDisabledPixels() = onMain { owner ->
        val mdpi = owner.context.createConfigurationContext(Configuration().apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        })
        val slider = NativeSlider(mdpi, palette, NativeSliderStyle(steps = 3)).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            max = 100
            progress = 50
            measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY))
            layout(0, 0, 200, 64)
        }
        // Pre-extraction NativeSlider at 74ac40d, at 1 px/dp with the default geometry.
        // The track spans x=2..198, with radius 8 and center y=32. Its midpoint thumb
        // has an 8px clearance from center to track; interior ticks are at 55 and 145.
        val bitmap = Bitmap.createBitmap(200, 64, Bitmap.Config.ARGB_8888)
        try {
            fun pixel(part: String, x: Int, y: Int, expected: Int) {
                assertEquals(part, expected, bitmap.getPixel(x, y))
            }

            slider.draw(Canvas(bitmap))
            pixel("enabled active track", 30, 32, Color.BLUE)
            pixel("enabled inactive track: green under 18% black", 165, 32, 0xff00d100.toInt())
            pixel("active tick", 55, 32, Color.GREEN)
            pixel("inactive tick", 145, 32, Color.BLUE)
            pixel("right stop indicator", 190, 32, Color.BLUE)
            pixel("thumb above the track", 100, 16, Color.BLUE)
            pixel("thumb clearance", 95, 32, Color.TRANSPARENT)
            pixel("rounded outer corner", 3, 25, Color.TRANSPARENT)

            slider.isEnabled = false
            bitmap.eraseColor(Color.TRANSPARENT)
            slider.draw(Canvas(bitmap))
            pixel("disabled active track: 38% black", 30, 32, 0x61000000)
            pixel("disabled inactive track: 12% black", 165, 32, 0x1f000000)
            pixel("disabled thumb: 38% black over white", 100, 16, 0xff9e9e9e.toInt())
            pixel("disabled thumb clearance", 95, 32, Color.TRANSPARENT)
            pixel("disabled rounded outer corner", 3, 25, Color.TRANSPARENT)
        } finally {
            bitmap.recycle()
        }
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
