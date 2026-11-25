package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R

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
}
