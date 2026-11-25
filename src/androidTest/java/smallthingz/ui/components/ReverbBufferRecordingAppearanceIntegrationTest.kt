package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
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
                    val palette = NativeDarkSurfacePreset.Amoled.colorScheme().resolve(dark, 30) { error("No resources") }
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
                        assertEquals(if (known) 31 else 0, Color.alpha(pixel))
                        if (known) {
                            assertTrue(kotlin.math.abs(Color.red(pixel) - Color.red(palette.primary)) <= 5)
                            assertTrue(kotlin.math.abs(Color.green(pixel) - Color.green(palette.primary)) <= 5)
                            assertTrue(kotlin.math.abs(Color.blue(pixel) - Color.blue(palette.primary)) <= 5)
                        }
                    } finally { bitmap.recycle() }
                    // Rebinding the viewed slot never changes the actual capture destination.
                    controls.bind(state.copy(selectedBuffer = 1 - viewed), ReverbHomeFlipFrame(),
                        ReverbHomeActions({}, {}, {}, {}, {}, {}, {}))
                    assertTrue(tabs[1 - viewed].isSelected)
                    assertFalse(tabs[viewed].isSelected)
                }
            } finally { runtime.close() }
        }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
