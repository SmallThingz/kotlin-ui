package smallthingz.ui.reverb

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ReverbProductHeaderIntegrationTest {
    @Test fun countsExtendHeaderWithoutResizingActionRowAndIdentityRetainsAnchor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val theme = ReverbDesign.theme(context, dark = false)
            val header = ReverbProductTopBar(context, theme)
            val row = header.getChildAt(0) as ReverbTopBar
            val strip = header.getChildAt(1) as ViewGroup
            val settings = ReverbHeaderAction("Settings", {})
            var click = ""
            fun bind(label: String, browser: ReverbHeaderAction?, counts: List<ReverbHeaderCount>) {
                header.bind(theme, ReverbProductIdentity.Monochrome(android.R.drawable.ic_menu_info_details),
                    label, { click = label }, browser, settings, counts = counts)
                val width = (320 * theme.density.density).roundToInt()
                header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                header.layout(0, 0, width, header.measuredHeight)
            }
            bind("About", ReverbHeaderAction("Browser", {}), emptyList())
            val anchor = checkNotNull(row.centerAnchor)
            val actionHeight = (58 * theme.density.density).roundToInt()
            assertEquals(actionHeight, row.measuredHeight)
            assertEquals(actionHeight, header.measuredHeight)
            assertEquals(View.GONE, strip.visibility)

            bind("About Control", null, listOf(ReverbHeaderCount("Completed", "1.2K", "Completed: 1,234")))
            assertSame(anchor, row.centerAnchor)
            assertEquals("About Control", anchor.contentDescription)
            anchor.performClick()
            assertEquals("About Control", click)
            assertEquals(actionHeight, row.measuredHeight)
            assertTrue(header.measuredHeight > actionHeight)
            assertEquals("Completed: 1,234", strip.getChildAt(0).contentDescription)
        }
    }
}
