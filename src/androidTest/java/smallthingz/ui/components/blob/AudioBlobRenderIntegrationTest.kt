// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.util.Log
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeCaptureControlsStyle
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbIconDrawable
import smallthingz.ui.reverb.home.reverbHomeGeometry
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

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
            return raster(size, size, hardware, ::draw)
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

    @Test @SdkSuppress(minSdkVersion = 33)
    fun agslBodyMaterialKeepsItsColorAcrossExplicitCompactAndFullBounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            for (dark in listOf(false, true)) {
                val page = if (dark) Color.BLACK else Color.WHITE
                val pigment = if (dark) Color.rgb(168, 211, 255) else Color.rgb(20, 33, 56)
                val style = NativeAudioBlobAppearance.Default.resolve(NativeColorScheme.fallback(dark))
                    .copy(primary = pigment, tertiary = pigment, resting = pigment, readoutBackground = page)
                val body = AudioReactiveBlobView(context, durationScale = { 0f })
                val backend = AudioReactiveBlobView::class.java.getDeclaredField("renderer")
                    .apply { isAccessible = true }.get(body)
                assertTrue("Physical parity must exercise AGSL", backend is AudioReactiveBlobView.ShaderRenderer)
                val readout = NativeAudioBlobReadout(
                    context, NativeAudioBlobIcons(
                        ReverbIconDrawable(ReverbIcon.Check, 1f, Color.WHITE),
                        ReverbIconDrawable(ReverbIcon.Pause, 1f, Color.WHITE),
                        ReverbIconDrawable(ReverbIcon.Capture, 1f, Color.WHITE),
                    ),
                    NativeAudioBlobDescriptions("Full", "Pause", "Capture"),
                    style, Density(1f), createFontFamilyResolver(context),
                )
                body.onReadoutAppearanceChanged = readout::setMaterialAppearance
                val bands = FloatArray(8) { .25f }
                fun sample(size: Int, activity: Float, suppressed: Boolean): List<Int> {
                    val exact = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
                    body.measure(exact, exact)
                    body.layout(0, 0, size, size)
                    val limit = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)
                    readout.measure(limit, limit)
                    val x = (size - readout.measuredWidth) / 2
                    val y = (size - readout.measuredHeight) / 2
                    readout.layout(x, y, x + readout.measuredWidth, y + readout.measuredHeight)
                    readout.updateMaterialGeometry(body, page)
                    body.submitFrame(activity, bands, silenceSuppressed = suppressed)
                    val pixels = raster(size, size, hardware = true, body::draw)
                    assertEquals("A body-only draw must leave the corner transparent", 0, Color.alpha(pixels[0]))
                    val center = size / 2
                    return listOf(0, size / 9, -size / 9).map { dx ->
                        pixels[center * size + center + dx].also { assertEquals(255, Color.alpha(it)) }
                    }
                }
                fun compare(scope: String, activity: Float, suppressed: Boolean): List<Int> {
                    val full = sample(405, activity, suppressed)
                    val compact = sample(81, activity, suppressed)
                    full.zip(compact).forEach { (expected, actual) ->
                        for (shift in listOf(0, 8, 16)) {
                            val delta = kotlin.math.abs((expected ushr shift and 255) - (actual ushr shift and 255))
                            assertTrue("$scope dark=$dark compact RGB differs from full: $expected vs $actual", delta <= 1)
                        }
                    }
                    assertEquals("$scope dark=$dark grow-back", full, sample(405, activity, suppressed))
                    return full
                }
                readout.bind(active = true, filled = false, primaryText = "7:57",
                    secondaryText = "42.0 MiB", contentAlpha = 1f)
                body.updateState(active = true, enabled = true, saving = false, visible = true, style = style)
                val audible = compare("audible", .5f, suppressed = false)
                val suppressed = compare("suppressed", .5f, suppressed = true)
                assertTrue("Suppression must still change the rendered pigment", audible.first() != suppressed.first())
                readout.bind(active = false, filled = false, primaryText = "7:57",
                    secondaryText = null, contentAlpha = 1f)
                body.updateState(active = false, enabled = true, saving = false, visible = true, style = style)
                compare("rest", 0f, suppressed = false)
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    fun compactAgslKeepsItsContourAndReadableGlyphs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (dark in listOf(false, true)) {
                verifyCompactAgsl(instrumentation, instrumentation.targetContext, dark)
            }
        }
    }

    private fun verifyCompactAgsl(instrumentation: Instrumentation, context: Context, dark: Boolean) {
        // Use the short Home body constraints exercised by the compact fallback fixture.
        val density = 1f
        val width = 360
        val height = 215
        val geometry = reverbHomeGeometry(width, height, density, NativeCaptureControlsStyle.Default)
        val size = geometry.blobSize
        val bodyLeft = geometry.side + ((geometry.bodyWidth - size) / 2f).roundToInt()
        val bodyTop = ((geometry.blobAreaHeight - size) / 2f).roundToInt()
        val page = if (dark) Color.BLACK else Color.WHITE
        val material = if (dark) Color.rgb(168, 211, 255) else Color.rgb(20, 33, 56)
        val style = NativeAudioBlobAppearance.Default.resolve(NativeColorScheme.fallback(dark))
            .copy(primary = material, tertiary = material, resting = material, readoutBackground = page)
        val body = AudioReactiveBlobView(context, durationScale = { 0f })
        val exact = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        body.measure(exact, exact)
        body.layout(0, 0, size, size)
        val readout = NativeAudioBlobReadout(
            context,
            NativeAudioBlobIcons(
                ReverbIconDrawable(ReverbIcon.Check, density, Color.WHITE),
                ReverbIconDrawable(ReverbIcon.Pause, density, Color.WHITE),
                ReverbIconDrawable(ReverbIcon.Capture, density, Color.WHITE),
            ),
            NativeAudioBlobDescriptions("Full", "Pause", "Capture"),
            style, Density(density), createFontFamilyResolver(context),
        )
        body.onReadoutAppearanceChanged = readout::setMaterialAppearance
        readout.bind(active = true, filled = false, primaryText = "7:57", secondaryText = null, contentAlpha = 1f)
        val limit = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)
        readout.measure(limit, limit)
        val readoutLeft = ((size - readout.measuredWidth) / 2f).roundToInt()
        val readoutTop = ((size - readout.measuredHeight) / 2f).roundToInt()
        readout.layout(readoutLeft, readoutTop, readoutLeft + readout.measuredWidth, readoutTop + readout.measuredHeight)
        readout.updateMaterialGeometry(body, page)
        body.updateState(active = true, enabled = true, saving = false, visible = true, style = style)
        assertTrue("Fixture stays detached and unfocused", !body.isAttachedToWindow && !body.isFocused)

        for (suppressed in listOf(false, true)) {
            body.submitFrame(1f, FloatArray(8) { 1f }, silenceSuppressed = suppressed)
            val scope = "dark=$dark suppressed=$suppressed size=$size"
            fun scene(backdrop: Int, drawBody: Boolean, drawReadout: Boolean): IntArray =
                raster(width, height, hardware = true) { canvas ->
                    canvas.drawColor(backdrop)
                    canvas.translate(bodyLeft.toFloat(), bodyTop.toFloat())
                    if (drawBody) body.draw(canvas)
                    if (drawReadout) {
                        canvas.translate(readoutLeft.toFloat(), readoutTop.toFloat())
                        readout.draw(canvas)
                    }
                }
            val silhouette = scene(Color.TRANSPARENT, drawBody = true, drawReadout = false)
            val underlay = scene(page, drawBody = true, drawReadout = false)
            val composite = scene(page, drawBody = true, drawReadout = true)
            val palette = if (dark) "dark" else "light"
            val signal = if (suppressed) "suppressed" else "audible"
            writeCompositePng(instrumentation, "compact-agsl-$palette-$signal.png", width, height, composite)
            val children = List(readout.childCount, readout::getChildAt)
            val visibility = children.map { it.visibility }
            val withoutGlyphs = try {
                children.forEach { if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
                scene(Color.TRANSPARENT, drawBody = true, drawReadout = true)
            } finally {
                children.zip(visibility).forEach { (child, state) -> child.visibility = state }
            }
            assertArrayEquals("$scope readout painted with its glyphs hidden", silhouette, withoutGlyphs)
            val glyphMasks = listOf("icon" to children[0], "label" to children[1]).map { (name, child) ->
                name to raster(width, height, hardware = true) { canvas ->
                    canvas.translate((bodyLeft + readoutLeft + child.left).toFloat(),
                        (bodyTop + readoutTop + child.top).toFloat())
                    child.draw(canvas)
                }
            }
            var corePixels = 0
            var contourPixels = 0
            var glowPixels = 0
            val energy = if (suppressed) .08 else 1.0
            for (pixel in silhouette.indices) {
                val x = (pixel % width + .5 - bodyLeft) / size - .5
                val y = (pixel / width + .5 - bodyTop) / size - .5
                val angle = atan2(y, x)
                // The view's zero duration scale snaps to full life without advancing shader time.
                val contour = .348 + energy * (.070 * sin(3 * angle) + .026 * sin(7 * angle + 1.8))
                val edge = hypot(x, y) - contour
                val alpha = Color.alpha(silhouette[pixel])
                val pixelMargin = 1.5 / size
                if (edge > .024 + pixelMargin) {
                    assertEquals("$scope detached paint at ($x, $y)", 0, alpha)
                    assertEquals("$scope exterior changed the page at ($x, $y)", page, underlay[pixel])
                }
                // Beyond the fallback path's antialias fringe, AGSL still paints its attached glow.
                if (edge in .016..0.019 && alpha >= 4) glowPixels++
                if (edge < -.006 - pixelMargin) {
                    assertEquals("$scope missing interior at ($x, $y)", 255, alpha)
                    corePixels++
                }
                // The analytic body retains visible coverage just inside its nominal edge.
                // A flat crop that removes the outward lobe must fail even with a clear exterior.
                if (edge in -.018..-.004) {
                    assertTrue("$scope clipped contour at ($x, $y)", alpha >= 128)
                    contourPixels++
                }
            }
            assertTrue("$scope requires AGSL glow outside the body path", glowPixels > 0)
            assertTrue("$scope requires material and contour witnesses", corePixels > 0 && contourPixels > 0)
            for ((name, mask) in glyphMasks) {
                var opaquePixels = 0
                for (pixel in mask.indices) {
                    if (Color.alpha(mask[pixel]) != 255) continue
                    opaquePixels++
                    val ink = audioBlobMaterialLuminance(composite[pixel])
                    val background = audioBlobMaterialLuminance(underlay[pixel])
                    val contrast = (maxOf(ink, background) + .05) / (minOf(ink, background) + .05)
                    assertTrue("$scope $name contrast $contrast at pixel $pixel", contrast >= 4.5)
                }
                assertTrue("$scope requires opaque $name pixels", opaquePixels > 0)
            }
        }
    }

    private fun writeCompositePng(
        instrumentation: Instrumentation,
        name: String,
        width: Int,
        height: Int,
        pixels: IntArray,
    ) {
        val directory = checkNotNull(instrumentation.context.getExternalFilesDir("qualification"))
        val file = File(directory, name)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not encode $name" }
            }
        } finally {
            bitmap.recycle()
        }
        Log.i("CompactAgslQualification", "Qualification PNG: ${file.absolutePath}")
    }

    private fun raster(width: Int, height: Int, hardware: Boolean, draw: (Canvas) -> Unit): IntArray {
        val bitmap = if (hardware) {
            val picture = Picture()
            val recording = picture.beginRecording(width, height)
            try {
                draw(recording)
            } finally {
                picture.endRecording()
            }
            val gpu = Bitmap.createBitmap(picture, width, height, Bitmap.Config.HARDWARE)
            try { requireNotNull(gpu.copy(Bitmap.Config.ARGB_8888, false)) } finally { gpu.recycle() }
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
        }
        return try {
            IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        } finally { bitmap.recycle() }
    }
}
