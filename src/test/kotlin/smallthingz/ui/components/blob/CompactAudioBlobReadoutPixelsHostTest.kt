// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.view.View
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
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
    @Test fun shortActiveStartupAndSettledPaintOnlyMaterialAndReadableGlyphs() = withAnimatorScale { setScale ->
        val context = RuntimeEnvironment.getApplication()
        for (scene in Scene.entries) {
            var startupExteriorGlyphs = 0
            var settledExteriorGlyphs = 0
            for (height in listOf(190, 215)) {
                val size = reverbHomeGeometry(360, height, 1f, NativeCaptureControlsStyle.Default).blobSize
                setScale(1f)
                val fixture = Fixture(context, scene, size)
                fixture.activate()
                assertTrue("Unattached control must retain its startup pose", fixture.transitioning)
                startupExteriorGlyphs += fixture.verify("$scene startup, ${size}px").exterior

                setScale(0f)
                fixture.settleActive()
                assertFalse("Sample the settled active paint path", fixture.transitioning)
                settledExteriorGlyphs += fixture.verify("$scene settled, ${size}px").exterior
            }
            assertTrue("$scene startup must qualify glyphs outside the body", startupExteriorGlyphs > 0)
            assertTrue("$scene settled must qualify glyphs outside the body", settledExteriorGlyphs > 0)
        }
    }

    @Test fun intermediateExpansionAndSettledFoldsPaintOnlyMaterialAndReadableGlyphs() = withAnimatorScale { setScale ->
        val context = RuntimeEnvironment.getApplication()
        val density = 2.9f
        val size = reverbHomeGeometry(720, 917, density, NativeCaptureControlsStyle.Default).blobSize
        assertEquals(508, size)
        for (scene in Scene.entries) {
            setScale(1f)
            val fixture = Fixture(context, scene, size, density, secondaryLabel = "4 KiB")
            fixture.assertFitsLegacyViewport()
            fixture.activate()
            fixture.advanceQuietExpansion()
            assertTrue("Sample the quiet expansion before active geometry settles", fixture.transitioning)
            assertTrue("$scene expansion must need material correction", fixture.needsSupport)
            assertTrue("$scene expansion must sample opaque glyphs on the material",
                fixture.verify("$scene intermediate quiet expansion").onMaterial > 0)

            setScale(0f)
            fixture.settleFold()
            assertFalse("Sample the settled active fold", fixture.transitioning)
            assertTrue("$scene fold must need material correction", fixture.needsSupport)
            assertTrue("$scene fold must sample opaque glyphs on the material",
                fixture.verify("$scene intermediate settled fold", folded = true).onMaterial > 0)
        }
    }

    @Test fun settledReadoutKeepsPageInkAndCorrectionFromQuietThroughFullBands() = withAnimatorScale { setScale ->
        setScale(0f)
        val density = 2.9f
        val size = reverbHomeGeometry(720, 917, density, NativeCaptureControlsStyle.Default).blobSize
        val fixture = Fixture(RuntimeEnvironment.getApplication(), Scene.DARK, size, density, secondaryLabel = "4 KiB")
        fixture.activate()
        fixture.settleActive()
        val footprint = fixture.projectedFootprintRatio()
        assertTrue("The measured footprint must fit quiet material and straddle a full-band fold: $footprint",
            footprint > .24f && footprint <= .318f)
        val quietCorrection = fixture.verifySettledPageInk("Quiet before full bands")

        fixture.settleFold()
        assertEquals("Full bands must keep the same measured footprint", footprint, fixture.projectedFootprintRatio(), 0f)
        assertEquals("Full bands must keep the same material correction", quietCorrection,
            fixture.verifySettledPageInk("Full bands", folded = true))

        fixture.settleActive()
        assertEquals("The return to quiet must keep the same measured footprint", footprint, fixture.projectedFootprintRatio(), 0f)
        assertEquals("The return to quiet must keep the same material correction", quietCorrection,
            fixture.verifySettledPageInk("Quiet after full bands"))
    }

    @Test fun bodyMaterialKeepsItsColorAcrossExplicitCompactAndFullBounds() = withAnimatorScale { setScale ->
        setScale(0f)
        val context = RuntimeEnvironment.getApplication()
        for (scene in Scene.entries) {
            val fixture = Fixture(context, scene, size = 405)
            fixture.activate()
            val audible = fixture.materialSamplesAcrossSizes("$scene audible", activity = .5f, suppressed = false)
            val suppressed = fixture.materialSamplesAcrossSizes("$scene suppressed", activity = .5f, suppressed = true)
            assertTrue("$scene suppression must remain visible in the body", audible.first() != suppressed.first())

            fixture.settleRest()
            fixture.materialSamplesAcrossSizes("$scene rest", activity = 0f, suppressed = false)
        }
    }

    private fun withAnimatorScale(test: ((Float) -> Unit) -> Unit) {
        val getScale = ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
            .apply { isAccessible = true }
        val setScale = ValueAnimator::class.java
            .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val previousScale = getScale.invoke(null)
        try {
            test { scale -> setScale.invoke(null, scale) }
        } finally {
            setScale.invoke(null, previousScale)
        }
    }

    private enum class Scene(val page: Int, val material: Int) {
        DARK(Color.BLACK, Color.rgb(168, 211, 255)),
        LIGHT(Color.WHITE, Color.rgb(20, 33, 56)),
    }

    private data class GlyphCoverage(val exterior: Int, val onMaterial: Int)

    private class Fixture(
        context: Context,
        private val scene: Scene,
        size: Int,
        private val readoutDensity: Float = 1f,
        secondaryLabel: String = "42.0 MiB",
    ) {
        private val style = NativeAudioBlobAppearance.Default.resolve(NativeColorScheme.fallback(scene == Scene.DARK))
            .copy(primary = scene.material, tertiary = scene.material, resting = scene.material, readoutBackground = scene.page)
        private val readout = NativeAudioBlobReadout(
            context,
            NativeAudioBlobIcons(
                ReverbIconDrawable(ReverbIcon.Check, readoutDensity, Color.WHITE),
                ReverbIconDrawable(ReverbIcon.Pause, readoutDensity, Color.WHITE),
                ReverbIconDrawable(ReverbIcon.Capture, readoutDensity, Color.WHITE),
            ),
            NativeAudioBlobDescriptions("Full", "Pause", "Capture"),
            style, Density(readoutDensity), createFontFamilyResolver(context),
        ).apply { bind(active = true, filled = false, primaryText = "7:57", secondaryText = secondaryLabel, contentAlpha = 1f) }
        private val control = Control(context, readout, style, readoutDensity)
        private val body = control.getChildAt(0) as AudioReactiveBlobView
        private var bitmapSize = size + 2 * PADDING
        val transitioning get() = control.transitioning
        val needsSupport get() = body.readoutNeedsSupport(body.readoutUsesDarkInk())

        init { resize(size) }

        private fun resize(size: Int) {
            bitmapSize = size + 2 * PADDING
            val exact = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            control.measure(exact, exact)
            control.layout(0, 0, size, size)
        }

        fun activate() = control.activate()
        fun settleActive() = body.submitFrame(0f, FloatArray(0), silenceSuppressed = false)

        fun settleRest() {
            readout.bind(active = false, filled = false, primaryText = "7:57", secondaryText = null, contentAlpha = 1f)
            control.rest()
        }

        fun materialSamplesAcrossSizes(scope: String, activity: Float, suppressed: Boolean): List<Int> {
            val bands = FloatArray(8) { .25f }
            fun sample(size: Int): List<Int> {
                resize(size)
                body.submitFrame(activity, bands, silenceSuppressed = suppressed)
                val pixels = raster { body.draw(this) }
                val center = PADDING + size / 2
                // Odd sizes give a true center pixel. The other two samples use the same
                // normalized offset because both sizes are multiples of nine.
                return listOf(0, size / 9, -size / 9).map { dx ->
                    val color = pixels[center * bitmapSize + center + dx]
                    assertEquals("$scope size=$size dx=$dx must be inside opaque material", 255, Color.alpha(color))
                    color
                }
            }
            val full = sample(405)
            val compact = sample(81)
            assertEquals("$scope body must preserve RGB under the same palette and input", full, compact)
            assertEquals("$scope full body must recover on grow-back", full, sample(405))
            return full
        }

        fun assertFitsLegacyViewport() {
            // The old optimized circle was no bigger than the measured bounds' centered circle.
            // Reaching the top and bottom bounds limits how far its center could have shifted.
            val halfHeight = readout.height * .5f
            val centeredRadius = hypot(readout.width * .5f, halfHeight)
            val oldSupportReach = 2f * centeredRadius - halfHeight + 26f * readoutDensity + 1f
            assertTrue("Witness must fit the old support gate: $oldSupportReach",
                oldSupportReach < body.width * .495f)
        }

        fun advanceQuietExpansion() {
            // The unattached fixture has no frame loop. Advance the actual motion and appearance publisher.
            val advance = AudioReactiveBlobView::class.java.getDeclaredMethod("advance", Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val initialRadius = body.currentBaseRadiusFraction()
            repeat(3) { advance.invoke(body, 1f / 30f) }
            assertTrue("The quiet body must have expanded partway",
                body.currentBaseRadiusFraction() > initialRadius && body.currentBaseRadiusFraction() < .33f)
        }

        fun settleFold() {
            body.submitFrame(1f, FloatArray(8) { 1f }, silenceSuppressed = false)
            // At this phase the third and seventh sine terms both reach a trough near 0.99 rad.
            AudioReactiveBlobView::class.java.getDeclaredField("shaderTimeSeconds").apply {
                isAccessible = true
                setFloat(body, 10.29138f)
            }
        }

        fun projectedFootprintRatio(): Float =
            AudioReactiveBlobView::class.java.getDeclaredField("readoutRadiusPixels").let {
                it.isAccessible = true
                it.getFloat(body) / minOf(body.width, body.height)
            }

        fun verifySettledPageInk(scope: String, folded: Boolean = false): Int {
            assertFalse("$scope must be settled", transitioning)
            assertFalse("$scope must keep white ink on the black page", body.readoutUsesDarkInk())

            val backend = AudioReactiveBlobView::class.java.getDeclaredField("renderer").let {
                it.isAccessible = true
                it.get(body) as AudioReactiveBlobView.FallbackRenderer
            }
            val paint = AudioReactiveBlobView.FallbackRenderer::class.java.getDeclaredField("paint").let {
                it.isAccessible = true
                it.get(backend) as Paint
            }
            val filter = checkNotNull(paint.colorFilter as? LightingColorFilter) {
                "$scope must install a material correction"
            }
            assertEquals("$scope white ink needs an RGB ceiling", 0, filter.colorAdd)
            val multiplier = filter.colorMultiply and 0x00ffffff
            assertTrue("$scope must darken the solid material", multiplier in 1 until 0x00ffffff)
            assertTrue("$scope must sample opaque glyphs over opaque material",
                verify(scope, folded, expectedOpaqueInk = Color.WHITE).onMaterial > 0)
            return multiplier
        }

        fun verify(scope: String, folded: Boolean = false, expectedOpaqueInk: Int? = null): GlyphCoverage {
            val glyphs = List(readout.childCount) { index ->
                val glyph = readout.getChildAt(index)
                raster {
                    translate((readout.left + glyph.left).toFloat(), (readout.top + glyph.top).toFloat())
                    if (glyph.visibility == View.VISIBLE) glyph.draw(this)
                }
            }
            val drawMaterial: Canvas.() -> Unit = {
                translate(body.left.toFloat(), body.top.toFloat())
                body.draw(this)
            }
            val material = raster(draw = drawMaterial)
            val materialOnPage = raster(scene.page, drawMaterial)
            val compositeLayer = raster { control.draw(this) }
            val compositePage = raster(scene.page) { control.draw(this) }
            val (materialAndField, pageUnderGlyphs) = withoutGlyphs {
                raster { control.draw(this) } to raster(scene.page) { control.draw(this) }
            }
            assertTrue("$scope must draw the actual material", material.any { Color.alpha(it) == 255 })
            if (folded) {
                val centerX = PADDING + body.left + body.width / 2
                val centerY = PADDING + body.top + body.height / 2
                val dx = (body.width * .16f).roundToInt()
                val dy = (body.height * .24f).roundToInt()
                fun alphaAt(x: Int, y: Int): Int =
                    Color.alpha(material[(centerY + y) * bitmapSize + centerX + x])
                assertEquals("$scope must expose a real notch", 0, alphaAt(dx, dy))
                assertEquals("$scope must retain the opposite fold lobe", 255, alphaAt(-dx, -dy))
            }
            val size = minOf(body.width, body.height)
            val radius = size * body.currentBaseRadiusFraction()
            val maximumRadius = radius + if (folded) size * .096f else 0f
            fun argb(color: Int): String = "#${color.toUInt().toString(16).padStart(8, '0')}"
            fun sample(pixel: Int): String =
                "transparent renderer=${argb(material[pixel])} hidden=${argb(materialAndField[pixel])} " +
                    "visible=${argb(compositeLayer[pixel])}; page backdrop=${argb(scene.page)} " +
                    "renderer=${argb(materialOnPage[pixel])} hidden=${argb(pageUnderGlyphs[pixel])} " +
                    "visible=${argb(compositePage[pixel])}; glyphs=${glyphs.joinToString { argb(it[pixel]) }}"
            var reportedZeroAlphaEdge = false
            var outsideMaterialWitnesses = 0
            for (pixel in material.indices) {
                val x = pixel % bitmapSize + .5f - PADDING - body.left
                val y = pixel / bitmapSize + .5f - PADDING - body.top
                val distance = hypot(x - body.width * .5f, y - body.height * .5f)
                val hasMaterialAlpha = Color.alpha(material[pixel]) != 0
                if (materialAndField[pixel] != material[pixel] || pageUnderGlyphs[pixel] != materialOnPage[pixel]) {
                    val reason = if (hasMaterialAlpha) "readout changed material with glyphs hidden" else "field outside material"
                    fail("$scope $reason at ($x, $y) radius=$distance body=$radius: ${sample(pixel)}")
                }
                if (distance > maximumRadius + 1f &&
                    (hasMaterialAlpha || materialOnPage[pixel] != scene.page)
                ) {
                    fail("$scope renderer painted outside its native contour at ($x, $y) " +
                        "radius=$distance maximum=$maximumRadius: ${sample(pixel)}")
                }
                if (hasMaterialAlpha) continue
                if (!reportedZeroAlphaEdge && materialOnPage[pixel] != scene.page) {
                    println("$scope zero transparent alpha with opaque backdrop edge at ($x, $y) " +
                        "radius=$distance body=$radius: ${sample(pixel)}")
                    reportedZeroAlphaEdge = true
                }
                if (glyphs.all { Color.alpha(it[pixel]) == 0 }) {
                    if (compositeLayer[pixel] != material[pixel] || compositePage[pixel] != materialOnPage[pixel]) {
                        fail("$scope paint outside material and glyphs at ($x, $y): ${sample(pixel)}")
                    }
                    if (distance > maximumRadius + 1f && x >= 0 && x < body.width && y >= 0 && y < body.height) {
                        outsideMaterialWitnesses++
                    }
                }
            }
            assertTrue("$scope must include empty pixels inside the control", outsideMaterialWitnesses > 0)
            var exteriorGlyphs = 0
            var onMaterialGlyphs = 0
            glyphs.forEachIndexed { index, glyph ->
                var cores = 0
                var lowestContrast = Double.POSITIVE_INFINITY
                for (pixel in glyph.indices) {
                    if (Color.alpha(glyph[pixel]) != 255) continue
                    if (expectedOpaqueInk != null && glyph[pixel] != expectedOpaqueInk) {
                        fail("$scope glyph $index changed its opaque ink")
                    }
                    cores++
                    val materialAlpha = Color.alpha(material[pixel])
                    if (materialAlpha == 0) exteriorGlyphs++
                    if (materialAlpha == 255) onMaterialGlyphs++
                    lowestContrast = minOf(lowestContrast, contrast(compositePage[pixel], pageUnderGlyphs[pixel]))
                }
                if (index < 2) assertTrue("$scope glyph $index needs opaque ink pixels", cores > 0)
                assertTrue("$scope glyph $index contrast=$lowestContrast", lowestContrast >= 4.5)
            }
            return GlyphCoverage(exteriorGlyphs, onMaterialGlyphs)
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

    private class Control(
        context: Context,
        readout: NativeAudioBlobReadout,
        private val style: NativeAudioBlobStyle,
        density: Float,
    ) : NativeAudioBlobControlView(context, readout, style, density) {
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

        fun rest() {
            renderer.updateState(active = false, enabled = true, saving = false, visible = true, style = style)
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
