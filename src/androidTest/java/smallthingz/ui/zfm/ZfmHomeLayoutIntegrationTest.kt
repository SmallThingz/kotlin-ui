package smallthingz.ui.zfm

import android.content.res.Configuration
import android.view.View
import android.widget.LinearLayout
import android.widget.Space
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZfmHomeLayoutIntegrationTest {
    @Test fun bottomDirectionUsesPaddedViewportAndRelayoutPreservesTheUsersEdge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 160
                fontScale = 1f
            })
            val home = ZfmHomeLayout(context, ZfmTheme.resolve(context, dark = false),
                Space(context), settingsIcon = null, settingsLabel = "Settings", onSettings = {})
            val scroll = home.scroll
            scroll.setPadding(0, 8, 0, 16)

            fun growContent(height: Int) {
                home.content.addView(Space(context),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
            }
            fun layoutHome() {
                val spec = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
                home.measure(spec, spec)
                home.layout(0, 0, home.measuredWidth, home.measuredHeight)
            }

            growContent(600)
            home.bindDirection(bottomUp = false)
            layoutHome()
            scroll.viewTreeObserver.dispatchOnPreDraw()
            val initialBottom = home.content.height - scroll.height + 24
            assertTrue(scroll.height > 0 && initialBottom > 24)

            home.bindDirection(bottomUp = true)
            layoutHome()
            assertEquals("Direction change is still waiting for pre-draw", 0, scroll.scrollY)
            scroll.viewTreeObserver.dispatchOnPreDraw()
            assertEquals("Pre-draw includes both vertical padding edges", initialBottom, scroll.scrollY)

            growContent(60)
            layoutHome()
            assertEquals("Late content keeps the bottom pinned", initialBottom + 60, scroll.scrollY)

            val manualOffset = initialBottom / 2
            scroll.scrollTo(0, manualOffset)
            growContent(50)
            layoutHome()
            assertEquals("Late content leaves a user who scrolled up in place", manualOffset, scroll.scrollY)
        }
    }
}
