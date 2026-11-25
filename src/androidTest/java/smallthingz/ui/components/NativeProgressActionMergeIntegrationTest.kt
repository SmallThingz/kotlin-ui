package smallthingz.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.settings.ReverbSettingsActionView
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(AndroidJUnit4::class)
class NativeProgressActionMergeIntegrationTest {
    @Test fun actualSettingsActionJoinsRetainedGeometryWithoutChangingStandalonePaintOrProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        try {
            for (progress in listOf(false, true)) for (enabled in listOf(false, true)) {
                lateinit var grouped: ReverbSettingsActionView
                lateinit var adjacent: ReverbSettingsActionView
                lateinit var standalone: ReverbSettingsActionView
                lateinit var originalVisual: NativeMaterialButtonView
                lateinit var originalEndpoint: Any
                lateinit var originalRole: View
                var groupedClicks = 0
                var standaloneClicks = 0
                try {
                    main {
                        val root = FrameLayout(activity)
                        val defaults = NativeUiAppearance.Default
                        val appearance = defaults.copy(calibration = defaults.calibration.copy(actionMinTargetDp = 72f))
                        val environment = ReverbEnvironment.withTestAppearance(Density(1f), createFontFamilyResolver(activity),
                            NativeColorScheme.fallback(false), appearance)
                        grouped = ReverbSettingsActionView(activity, environment, { root }, progressSurface = progress,
                            action = { groupedClicks++ })
                        adjacent = ReverbSettingsActionView(activity, environment, { root }, action = {})
                        standalone = ReverbSettingsActionView(activity, environment, { root }, progressSurface = progress,
                            action = { standaloneClicks++ })
                        for (action in listOf(grouped, adjacent, standalone)) {
                            action.bind("Action", enabled, environment)
                            if (progress) action.bindProgress(.5f, enabled)
                        }
                        originalVisual = grouped.getChildAt(0) as NativeMaterialButtonView
                        originalEndpoint = originalVisual.nativeClickEndpoint
                        originalRole = originalVisual.mergedRowAccessibility.roleView
                        val row = NativeMergedActionLayout(activity, listOf(grouped, adjacent), environment.palette)
                        assertEquals(72, grouped.actionMinimumWidthPx)
                        root.addView(LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(row, LinearLayout.LayoutParams(240, 72))
                            addView(standalone, LinearLayout.LayoutParams(120, 72))
                        })
                        activity.setContentView(root)
                    }
                    instrumentation.waitForIdleSync()
                    main {
                        val visual = grouped.getChildAt(0) as NativeMaterialButtonView
                        val separate = standalone.getChildAt(0) as NativeMaterialButtonView
                        assertSame(originalVisual, visual)
                        assertSame(originalEndpoint, visual.nativeClickEndpoint)
                        assertSame(originalRole, visual.mergedRowAccessibility.roleView)
                        val outline = Outline()
                        visual.outlineProvider.getOutline(visual, outline)
                        assertEquals(0f, outline.radius, 0f)
                        separate.outlineProvider.getOutline(separate, outline)
                        assertTrue(outline.radius > 0f)
                        assertTrue(visual.containsPointerLayer(visual.width - .5f, .5f))
                        assertFalse(separate.containsPointerLayer(separate.width - .5f, .5f))
                        assertEquals(enabled, visual.isEnabled)
                        assertEquals(enabled, separate.isEnabled)
                        assertEquals(ViewCompat.getStateDescription(separate), ViewCompat.getStateDescription(visual))
                        if (progress && enabled) assertEquals("50%", ViewCompat.getStateDescription(visual))
                        // Equal interior samples cover raw disabled fills/ink and determinate overlay paint.
                        assertEquals(samples(separate), samples(visual))
                        val originalLabel = separate.label.getText()
                        assertEquals(originalLabel, visual.label.getText())
                        tapCorner(visual)
                        tapCorner(separate)
                        assertEquals(if (enabled) 1 else 0, groupedClicks)
                        assertEquals(0, standaloneClicks)
                        assertSame(originalVisual, grouped.getChildAt(0))
                    }
                } finally { main { grouped.close(); adjacent.close(); standalone.close() } }
            }
        } finally { main { activity.finish() } }
    }

    private fun samples(view: View): List<Int> {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            listOf(bitmap.getPixel(view.width / 4, view.height - 8),
                bitmap.getPixel(view.width * 3 / 4, view.height - 8))
        } finally { bitmap.recycle() }
    }
    private fun tapCorner(view: View) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = view.width - .5f; y = .5f; pressure = 1f; size = 1f })
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 1 else 0,
                action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
}
