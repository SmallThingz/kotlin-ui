package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ControlStyleGrainDrawableIntegrationTest {
    @Test fun retainedGrainHonorsDrawableAlphaFilterAndRoundedClipping() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var observer: NoiseDrawable
        val readinessCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                if (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null) ready.countDown()
            }
            override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        instrumentation.runOnMainSync {
            observer = NoiseDrawable().apply { callback = readinessCallback }
            if (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null) ready.countDown()
        }
        try {
            // Wait on the instrumentation thread so the main-thread readiness callback can run.
            assertTrue("Shared noise shader did not become ready", ready.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val context = instrumentation.targetContext
                val palette = NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 30) { error("Unused") }.pageControls
                fun overlay(fill: Int): Drawable {
                    val background = ControlStyle(context, palette.copy(accent = fill)).background(filled = true)
                    background.state = intArrayOf(android.R.attr.state_enabled)
                    val states = background.getDrawable(0) as StateListDrawable
                    return (states.current as LayerDrawable).getDrawable(1).apply { setBounds(8, 8, 88, 88) }
                }
                val grain = overlay(Color.BLACK)
                val full = pixels(grain)
                assertTrue("Test must exercise visible grain", full.any { Color.alpha(it) > 0 })
                grain.alpha = 128
                val half = pixels(grain)
                assertTrue(half.indices.all { Color.alpha(half[it]) <= Color.alpha(full[it]) })
                assertTrue(half.indices.any { Color.alpha(half[it]) in 1 until Color.alpha(full[it]) })
                grain.alpha = 0
                assertTrue("Drawable alpha zero must hide grain", pixels(grain).all { it == 0 })
                grain.alpha = 255
                assertArrayEquals(full, pixels(grain))

                val filter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
                grain.colorFilter = filter
                val filtered = pixels(grain)
                val expectedFiltered = platformFilteredPixels(full, filter)
                assertTrue("Platform reference must visibly tint grain", expectedFiltered.any {
                    Color.alpha(it) > 0 && Color.red(it) > Color.green(it) && Color.red(it) > Color.blue(it)
                })
                assertFalse("The filter must change the unfiltered grain", full.contentEquals(filtered))
                val mismatch = filtered.indices.firstOrNull { filtered[it] != expectedFiltered[it] }
                assertTrue(if (mismatch == null) "Filtered pixels match the platform reference" else
                    "Filtered mismatch at ${mismatch % 96},${mismatch / 96}: " +
                        "actual=${Integer.toHexString(filtered[mismatch])}, " +
                        "reference=${Integer.toHexString(expectedFiltered[mismatch])}, " +
                        "unfiltered=${Integer.toHexString(full[mismatch])}", mismatch == null)
                grain.clearColorFilter()
                assertArrayEquals("Clearing filter restores the original shared grain", full, pixels(grain))

                val translucent = pixels(overlay(0x80000000.toInt()))
                assertTrue(translucent.indices.all { Color.alpha(translucent[it]) <= Color.alpha(full[it]) })
                assertTrue(translucent.indices.any { Color.alpha(translucent[it]) < Color.alpha(full[it]) })
            }
        } finally { instrumentation.runOnMainSync {
            // Drawable callbacks are weak; retain this callback through the readiness wait.
            if (observer.callback === readinessCallback) observer.callback = null
        } }
    }

    /** Apply the same platform filter to captured unfiltered pixels, independently of the wrapper. */
    private fun platformFilteredPixels(sourcePixels: IntArray, filter: android.graphics.ColorFilter): IntArray {
        val source = Bitmap.createBitmap(sourcePixels, 96, 96, Bitmap.Config.ARGB_8888)
        val result = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or
                android.graphics.Paint.DITHER_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = filter
            }
            Canvas(result).drawBitmap(source, 0f, 0f, paint)
            return IntArray(96 * 96).also { result.getPixels(it, 0, 96, 0, 0, 96, 96) }
        } finally {
            source.recycle()
            result.recycle()
        }
    }

    private fun pixels(drawable: Drawable): IntArray {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            drawable.draw(Canvas(bitmap))
            val result = IntArray(96 * 96)
            bitmap.getPixels(result, 0, 96, 0, 0, 96, 96)
            for ((x, y) in listOf(0 to 0, 8 to 8, 87 to 8, 8 to 87, 87 to 87, 95 to 95)) {
                assertEquals("Grain must remain inside the rounded bounds at $x,$y", 0, result[y * 96 + x])
            }
            return result
        } finally { bitmap.recycle() }
    }
}
