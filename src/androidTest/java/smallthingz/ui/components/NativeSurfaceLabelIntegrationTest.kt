package smallthingz.ui.components

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.alldoc.AllDocAppearance
import smallthingz.ui.animation.ColorTransition
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable

/** Detached real views; no Activity, window, sleeps, shader readiness or animation-scale changes. */
@RunWith(AndroidJUnit4::class)
class NativeSurfaceLabelIntegrationTest {
    @Test fun callbackUsesCurrentPaintInBothDirectionsAndRetainsLabel() = forEachBinding { context, bind ->
        for ((start, target) in listOf(Color.BLACK to Color.WHITE, Color.WHITE to Color.BLACK)) {
            val palette = NativeColorScheme.fallback(false).pageControls.copy(
                surface = target, raised = target, content = start, surfacePreset = null)
            val label = NativeText(context, palette).apply { text = "Retained label" }
            var clicks = 0
            label.setOnClickListener { clicks++ }
            var paints = 0
            // The callback can fire before the surface variable is assigned.
            val surface = NativeSurface(context, NativeSurfaceStyle(start)) { painted ->
                paints++
                bind(label, painted as NativeSurface, palette)
            }
            surface.addView(label)
            assertTrue("Constructor publishes paint", paints > 0)
            surface.applyStyle(NativeSurfaceStyle(target))
            val motion = surfaceMotion(surface)
            try {
                val samples = ColorTransition(intArrayOf(start))
                samples.retarget(intArrayOf(target), 0L)
                for (ms in 0..160 step 8) {
                    samples.frame(ms * 1_000_000L)
                    // Feed deterministic samples through the real paint publisher/callback.
                    // Requested style remains target, so sampling that target would fail at ms=0.
                    paints = 0
                    motion.snap(samples.color(0), Color.TRANSPARENT, Color.TRANSPARENT)
                    assertTrue("Paint must deliver the callback", paints > 0)
                    assertEquals(samples.color(0), surface.paintedFillColor)
                    assertEquals(textSurfaceForeground(surface.paintedTextSurface, palette.content), label.currentTextColor)
                    assertTrue("ms=$ms start=$start", ColorUtils.calculateContrast(
                        label.currentTextColor, surface.paintedFillColor) >= 4.5)
                    assertSame(label, surface.getChildAt(0))
                    assertEquals("Retained label", label.text.toString())
                }
                assertEquals(0, clicks)
                label.performClick()
                assertEquals(1, clicks)
            } finally { motion.close() }
        }
    }

    @Test fun paletteOnlyChangeNeedsExplicitRebindAndCallbackReadsLatestPalette() = forEachBinding { context, bind ->
        var palette = NativeColorScheme.fallback(false).pageControls.copy(
            content = Color.BLACK, surfacePreset = null)
        val label = NativeText(context, palette)
        val style = NativeSurfaceStyle(Color.WHITE)
        var paints = 0
        val surface = NativeSurface(context, style) { painted ->
            paints++
            bind(label, painted as NativeSurface, palette)
        }
        surface.addView(label)
        val retainedBackground = surface.background
        try {
            paints = 0
            palette = palette.copy(content = 0xff123456.toInt())
            NativeForeground.bindSurface(surface, palette, style)
            assertEquals("An unchanged surface need not notify", 0, paints)
            assertEquals(Color.BLACK, label.currentTextColor)
            bind(label, surface, palette)
            assertEquals("Readable custom content is preserved", palette.content, label.currentTextColor)
            palette = palette.copy(content = 0xff003300.toInt())
            surface.setNoiseIntensity(.25f)
            assertTrue(paints > 0)
            assertEquals("Callback reads caller-owned latest palette", palette.content, label.currentTextColor)
            assertSame(retainedBackground, surface.background)
            assertSame(label, surface.getChildAt(0))
        } finally { surfaceMotion(surface).close() }
    }

