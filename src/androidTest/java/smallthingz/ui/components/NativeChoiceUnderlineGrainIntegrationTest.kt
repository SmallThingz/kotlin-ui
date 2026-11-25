package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Detached bitmap fixtures: no Activity, device input, global settings or animation clock. */
@RunWith(AndroidJUnit4::class)
class NativeChoiceUnderlineGrainIntegrationTest {
    private val preset = NativeGrainPreset(backgroundGrain = 0f, foregroundGrain = .5f)
    private val style = NativeChoiceStyle(outlined = false, selectedFill = false,
        indicatorHeightDp = 2f, indicatorWidthDp = 24f, indicatorInsetDp = 8f,
        animateSelection = false)

    @Test fun retainedUnderlineTracksOnlyForegroundGrainAndPalette() = withShader { context ->
        val chip = NativeChip(context, palette(false, null), style, NativeChoiceSemantics.TAB)
        var requests = 0
        chip.onActivationRequest = { requests++; false }
        try {
            chip.bind("", true)
            size(chip)
            for (dark in listOf(false, true, false)) {
                val flatPalette = palette(dark, null)
                chip.applyPalette(flatPalette)
                val flat = pixels(chip)
                assertArrayEquals(expected(flatPalette.accent, 0f), flat)
                chip.applyPalette(flatPalette.copy(surfacePreset = preset))
                val textured = pixels(chip)
                assertArrayEquals(expected(flatPalette.accent, .5f), textured)
                assertTrue("Opaque underline interior must carry grain", flat.indices.any {
                    Color.alpha(flat[it]) == 255 && flat[it] != textured[it]
                })
                for (i in flat.indices) if (flat[i] == 0) {
                    assertEquals("No grain outside the underline", 0, textured[i])
                }
                assertArrayEquals("Drawing cannot accumulate texture", textured, pixels(chip))
                chip.applyPalette(flatPalette.copy(surfacePreset = preset.copy(backgroundGrain = 1f)))
                assertArrayEquals("Background grain must not control the underline", textured, pixels(chip))
                chip.applyPalette(flatPalette.copy(surfacePreset = preset.copy(foregroundGrain = 1f)))
                assertArrayEquals(expected(flatPalette.accent, 1f), pixels(chip))
                chip.applyPalette(flatPalette.copy(surfacePreset = preset.copy(foregroundGrain = 0f)))
                assertArrayEquals("Turning off foreground grain restores exact flat paint", flat, pixels(chip))
                chip.applyPalette(flatPalette.copy(surfacePreset = preset))
                assertArrayEquals("Retained layer can be re-enabled", textured, pixels(chip))
                assertTrue(chip.isChecked)
                assertEquals("Paint binding must not request selection", 0, requests)
            }
            assertFalse(chip.performClick())
            assertTrue("Rejected activation preserves selection", chip.isChecked)
            assertEquals(1, requests)
            chip.close()
            assertFalse(chip.performClick())
            assertEquals(1, requests)
        } finally { chip.close() }
    }

    @Test fun enabledAlphaAndTransparentIndicatorsKeepTheirExistingCoverage() = withShader { context ->
        for (alpha in listOf(255, 128, 0)) for (enabled in listOf(true, false)) {
            val ink = Color.argb(alpha, 92, 68, 131)
            val flatPalette = palette(false, null).copy(accent = ink)
            val chip = NativeChip(context, flatPalette, style, NativeChoiceSemantics.TAB)
            try {
                chip.bind("", true, enabled)
                size(chip)
                val coverage = if (enabled) alpha else alpha / 2
                val displayedInk = Color.argb(coverage, 92, 68, 131)
                val flat = pixels(chip)
                assertArrayEquals("Original fill alpha is unchanged", expected(displayedInk, 0f), flat)
                chip.applyPalette(flatPalette.copy(surfacePreset = preset))
                assertArrayEquals("Grain uses the same enabled/disabled paint coverage",
                    expected(displayedInk, .5f), pixels(chip))
                chip.bindChecked(false, enabled)
                assertTrue("Unselected tabs paint no underline or grain", pixels(chip).all { it == 0 })
                chip.bindChecked(true, enabled)
                chip.applyPalette(flatPalette)
                assertArrayEquals(flat, pixels(chip))
            } finally { chip.close() }
        }
    }

    @Test fun emptyIndicatorGeometryDoesNotLeaveGrain() = withShader { context ->
        for (empty in listOf(style.copy(indicatorHeightDp = 0f),
            style.copy(indicatorWidthDp = 0f), style.copy(indicatorInsetDp = 48f))) {
            val chip = NativeChip(context, palette(false, preset), empty, NativeChoiceSemantics.TAB)
            try {
                chip.bind("", true)
                size(chip)
                assertTrue(pixels(chip).all { it == 0 })
            } finally { chip.close() }
        }
    }

    private fun palette(dark: Boolean, grain: NativeSurfacePreset?): ControlPalette =
        NativeColorScheme.fallback(dark).pageControls.copy(surfacePreset = grain)

    private fun size(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 96, 48)
    }

    private fun pixels(chip: NativeChip): IntArray = bitmapPixels { canvas ->
        // Isolate onDraw's underline from the independent background/ripple owner.
        chip.background = null
        chip.draw(canvas)
    }

    private fun expected(ink: Int, intensity: Float): IntArray = bitmapPixels { canvas ->
        canvas.drawRect(36f, 46f, 60f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
        NoiseDrawable().apply { setNoiseIntensity(intensity) }
            .drawGrain(canvas, 36f, 46f, 60f, 48f, coverage = Color.alpha(ink))
    }

    private fun bitmapPixels(draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap))
            return IntArray(96 * 48).also { bitmap.getPixels(it, 0, 96, 0, 0, 96, 48) }
        } finally { bitmap.recycle() }
    }

    private fun withShader(block: (Context) -> Unit) {
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
            assertTrue("Shared shader did not become ready", ready.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val base = instrumentation.targetContext
                block(base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    densityDpi = 160
                    fontScale = 1f
                }))
            }
        } finally { instrumentation.runOnMainSync { observer.callback = null } }
    }
}
