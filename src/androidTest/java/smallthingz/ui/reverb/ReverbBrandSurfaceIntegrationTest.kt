package smallthingz.ui.reverb

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDefaultColorScheme
import smallthingz.ui.components.NativeGrainPreset
import smallthingz.ui.components.NativeGroupSurfaceRecipes
import smallthingz.ui.components.NativeLayeredMarkPalette

@RunWith(AndroidJUnit4::class)
class ReverbBrandSurfaceIntegrationTest {
    @Test fun defaultMarkAndLauncherPlatePaintTheSameSharedSurfaceInDayAndNight() = onMain { context ->
        for (dark in listOf(false, true)) {
            val themed = context.withNightMode(dark)
            val palette = colors(themed, dark)
            val fill = NativeGroupSurfaceRecipes.Default.recordingInfoRow.fillColor(palette)
            assertEquals(NativeLayeredMarkPalette(fill, palette.primary, palette.ink), readReverbBrandPalette(themed))
            val mark = renderMark(ReverbBrandMarkView(themed))
            val launcher = render {
                checkNotNull(themed.getDrawable(R.drawable.reverb_brand_plate)).apply {
                    setBounds(0, 0, SIZE, SIZE)
                }.draw(it)
            }
            // A region inside the circular clip and above all three strokes.
            val sample = platePixels(launcher)
            assertTrue(sample.any { it != fill })
            assertArrayEquals(sample, platePixels(mark))
        }
    }

    @Test fun explicitWindowPaintOverridesOppositeResourceConfigurationInBothDirections() = onMain { context ->
        for (dark in listOf(false, true)) {
            val themed = context.withNightMode(dark)
            val palette = colors(themed, dark)
            val defaultMark = renderMark(ReverbBrandMarkView(themed))
            val boundMark = ReverbBrandMarkView(context.withNightMode(!dark)).apply {
                bindReverbBrandSurface(palette)
                refreshResourcePalette()
            }
            assertArrayEquals(defaultMark, renderMark(boundMark))
        }
    }

    @Test fun aGrainOnlyRebindUpdatesThePlateAndPreservesTheGlyph() = onMain { context ->
        val themed = context.withNightMode(true)
        val palette = colors(themed, dark = true)
        val mark = ReverbBrandMarkView(themed)
        val textured = renderMark(mark)
        mark.bindReverbBrandSurface(palette.copy(surfacePreset = NativeGrainPreset(0f, 0f)))
        val flat = renderMark(mark)
        assertTrue(platePixels(flat).all { it == palette.surfaceContainerHighest })
        assertTrue(platePixels(textured).any { it != palette.surfaceContainerHighest })
        // This pixel is in the interior of the opaque main stroke.
        val mainStroke = 62 * SIZE + 43
        assertEquals(palette.ink, textured[mainStroke])
        assertEquals(textured[mainStroke], flat[mainStroke])
        mark.bindReverbBrandSurface(palette)
        assertArrayEquals(textured, renderMark(mark))
    }

    private fun colors(context: Context, dark: Boolean): NativeColorScheme =
        NativeDefaultColorScheme.resolve(dark, Build.VERSION.SDK_INT) {
            context.resources.getColor(it, context.theme)
        }

    private fun Context.withNightMode(dark: Boolean): Context = createConfigurationContext(
        Configuration(resources.configuration).apply {
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

    private fun render(draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return try {
            draw(Canvas(bitmap))
            IntArray(SIZE * SIZE).also { bitmap.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun platePixels(pixels: IntArray): IntArray = IntArray(32 * 16) { index ->
        pixels[(16 + index / 32) * SIZE + 48 + index % 32]
    }

    private fun onMain(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching { block(instrumentation.targetContext) } }
        checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val SIZE = 128
    }
}
