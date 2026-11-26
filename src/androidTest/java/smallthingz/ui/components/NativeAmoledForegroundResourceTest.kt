package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.graphics.NOISE_TILE_SIZE
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.graphics.generateNoisePixels

@RunWith(AndroidJUnit4::class)
class NativeAmoledForegroundResourceTest {
    @Test fun packagedNoisePreservesEveryOriginalPixel() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val options = BitmapFactory.Options().apply { inScaled = false; inPremultiplied = false }
        val bitmap = checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.native_foreground_noise, options))
        try {
            assertEquals(NOISE_TILE_SIZE, bitmap.width)
            assertEquals(NOISE_TILE_SIZE, bitmap.height)
            val pixels = IntArray(NOISE_TILE_SIZE * NOISE_TILE_SIZE)
            bitmap.getPixels(pixels, 0, NOISE_TILE_SIZE, 0, 0, NOISE_TILE_SIZE, NOISE_TILE_SIZE)
            assertArrayEquals(generateNoisePixels(), pixels)
        } finally { bitmap.recycle() }
    }

    @Test fun platformLayersMatchNativeForegroundAcrossDensityAndNightMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var observer: NoiseDrawable
        onMain {
            observer = NoiseDrawable().apply {
                callback = object : Drawable.Callback {
                    override fun invalidateDrawable(who: Drawable) {
                        if (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null) ready.countDown()
                    }
                    override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = Unit
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
                }
            }
            if (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null) ready.countDown()
        }
        try {
            assertTrue("Shared noise shader did not become ready", ready.await(5, TimeUnit.SECONDS))
            onMain {
                val base = instrumentation.targetContext
                val palette = NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 28) {
                    error("Fallback AMOLED must not read platform colors")
                }.pageControls
                val expected = pixels(NativeForeground.foregroundBackground(palette))
                assertTrue("Fixture must exercise visible grain", expected.any { it != palette.raised })
                for (density in listOf(160, 320)) {
                    for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                            densityDpi = density
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                        })
                        assertEquals(palette.raised, context.getColor(R.color.native_amoled_foreground_fill))
                        val layers = checkNotNull(context.getDrawable(R.drawable.native_amoled_foreground_background))
                        assertTrue(layers is LayerDrawable)
                        layers as LayerDrawable
                        assertEquals(2, layers.numberOfLayers)
                        assertTrue(layers.getDrawable(0) is GradientDrawable)
                        assertTrue(layers.getDrawable(1) is BitmapDrawable)
                        // XML inflation, not an app drawable factory, owns both layers. The width
                        // crosses a complete tile so scaling or a wrong period cannot pass unnoticed.
                        val actual = pixels(layers)
                        assertArrayEquals("density=$density night=$night; fill/alpha/phase/filter parity", expected, actual)
                        assertEquals(actual[0], actual[NOISE_TILE_SIZE])
                    }
                }
            }
        } finally { onMain { observer.callback = null } }
    }

    private fun pixels(drawable: Drawable): IntArray {
        val width = NOISE_TILE_SIZE + 7
        val height = 32
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        } finally { bitmap.recycle() }
    }

    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { action() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
