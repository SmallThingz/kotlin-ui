package smallthingz.ui.zfm

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.reverb.ReverbDesign
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZfmBrowserHeaderLayoutHostTest {
    @Test fun goneEdgeReleasesItsSlotAndGapWhileInvisibleKeepsThem() {
        val application = RuntimeEnvironment.getApplication()
        val info = application.applicationInfo
        val oldFlags = info.flags
        info.flags = oldFlags or ApplicationInfo.FLAG_SUPPORTS_RTL
        try {
            val context = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
                densityDpi = 160
                fontScale = 1f
            })
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                checkEdgeVisibility(context, direction, hideNavigation = false)
                checkEdgeVisibility(context, direction, hideNavigation = true)
            }
        } finally {
            info.flags = oldFlags
        }
    }

    private fun checkEdgeVisibility(context: Context, direction: Int, hideNavigation: Boolean) {
        val theme = ZfmTheme.resolve(context, dark = false)
        val navigation = ActionSlot(context)
        val actions = ActionSlot(context)
        val location = ZfmLocationControl(context, theme, ColorDrawable(Color.BLACK)).apply {
            bindLocation("Downloads", "Internal storage", "Downloads in internal storage", enabled = true) {}
        }
        val header = ZfmBrowserHeaderLayout(context, theme, navigation, location, actions)
        header.layoutDirection = direction
        val hiddenSlot = if (hideNavigation) navigation else actions
        val style = ReverbDesign.appearance.toolbars
        val edge = style.compactEdgeInsetDp.roundToInt()
        val gap = style.centeredPaddingDp.roundToInt()
        val inlineWidth = location.minimumReadableWidth
        // Leave exactly enough room for one action and one gap.
        val width = inlineWidth + 2 * edge + navigation.preferredWidth + gap
        fun place() {
            header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            header.layout(0, 0, header.measuredWidth, header.measuredHeight)
        }
        fun logicalStart(view: View) = if (direction == View.LAYOUT_DIRECTION_RTL) width - view.right else view.left

        place()
        assertEquals(direction, header.layoutDirection)
        val stackedTop = location.top
        val stackedHeight = header.height
        assertTrue(stackedTop >= maxOf(navigation.bottom, actions.bottom))
        hiddenSlot.visibility = View.INVISIBLE
        place()
        assertEquals(stackedTop, location.top)
        assertEquals(stackedHeight, header.height)

        val measuresBeforeGone = hiddenSlot.measures
        hiddenSlot.visibility = View.GONE
        place()
        assertEquals(measuresBeforeGone, hiddenSlot.measures)
        assertEquals(inlineWidth, location.width)
        assertTrue(location.top < stackedTop)
        assertTrue(header.height < stackedHeight)
        val measuredGap = if (hideNavigation) logicalStart(actions) - logicalStart(location) - location.width
            else logicalStart(location) - logicalStart(navigation) - navigation.width
        assertEquals(gap, measuredGap)

        hiddenSlot.preferredWidth = 56
        hiddenSlot.visibility = View.VISIBLE
        place()
        assertTrue(hiddenSlot.measures > measuresBeforeGone)
        assertEquals(56, hiddenSlot.width)
        assertEquals(stackedTop, location.top)
        assertEquals(stackedHeight, header.height)
        assertSame(header, location.parent)
        assertSame(header, hiddenSlot.parent)
    }

    private class ActionSlot(context: Context) : View(context) {
        var preferredWidth = 48
            set(value) {
                field = value
                requestLayout()
            }
        var measures = 0
            private set

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measures++
            setMeasuredDimension(resolveSize(preferredWidth, widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        }
    }
}
