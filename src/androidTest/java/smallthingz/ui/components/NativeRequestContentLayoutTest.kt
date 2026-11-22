package smallthingz.ui.components

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRequestContentLayoutTest {
    @Test fun longBodyScrollsWithoutMovingTheFooterAndCloseIsSilent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                fontScale = 1.6f
            })
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
            var actions = 0
            var cleanups = 0
            val header = View(context)
            val action = View(context).apply {
                minimumHeight = dp(48)
                setOnClickListener { actions++ }
            }
            val content = NativeRequestContent(context, NativeColorScheme.fallback(false),
                "Review request", "Long request body. ".repeat(160), action, header = header,
                maxHeightDp = 240, dispose = { cleanups++ })
            val root = content.view as ViewGroup
            val width = dp(320)
            val height = dp(200)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)

            val body = root.getChildAt(0) as ScrollView
            val footer = root.getChildAt(1) as ScrollView
            val footerTop = footer.top
            assertEquals(width, root.measuredWidth)
            assertTrue(root.measuredHeight <= height)
            assertTrue(body.canScrollVertically(1))
            assertTrue(action.height >= dp(48))
            body.scrollTo(0, dp(50))
            assertTrue(body.scrollY > 0)
            assertEquals(footerTop, footer.top)
            assertEquals(0, footer.scrollY)
            assertEquals(0, actions)

            content.close()
            content.close()
            assertEquals(1, cleanups)
            assertEquals(0, actions)
            assertNull(header.parent)
            assertNull(action.parent)
            assertEquals(0, root.childCount)
        }
    }
}