    @Test fun customForegroundPresetUsesPaintedGrainRangeAndCanRestoreFlatInk() = forEachBinding { context, bind ->
        val fill = 0xff757575.toInt()
        var palette = NativeColorScheme.fallback(false).pageControls.copy(
            content = Color.WHITE, surfacePreset = null)
        val label = NativeText(context, palette)
        val style = NativeSurfaceStyle(fill)
        val surface = NativeSurface(context, style) { painted ->
            bind(label, painted as NativeSurface, palette)
        }
        surface.addView(label)
        try {
            assertEquals(Color.WHITE, label.currentTextColor)
            palette = palette.copy(surfacePreset = NativeGrainPreset(backgroundGrain = .13f, foregroundGrain = 1f))
            NativeForeground.bindSurface(surface, palette, style)
            val expected = NoiseDrawable(fill).apply { setNoiseIntensity(1f) }.paintedTextSurface
            assertEquals(expected, surface.paintedTextSurface)
            assertNotEquals(textSurfaceRange(fill), expected)
            assertEquals(Color.BLACK, label.currentTextColor)
            fun worst(ink: Int) = minOf(ColorUtils.calculateContrast(ink, (expected ushr 32).toInt()),
                ColorUtils.calculateContrast(ink, expected.toInt()))
            assertTrue(worst(label.currentTextColor) >= worst(Color.WHITE))
            // This textured midpoint cannot guarantee 4.5; it must maximize achievable contrast.
            assertTrue(worst(label.currentTextColor) < 4.5)
            palette = palette.copy(surfacePreset = null)
            NativeForeground.bindSurface(surface, palette, style)
            bind(label, surface, palette)
            assertEquals(textSurfaceRange(fill), surface.paintedTextSurface)
            assertEquals(Color.WHITE, label.currentTextColor)
            assertSame(label, surface.getChildAt(0))
        } finally { surfaceMotion(surface).close() }
    }

    @Test fun bindingCancelsIndependentInkOwnerAndRejectsStaleFrame() = forEachBinding { context, bind ->
        val palette = NativeColorScheme.fallback(false).pageControls.copy(content = Color.WHITE, surfacePreset = null)
        val label = NativeText(context, palette)
        val surface = NativeSurface(context, NativeSurfaceStyle(Color.WHITE))
        label.animateTextColor(Color.RED)
        val oldInk = field(NativeText::class.java, label, "inkMotion") as ViewColorTransition
        try {
            // Prime a pending transition without attaching a window or posting a frame.
            val state = field(ViewColorTransition::class.java, oldInk, "state") as ColorTransition
            state.retarget(intArrayOf(Color.BLUE), 0L)
            assertTrue(state.running)
            bind(label, surface, palette)
            assertEquals(Color.BLACK, label.currentTextColor)
            assertNull(field(NativeText::class.java, label, "inkMotion"))
            assertNull(field(NativeText::class.java, label, "inkTargets"))
            assertEquals(true, field(ViewColorTransition::class.java, oldInk, "closed"))
            assertFalse(state.running)
            oldInk.doFrame(Long.MAX_VALUE)
            label.isEnabled = false
            label.isSelected = true
            assertEquals("Neither a stale frame nor old state colors may replace surface ink", Color.BLACK, label.currentTextColor)
        } finally {
            oldInk.close()
            surfaceMotion(surface).close()
        }
    }

    private fun surfaceMotion(surface: NativeSurface): ViewColorTransition =
        field(NativeSurface::class.java, surface, "colorMotion") as ViewColorTransition

    private fun field(type: Class<*>, owner: Any, name: String): Any? =
        type.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun forEachBinding(block: (Context, (NativeText, NativeSurface, ControlPalette) -> Unit) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            block(instrumentation.targetContext, NativeForeground::bindSurfaceLabel)
            block(instrumentation.targetContext, AllDocAppearance::bindSurfaceLabel)
        }
    }
}
