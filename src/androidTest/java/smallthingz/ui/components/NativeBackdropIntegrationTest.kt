package smallthingz.ui.components

import android.content.Intent
import android.util.TypedValue
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable

@RunWith(AndroidJUnit4::class)
class NativeBackdropIntegrationTest {
    @Test fun pageAndWindowGrainCanBeConfiguredIndependently() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val appearance = NativeUiAppearance.Default
                val palette = NativeColorScheme.fallback(false)
                val page = View(activity)
                var repaints = 0
                val pageBinding = appearance.backdrops.attachPage(page, palette) { repaints++ }
                val original = page.background as NoiseDrawable
                assertNotEquals(textSurfaceRange(palette.surface), original.paintedTextSurface)

                val solidPage = appearance.copy(backdrops = appearance.backdrops.copy(pageGrainSeed = null))
                solidPage.backdrops.bindPage(pageBinding, palette)
                solidPage.backdrops.bindPage(pageBinding, palette)
                assertSame(original, page.background)
                assertEquals(textSurfaceRange(palette.surface), original.paintedTextSurface)
                assertEquals(1, repaints)

                val value = TypedValue()
                check(activity.theme.resolveAttribute(android.R.attr.colorBackground, value, true))
                val windowColor = if (value.resourceId != 0) activity.getColor(value.resourceId) else value.data
                val defaultWindow = requireNotNull(appearance.backdrops.windowBackground(activity))
                val grainedWindow = requireNotNull(solidPage.backdrops.windowBackground(activity))
                assertNotEquals(textSurfaceRange(windowColor), defaultWindow.paintedTextSurface)
                assertEquals(defaultWindow.paintedTextSurface, grainedWindow.paintedTextSurface)

                val solidWindow = appearance.copy(backdrops = appearance.backdrops.copy(windowGrainSeed = null))
                val window = requireNotNull(solidWindow.backdrops.windowBackground(activity))
                assertEquals(textSurfaceRange(windowColor), window.paintedTextSurface)
                assertEquals(appearance.backdrops.pageGrainSeed, solidWindow.backdrops.pageGrainSeed)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
