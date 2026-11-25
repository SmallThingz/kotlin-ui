package smallthingz.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.alldoc.AllDocAppearance
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.runtime.MonotonicFrameClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bitmap-only fixtures: no Activity, window attachment, clock advancement or global settings. */
@RunWith(AndroidJUnit4::class)
class NativeProgressGrainIntegrationTest {
    private val preset = NativeGrainPreset(backgroundGrain = 0f, foregroundGrain = .5f)

    @Test fun linearGrainStaysInRoundAndButtStrokesAndRestoresFlatPixels() = withShader { context ->
        for ((width, height) in listOf(160 to 16, 12 to 32)) {
            for (rtl in listOf(false, true)) {
                val view = LineProbe(context).apply {
                    layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                size(view, width, height)
                assertEquals("Fixture must resolve the requested direction",
                    if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, view.layoutDirection)
                val flat = pixels(view)
                assertTrue("Fixture must paint a stroke", flat.any { Color.alpha(it) != 0 })
                NativeForeground.bindProgress(view, palette(preset))
                val textured = pixels(view)
                assertGrainVisible(flat, textured)
                assertNoEscapedCoverage(flat, textured, width, height)
                assertArrayEquals("Repeated drawing must not accumulate grain", textured, pixels(view))
                view.bindSurfacePreset(preset.copy(foregroundGrain = 1f))
                assertTrue("Intensity-only rebind must repaint", !textured.contentEquals(pixels(view)))
                AllDocAppearance.bindProgress(view, palette(null))
                assertArrayEquals("Disabling grain restores exact original pixels", flat, pixels(view))
                view.bindSurfacePreset(preset)
                assertArrayEquals("Re-enabling grain restores exact texture", textured, pixels(view))
            }
        }
    }

    @Test fun linearTransparentSegmentsAndZeroLengthProgressDoNotPaintGrain() = withShader { context ->
        val view = LineProbe(context)
        size(view, 160, 16)
        view.bindSurfacePreset(preset)
        view.ink = Color.TRANSPARENT
        assertTrue("Invisible track and indicator must remain invisible", pixels(view).all { it == 0 })
        view.ink = Color.BLACK
        view.start = .3f
        view.end = .3f
        assertTrue("Empty progress must not acquire a grain cap", pixels(view).all { it == 0 })
        view.start = .2f
        view.end = .65f
        val opaque = pixels(view)
        view.ink = 0x80000000.toInt()
        val translucent = pixels(view)
        assertTrue("Translucent paint must still carry grain", translucent.any {
            Color.alpha(it) > 128 && Color.alpha(it) < 255
        })
        assertTrue("Paint alpha must bound both stroke and grain", translucent.indices.all {
            Color.alpha(translucent[it]) <= Color.alpha(opaque[it])
        })
        view.ink = Color.TRANSPARENT
        assertTrue("Alpha updates must not retain old grain", pixels(view).all { it == 0 })
    }

    @Test fun circularGrainPreservesRotatedArcHoleCapsAndNonSquareClipping() = withShader { context ->
        val scope = CoroutineScope(SupervisorJob())
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
                error("Unattached bitmap fixture must never request a frame")
        }
        try {
            // 12 physical pixels independent of the device's density.
            val view = NativeCircularProgress(context, Color.BLACK, clock, scope, { 0f },
                strokeWidthDp = 12f / context.resources.displayMetrics.density)
            floatField(view, "rotation", 73f)
            floatField(view, "progress", .63f)
            for ((width, height) in listOf(96 to 96, 96 to 64)) {
                size(view, width, height)
                view.bindSurfacePreset(null)
                val flat = pixels(view)
                NativeForeground.bindProgress(view, palette(preset))
                val textured = pixels(view)
                assertGrainVisible(flat, textured)
                assertNoEscapedCoverage(flat, textured, width, height)
                assertArrayEquals("Arc drawing must not mutate its pose or accumulate texture", textured, pixels(view))
                AllDocAppearance.bindProgress(view, palette(null))
                assertArrayEquals("Disabling circular grain restores flat arc", flat, pixels(view))
            }
            size(view, 96, 96)
            view.bindSurfacePreset(preset)
            view.setColorImmediately(Color.TRANSPARENT)
            assertTrue("Transparent arc cannot leave a grain ring", pixels(view).all { it == 0 })
            view.setColorImmediately(0x80000000.toInt())
            val translucent = pixels(view)
            view.setColorImmediately(Color.BLACK)
            val opaque = pixels(view)
            assertTrue(translucent.indices.all { Color.alpha(translucent[it]) <= Color.alpha(opaque[it]) })
            assertEquals("Circular center stays hollow", 0, opaque[48 * 96 + 48])
        } finally { scope.cancel() }
    }

