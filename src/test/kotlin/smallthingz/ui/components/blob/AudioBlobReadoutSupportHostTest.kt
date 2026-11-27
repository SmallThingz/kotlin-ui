// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    @Test fun shortHomeSupportKeepsContrastAndFadesBeforeTheRendererBoundary() {
        val fixture = Fixture(RuntimeEnvironment.getApplication())
        // The fixed 112dp controls and 18dp bottom spacer leave 60dp or 85dp above them.
        for (homeHeight in listOf(190, 215)) {
            val blobSize = reverbHomeGeometry(360, homeHeight, 1f, NativeCaptureControlsStyle.Default).blobSize
            // Also exercise rounded centering and a renderer whose width and height differ.
            fixture.layout(blobSize + 9, blobSize)
            for (darkInk in listOf(false, true)) for (transitioning in listOf(false, true)) {
                val background = if (darkInk) Color.rgb(32, 32, 32) else Color.rgb(224, 224, 224)
                val ink = if (darkInk) Color.BLACK else Color.WHITE
                val reference = fixture.render(background, darkInk, transitioning, support = false)
                val corrected = fixture.render(background, darkInk, transitioning, support = true)
                var protectedIconRegion = 0
                var protectedLabelRegion = 0
                var featheredPixels = 0
                val radius = blobSize * .495f
                val coreCorrection = abs(Color.red(audioBlobReadoutSupport(darkInk, transitioning)) - Color.red(background))
                for (index in corrected.indices) {
                    if (reference[index] == corrected[index]) continue
                    val x = index % IMAGE_SIZE + .5f - PADDING
                    val y = index / IMAGE_SIZE + .5f - PADDING
                    val distance = hypot(x - fixture.centerX, y - fixture.centerY)
                    if (distance >= radius) {
                        fail("Support must be transparent beyond the circular renderer at ($x,$y)")
                    }
                    if (reference[index] != background) continue
                    if (distance < radius * .7f && contrast(ink, corrected[index]) >= 4.5) {
                        if (fixture.inReadoutRegion(x, y, 0)) protectedIconRegion++
                        if (fixture.inReadoutRegion(x, y, 1)) protectedLabelRegion++
                    }
                    if (distance > radius * .84f) {
                        val correction = abs(Color.red(corrected[index]) - Color.red(background))
                        if (correction in 1 until (coreCorrection - 5)) featheredPixels++
                    }
                }
                assertTrue("Keep contrast support under the actual icon", protectedIconRegion > 0)
                if (homeHeight == 215) {
                    assertTrue("Keep contrast support under the retention label when it fits", protectedLabelRegion > 0)
                }
                assertTrue("Compact support must have a feather, not a hard edge", featheredPixels > 0)
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
        assertTrue(!render(true).contentEquals(render(false)))
        fixture.layout(1, 1)
        val tinyReference = render(false)
        val tinySupport = render(true)
        assertTrue(tinyReference.indices.count { tinyReference[it] != tinySupport[it] } <= 1)
        fixture.layout(0, 0)
        assertArrayEquals(render(false), render(true))
        fixture.layout(200, 200)
        assertArrayEquals(normal, render(true))
    }

    @Test fun compactSupportUsesOpacityChangedWhileRendererIsEmptyWithoutAnAppearanceCallback() {
        val fixture = Fixture(RuntimeEnvironment.getApplication())
        fixture.layout(56, 56)
        // The midpoint selects white even without compact geometry, while the bright endpoint
        // requires support. The appearance callback can stay deduplicated across both resizes.
        fixture.presentActiveMaterial(Color.BLACK, 0xffe0e0e0.toInt())
        assertEquals(Triple(false, false, true), fixture.lastAppearance)
        val opaqueSupport = fixture.paintPixels(Color.WHITE)

        fixture.layout(56, 56, opacity = .56f)
        val directlyDimmedSupport = fixture.paintPixels(Color.WHITE)
        assertTrue("This geometry must adjust support for group opacity",
            !opaqueSupport.contentEquals(directlyDimmedSupport))
        fixture.layout(56, 56)
        assertArrayEquals(opaqueSupport, fixture.paintPixels(Color.WHITE))

        val priorCallbacks = fixture.appearanceCallbacks
        fixture.layout(0, 0)
        fixture.layout(0, 0, opacity = .56f)
        fixture.layout(56, 56, opacity = .56f)
        assertEquals("Only paint inputs change across the empty layout", priorCallbacks, fixture.appearanceCallbacks)
        assertArrayEquals(directlyDimmedSupport, fixture.paintPixels(Color.WHITE))
    }

    @Test fun compactDimmedReadoutChecksContrastAfterLayerCompositingBeforeAdmittingSupport() {
        val fixture = Fixture(RuntimeEnvironment.getApplication())
        fixture.layout(56, 56)
        fixture.presentActiveMaterial(0xff6d6d6d.toInt())
        assertEquals(Triple(false, false, false), fixture.lastAppearance)
        val opaque = fixture.paintPixels(Color.WHITE)

        fixture.layout(56, 56, opacity = .56f)
        assertEquals(Triple(false, false, true), fixture.lastAppearance)
        assertTrue("Dimming must admit support for the gray material", !opaque.contentEquals(fixture.paintPixels(Color.WHITE)))
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

    private class Fixture(context: Context) {
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
        val centerX get() = renderer.left + renderer.width * .5f
        val centerY get() = renderer.top + renderer.height * .5f

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
            readout.updateMaterialGeometry(renderer, Color.BLACK, opacity)
        }

        fun inReadoutRegion(x: Float, y: Float, childIndex: Int): Boolean {
            val child = readout.getChildAt(childIndex)
            val left = readout.left + child.left + child.width * .25f
            val top = readout.top + child.top + child.height * .25f
            return x >= left && x < left + child.width * .5f && y >= top && y < top + child.height * .5f
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

    private fun contrast(first: Int, second: Int): Double {
        val a = audioBlobMaterialLuminance(first)
        val b = audioBlobMaterialLuminance(second)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }

    private companion object {
        const val IMAGE_SIZE = 256
        const val PADDING = 24
    }
}
