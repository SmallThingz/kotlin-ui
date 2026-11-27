// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
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
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbIconDrawable
import smallthingz.ui.reverb.home.ReverbHomeBlobAttachment
import smallthingz.ui.reverb.home.ReverbHomeBlobControl
import smallthingz.ui.reverb.home.ReverbHomeBlobState
import smallthingz.ui.reverb.home.ReverbHomeFlipFrame
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
            fixture.layoutHome(COVERED_HOME_HEIGHT, dimmed = false)
            val normalFootprint = fixture.projectedFootprintRatio()
            assertTrue("${scene.name} normal readout must fit stable material coverage: $normalFootprint",
                normalFootprint in 0f..STABLE_SETTLED_COVERAGE)
            val normal = fixture.sample(expectedDarkInk = !scene.backdropUsesDarkInk, failures)

            fixture.layoutHome(INTERMEDIATE_HOME_HEIGHT, dimmed = false)
            assertEquals(216, fixture.audioView.width)
            val intermediateFootprint = fixture.projectedFootprintRatio()
            assertTrue("${scene.name} intermediate readout must use page ink: $intermediateFootprint",
                intermediateFootprint > STABLE_SETTLED_COVERAGE)
            val intermediate = fixture.sample(expectedDarkInk = scene.backdropUsesDarkInk, failures)

            fixture.layoutHome(190, dimmed = false)
            assertTrue("${scene.name}: the complete timer must fit the 56px readout", fixture.primaryFits())
            val shortest = fixture.sample(expectedDarkInk = scene.backdropUsesDarkInk, failures)
            fixture.layoutHome(215, dimmed = false)
            if (!fixture.primaryFits()) failures += "${scene.name}: the complete timer does not fit the 80px readout"
            val short = fixture.sample(expectedDarkInk = scene.backdropUsesDarkInk, failures)
            recordEdgeWitnesses("${scene.name} undimmed", shortest.fadePixels + short.fadePixels,
                shortest.outsidePixels + short.outsidePixels, failures)

            fixture.layoutHome(INTERMEDIATE_HOME_HEIGHT, dimmed = false)
            val intermediateGrown = fixture.sample(expectedDarkInk = scene.backdropUsesDarkInk, failures)
            if (!intermediate.pixels.contentEquals(intermediateGrown.pixels)) {
                failures += "${scene.name}: growing to the intermediate size changed the per-pixel output"
            }

            fixture.layoutHome(COVERED_HOME_HEIGHT, dimmed = false)
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

    @Test fun dimmedMaterialCorrectionPreservesAlphaAndRecoversAfterEmptyLayout() = withSettledAnimations {
        val fixture = Fixture(RuntimeEnvironment.getApplication(), Scene.DarkBackdrop,
            material = Color.BLACK, tertiary = 0xffe0e0e0.toInt())
        fixture.layoutHome(190, dimmed = false)
        val opaque = fixture.material()
        fixture.layoutHome(190, dimmed = true)
        assertEquals(Triple(false, false, true), fixture.lastAppearance)
        val directlyDimmed = fixture.material()
        assertFalse("The outer layer's opacity must change the correction", opaque.contentEquals(directlyDimmed))
        fixture.assertMaterialOnlySupport()

        fixture.layoutBlob(0, dimmed = false)
        fixture.layoutBlob(0, dimmed = true)
        fixture.layoutHome(190, dimmed = true)
        assertArrayEquals("Apply the latest correction when a zero-size material grows",
            directlyDimmed, fixture.material())
    }

    @Test fun actionableDimmedReadyReadoutFitsAndKeepsContrastOnHomeBackdrops() = withSettledAnimations {
        val context = RuntimeEnvironment.getApplication()
        NativeModalWindowRuntime(context).use { runtime ->
            for (dark in listOf(false, true)) {
                val environment = ReverbEnvironment(context, dark)
                val backdrop = environment.palette.background
                val size = reverbHomeGeometry(360, 190, 1f, environment.appearance.captureControls).blobSize
                assertEquals(56, size)
                val attachment = object : ReverbHomeBlobAttachment {
                    override fun attach(view: AudioReactiveBlobView) = Unit
                    override fun detach(view: AudioReactiveBlobView) = Unit
                    override fun currentBaseRadiusFraction(): Float? = null
                }
                val state = ReverbHomeBlobState(
                    attachment, active = false, saving = false, captureEnabled = true,
                    clickableEnabled = true, filled = false, dimmed = true, primaryText = PRIMARY,
                    secondaryText = "42.0 MiB", visualizerVisible = true,
                )
                ReverbHomeBlobControl(context, environment, NoPointerInput, runtime.scope) {}.use { control ->
                    control.bind(state, ReverbHomeFlipFrame()) {}
                    val exact = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
                    control.measure(exact, exact)
                    control.layout(0, 0, size, size)
                    assertTrue(control.isEnabled)
                    assertEquals(.56f, control.alpha, 0f)

                    val material = control.getChildAt(0)
                    val readout = control.getChildAt(1) as NativeAudioBlobReadout
                    val primary = readout.getChildAt(1) as ParagraphView
                    assertTrue("The complete 56px primary line must fit", completePrimaryFits(primary))
                    val underlay = rasterOverPage(backdrop, control.alpha, material)
                    val composite = rasterOverPage(backdrop, control.alpha, control)
                    for (index in 0..1) {
                        val child = readout.getChildAt(index)
                        val mask = raster { canvas ->
                            canvas.translate((PADDING + readout.left + child.left).toFloat(),
                                (PADDING + readout.top + child.top).toFloat())
                            child.draw(canvas)
                        }
                        val glyphCores = mask.indices.filter { Color.alpha(mask[it]) == 255 }
                        assertTrue("dark=$dark glyph=$index must have opaque samples", glyphCores.isNotEmpty())
                        assertTrue("dark=$dark glyph=$index contrast after group opacity",
                            glyphCores.all { contrast(composite[it], underlay[it]) >= MIN_CONTRAST })
                        if (index == 1) assertTrue("The short primary line must cross the material edge",
                            glyphCores.any { underlay[it] == backdrop })
                    }
                }
            }
        }
    }

    @Test fun settledAudioOnlyFramesSkipReadoutProjectionUntilSilenceChanges() = withSettledAnimations {
        val fixture = Fixture(RuntimeEnvironment.getApplication(), Scene.DarkBackdrop)
        fixture.layoutHome(190, dimmed = false)
        val renderer = fixture.audioView
        val lastAppearance = AudioReactiveBlobView::class.java.getDeclaredField("lastReadoutAppearance")
            .apply { isAccessible = true }
        // Clear only the delivery sentinel so a redundant projection would become observable.
        lastAppearance.setInt(renderer, -1)
        val quietRadius = renderer.currentBaseRadiusFraction()
        renderer.submitFrame(.4f, FloatArray(8) { .3f }, silenceSuppressed = false)
        val snappedRadius = renderer.currentBaseRadiusFraction()
        assertTrue(snappedRadius > quietRadius)
        assertEquals("Audio-only snap must leave the projection untouched", -1, lastAppearance.getInt(renderer))

        // The unattached fixture has no vsync. Set a new audio target and step the real frame path.
        AudioReactiveBlobView::class.java.getDeclaredField("targetActivity").apply {
            isAccessible = true
            setFloat(renderer, .9f)
        }
        AudioReactiveBlobView::class.java.getDeclaredMethod("advance", Float::class.javaPrimitiveType).apply {
            isAccessible = true
            invoke(renderer, 1f / 60f)
        }
        assertTrue(renderer.currentBaseRadiusFraction() > snappedRadius)
        assertEquals("Audio-only animation must leave the projection untouched", -1, lastAppearance.getInt(renderer))

        renderer.submitFrame(.9f, FloatArray(8) { .3f }, silenceSuppressed = true)
        assertTrue("Silence pigment must refresh the projection", lastAppearance.getInt(renderer) >= 0)
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
        val drainedCallbackCount = appearances.size

        renderer.clearFrame()
        assertTrue(renderer.readoutNeedsSupport(darkInk = false))
        assertEquals(drainedCallbackCount + 1, appearances.size)
        assertEquals(Triple(false, false, true), appearances.last())
        // The recorded palette is gray again after clearing silence. Its actual material must
        // immediately be protected against the white ink, independently of glyph paint.
        assertTrue(Color.red(fixture.material()[fixture.centerPixel]) < Color.red(gray))

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

    private class Fixture(
        context: Context,
        private val scene: Scene,
        material: Int = scene.material,
        tertiary: Int = material,
    ) {
        private val style = NativeAudioBlobAppearance.Default.resolve(NativeColorScheme.fallback(false)).copy(
            primary = material,
            tertiary = tertiary,
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
        val centerPixel get() = (PADDING + control.height / 2) * IMAGE_SIZE + PADDING + control.width / 2
        var lastAppearance: Triple<Boolean, Boolean, Boolean>? = null
            private set

        init {
            val listener = requireNotNull(audioView.onReadoutAppearanceChanged)
            audioView.onReadoutAppearanceChanged = { darkInk, transitioning, support ->
                lastAppearance = Triple(darkInk, transitioning, support)
                listener(darkInk, transitioning, support)
            }
        }

        fun material(): IntArray = raster { canvas ->
            canvas.translate(PADDING.toFloat(), PADDING.toFloat())
            audioView.draw(canvas)
        }

        fun assertMaterialOnlySupport() {
            val corrected = material()
            val withoutGlyphs = scene(showInk = false)
            val uncorrected = try {
                // The measured fixture is unattached, so use the synchronous readout opacity path.
                readout.setContentOpacity(0f)
                material()
            } finally {
                readout.setContentOpacity(1f)
            }
            var visibleCorrection = false
            var witnessesInsideViewport = 0
            var changedAlphaPixels = 0
            var maximumAlphaDelta = 0
            var maximumDeltaIndex = -1
            var zeroCoverageChanges = 0
            var firstZeroCoverageChange = -1
            var backdropChanges = 0
            var firstBackdropChange = -1
            var firstSaturationChange: String? = null
            val edgeSamples = mutableListOf<String>()
            for (index in corrected.indices) {
                val expectedAlpha = Color.alpha(uncorrected[index])
                val actualAlpha = Color.alpha(corrected[index])
                if (expectedAlpha == 255 && actualAlpha == 255 && corrected[index] != uncorrected[index]) {
                    visibleCorrection = true
                }
                val delta = abs(actualAlpha - expectedAlpha)
                if (delta != 0) {
                    changedAlphaPixels++
                    if (delta > maximumAlphaDelta) {
                        maximumAlphaDelta = delta
                        maximumDeltaIndex = index
                    }
                    if (edgeSamples.size < 8) {
                        edgeSamples += "(${index % IMAGE_SIZE},${index / IMAGE_SIZE}) $expectedAlpha->$actualAlpha"
                    }
                    if (firstSaturationChange == null && (expectedAlpha == 255 || actualAlpha == 255)) {
                        firstSaturationChange =
                            "(${index % IMAGE_SIZE},${index / IMAGE_SIZE}) $expectedAlpha->$actualAlpha"
                    }
                }
                if ((expectedAlpha == 0) != (actualAlpha == 0)) {
                    if (zeroCoverageChanges == 0) firstZeroCoverageChange = index
                    zeroCoverageChanges++
                }
                if (expectedAlpha != 0) continue
                if (radiusFraction(index) < .9) witnessesInsideViewport++
                if (withoutGlyphs[index] != scene.backdrop) {
                    if (backdropChanges == 0) firstBackdropChange = index
                    backdropChanges++
                }
            }
            val diagnostics = "Material alpha maxDelta=$maximumAlphaDelta at index $maximumDeltaIndex, " +
                "changedPixels=$changedAlphaPixels, zeroCoverageChanges=$zeroCoverageChanges " +
                "(first index $firstZeroCoverageChange), edgeSamples=$edgeSamples, " +
                "firstSaturationChange=$firstSaturationChange"
            println(diagnostics)
            assertTrue("The fixture must need a visible material correction", visibleCorrection)
            assertEquals(diagnostics, 0, zeroCoverageChanges)
            // Filtered and unfiltered Skia paints can round edge opacity by one 8-bit code,
            // including at the upper saturation endpoint. Zero coverage must match exactly.
            assertTrue(diagnostics, maximumAlphaDelta <= 1)
            assertEquals("Backdrop changed outside material (first index $firstBackdropChange)", 0, backdropChanges)
            assertTrue("Use blank pixels inside the old support radius", witnessesInsideViewport > 0)
        }

        fun layoutHome(homeHeight: Int, dimmed: Boolean) {
            layoutBlob(reverbHomeGeometry(360, homeHeight, 1f, NativeCaptureControlsStyle.Default).blobSize, dimmed)
        }

        fun layoutBlob(size: Int, dimmed: Boolean) {
            val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            control.measure(spec, spec)
            control.layout(0, 0, size, size)
            control.present(dimmed)
        }

        fun projectedFootprintRatio(): Float =
            AudioReactiveBlobView::class.java.getDeclaredField("readoutRadiusPixels").let {
                it.isAccessible = true
                it.getFloat(audioView) / minOf(audioView.width, audioView.height)
            }

        fun primaryFits(): Boolean = completePrimaryFits(readout.getChildAt(1) as ParagraphView)

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

    private object NoPointerInput : NativeClickEnvironment {
        override fun admitDown(event: MotionEvent) = false
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
        override val minimumTouchWidthPx = 0f
        override val minimumTouchHeightPx = 0f
    }

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
        const val COVERED_HOME_HEIGHT = 430
        const val INTERMEDIATE_HOME_HEIGHT = 360
        const val STABLE_SETTLED_COVERAGE = .33f - .079f - .012f
        const val IMAGE_SIZE = 336
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

        fun rasterOverPage(page: Int, opacity: Float, view: View): IntArray = raster { canvas ->
            canvas.drawColor(page)
            canvas.translate(PADDING.toFloat(), PADDING.toFloat())
            val layer = canvas.saveLayerAlpha(0f, 0f, view.width.toFloat(), view.height.toFloat(),
                (opacity * 255f).roundToInt())
            try { view.draw(canvas) } finally { canvas.restoreToCount(layer) }
        }

        fun completePrimaryFits(primary: ParagraphView): Boolean {
            val line = requireNotNull(primary.createOriginalSemanticsParagraph())
            return line.lineCount == 1 && !line.didExceedMaxLines && line.height <= primary.height &&
                line.getLineEnd(0, visibleEnd = true) == PRIMARY.length
        }

        fun contrast(ink: Int, underlay: Int): Double {
            val foreground = audioBlobMaterialLuminance(ink)
            val background = audioBlobMaterialLuminance(underlay)
            return (maxOf(foreground, background) + .05) / (minOf(foreground, background) + .05)
        }
    }
}
