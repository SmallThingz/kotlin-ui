// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioBlobRenderIntegrationTest {
    @Test fun fallbackHasAStationarySilentMaterial() =
        verifyTemporalResponse(AudioReactiveBlobView.FallbackRenderer(), hardware = false)

    @Test @SdkSuppress(minSdkVersion = 33)
    fun agslHasAStationarySilentMaterial() =
        verifyTemporalResponse(AudioReactiveBlobView.ShaderRenderer(), hardware = true)

    private fun verifyTemporalResponse(renderer: AudioReactiveBlobView.Renderer, hardware: Boolean) {
        val size = 160
        val bands = FloatArray(8) { 1f }
        renderer.resize(size, size)
        renderer.setPalette(Color.CYAN, Color.MAGENTA, Color.GRAY)
        fun pixels(activity: Float, phase: Float, silence: Float = 0f): IntArray {
            fun draw(canvas: Canvas) {
                renderer.draw(canvas, size, size, phase, activity, audioBlobMotionEnergy(activity, silence),
                    bands, 1f, 1f, silence)
            }
            val bitmap = if (hardware) {
                val picture = Picture()
                draw(picture.beginRecording(size, size))
                picture.endRecording()
                val gpu = Bitmap.createBitmap(picture, size, size, Bitmap.Config.HARDWARE)
                try { requireNotNull(gpu.copy(Bitmap.Config.ARGB_8888, false)) } finally { gpu.recycle() }
            } else {
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
            }
            return try {
                IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
            } finally { bitmap.recycle() }
        }
        val center = size * (size / 2) + size / 2
        for (silence in listOf(0f, 1f)) {
            val rest = pixels(0f, 0f, silence)
            assertTrue(Color.alpha(rest[center]) == 255)
            assertArrayEquals(rest, pixels(0f, 2f, silence))
        }
        assertTrue(pixels(0f, 0f)[center] != pixels(0f, 0f, 1f)[center])
        fun alphaDifference(activity: Float): Int {
            val a = pixels(activity, 0f)
            val b = pixels(activity, 2f)
            return a.indices.sumOf { kotlin.math.abs(Color.alpha(a[it]) - Color.alpha(b[it])) }
        }
        val audible = alphaDifference(.5f)
        assertTrue(audible > 0)
        assertTrue(alphaDifference(.001f) < audible / 100)
    }
}
