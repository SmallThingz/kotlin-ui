package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.textSurfaceRange

@RunWith(AndroidJUnit4::class)
class NativeSegmentedPresentationFactoryIntegrationTest {
    @Test fun publicFactoryRetainsCustomSurfaceAndReportsPaletteAndGrainChangesWithoutSelectionRequests() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        main {
            val base = instrumentation.targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 160
                fontScale = 1f
            })
            assertEquals(1f, context.resources.displayMetrics.density, 0f)
            val settings = NativeUiAppearance.Default.settingsSelectors
            val recipe = settings.copy(segmented = settings.segmented.copy(surfaceRadiusDp = 4f))
            var notifications = 0
            var notifiedSurface: View? = null
            // Compose using the same public interface available to an external consumer.
            val presentation = object : NativeSegmentedPresentation by recipe.createSegmentedPresentation(context, {
                notifications++
                notifiedSurface = it
            }) {}
            val surface = presentation.surface
            val light = NativeColorScheme.fallback(false).settingsControls.copy(surface = Color.WHITE, outline = Color.WHITE)
            val flatPreset = NativeDarkSurfacePreset.Amoled.copy(foregroundLightness = 0f, foregroundGrain = 0f)
            val flat = flatPreset.colorScheme().resolve(true, 28) { error("SDK 28 uses fallback colors") }.settingsControls
            val grain = flatPreset.copy(foregroundGrain = .5f).colorScheme()
                .resolve(true, 28) { error("SDK 28 uses fallback colors") }.settingsControls
            val choices = listOf(NativeChoice("one", "One"), NativeChoice("two", "Two"))
            var requests = 0
            val control = NativeSegmentedControl(context, light, recipe.segmented, presentation = presentation)
            control.onSelectionRequest = { requests++; true }
            try {
                control.bind(choices, "one", light)
                control.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                control.layout(0, 0, control.measuredWidth, control.measuredHeight)
                val group = surface as NativeGroupLayout
                assertTrue("The 4dp corner includes this point; the default capsule excludes it",
                    group.containsVisualPoint(2f, 2f))
                assertFalse("The custom corner remains rounded", group.containsVisualPoint(0f, 0f))
                assertTrue(notifications > 0)
                assertSame(surface, notifiedSurface)
                assertEquals(Color.WHITE, presentation.paintedSurfaceColor)
                assertEquals(textSurfaceRange(Color.WHITE), presentation.paintedTextSurface)
                assertEquals(Color.WHITE, cornerPixel(surface))
                val options = descendants(control).filterIsInstance<NativeSegmentedChoice>().single()
                val retainedOne = requireNotNull(options.choiceView("one"))
                val retainedTwo = requireNotNull(options.choiceView("two"))
                var before = notifications

                control.bind(choices, "one", light)
                assertEquals("Identical binding keeps the surface quiet", before, notifications)
                control.bind(choices, "two", flat)
                assertTrue("Palette changes notify the supplied callback", notifications > before)
                assertSame(surface, notifiedSurface)
                assertEquals(Color.BLACK, presentation.paintedSurfaceColor)
                assertEquals(textSurfaceRange(Color.BLACK), presentation.paintedTextSurface)
                assertEquals(Color.BLACK, cornerPixel(surface))
                assertEquals("two", options.selectedId)
                before = notifications

                control.bind(choices, "two", grain)
                assertEquals("Grain-only rebinding publishes the changed underlay once", before + 1, notifications)
                assertSame(surface, notifiedSurface)
                assertEquals(Color.BLACK, presentation.paintedSurfaceColor)
                // Half-strength grain over black has the existing public paint range 0..9.
                assertEquals(textSurfaceRange(Color.BLACK, 0xff090909.toInt()), presentation.paintedTextSurface)
                before = notifications
                control.bind(choices, "two", grain)
                assertEquals(before, notifications)
                control.bind(choices, "one", flat)
                assertEquals(before + 1, notifications)
                assertEquals(textSurfaceRange(Color.BLACK), presentation.paintedTextSurface)
                assertEquals("one", options.selectedId)

                before = notifications
                control.applyPalette(grain)
                assertEquals("Palette-only rebinding keeps the current selection", "one", options.selectedId)
                assertEquals(textSurfaceRange(Color.BLACK, 0xff090909.toInt()), presentation.paintedTextSurface)
                assertEquals(before + 1, notifications)
                control.applyPalette(grain)
                assertEquals("Equal direct palettes keep the surface quiet", before + 1, notifications)

                assertSame(surface, presentation.surface)
                assertSame(surface, control.getChildAt(0))
                assertSame(retainedOne, options.choiceView("one"))
                assertSame(retainedTwo, options.choiceView("two"))
                assertEquals("Construction, rebind and drawing preserve controlled selection", 0, requests)
            } finally {
                control.close()
            }
        }
    }

    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { action() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    private fun cornerPixel(surface: View): Int {
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        return try {
            surface.draw(Canvas(bitmap))
            bitmap.getPixel(2, 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
