// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

/** SDK 28 uses the production fallback renderer, which supports a bitmap Canvas. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AudioBlobReadoutContrastHostTest {
    @Test fun rendererSwitchesToBackdropInkOnShrinkAndRestoresMaterialInkOnGrow() = withSettledAnimations {
        val failures = mutableListOf<String>()
        for (scene in Scene.entries) {
            val fixture = Fixture(RuntimeEnvironment.getApplication(), scene)
            fixture.layoutHome(360, dimmed = false)
            val normal = fixture.sample(expectedDarkInk = !scene.backdropUsesDarkInk, failures)

            fixture.layoutHome(190, dimmed = false)
            val shortest = fixture.sample(expectedDarkInk = scene.backdropUsesDarkInk, failures)
            fixture.layoutHome(215, dimmed = false)
            if (!fixture.primaryFits()) failures += "${scene.name}: the complete timer does not fit the 80px readout"
            val short = fixture.sample(expectedDarkInk = scene.backdropUsesDarkInk, failures)
            recordEdgeWitnesses("${scene.name} undimmed", shortest.fadePixels + short.fadePixels,
                shortest.outsidePixels + short.outsidePixels, failures)

            fixture.layoutHome(360, dimmed = false)
            val grown = fixture.sample(expectedDarkInk = !scene.backdropUsesDarkInk, failures)
            if (!normal.pixels.contentEquals(grown.pixels)) {
                failures += "${scene.name}: growing did not restore the original per-pixel output"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun dimmedShortReadoutKeepsContrastAfterParentCompositing() = withSettledAnimations {
        val failures = mutableListOf<String>()
        for (scene in Scene.entries) {
            val fixture = Fixture(RuntimeEnvironment.getApplication(), scene)
            var fadePixels = 0
            var outsidePixels = 0
            for (homeHeight in listOf(190, 215)) {
                fixture.layoutHome(homeHeight, dimmed = true)
                assertEquals(.56f, fixture.alpha, 0f)
                val sample = fixture.sample(expectedDarkInk = scene.backdropUsesDarkInk, failures)
                fadePixels += sample.fadePixels
                outsidePixels += sample.outsidePixels
            }
            recordEdgeWitnesses("${scene.name} dimmed", fadePixels, outsidePixels, failures)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun clearingSettledSilencePublishesCompactReadoutSupportImmediately() = withSettledAnimations {
        val gray = Color.rgb(120, 120, 120)
        val fixture = Fixture(RuntimeEnvironment.getApplication(), Scene.DarkBackdrop, material = gray)
        fixture.layoutHome(190, dimmed = false)
        val renderer = fixture.audioView
        val readoutListener = requireNotNull(renderer.onReadoutAppearanceChanged)
        val appearances = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        renderer.onReadoutAppearanceChanged = { darkInk, transitioning, support ->
            appearances += Triple(darkInk, transitioning, support)
            readoutListener(darkInk, transitioning, support)
        }

        renderer.submitFrame(0f, FloatArray(0), silenceSuppressed = true)
        assertEquals(Triple(false, false, false), appearances.last())
        val drainedReadout = fixture.paintReadoutOver(gray)
        val drainedCallbackCount = appearances.size

        renderer.clearFrame()
        assertTrue(renderer.readoutNeedsSupport(darkInk = false))
        assertEquals(drainedCallbackCount + 1, appearances.size)
        assertEquals(Triple(false, false, true), appearances.last())
        // Hold the underlay fixed so only the readout's newly published support can change pixels.
        assertFalse(drainedReadout.contentEquals(fixture.paintReadoutOver(gray)))

        renderer.clearFrame()
        assertEquals(drainedCallbackCount + 1, appearances.size)
    }

    private fun recordEdgeWitnesses(scope: String, fade: Int, outside: Int, failures: MutableList<String>) {
        if (fade == 0) failures += "$scope: no opaque ink in the viewport fade band (0.9 <= r < 1)"
        if (outside == 0) failures += "$scope: no opaque ink outside the viewport radius (r >= 1)"
    }

    private enum class Scene(val backdrop: Int, val material: Int, val backdropUsesDarkInk: Boolean) {
        DarkBackdrop(Color.BLACK, Color.rgb(168, 211, 255), false),
        LightBackdrop(Color.WHITE, Color.rgb(20, 33, 56), true),
    }

    private class Fixture(context: Context, private val scene: Scene, material: Int = scene.material) {
        private val style = NativeAudioBlobAppearance.Default.resolve(NativeColorScheme.fallback(false)).copy(
            primary = material,
            tertiary = material,
            resting = material,
            readoutBackground = scene.backdrop,
        )
        private val readout = NativeAudioBlobReadout(
            context,
            NativeAudioBlobIcons(
                ReverbIconDrawable(ReverbIcon.Check, 1f, Color.WHITE),
                ReverbIconDrawable(ReverbIcon.Pause, 1f, Color.WHITE),
                ReverbIconDrawable(ReverbIcon.Capture, 1f, Color.WHITE),
            ),
            NativeAudioBlobDescriptions("Full", "Pause", "Capture"),
            style, Density(1f), createFontFamilyResolver(context),
        ).apply {
            bind(active = true, filled = false, primaryText = PRIMARY, secondaryText = "42.0 MiB", contentAlpha = 1f)
        }
        // Keep the production renderer, sibling layout, and onReadoutAppearanceChanged callback.
        private val control = Control(context, readout, style)
        val alpha get() = control.alpha
        val audioView get() = control.getChildAt(0) as AudioReactiveBlobView

        fun paintReadoutOver(color: Int): IntArray = raster { canvas ->
            canvas.drawColor(color)
            canvas.translate((PADDING + readout.left).toFloat(), (PADDING + readout.top).toFloat())
            readout.draw(canvas)
        }

        fun layoutHome(homeHeight: Int, dimmed: Boolean) {
            val size = reverbHomeGeometry(360, homeHeight, 1f, NativeCaptureControlsStyle.Default).blobSize
            val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            control.measure(spec, spec)
            control.layout(0, 0, size, size)
            control.present(dimmed)
        }

        fun primaryFits(): Boolean {
            val primary = readout.getChildAt(1) as ParagraphView
            val line = requireNotNull(primary.createOriginalSemanticsParagraph())
            return line.lineCount == 1 && !line.didExceedMaxLines && line.height <= primary.height &&
                line.getLineEnd(0, visibleEnd = true) == PRIMARY.length
        }

        fun sample(expectedDarkInk: Boolean, failures: MutableList<String>): Sample {
            val scope = "${scene.name} ${control.width}px alpha=$alpha expectedDarkInk=$expectedDarkInk"
            if (control.usesDarkInk != expectedDarkInk) {
                failures += "$scope renderer reported darkInk=${control.usesDarkInk}"
            }
            val composite = scene(showInk = true)
            val underlay = scene(showInk = false)
            assertEquals("$scope uses the supplied backdrop", scene.backdrop, underlay[0])
            val inkCode = if (expectedDarkInk) 0 else 255
            val expectedCode = (inkCode * alpha + Color.red(scene.backdrop) * (1f - alpha)).roundToInt()
            var fadePixels = 0
            var outsidePixels = 0
            for ((childIndex, label) in CHILDREN.withIndex()) {
                val footprint = childFootprint(readout.getChildAt(childIndex))
                var opaquePixels = 0
                var wrongInk = 0
                var lowContrast = 0
                var lowContrastInFade = 0
                var lowContrastOutside = 0
                var worstContrast = Double.POSITIVE_INFINITY
                var worstPixel = 0
                for (pixel in footprint.indices) {
                    // Use native glyph cores; the blended edges are not opaque ink.
                    if (Color.alpha(footprint[pixel]) != 255) continue
                    opaquePixels++
                    // For these short sizes the viewport feather is 10% of the radius.
                    val radius = radiusFraction(pixel)
                    val outside = radius >= 1.0
                    val inFade = radius >= .9 && !outside
                    if (inFade) fadePixels++
                    if (outside) outsidePixels++
                    val ink = composite[pixel]
                    if (abs(Color.red(ink) - expectedCode) > 1 ||
                        abs(Color.green(ink) - expectedCode) > 1 ||
                        abs(Color.blue(ink) - expectedCode) > 1) wrongInk++
                    val contrast = contrast(ink, underlay[pixel])
                    if (contrast < MIN_CONTRAST) {
                        lowContrast++
                        if (inFade) lowContrastInFade++
                        if (outside) lowContrastOutside++
                    }
                    if (contrast < worstContrast) {
                        worstContrast = contrast
                        worstPixel = pixel
                    }
                }
                if (childIndex != 2 && opaquePixels == 0) {
                    failures += "$scope $label has no opaque ink to qualify"
                }
                if (wrongInk > 0) {
                    failures += "$scope $label $wrongInk/$opaquePixels pixels do not carry callback ink code $expectedCode"
                }
                if (lowContrast > 0) {
                    val lowContrastCore = lowContrast - lowContrastInFade - lowContrastOutside
                    failures += "$scope $label $lowContrast/$opaquePixels opaque pixels below $MIN_CONTRAST" +
                        " (core=$lowContrastCore, fade=$lowContrastInFade, outside=$lowContrastOutside)" +
                        ", min=$worstContrast radius fraction=${radiusFraction(worstPixel)}" +
                        " ink=#${composite[worstPixel].toUInt().toString(16)}" +
                        " underlay=#${underlay[worstPixel].toUInt().toString(16)}"
                }
            }
            return Sample(composite, fadePixels, outsidePixels)
        }

        private fun radiusFraction(pixel: Int): Double {
            val x = pixel % IMAGE_SIZE + .5 - PADDING - control.width * .5
            val y = pixel / IMAGE_SIZE + .5 - PADDING - control.height * .5
            return hypot(x, y) / (control.width * .495)
        }

        private fun childFootprint(child: View): IntArray = raster { canvas ->
            canvas.translate((PADDING + readout.left + child.left).toFloat(),
                (PADDING + readout.top + child.top).toFloat())
            if (child.visibility == View.VISIBLE) child.draw(canvas)
        }

        private fun scene(showInk: Boolean): IntArray {
            val visibility = IntArray(readout.childCount) { readout.getChildAt(it).visibility }
            try {
                // Keep the existing measure/layout and shader inputs while removing only child paint.
                if (!showInk) for (i in visibility.indices) {
                    if (visibility[i] == View.VISIBLE) readout.getChildAt(i).visibility = View.INVISIBLE
                }
                return raster { canvas ->
                    canvas.drawColor(scene.backdrop)
                    canvas.translate(PADDING.toFloat(), PADDING.toFloat())
                    // View.draw(Canvas) omits the parent's layer alpha. Apply the production
                    // alpha once to the whole blob and readout before compositing on the page.
                    if (alpha < 1f) {
                        val layer = canvas.saveLayerAlpha(-PADDING.toFloat(), -PADDING.toFloat(),
                            (IMAGE_SIZE - PADDING).toFloat(), (IMAGE_SIZE - PADDING).toFloat(),
                            (alpha * 255f).roundToInt())
                        try { control.draw(canvas) } finally { canvas.restoreToCount(layer) }
                    } else control.draw(canvas)
                }
            } finally {
                for (i in visibility.indices) readout.getChildAt(i).visibility = visibility[i]
            }
        }
    }

    private class Control(context: Context, readout: NativeAudioBlobReadout, private val style: NativeAudioBlobStyle) :
        NativeAudioBlobControlView(context, readout, style, 1f) {
        val usesDarkInk get() = renderer.readoutUsesDarkInk()

        fun present(dimmed: Boolean) {
            renderer.updateState(active = true, enabled = true, saving = false, visible = true, style = style)
            updateBlobDecoration(dimmed)
        }
    }

    private data class Sample(val pixels: IntArray, val fadePixels: Int, val outsidePixels: Int)

    private fun withSettledAnimations(test: () -> Unit) {
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        // The getter is hidden in the Android 28 SDK but is present in the host runtime.
        val getScale = ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
            .apply { isAccessible = true }
        val prior = getScale.invoke(null) as Float
        setScale.invoke(null, 0f)
        try {
            assertFalse(ValueAnimator.areAnimatorsEnabled())
            test()
        } finally {
            setScale.invoke(null, prior)
        }
    }

    private companion object {
        const val PRIMARY = "7:57"
        const val MIN_CONTRAST = 4.5
        const val IMAGE_SIZE = 272
        const val PADDING = 24
        val CHILDREN = listOf("icon", "time label", "size label")

        fun raster(draw: (Canvas) -> Unit): IntArray {
            val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
            try {
                draw(Canvas(bitmap))
                return IntArray(IMAGE_SIZE * IMAGE_SIZE).also {
                    bitmap.getPixels(it, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
                }
            } finally {
                bitmap.recycle()
            }
        }

        fun contrast(ink: Int, underlay: Int): Double {
            val foreground = audioBlobMaterialLuminance(ink)
            val background = audioBlobMaterialLuminance(underlay)
            return (maxOf(foreground, background) + .05) / (minOf(foreground, background) + .05)
        }
    }
}
