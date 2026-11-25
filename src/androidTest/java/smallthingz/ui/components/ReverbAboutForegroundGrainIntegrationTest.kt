package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.ReverbAboutContentView
import smallthingz.ui.reverb.ReverbAboutDetails
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Exercises About's actual retained panel, including initial paint and both theme rebinds. */
@RunWith(AndroidJUnit4::class)
class ReverbAboutForegroundGrainIntegrationTest {
    @Test fun lightAmoledLightRetainsPanelAndRestoresFlatPaint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val resolver = createFontFamilyResolver(context)
            val light = NativeColorScheme.fallback(false)
            val dark = NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 30) { error("No resources") }
            fun theme(palette: NativeColorScheme) = ReverbDesign.windowTheme(Density(1f), resolver, palette)
            val runtime = NativeModalWindowRuntime(context)
            val details = ReverbAboutDetails("Reverb", "1.0", "GitHub", "Repository", "Unavailable")
            val about = ReverbAboutContentView(context, theme(light), details, {}, { false }, runtime, 0)
            try {
                val panel = descendants(about).filterIsInstance<NativeFloatingPanelSurface>().single()
                val clip = panel.clipHost
                val handle = descendants(about).filterIsInstance<NativeSheetDragHandle>().single()
                for ((index, palette) in listOf(light, dark, light).withIndex()) {
                    if (index != 0) about.bind(theme(palette), details)
                    assertSame(panel, descendants(about).filterIsInstance<NativeFloatingPanelSurface>().single())
                    assertSame(panel, about.predictiveMotionTarget)
                    assertSame(clip, panel.clipHost)
                    assertSame(handle, descendants(about).filterIsInstance<NativeSheetDragHandle>().single())
                    val fill = ReverbDesign.appearance.modals.floating.copy(surfaceElevationDp = 0f)
                        .surfaceColor(palette.copy(surfaceContainerHigh = ReverbDesign.modalSurface(palette)))
                    val expected = if (palette.surfacePreset == null) textSurfaceRange(fill)
                    else NoiseDrawable(fill).apply {
                        setNoiseIntensity(palette.surfacePreset.foregroundGrain)
                    }.paintedTextSurface
                    assertEquals("Actual panel underlay at step $index", expected, panel.paintedTextSurface)
                    about.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
                    about.layout(0, 0, about.measuredWidth, about.measuredHeight)
                    if (palette.surfacePreset == null) {
                        val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                        try {
                            panel.draw(Canvas(bitmap))
                            // The top-origin panel's inset above its content is painted by the panel itself.
                            for (y in 2..7) for (x in 2 until panel.width - 2) {
                                assertEquals("Flat light panel pixel ($x,$y), step $index", fill, bitmap.getPixel(x, y))
                            }
                        } finally { bitmap.recycle() }
                    }
                }
            } finally {
                try { about.dispose() } finally { runtime.close() }
            }
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
