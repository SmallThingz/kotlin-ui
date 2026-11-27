// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
    @Test fun compactReadoutItselfPaintsNoFieldInEitherCaptureState() {
        val fixture = Fixture(RuntimeEnvironment.getApplication())
        for (homeHeight in listOf(190, 215)) {
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

    @Test fun supportDropsOnEmptyLayoutAndRecoversAfterResize() {
        val fixture = Fixture(RuntimeEnvironment.getApplication())
        val background = Color.rgb(32, 32, 32)
        fun render(support: Boolean) = fixture.render(background, darkInk = true, transitioning = false, support = support)
        fixture.layout(200, 200)
        val normal = render(true)
        assertTrue(!normal.contentEquals(render(false)))
        fixture.layout(56, 56)
        assertArrayEquals(render(false), render(true))
        fixture.layout(1, 1)
        assertArrayEquals(render(false), render(true))
        fixture.layout(0, 0)
        assertArrayEquals(render(false), render(true))
        fixture.layout(200, 200)
        assertArrayEquals(normal, render(true))
    }

    @Test fun compactModeChangesInvalidateReadoutWithoutAnAppearanceCallback() {
        val fixture = Fixture(RuntimeEnvironment.getApplication(), materialBackdrop = Color.WHITE)
        fixture.layout(200, 200)
        // This material needs support for dark ink in both modes, so appearance stays unchanged.
        fixture.presentActiveMaterial(0xff757575.toInt())
        assertEquals(Triple(true, false, true), fixture.lastAppearance)
        val appearanceCallbacks = fixture.appearanceCallbacks
        val readout = shadowOf(fixture.readout)

        readout.clearWasInvalidated()
        fixture.resizeMaterialAroundReadout(80)
        assertTrue("Entering compact mode must discard the recorded field", readout.wasInvalidated())

        readout.clearWasInvalidated()
        fixture.resizeMaterialAroundReadout(82)
        assertFalse("Resizing within compact mode must not redraw the readout", readout.wasInvalidated())

        fixture.resizeMaterialAroundReadout(200)
        assertTrue("Leaving compact mode must record the field again", readout.wasInvalidated())
        assertEquals(appearanceCallbacks, fixture.appearanceCallbacks)
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

    @Test fun compactDimmedReadoutChecksContrastAfterLayerCompositingBeforeAdmittingSupport() {
        val fixture = Fixture(RuntimeEnvironment.getApplication())
        fixture.layout(56, 56)
        fixture.presentActiveMaterial(0xff6d6d6d.toInt())
        assertEquals(Triple(false, false, false), fixture.lastAppearance)
        val opaque = fixture.paintPixels(Color.WHITE)

        fixture.layout(56, 56, opacity = .56f)
        assertEquals(Triple(false, false, true), fixture.lastAppearance)
        assertArrayEquals("The admitted correction belongs to the material, not the readout",
            opaque, fixture.paintPixels(Color.WHITE))
        fixture.layout(56, 56)
        assertEquals(Triple(false, false, false), fixture.lastAppearance)
        assertArrayEquals(opaque, fixture.paintPixels(Color.WHITE))

        val safe = Fixture(RuntimeEnvironment.getApplication())
        safe.layout(56, 56)
        safe.presentActiveMaterial(0xff202020.toInt())
        assertEquals(Triple(false, false, false), safe.lastAppearance)
        val safeOpaque = safe.paintPixels(Color.WHITE)
        safe.layout(56, 56, opacity = .56f)
        assertEquals(Triple(false, false, false), safe.lastAppearance)
        assertArrayEquals("Already readable material must leave support off", safeOpaque, safe.paintPixels(Color.WHITE))
    }

    private class Fixture(context: Context, private val materialBackdrop: Int = Color.BLACK) {
        private val renderer = AudioReactiveBlobView(context) { 0f }
        private val style = NativeAudioBlobAppearance.Default.resolve(NativeColorScheme.fallback(false))
        var appearanceCallbacks = 0
            private set
        var lastAppearance: Triple<Boolean, Boolean, Boolean>? = null
            private set
        val readout = NativeAudioBlobReadout(
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
