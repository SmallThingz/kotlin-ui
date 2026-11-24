package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.roundToInt

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
                val radius = (12 * context.resources.displayMetrics.density).roundToInt().toFloat()
                val intensity = NativeDarkSurfacePreset.Amoled.foregroundGrain
                val grain = overlay(Color.BLACK)
                val full = pixels(grain)
                assertArrayEquals("Unfiltered shader output matches the independent platform reference",
                    platformShaderPixels(radius, intensity), full)
                assertTrue("Test must exercise visible grain", full.any { Color.alpha(it) > 0 })
                grain.alpha = 128
                val half = pixels(grain)
                assertArrayEquals("Half-alpha shader output matches the platform reference",
                    platformShaderPixels(radius, intensity, drawableAlpha = 128), half)
                assertTrue(half.indices.all { Color.alpha(half[it]) <= Color.alpha(full[it]) })
                assertTrue(half.indices.any { Color.alpha(half[it]) in 1 until Color.alpha(full[it]) })
                grain.alpha = 0
                val hidden = pixels(grain)
                assertTrue("Drawable alpha zero must hide grain", hidden.all { it == 0 })
                assertArrayEquals(platformShaderPixels(radius, intensity, drawableAlpha = 0), hidden)
                grain.alpha = 255
                assertArrayEquals(full, pixels(grain))

                val filter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
                grain.colorFilter = filter
                val filtered = pixels(grain)
                val expectedFiltered = platformShaderPixels(radius, intensity, filter = filter)
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
                assertArrayEquals("Fill alpha scales grain before Drawable-level effects",
                    platformShaderPixels(radius, intensity, fillAlpha = 128), translucent)
                assertTrue(translucent.indices.all { Color.alpha(translucent[it]) <= Color.alpha(full[it]) })
                assertTrue(translucent.indices.any { Color.alpha(translucent[it]) < Color.alpha(full[it]) })
            }
        } finally { instrumentation.runOnMainSync {
            // Drawable callbacks are weak; retain this callback through the readiness wait.
            if (observer.callback === readinessCallback) observer.callback = null
        } }
    }

    /**
     * Independent platform rendering: no production Drawable or ControlStyle calls. Preserve
     * clip -> translation -> optional filtered layer -> shader order. Filtering a previously
     * flattened/clipped bitmap changes coverage and low-alpha quantization at rounded edges.
     */
    private fun platformShaderPixels(
        radius: Float,
        intensity: Float,
        fillAlpha: Int = 255,
        drawableAlpha: Int = 255,
        filter: ColorFilter? = null,
    ): IntArray {
        val result = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            if (drawableAlpha != 0) {
                val flags = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
                val noise = Paint(flags).apply {
                    color = Color.BLACK
                    shader = checkNotNull(NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND))
                    alpha = ((intensity * 255f).roundToInt() * fillAlpha + 127) / 255
                }
                val clip = Path().apply { addRoundRect(8f, 8f, 88f, 88f, radius, radius, Path.Direction.CW) }
                val canvas = Canvas(result)
                val checkpoint = canvas.save()
                try {
                    canvas.clipPath(clip)
                    canvas.translate(8f, 8f)
                    if (drawableAlpha != 255 || filter != null) {
                        val composite = Paint(flags).apply {
                            alpha = drawableAlpha
                            colorFilter = filter
                        }
                        canvas.saveLayer(0f, 0f, 80f, 80f, composite)
                    }
                    canvas.drawRect(0f, 0f, 80f, 80f, noise)
                } finally { canvas.restoreToCount(checkpoint) }
            }
            return IntArray(96 * 96).also { result.getPixels(it, 0, 96, 0, 0, 96, 96) }
        } finally { result.recycle() }
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
