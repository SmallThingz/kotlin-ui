package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.zfm.ZfmTheme

@RunWith(AndroidJUnit4::class)
class NativeDefaultBackdropTest {
    @Test fun hostWindowAndDefaultPaletteShareTheQualifiedBackdrop() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) {
            val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val context = target.createConfigurationContext(Configuration().apply { uiMode = night })
            val host = ContextThemeWrapper(context, R.style.ReverbHostTheme)
            val attributes = host.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            val windowColor = try {
                (attributes.getDrawable(0) as ColorDrawable).color
            } finally {
                attributes.recycle()
            }
            val palette = NativeUiAppearance.Default.resolveColorScheme(host, dark)
            assertEquals(windowColor, palette.background)
            assertEquals(windowColor, palette.surface)
        }
    }

    @Test fun defaultAdaptersResolveTheSharedAppearanceInBothModes() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val night = Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
        val context = target.createConfigurationContext(night)
        val appearance = NativeUiAppearance.Default
        // With night resources held fixed, an explicit Light request must still select Light.
        for (dark in listOf(false, true)) {
            val expected = appearance.resolveColorScheme(context, dark)
            val common = ControlTheme.resolve(context, dark)
            val zfm = ZfmTheme.resolve(context, dark)
            val reverb = ReverbEnvironment(context, dark)
            val adapters = listOf(
                Triple("Common controls", common.appearance, common.colors),
                Triple("ZFM", zfm.appearance, zfm.colors),
                Triple("Reverb", reverb.appearance, reverb.palette),
            )
            for ((name, adapterAppearance, palette) in adapters) {
                assertSame("$name resolver, dark=$dark", appearance.colorScheme, adapterAppearance.colorScheme)
                assertEquals("$name palette, dark=$dark", expected, palette)
                assertSame("$name surface preset, dark=$dark", expected.surfacePreset, palette.surfacePreset)
            }
        }
    }
}
