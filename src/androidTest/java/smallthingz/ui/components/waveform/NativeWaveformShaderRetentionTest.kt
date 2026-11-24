package smallthingz.ui.components.waveform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Shader
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android shaders and software pixels; does not claim hardware-frame timing or RAM totals. */
@RunWith(AndroidJUnit4::class)
class NativeWaveformShaderRetentionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val initial = WaveformStyle(0xff336699.toInt(), 0xff224466.toInt(),
        0xffcc8844.toInt(), 0xff000000.toInt())

    @Test fun solidInkChangesReuseGradientsAndMatchFreshRendering() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val view = waveform(context, initial)
        val original = shaders(view)
        val nextTrack = initial.copy(trackColor = 0xffaa3322.toInt())
        val nextEmphasis = nextTrack.copy(emphasisColor = 0xff22aa44.toInt())
        for (style in listOf(nextTrack, nextEmphasis, nextEmphasis)) {
            view.setStyle(style) // Detached View deliberately snaps; no global animation-scale mutation.
            val actual = shaders(view)
            original.indices.forEach { assertSame("Solid ink must retain gradient $it", original[it], actual[it]) }
            assertPixelsMatch(view, waveform(context, style))
        }
    }

    @Test fun bodyColorAndSizeStillRebuildGradients() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val view = waveform(context, initial)
        var previous = shaders(view)
        val next = initial.copy(bodyColor = 0xff993366.toInt())
        view.setStyle(next)
        var actual = shaders(view)
        previous.indices.forEach { assertNotSame(previous[it], actual[it]) }
        assertPixelsMatch(view, waveform(context, next))
        previous = actual
        size(view, 320, 112)
        actual = shaders(view)
        previous.indices.forEach { assertNotSame(previous[it], actual[it]) }
        assertPixelsMatch(view, waveform(context, next, 320, 112))
    }

    @Test fun zeroInitialColorsStillInitializeAllGradients() = instrumentation.runOnMainSync {
        val view = waveform(instrumentation.targetContext, WaveformStyle(0, 0, 0, 0))
        assertTrue(shaders(view).all { it != null })
    }

    private fun waveform(context: Context, style: WaveformStyle, width: Int = 256, height: Int = 96) =
        ProgressiveWaveformView(context).apply {
            size(this, width, height)
            setStyle(style)
            setWaveform(ProgressiveWaveformData(FloatArray(16) { .15f }, 16,
                FloatArray(64) { .35f }, 64, true))
            // Partially revealed detail also exercises the independent emphasis stroke.
            setFrame(ProgressiveWaveformFrame(1f, 1f, .5f, 0f, .2f, .8f, false))
        }

    private fun size(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    // Observe retained native objects without adding a production test API.
    private fun shaders(view: ProgressiveWaveformView): List<Shader?> =
        listOf("builtShader", "selectedShader", "selectedLightShader", "unresolvedShader").map { name ->
            ProgressiveWaveformView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view) as Shader?
        }

    private fun assertPixelsMatch(actual: View, reference: View) {
        val a = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(reference.width, reference.height, Bitmap.Config.ARGB_8888)
        try {
            actual.draw(Canvas(a))
            reference.draw(Canvas(b))
            assertTrue("Reused gradients must paint the same pixels as fresh styling", a.sameAs(b))
        } finally { a.recycle(); b.recycle() }
    }
}
