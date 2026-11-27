// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.view.View
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeCaptureControlsStyle
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbIconDrawable
import smallthingz.ui.reverb.home.reverbHomeGeometry
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioBlobReadoutSupportHostTest {
    @Test fun readoutItselfPaintsNoFieldAtAnySampledHeightOrCaptureState() {
        val fixture = Fixture(RuntimeEnvironment.getApplication())
        for (homeHeight in listOf(190, 215, 316, 420)) {
            val blobSize = reverbHomeGeometry(360, homeHeight, 1f, NativeCaptureControlsStyle.Default).blobSize
            // Also exercise rounded centering and a renderer whose width and height differ.
            fixture.layout(blobSize + 9, blobSize)
            for (darkInk in listOf(false, true)) for (transitioning in listOf(false, true)) {
                val background = if (darkInk) Color.rgb(32, 32, 32) else Color.rgb(224, 224, 224)
                assertArrayEquals(
                    fixture.render(background, darkInk, transitioning, support = false),
                    fixture.render(background, darkInk, transitioning, support = true),
                )
            }
        }
    }

    @Test fun rendererCorrectionRecoversAfterEmptyLayout() {
        val fixture = Fixture(RuntimeEnvironment.getApplication(), materialBackdrop = Color.WHITE)
        fixture.layout(200, 200)
        fixture.presentActiveMaterial(0xff757575.toInt())
        val corrected = fixture.appliedCorrection()
        assertTrue("Dark ink needs a lightening filter", corrected.second > 0)
        for (size in listOf(56, 1, 0)) fixture.layout(size, size)
        assertNull("An empty layout has no material correction", fixture.installedCorrection())
        fixture.layout(200, 200)
        assertEquals(corrected, fixture.appliedCorrection())
    }

    @Test fun resizeKeepsCorrectionOnTheRendererWithUnchangedReadoutBounds() {
        val fixture = Fixture(RuntimeEnvironment.getApplication(), materialBackdrop = Color.WHITE)
        fixture.layout(200, 200)
        fixture.presentActiveMaterial(0xff757575.toInt())
        assertEquals(Triple(true, false, true), fixture.lastAppearance)
        val corrected = fixture.appliedCorrection()
        val readoutOnly = fixture.paintPixels(Color.BLACK)

        for (size in listOf(80, 82, 200)) {
            fixture.resizeMaterialAroundReadout(size)
            assertArrayEquals("The unchanged readout must paint only its glyphs", readoutOnly, fixture.paintPixels(Color.BLACK))
            assertEquals(corrected, fixture.appliedCorrection())
        }
    }

    @Test fun compactOpacityChangedWhileRendererIsEmptyKeepsAppearanceDeduplicated() {
        val fixture = Fixture(RuntimeEnvironment.getApplication())
        fixture.layout(56, 56)
        // The midpoint selects white even without compact geometry, while the bright endpoint
        // requires support. The appearance callback can stay deduplicated across both resizes.
        fixture.presentActiveMaterial(Color.BLACK, 0xffe0e0e0.toInt())
        assertEquals(Triple(false, false, true), fixture.lastAppearance)
        val priorCallbacks = fixture.appearanceCallbacks
        fixture.layout(0, 0)
        fixture.layout(0, 0, opacity = .56f)
        fixture.layout(56, 56, opacity = .56f)
        assertEquals("Only paint inputs change across the empty layout", priorCallbacks, fixture.appearanceCallbacks)
    }

    @Test fun dimmedReadoutChecksContrastAfterLayerCompositingAtBothSizes() {
        for (size in listOf(56, 200)) {
            val fixture = Fixture(RuntimeEnvironment.getApplication())
            fixture.layout(size, size)
            fixture.presentActiveMaterial(0xff6d6d6d.toInt())
            assertEquals(Triple(false, false, false), fixture.lastAppearance)
            val opaque = fixture.paintPixels(Color.WHITE)

            fixture.layout(size, size, opacity = .56f)
            assertEquals(Triple(false, false, true), fixture.lastAppearance)
            assertArrayEquals("The admitted correction belongs to the material",
                opaque, fixture.paintPixels(Color.WHITE))
            assertEquals("White ink needs a darkening filter", 0, fixture.appliedCorrection().second)
            fixture.layout(size, size)
            assertEquals(Triple(false, false, false), fixture.lastAppearance)
            assertNull("Restoring full layer opacity clears the correction", fixture.installedCorrection())
            assertArrayEquals(opaque, fixture.paintPixels(Color.WHITE))
        }

        val safe = Fixture(RuntimeEnvironment.getApplication())
        safe.layout(56, 56)
        safe.presentActiveMaterial(0xff202020.toInt())
        assertEquals(Triple(false, false, false), safe.lastAppearance)
        val safeOpaque = safe.paintPixels(Color.WHITE)
        safe.layout(56, 56, opacity = .56f)
        assertEquals(Triple(false, false, false), safe.lastAppearance)
        assertNull("Already readable material needs no filter", safe.installedCorrection())
        assertArrayEquals("Already readable material must leave support off", safeOpaque, safe.paintPixels(Color.WHITE))
    }

    private class Fixture(context: Context, private val materialBackdrop: Int = Color.BLACK) {
        private val renderer = AudioReactiveBlobView(context) { 0f }
        private val style = NativeAudioBlobAppearance.Default.resolve(NativeColorScheme.fallback(false))
        var appearanceCallbacks = 0
            private set
        var lastAppearance: Triple<Boolean, Boolean, Boolean>? = null
            private set
        private val readout = NativeAudioBlobReadout(
            context,
            NativeAudioBlobIcons(
                ReverbIconDrawable(ReverbIcon.Check, 1f, Color.WHITE),
                ReverbIconDrawable(ReverbIcon.Pause, 1f, Color.WHITE),
                ReverbIconDrawable(ReverbIcon.Capture, 1f, Color.WHITE),
            ),
            NativeAudioBlobDescriptions("Full", "Pause", "Capture"),
            style,
            Density(1f),
            createFontFamilyResolver(context),
        ).apply { bind(active = true, filled = false, primaryText = "42 MiB", secondaryText = "9:59", contentAlpha = 1f) }

        fun presentActiveMaterial(primary: Int, tertiary: Int = primary) {
            renderer.onReadoutAppearanceChanged = { darkInk, transitioning, support ->
                appearanceCallbacks++
                lastAppearance = Triple(darkInk, transitioning, support)
                readout.setMaterialAppearance(darkInk, transitioning, support)
            }
            renderer.updateState(active = true, enabled = true, saving = false, visible = true,
                style = style.copy(primary = primary, tertiary = tertiary, resting = Color.BLACK))
        }

        fun layout(width: Int, height: Int, opacity: Float = 1f) {
            renderer.measure(spec(width, View.MeasureSpec.EXACTLY), spec(height, View.MeasureSpec.EXACTLY))
            renderer.layout(3, 5, 3 + width, 5 + height)
            readout.measure(spec(width, View.MeasureSpec.AT_MOST), spec(height, View.MeasureSpec.AT_MOST))
            val left = renderer.left + ((width - readout.measuredWidth) * .5f).roundToInt()
            val top = renderer.top + ((height - readout.measuredHeight) * .5f).roundToInt()
            readout.layout(left, top, left + readout.measuredWidth, top + readout.measuredHeight)
            readout.updateMaterialGeometry(renderer, materialBackdrop, opacity)
        }

        fun resizeMaterialAroundReadout(size: Int) {
            // Keep the readout and its children laid out exactly as before the renderer resize.
            val left = renderer.left + (renderer.width - size) / 2
            val top = renderer.top + (renderer.height - size) / 2
            val exact = spec(size, View.MeasureSpec.EXACTLY)
            renderer.measure(exact, exact)
            renderer.layout(left, top, left + size, top + size)
            readout.updateMaterialGeometry(renderer, materialBackdrop, opacity = 1f)
        }

        fun render(background: Int, darkInk: Boolean, transitioning: Boolean, support: Boolean): IntArray {
            // Isolate the real support paint against a color that needs correction. The icon and
            // labels are drawn identically in the reference, so only support changes these pixels.
            readout.setMaterialAppearance(darkInk, transitioning, support)
            return paintPixels(background)
        }

        fun appliedCorrection(): Pair<Int, Int> {
            val corrected = checkNotNull(installedCorrection()) { "Contrast support must install a material filter" }
            assertTrue("The installed filter must change RGB", corrected != (0x00ffffff to 0))
            try {
                readout.setContentOpacity(0f)
                assertNull("Hiding the glyphs clears the renderer's filter", installedCorrection())
            } finally {
                readout.setContentOpacity(1f)
            }
            assertEquals("Restoring glyph opacity restores the same correction", corrected, installedCorrection())
            return corrected
        }

        fun installedCorrection(): Pair<Int, Int>? {
            // Inspect the real paint without drawing a RuntimeShader into a software bitmap.
            val backend = AudioReactiveBlobView::class.java.getDeclaredField("renderer").let {
                it.isAccessible = true
                it.get(renderer)
            }
            val paint = backend.javaClass.getDeclaredField("paint").let {
                it.isAccessible = true
                it.get(backend) as Paint
            }
            val filter = paint.colorFilter ?: return null
            check(filter is LightingColorFilter)
            return filter.colorMultiply to filter.colorAdd
        }

        fun paintPixels(background: Int): IntArray {
            val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(background)
                canvas.translate((PADDING + readout.left).toFloat(), (PADDING + readout.top).toFloat())
                readout.draw(canvas)
                return IntArray(IMAGE_SIZE * IMAGE_SIZE).also {
                    bitmap.getPixels(it, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
                }
            } finally {
                bitmap.recycle()
            }
        }

        private fun spec(size: Int, mode: Int) = View.MeasureSpec.makeMeasureSpec(size, mode)
    }

    private companion object {
        const val IMAGE_SIZE = 256
        const val PADDING = 24
    }
}
