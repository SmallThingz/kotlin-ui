package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.animation.AnimatedValue
import smallthingz.ui.animation.core.AnimationVector4D
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.home.*
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(AndroidJUnit4::class)
class ReverbBufferRecordingAppearanceIntegrationTest {
    @Test fun actualHomeTabsKeepViewedSelectionSeparateFromInactiveCaptureFirstPaint() =
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val runtime = NativeModalWindowRuntime(context)
            try {
                for (dark in listOf(false, true)) for (viewed in 0..1) for (known in listOf(true, false)) {
                    // Disable only texture. Keep the dark preset so the popup retains its dark fill.
                    val palette = flatPalette(NativeDefaultColorScheme.resolve(dark, 30) { 0xfff3edf4.toInt() })
                    val environment = ReverbEnvironment(Density(1f), createFontFamilyResolver(context), palette)
                    val root = View(context)
                    val controls = ReverbHomeControls(context, environment, { root }, runtime.scope, 0, 1)
                    val state = ReverbHomeControlsState(viewed, 1 - viewed, true, true, false,
                        true, true, false, true, false, captureStateKnown = known)
                    controls.bind(state, ReverbHomeFlipFrame(), ReverbHomeActions({}, {}, {}, {}, {}, {}, {}))
                    val tabs = descendants(controls).filterIsInstance<HomeBufferSegment>()
                    assertEquals(2, tabs.size)
                    assertTrue(tabs[viewed].isSelected)
                    assertFalse(tabs[1 - viewed].isSelected)
                    val idle = tabs[1 - viewed]
                    idle.measure(exact(112), exact(54)); idle.layout(0, 0, 112, 54)
                    val bitmap = Bitmap.createBitmap(112, 54, Bitmap.Config.ARGB_8888)
                    try {
                        idle.draw(Canvas(bitmap))
                        val pixel = bitmap.getPixel(56, 2)
                        val expectedFill = if (known) NativeAnchoredMenuRecipe.Default.surfaceColor(palette)
                            else Color.TRANSPARENT
                        val reference = flatReferencePixel(expectedFill)
                        val retainedFill = (field(idle, "fill") as Paint).color
                        val detail = "dark=$dark viewed=$viewed known=$known " +
                            "pixel=${pixel.toUInt().toString(16)} primary=${palette.primary.toUInt().toString(16)} " +
                            "fill=${retainedFill.toUInt().toString(16)} expectedFill=${expectedFill.toUInt().toString(16)} " +
                            "reference=${reference.toUInt().toString(16)}"
                        assertEquals(detail, if (known) 255 else 0, Color.alpha(pixel))
                        assertEquals("Retained semantic fill $detail", expectedFill, retainedFill)
                        assertEquals("Independent flat raster $detail", reference, pixel)
                    } finally { bitmap.recycle() }
                    // Rebinding the viewed slot never changes the actual capture destination.
                    controls.bind(state.copy(selectedBuffer = 1 - viewed), ReverbHomeFlipFrame(),
                        ReverbHomeActions({}, {}, {}, {}, {}, {}, {}))
                    assertTrue(tabs[1 - viewed].isSelected)
                    assertFalse(tabs[viewed].isSelected)
                }
            } finally { runtime.close() }
        }

    @Test fun composedHomeTabsBoundGrainAndInkForIdleRecordingAndFullStates() {
        awaitTexture()
        val shader = NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val runtime = NativeModalWindowRuntime(context)
            try {
                val root = View(context)
                for (dark in listOf(false, true)) {
                    val palette = NativeDefaultColorScheme.resolve(dark, 30) { 0xfff3edf4.toInt() }
                    val base = ReverbEnvironment(Density(1f), createFontFamilyResolver(context), palette)
                    val cases = listOf(
                        homeState(viewed = 0), homeState(viewed = 1),
                        homeState(viewed = 0, recording = 0), homeState(viewed = 1, recording = 1),
                        homeState(viewed = 0, full = true),
                        homeState(viewed = 0, recording = 1), homeState(viewed = 1, recording = 0),
                    )
                    for (state in cases) {
                        val controls = ReverbHomeControls(context, base, { root }, runtime.scope, 0, 1)
                        controls.bind(state, ReverbHomeFlipFrame(), ReverbHomeActions({}, {}, {}, {}, {}, {}, {}))
                        layoutCluster(controls)
                        val tabs = descendants(controls).filterIsInstance<HomeBufferSegment>()
                        val renderers = tabs.map { field(it, "grain") }
                        assertTrue(renderers.all { it != null })
                        val original = assertComposedTabs(controls, tabs, state, base, palette.field)
                        // Same-palette preset changes exercise the retained owners without a color tween.
                        val flat = base.withPalette(flatPalette(palette))
                        controls.setEnvironment(flat)
                        val plain = assertComposedTabs(controls, tabs, state, flat, palette.field)
                        assertTrue("Grain changes the composed strips for dark=$dark state=$state",
                            !original.contentEquals(plain))
                        controls.setEnvironment(base)
                        assertArrayEquals(original, assertComposedTabs(controls, tabs, state, base, palette.field))
                        for (index in tabs.indices) {
                            assertSame(tabs[index], descendants(controls).filterIsInstance<HomeBufferSegment>()[index])
                            assertSame(renderers[index], field(tabs[index], "grain"))
                        }
                        // The parent publishes a sampled color while the tab's own target stays still.
                        val sampledParent = if (dark) Color.WHITE else Color.BLACK
                        (field(controls, "colors") as ViewColorTransition).snap(sampledParent, palette.border)
                        assertComposedTabs(controls, tabs, state, base, sampledParent)
                    }
                    // A palette role can equal its fill. Settled Home ink must still be qualified.
                    val lowContrast = base.withPalette(palette.copy(onPrimary = palette.primary))
                    val controls = ReverbHomeControls(context, lowContrast, { root }, runtime.scope, 0, 1)
                    val state = homeState(viewed = 0, recording = 0)
                    controls.bind(state, ReverbHomeFlipFrame(), ReverbHomeActions({}, {}, {}, {}, {}, {}, {}))
                    layoutCluster(controls)
                    val tabs = descendants(controls).filterIsInstance<HomeBufferSegment>()
                    assertComposedTabs(controls, tabs, state, lowContrast, palette.field)
                    assertNotEquals(palette.primary, displayedInk(tabs[0]))
                    assertSame(shader, NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND))

                    // Sample the real tab motion owners and the real parent publisher independently.
                    val moving = ReverbHomeControls(context, base, { root }, runtime.scope, 0, 1)
                    val start = homeState(viewed = 0, recording = 0)
                    moving.bind(start, ReverbHomeFlipFrame(), ReverbHomeActions({}, {}, {}, {}, {}, {}, {}))
                    layoutCluster(moving)
                    val movingTabs = descendants(moving).filterIsInstance<HomeBufferSegment>()
                    val end = homeState(viewed = 1, recording = 0)
                    moving.bind(end, ReverbHomeFlipFrame(), ReverbHomeActions({}, {}, {}, {}, {}, {}, {}))
                    for (millis in 0..180 step 20) {
                        val sampledFills = movingTabs.map { sampleTabMotion(it, millis * 1_000_000L) }
                        val parent = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(
                            palette.field, if (dark) Color.WHITE else Color.BLACK, millis / 180f), 255)
                        assertEquals("Opaque sampled capture parent dark=$dark millis=$millis", 255, Color.alpha(parent))
                        (field(moving, "colors") as ViewColorTransition).snap(parent, palette.border)
                        assertComposedTabs(moving, movingTabs, end, base, parent, sampledFills)
                    }
                }
            } finally { runtime.close() }
        }
    }

    private fun flatPalette(palette: NativeColorScheme) = palette.copy(surfacePreset =
        (palette.surfacePreset as? NativeDarkSurfacePreset)?.copy(foregroundGrain = 0f)
            ?: NativeGrainPreset(backgroundGrain = 1f, foregroundGrain = 0f))

    /** Independent flat-fill raster oracle includes the backend's premultiplied alpha round trip. */
    private fun flatReferencePixel(fill: Int): Int {
        val bitmap = Bitmap.createBitmap(112, 54, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
            paint.color = fill
            Canvas(bitmap).drawRect(0f, 0f, 112f, 54f, paint)
            return bitmap.getPixel(56, 2)
        } finally { bitmap.recycle() }
    }

    private fun homeState(viewed: Int, recording: Int? = null, full: Boolean = false) =
        ReverbHomeControlsState(selectedBuffer = viewed, activeBuffer = recording, listening = recording != null,
            oneShotEnabled = true, oneShotFull = full, loopingEnabled = true, serviceReady = true,
            saving = false, hasHistory = true, selectedRecording = viewed == recording)

    private fun layoutCluster(controls: ReverbHomeControls<Int>) {
        controls.measure(exact(256), exact(112))
        controls.layout(0, 0, 256, 112)
    }

    private fun assertComposedTabs(controls: ReverbHomeControls<Int>, tabs: List<HomeBufferSegment>,
        state: ReverbHomeControlsState<Int>, environment: ReverbEnvironment, sampledParent: Int,
        sampledFills: List<Int>? = null): IntArray {
        val palette = environment.palette
        val intensity = palette.surfacePreset?.foregroundGrain ?: 0f
        val actual = Bitmap.createBitmap(controls.width, controls.height, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(controls.width, controls.height, Bitmap.Config.ARGB_8888)
        val pixels = ArrayList<Int>()
        try {
            controls.draw(Canvas(actual))
            assertEquals(Color.TRANSPARENT, actual.getPixel(0, 0))
            for ((index, tab) in tabs.withIndex()) {
                val selected = state.selectedBuffer == index
                val recording = state.listening && state.activeBuffer == index
                val full = index == 0 && state.oneShotFull
                val fill = sampledFills?.get(index)
                    ?: environment.appearance.bufferSegment.fill(palette, selected, recording, full)
                if (sampledFills == null && (recording || selected && full)) assertEquals(255, Color.alpha(fill))
                if (sampledFills == null && recording && !selected) {
                    val popup = NativeAnchoredMenuRecipe.Default.colors(palette)
                    assertEquals(popup.raised, fill)
                    assertEquals(popup.grainIntensity, intensity, 0f)
                }
                val offset = Rect(0, 0, tab.width, tab.height)
                controls.offsetDescendantRectToMyCoords(tab, offset)
                expected.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(expected)
                // Independent composition oracle in the real cluster and tab coordinate systems.
                val parent = NoiseDrawable(sampledParent).apply { setNoiseIntensity(intensity) }
                parent.setBounds(0, 0, controls.width, controls.height)
                parent.draw(canvas)
                canvas.save()
                canvas.translate(offset.left.toFloat(), offset.top.toFloat())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
                paint.color = fill
                canvas.drawRect(0f, 0f, tab.width.toFloat(), tab.height.toFloat(), paint)
                NoiseDrawable().apply { setNoiseIntensity(intensity) }.drawGrain(canvas,
                    0f, 0f, tab.width.toFloat(), tab.height.toFloat(), coverage = Color.alpha(fill))
                canvas.restore()
                val range = tab.paintedTextSurface
                val ink = displayedInk(tab)
                val low = (range ushr 32).toInt()
                val high = range.toInt()
                assertEquals(255, Color.alpha(low))
                assertEquals(255, Color.alpha(high))
                val minimum = minOf(4.5, maxOf(contrastBound(range, Color.BLACK, palette.ink),
                    contrastBound(range, Color.WHITE, palette.ink)))
                for (y in 2..6) for (x in tab.width / 2 - 20 until tab.width / 2 + 20) {
                    val pixel = actual.getPixel(offset.left + x, offset.top + y)
                    assertEquals("Composed slot=$index x=$x y=$y fill=${fill.toUInt().toString(16)}",
                        expected.getPixel(offset.left + x, offset.top + y), pixel)
                    for (shift in listOf(0, 8, 16)) {
                        val channel = pixel ushr shift and 255
                        assertTrue("Painted range encloses the actual textured pixel: " +
                            "state=$state slot=$index x=$x y=$y shift=$shift grain=$intensity " +
                            "pixel=${pixel.toUInt().toString(16)} low=${low.toUInt().toString(16)} " +
                            "high=${high.toUInt().toString(16)} fill=${fill.toUInt().toString(16)} " +
                            "parent=${sampledParent.toUInt().toString(16)} sampled=${sampledFills != null}",
                            channel >= (low ushr shift and 255) && channel <= (high ushr shift and 255))
                    }
                    for (focus in listOf(0, 26)) for (press in listOf(0, 26)) {
                        val background = indicate(pixel, palette.ink, focus, press)
                        val foreground = indicate(composite(ink, pixel, Color.alpha(ink)), palette.ink, focus, press)
                        assertTrue("Current ink respects the achievable textured indication bound",
                            ColorUtils.calculateContrast(foreground, background) + 1e-10 >= minimum)
                    }
                    pixels.add(pixel)
                }
                if (intensity == 0f) assertEquals(1, pixels.takeLast(200).toSet().size)
            }
        } finally { actual.recycle(); expected.recycle() }
        return pixels.toIntArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun sampleTabMotion(tab: HomeBufferSegment, nanos: Long): Int {
        val fill = field(tab, "backgroundMotion") as AnimatedValue<smallthingz.ui.graphics.Color, AnimationVector4D>
        val ink = field(tab, "foregroundMotion") as AnimatedValue<smallthingz.ui.graphics.Color, AnimationVector4D>
        fill.frame(nanos, 1f)
        ink.frame(nanos, 1f)
        HomeBufferSegment::class.java.getDeclaredMethod("applyColors").apply { isAccessible = true }.invoke(tab)
        return fill.value.toArgb()
    }

    @Test fun texturedInkPreservesDimmedAlphaAndAdjustsSettledOpaqueInk() {
        val surface = smallthingz.ui.controls.textSurfaceRange(0xff686868.toInt(), 0xff909090.toInt())
        val preferred = 0xff808080.toInt()
        val indication = Color.WHITE
        val policy = NativeBufferSegmentInk()
        policy.target(surface, indication, preferred)
        val settled = policy.foreground(surface, preferred, false)
        assertNotEquals(preferred, settled)
        val achievable = minOf(4.5, maxOf(contrastBound(surface, Color.BLACK, indication),
            contrastBound(surface, Color.WHITE, indication)))
        assertTrue(contrastBound(surface, settled, indication) + 1e-10 >= achievable)
        for (alpha in listOf(77, 158)) {
            val dimmed = (preferred and 0x00ffffff) or (alpha shl 24)
            policy.target(surface, indication, dimmed)
            assertEquals("Settled intentional dimming", dimmed, policy.foreground(surface, dimmed, false))
            val changedSurface = smallthingz.ui.controls.textSurfaceRange(Color.BLACK, 0xff282828.toInt())
            val changing = policy.foreground(changedSurface, dimmed, true)
            assertEquals(alpha, Color.alpha(changing))
            val floor = minOf(4.5, contrastBound(surface, dimmed, indication),
                maxOf(contrastBound(changedSurface, alpha shl 24, indication),
                    contrastBound(changedSurface, (alpha shl 24) or 0x00ffffff, indication)))
            assertTrue(contrastBound(changedSurface, changing, indication) + 1e-10 >= floor)
        }
    }

    private fun displayedInk(tab: HomeBufferSegment): Int {
        val glyph = field(tab, "glyph")!!
        val ink = field(glyph, "tint") as Int
        val label = field(tab, "label") as ParagraphView
        val packed = field(label, "textColor") as Long
        assertEquals("Glyph and label consume the same qualified ink", ink,
            smallthingz.ui.graphics.Color(packed.toULong()).toArgb())
        return ink
    }

    private fun contrastBound(surface: Long, ink: Int, indication: Int): Double {
        val backgrounds = listOf((surface ushr 32).toInt(), surface.toInt())
        val paintedInk = backgrounds.map { composite(ink, it, Color.alpha(ink)) }
        fun extrema(colors: List<Int>): Pair<Double, Double> {
            val samples = colors + colors.map { indicate(it, indication, 26, 26) }
            var low = Color.BLACK
            var high = Color.BLACK
            for (shift in listOf(0, 8, 16)) {
                low = low or (samples.minOf { it ushr shift and 255 } shl shift)
                high = high or (samples.maxOf { it ushr shift and 255 } shl shift)
            }
            return ColorUtils.calculateLuminance(low) to ColorUtils.calculateLuminance(high)
        }
        val (backgroundLow, backgroundHigh) = extrema(backgrounds)
        val (inkLow, inkHigh) = extrema(paintedInk)
        return when {
            inkLow >= backgroundHigh -> (inkLow + .05) / (backgroundHigh + .05)
            backgroundLow >= inkHigh -> (backgroundLow + .05) / (inkHigh + .05)
            else -> 1.0
        }
    }

    private fun composite(front: Int, back: Int, alpha: Int): Int {
        var color = Color.BLACK
        for (shift in listOf(0, 8, 16)) {
            val channel = ((front ushr shift and 255) * alpha + (back ushr shift and 255) * (255 - alpha) + 127) / 255
            color = color or (channel shl shift)
        }
        return color
    }

    private fun indicate(color: Int, ink: Int, focus: Int, press: Int): Int =
        composite(ink, composite(ink, color, focus), press)

    private fun awaitTexture() {
        NoiseTexture.prepare()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertNotNull(NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND))
    }

    private fun field(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) { field.isAccessible = true; return field.get(owner) }
            type = type.superclass
        }
        error("Missing field $name")
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
