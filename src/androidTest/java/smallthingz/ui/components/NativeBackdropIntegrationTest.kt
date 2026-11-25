package smallthingz.ui.components

import android.content.Intent
import android.os.Build
import android.util.TypedValue
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.ReverbDesign

@RunWith(AndroidJUnit4::class)
class NativeBackdropIntegrationTest {
    @Test fun reverbAppearanceUsesAmoledSurfacesAndKeepsMaterialAccentsAcrossResolvers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (sdk in listOf(30, 31, 34).filter { it < 31 || it <= Build.VERSION.SDK_INT }) {
            val base = NativeColorScheme.resolve(true, sdk, context::getColor)
            val dark = ReverbDesign.appearance.colorScheme.resolve(true, sdk, context::getColor)
            assertEquals(0xff000000.toInt(), dark.background)
            assertEquals(0xff000000.toInt(), dark.surface)
            assertEquals(0xff0a0a0a.toInt(), dark.field)
            assertEquals(0xff0e0e0e.toInt(), dark.raised)
            assertEquals(0xff262626.toInt(), dark.border)
            assertEquals(NativeDarkSurfacePreset.Amoled, dark.surfacePreset)
            assertEquals(base.primary, dark.primary)
            assertEquals(base.onPrimary, dark.onPrimary)
            assertEquals(base.error, dark.error)
            val lightBase = NativeColorScheme.resolve(false, sdk, context::getColor)
            val light = ReverbDesign.appearance.colorScheme.resolve(false, sdk, context::getColor)
            val backdrop = context.getColor(R.color.native_default_light_background)
            assertEquals(lightBase.copy(background = backdrop, surface = backdrop,
                surfacePreset = NativeGrainPreset(backgroundGrain = 1f, foregroundGrain = .5f)), light)
        }
    }

    @Test fun retainedPageTracksPresetGrainWithoutReplacingItsDrawable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = View(instrumentation.targetContext)
            val palette = NativeColorScheme.fallback(true).copy(surfacePreset =
                NativeDarkSurfacePreset.Amoled.copy(backgroundGrain = 0f))
            var changes = 0
            val style = NativeBackdropStyle.Default
            val binding = style.attachPage(view, palette) { changes++ }
            val drawable = view.background as NoiseDrawable
            assertEquals(textSurfaceRange(palette.surface), drawable.paintedTextSurface)
            val textured = palette.copy(surfacePreset = NativeDarkSurfacePreset.Amoled)
            style.bindPage(binding, textured)
            assertSame(drawable, view.background)
            assertNotEquals(textSurfaceRange(palette.surface), drawable.paintedTextSurface)
            assertEquals(style.pageBackground(textured).paintedTextSurface, drawable.paintedTextSurface)
            assertEquals(1, changes)
            style.bindPage(binding, textured)
            assertEquals(1, changes)
            style.bindPage(binding, palette)
            assertEquals(textSurfaceRange(palette.surface), drawable.paintedTextSurface)
            assertEquals(2, changes)
        }
    }

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
