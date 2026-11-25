package smallthingz.ui.alldoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeGrainPreset
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture

@RunWith(AndroidJUnit4::class)
class AllDocSelectionHandlePainterTest {
    @Test fun grainPreservesEveryArtworkAlphaAndTransparentCorner() = withShader { context ->
        val owner = View(context)
        val palette = NativeColorScheme.fallback(false).pageControls.copy(
            surfacePreset = NativeGrainPreset(backgroundGrain = 0f, foregroundGrain = 1f))
        val artwork = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xff808080.toInt())
            setBounds(8, 8, 56, 56)
        }
        val painter = AllDocSelectionHandlePainter()
        val flat = pixels { artwork.draw(it) }
        painter.bind(palette)
        val noisy = pixels { painter.drawArtwork(it, owner, artwork) }
        assertTrue("Shared grain must reach opaque artwork", flat.indices.any { flat[it] != noisy[it] })
        for (index in flat.indices) {
            assertEquals("Artwork alpha changed at $index", Color.alpha(flat[index]), Color.alpha(noisy[index]))
            if (Color.alpha(flat[index]) == 0) assertEquals(0, noisy[index])
        }
        painter.bind(palette.copy(surfacePreset = null))
        assertArrayEquals("Disabling the app preset restores the exact artwork", flat,
            pixels { painter.drawArtwork(it, owner, artwork) })
    }

    @Test fun worksheetRingUsesAppPaletteWithoutDocumentInputs() = withShader { context ->
        val owner = View(context)
        val painter = AllDocSelectionHandlePainter()
        val flatPalette = NativeColorScheme.fallback(false).pageControls.copy(accent = 0xff3768b0.toInt(), surfacePreset = null)
        painter.bind(flatPalette)
        val flat = pixels { painter.drawRing(it, owner, 32f, 32f, 20f, 8f) }
        assertEquals(flatPalette.accent, flat[32 * 64 + 46])
        painter.bind(flatPalette.copy(surfacePreset = NativeGrainPreset(backgroundGrain = 0f, foregroundGrain = 1f)))
        val noisy = pixels { painter.drawRing(it, owner, 32f, 32f, 20f, 8f) }
        assertTrue(flat.indices.any { flat[it] != noisy[it] })
        for (index in flat.indices) if (Color.alpha(flat[index]) == 0) assertEquals(0, noisy[index])
        painter.bind(flatPalette)
        assertArrayEquals(flat, pixels { painter.drawRing(it, owner, 32f, 32f, 20f, 8f) })
    }

    private fun pixels(draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            draw(Canvas(bitmap))
            IntArray(64 * 64).also { bitmap.getPixels(it, 0, 64, 0, 0, 64, 64) }
        } finally { bitmap.recycle() }
    }

    private fun withShader(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var observer: NoiseDrawable
        val callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                if (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null) ready.countDown()
            }
            override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        instrumentation.runOnMainSync {
            observer = NoiseDrawable().apply { this.callback = callback }
            if (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null) ready.countDown()
        }
        try {
            assertTrue("Shared shader did not become ready", ready.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { block(instrumentation.targetContext) }
        } finally { instrumentation.runOnMainSync { observer.callback = null } }
    }
}
