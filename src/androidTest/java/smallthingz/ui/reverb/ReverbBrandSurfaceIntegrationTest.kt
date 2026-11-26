package smallthingz.ui.reverb

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeDefaultColorScheme
import smallthingz.ui.components.NativeLayeredMarkPalette
import smallthingz.ui.geometry.roundedGroupGeometry

@RunWith(AndroidJUnit4::class)
class ReverbBrandSurfaceIntegrationTest {
    @Test fun defaultMarkAndLauncherPlateStayDarkInDayAndNight() = onMain { context ->
        for (density in listOf(160, 320)) for (night in listOf(false, true)) {
            val themed = context.withNightMode(night, density)
            val palette = colors(themed, dark = true)
            val fill = palette.pageControls.raised
            assertEquals(fill, themed.getColor(R.color.native_amoled_foreground_fill))
            assertEquals(NativeLayeredMarkPalette(fill, palette.primary, palette.ink), readReverbBrandPalette(themed))
            assertTrue(ColorUtils.calculateContrast(palette.ink, fill) >= 4.5)
            assertTrue(ColorUtils.calculateContrast(palette.primary, fill) >= 4.5)
            val mark = renderMark(ReverbBrandMarkView(themed).apply { setReverbBrandOutline(true) })
            assertEquals(palette.ink, mark[MAIN_STROKE_PIXEL])
            val launcher = renderDrawable(themed, R.drawable.reverb_brand_plate)
            val foreground = renderDrawable(themed, R.drawable.native_amoled_foreground_background)

            // Interior above the glyphs and inside the first compact grain tile.
            val canonical = platePixels(foreground, width = 16)
            assertTrue(canonical.any { it != fill })
            assertArrayEquals(canonical, platePixels(launcher, width = 16))
            assertArrayEquals(platePixels(launcher), platePixels(mark))
        }
    }

    @Suppress("DEPRECATION")
    @Test fun legacyWindowPaletteCannotRecolorTheFixedDarkMark() = onMain { context ->
        for (night in listOf(false, true)) {
            val mark = ReverbBrandMarkView(context.withNightMode(night))
            val fixed = renderMark(mark)
            mark.bindReverbBrandSurface(colors(context.withNightMode(!night), dark = !night))
            assertArrayEquals(fixed, renderMark(mark))
        }
    }

    @Test fun homeOutlineUsesTheSharedButtonStrokeAndLeavesTheGlyphIntact() = onMain { context ->
        val preset = NativeDarkSurfacePreset.Amoled
        for (night in listOf(false, true)) {
            val themed = context.withNightMode(night)
            val mark = ReverbBrandMarkView(themed)
            val plain = renderMark(mark)
            mark.setReverbBrandOutline(true)
            val outlined = renderMark(mark)

            val density = themed.resources.displayMetrics.density
            val diameter = SIZE.toFloat()
            val stroke = roundedGroupGeometry(
                diameter, diameter, density, diameter / (2f * density), preset.borderWidthDp,
            ).strokeWidth.toInt()
            val lastRimPixel = (stroke - 1) * SIZE + SIZE / 2
            val insideRim = (stroke + 1) * SIZE + SIZE / 2
            assertEquals(Color.TRANSPARENT, outlined[0])
            assertTrue(plain[lastRimPixel] != preset.borderColor)
            assertEquals(preset.borderColor, outlined[lastRimPixel])
            assertEquals(plain[insideRim], outlined[insideRim])
            assertArrayEquals(platePixels(plain), platePixels(outlined))
            assertEquals(plain[MAIN_STROKE_PIXEL], outlined[MAIN_STROKE_PIXEL])

            mark.setReverbBrandOutline(false)
            assertArrayEquals(plain, renderMark(mark))
        }
    }

    private fun colors(context: Context, dark: Boolean): NativeColorScheme =
        NativeDefaultColorScheme.resolve(dark, Build.VERSION.SDK_INT) {
            context.resources.getColor(it, context.theme)
        }

    private fun Context.withNightMode(
        dark: Boolean,
        density: Int = resources.configuration.densityDpi,
    ): Context = createConfigurationContext(
        Configuration(resources.configuration).apply {
            densityDpi = density
            val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        },
    )

    private fun renderMark(mark: ReverbBrandMarkView): IntArray {
        val spec = View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY)
        mark.measure(spec, spec)
        mark.layout(0, 0, SIZE, SIZE)
        return render(mark::draw)
    }

    private fun renderDrawable(context: Context, resource: Int): IntArray = render { canvas ->
        checkNotNull(context.getDrawable(resource)).apply { setBounds(0, 0, SIZE, SIZE) }.draw(canvas)
    }

    private fun render(draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return try {
            draw(Canvas(bitmap))
            IntArray(SIZE * SIZE).also { bitmap.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun platePixels(pixels: IntArray, width: Int = 32): IntArray = IntArray(width * 16) { index ->
        pixels[(16 + index / width) * SIZE + 48 + index % width]
    }

    private fun onMain(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching { block(instrumentation.targetContext) } }
        checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val SIZE = 128
        const val MAIN_STROKE_PIXEL = 62 * SIZE + 43
    }
}
