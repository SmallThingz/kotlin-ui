package smallthingz.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.alldoc.AllDocHomeGroupGrain
import smallthingz.ui.controls.ControlPalette

/** No Activity or shader readiness required: first enablement must schedule the owner's draw. */
@RunWith(AndroidJUnit4::class)
class NativeManualForegroundGrainIntegrationTest {
    @Test fun commonAndLegacyManualGrainScheduleFirstEnablementAndTrackOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val common = NativeForegroundGrain()
            checkOwner(instrumentation.targetContext, common::bind, common::draw)
            val legacy = AllDocHomeGroupGrain()
            checkOwner(instrumentation.targetContext, legacy::bind, legacy::draw)
        }
    }

    private fun checkOwner(context: Context, bind: (ControlPalette?) -> Unit,
                           draw: (Canvas, View, RectF, Float, Int) -> Unit) {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val bounds = RectF(0f, 0f, 40f, 40f)
            val first = TrackingView(context)
            val second = TrackingView(context)
            val noisy = NativeColorScheme.fallback(false).pageControls.copy(
                surfacePreset = NativeGrainPreset(backgroundGrain = 0f, foregroundGrain = .5f))
            bind(null)
            draw(canvas, first, bounds, 8f, Color.BLACK)
            first.invalidations = 0
            bind(noisy)
            assertTrue("A prior flat draw must register the owner for first enablement", first.invalidations > 0)
            first.invalidations = 0
            bind(noisy)
            assertEquals("An unchanged preset must not schedule another frame", 0, first.invalidations)
            bind(null)
            draw(canvas, second, bounds, 8f, Color.BLACK)
            first.invalidations = 0
            second.invalidations = 0
            bind(noisy)
            assertEquals("A replaced owner must not be invalidated", 0, first.invalidations)
            assertTrue("The current flat owner must receive the next enablement", second.invalidations > 0)
        } finally { bitmap.recycle() }
    }

    private class TrackingView(context: Context) : View(context) {
        var invalidations = 0
        override fun invalidate() { invalidations++; super.invalidate() }
    }
}