    @Test fun circularHairlinePreservesBaseStrokeAndRestoresGrainAfterWidthChanges() = withShader { context ->
        val scope = CoroutineScope(SupervisorJob())
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
                error("Unattached bitmap fixture must never request a frame")
        }
        try {
            val hairline = NativeCircularProgress(context, Color.BLACK, clock, scope, { 0f },
                strokeWidthDp = 0f).apply {
                floatField(this, "rotation", 73f)
                // Filling this long arc would cover the center instead of leaving it hollow.
                floatField(this, "progress", .63f)
                size(this, 96, 96)
            }
            val flatHairline = pixels(hairline)
            assertTrue("Zero width must retain the visible base hairline", flatHairline.any { Color.alpha(it) != 0 })
            hairline.bindSurfacePreset(preset)
            val enabledHairline = pixels(hairline)
            assertArrayEquals("Constructor zero width must skip grain only", flatHairline, enabledHairline)
            assertEquals("Long hairline arc center stays empty", 0, enabledHairline[48 * 96 + 48])

            val positiveWidthDp = 12f / context.resources.displayMetrics.density
            val view = NativeCircularProgress(context, Color.BLACK, clock, scope, { 0f },
                strokeWidthDp = positiveWidthDp).apply {
                floatField(this, "rotation", 73f)
                floatField(this, "progress", .63f)
                size(this, 96, 96)
            }
            val flat = pixels(view)
            view.bindSurfacePreset(preset)
            val textured = pixels(view)
            assertGrainVisible(flat, textured)
            assertEquals("Positive-width long arc center stays empty", 0, textured[48 * 96 + 48])

            // Keep the same enabled grain layer throughout positive -> zero -> positive.
            view.setStrokeWidthDp(0f)
            val changedToHairline = pixels(view)
            assertArrayEquals("Changing to zero must preserve exactly the base hairline", flatHairline, changedToHairline)
            assertEquals("Changing to zero must not fill the long arc center", 0, changedToHairline[48 * 96 + 48])
            view.setStrokeWidthDp(positiveWidthDp)
            assertArrayEquals("Restoring positive width must restore the retained grain", textured, pixels(view))
        } finally { scope.cancel() }
    }

    @Test fun determinateStopDotReceivesGrainEvenWithTransparentTrack() = withShader { context ->
        // At zero progress the stop dot is the only opaque foreground on the right.
        // This detects the separate drawCircle path, rather than merely checking the line helper.
        for (rtl in listOf(false, true)) {
            val view = NativeDeterminateLinearProgress(context, Color.BLACK, Color.TRANSPARENT, 4f).apply {
                layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                bind(0f, Color.BLACK, Color.TRANSPARENT, 4f, operationId = 1L, terminal = false)
            }
            size(view, 240, 16)
            assertEquals("Fixture must resolve the requested direction",
                if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, view.layoutDirection)
            val flat = pixels(view)
            view.bindSurfacePreset(preset)
            val textured = pixels(view)
            val centerX = if (rtl) 8 else 232
            val interior = (4..11).flatMap { y -> (centerX - 4..centerX + 3).map { x -> y * 240 + x } }
            assertTrue("Stop-dot fixture must have an opaque interior", interior.all { flat[it] == Color.BLACK })
            assertTrue("Determinate stop dot must be textured", interior.any { flat[it] != textured[it] })
            assertNoEscapedCoverage(flat, textured, 240, 16)
            view.bindSurfacePreset(null)
            assertArrayEquals(flat, pixels(view))
        }
    }

    private fun palette(preset: NativeSurfacePreset?) =
        NativeColorScheme.fallback(false).pageControls.copy(surfacePreset = preset)

    private class LineProbe(context: Context) : NativeLinearProgressView(context,
        Color.BLACK, Color.TRANSPARENT, 1f) {
        override val accessibilityProgress: Float? = null
        var start = .2f
        var end = .65f
        var ink = Color.BLACK
        override fun onDraw(canvas: Canvas) {
            // Exercise a transparent segment after the visible segment to catch stale alpha/path state.
            drawProgressLine(canvas, start, end, ink)
            drawProgressLine(canvas, .8f, 1f, Color.TRANSPARENT)
        }
    }

    private fun assertGrainVisible(flat: IntArray, textured: IntArray) {
        assertTrue("Grain must change opaque stroke interior, not just antialiasing", flat.indices.any {
            Color.alpha(flat[it]) == 255 && flat[it] != textured[it]
        })
    }

    private fun assertNoEscapedCoverage(flat: IntArray, textured: IntArray, width: Int, height: Int) {
        // drawArc/drawLine and filled stroked paths can differ by a boundary antialias sample.
        // No grain may escape even that one-pixel fringe, including the circular hole.
        for (y in 0 until height) for (x in 0 until width) {
            var nearStroke = false
            for (yy in maxOf(0, y - 1)..minOf(height - 1, y + 1))
                for (xx in maxOf(0, x - 1)..minOf(width - 1, x + 1))
                    if (Color.alpha(flat[yy * width + xx]) != 0) nearStroke = true
            if (!nearStroke) assertEquals("Grain escaped stroke at $x,$y", 0, textured[y * width + x])
        }
    }

    private fun size(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            return IntArray(view.width * view.height).also {
                bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height)
            }
        } finally { bitmap.recycle() }
    }

    private fun floatField(view: NativeCircularProgress, name: String, value: Float) {
        NativeCircularProgress::class.java.getDeclaredField(name).apply { isAccessible = true }.setFloat(view, value)
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
            instrumentation.runOnMainSync { block(instrumentation.targetContext) }
        } finally { instrumentation.runOnMainSync { observer.callback = null } }
    }
}
