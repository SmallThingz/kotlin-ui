// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import kotlin.math.hypot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.components.NativeCaptureControlsStyle
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbIconDrawable
import smallthingz.ui.reverb.home.reverbHomeGeometry
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Native bitmap rendering of the SDK 28 production fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CompactAudioBlobReadoutPixelsHostTest {
    @Test fun shortActiveStartupAndSettledPaintOnlyMaterialAndReadableGlyphs() {
        val getScale = ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
            .apply { isAccessible = true }
        val setScale = ValueAnimator::class.java
            .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val previousScale = getScale.invoke(null)
        val context = RuntimeEnvironment.getApplication()
        try {
            for (scene in Scene.entries) {
                var startupExteriorGlyphs = 0
                var settledExteriorGlyphs = 0
                for (height in listOf(190, 215)) {
                    val size = reverbHomeGeometry(360, height, 1f, NativeCaptureControlsStyle.Default).blobSize
                    setScale.invoke(null, 1f)
                    val fixture = Fixture(context, scene, size)
                    fixture.activate()
                    assertTrue("Unattached control must retain its startup pose", fixture.transitioning)
                    startupExteriorGlyphs += fixture.verify("$scene startup, ${size}px")

                    setScale.invoke(null, 0f)
                    fixture.settleActive()
                    assertFalse("Sample the settled active paint path", fixture.transitioning)
                    settledExteriorGlyphs += fixture.verify("$scene settled, ${size}px")
                }
                assertTrue("$scene startup must qualify glyphs outside the body", startupExteriorGlyphs > 0)
                assertTrue("$scene settled must qualify glyphs outside the body", settledExteriorGlyphs > 0)
            }
        } finally {
            setScale.invoke(null, previousScale)
        }
    }

    private enum class Scene(val page: Int, val material: Int) {
        DARK(Color.BLACK, Color.rgb(168, 211, 255)),
        LIGHT(Color.WHITE, Color.rgb(20, 33, 56)),
    }

    private class Fixture(context: Context, private val scene: Scene, size: Int) {
        private val style = NativeAudioBlobAppearance.Default.resolve(NativeColorScheme.fallback(scene == Scene.DARK))
            .copy(primary = scene.material, tertiary = scene.material, resting = scene.material, readoutBackground = scene.page)
        private val readout = NativeAudioBlobReadout(
            context,
            NativeAudioBlobIcons(
                ReverbIconDrawable(ReverbIcon.Check, 1f, Color.WHITE),
                ReverbIconDrawable(ReverbIcon.Pause, 1f, Color.WHITE),
                ReverbIconDrawable(ReverbIcon.Capture, 1f, Color.WHITE),
            ),
            NativeAudioBlobDescriptions("Full", "Pause", "Capture"),
            style, Density(1f), createFontFamilyResolver(context),
        ).apply { bind(active = true, filled = false, primaryText = "7:57", secondaryText = "42.0 MiB", contentAlpha = 1f) }
        private val control = Control(context, readout, style)
        private val body = control.getChildAt(0) as AudioReactiveBlobView
        private val bitmapSize = size + 2 * PADDING
        val transitioning get() = control.transitioning

        init {
            val exact = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            control.measure(exact, exact)
            control.layout(0, 0, size, size)
        }

        fun activate() = control.activate()
        fun settleActive() = body.submitFrame(0f, FloatArray(0), silenceSuppressed = false)

        fun verify(scope: String): Int {
            val glyphs = List(readout.childCount) { index ->
                val glyph = readout.getChildAt(index)
                raster {
                    translate((readout.left + glyph.left).toFloat(), (readout.top + glyph.top).toFloat())
                    if (glyph.visibility == View.VISIBLE) glyph.draw(this)
                }
            }
            val material = raster {
                translate(body.left.toFloat(), body.top.toFloat())
                body.draw(this)
            }
            val compositeLayer = raster { control.draw(this) }
            val compositePage = raster(scene.page) { control.draw(this) }
            val (materialAndField, pageUnderGlyphs) = withoutGlyphs {
                raster { control.draw(this) } to raster(scene.page) { control.draw(this) }
            }
            assertTrue("$scope must draw the actual material", material.any { Color.alpha(it) == 255 })
            val radius = minOf(body.width, body.height) * body.currentBaseRadiusFraction()
            var outsideMaterialWitnesses = 0
            for (pixel in material.indices) {
                val x = pixel % bitmapSize + .5f - PADDING - body.left
                val y = pixel / bitmapSize + .5f - PADDING - body.top
                if (Color.alpha(material[pixel]) != 0) {
                    // With no audio signal the native body is circular. Allow one pixel for AA.
                    assertTrue("$scope material exceeds its native radius at ($x, $y)",
                        hypot(x - body.width * .5f, y - body.height * .5f) <= radius + 1f)
                    continue
                }
                if (Color.alpha(materialAndField[pixel]) != 0 || pageUnderGlyphs[pixel] != scene.page) {
                    fail("$scope field outside material at ($x, $y)")
                }
                if (glyphs.all { Color.alpha(it[pixel]) == 0 }) {
                    if (Color.alpha(compositeLayer[pixel]) != 0 || compositePage[pixel] != scene.page) {
                        fail("$scope paint outside material and glyphs at ($x, $y)")
                    }
                    if (x >= 0 && x < body.width && y >= 0 && y < body.height) outsideMaterialWitnesses++
                }
            }
            assertTrue("$scope must include empty pixels inside the control", outsideMaterialWitnesses > 0)
            var exteriorGlyphs = 0
            glyphs.forEachIndexed { index, glyph ->
                var cores = 0
                var lowestContrast = Double.POSITIVE_INFINITY
                for (pixel in glyph.indices) {
                    if (Color.alpha(glyph[pixel]) != 255) continue
                    cores++
                    if (Color.alpha(material[pixel]) == 0) exteriorGlyphs++
                    lowestContrast = minOf(lowestContrast, contrast(compositePage[pixel], pageUnderGlyphs[pixel]))
                }
                if (index < 2) assertTrue("$scope glyph $index needs opaque ink pixels", cores > 0)
                assertTrue("$scope glyph $index contrast=$lowestContrast", lowestContrast >= 4.5)
            }
            return exteriorGlyphs
        }

        private fun <T> withoutGlyphs(draw: () -> T): T {
            val visibility = IntArray(readout.childCount) { readout.getChildAt(it).visibility }
            try {
                // Keep measured positions and contrast geometry. The readout itself still draws.
                visibility.forEachIndexed { index, value ->
                    if (value == View.VISIBLE) readout.getChildAt(index).visibility = View.INVISIBLE
                }
                return draw()
            } finally {
                visibility.forEachIndexed { index, value -> readout.getChildAt(index).visibility = value }
            }
        }

        private fun raster(background: Int = Color.TRANSPARENT, draw: Canvas.() -> Unit): IntArray {
            val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(background)
                    translate(PADDING.toFloat(), PADDING.toFloat())
                    draw()
                }
                return IntArray(bitmapSize * bitmapSize).also {
                    bitmap.getPixels(it, 0, bitmapSize, 0, 0, bitmapSize, bitmapSize)
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private class Control(context: Context, readout: NativeAudioBlobReadout, private val style: NativeAudioBlobStyle) :
        NativeAudioBlobControlView(context, readout, style, 1f) {
        var transitioning = false
            private set

        init {
            val publishToReadout = checkNotNull(renderer.onReadoutAppearanceChanged)
            renderer.onReadoutAppearanceChanged = { darkInk, transitioning, needsSupport ->
                this.transitioning = transitioning
                publishToReadout(darkInk, transitioning, needsSupport)
            }
        }

        fun activate() {
            renderer.updateState(active = true, enabled = true, saving = false, visible = true, style = style)
            updateBlobDecoration(dimmed = false)
        }
    }

    private companion object {
        const val PADDING = 12

        fun contrast(ink: Int, underlay: Int): Double {
            val first = audioBlobMaterialLuminance(ink)
            val second = audioBlobMaterialLuminance(underlay)
            return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
        }
    }
}
