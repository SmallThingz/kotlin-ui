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
import smallthingz.ui.components.NativeTopBarInsets
import smallthingz.ui.components.NativeTopBarLayout

@RunWith(AndroidJUnit4::class)
class ReverbTopBarIntegrationTest {
    @Test fun defaultsToReverbMarkAndMirrorsOnlyTheOuterSlots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val theme = ReverbDesign.theme(context, dark = false)
            val bar = ReverbTopBar(context, theme)
            val slots = bar.getChildAt(0) as ViewGroup
            val mark = slots.getChildAt(0) as ReverbBrandMarkView
            val insets = NativeTopBarInsets(13, 25, 7)
            bar.bind(theme,
                start = ReverbTopBarActionContent(ReverbIcon.Back, "Back", {}),
                end = ReverbTopBarActionContent(ReverbIcon.Settings, "Settings", {}),
                insets = insets)
            val start = slots.getChildAt(1)
            val end = slots.getChildAt(2)
            assertEquals(NativeTopBarLayout.Slot.START, (start.layoutParams as NativeTopBarLayout.ActionLayoutParams).slot)
            assertEquals(NativeTopBarLayout.Slot.END, (end.layoutParams as NativeTopBarLayout.ActionLayoutParams).slot)
            val width = (600 * theme.density.density).toInt()
            fun layout(direction: Int) {
                bar.layoutDirection = direction
                slots.layoutDirection = direction
                bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                bar.layout(0, 0, width, bar.measuredHeight)
            }
            layout(View.LAYOUT_DIRECTION_LTR)
            val centerLeft = mark.left
            val startLeft = start.left
            val endLeft = end.left
            assertTrue(startLeft >= insets.left)
            assertTrue(startLeft < centerLeft && centerLeft < endLeft)
            assertTrue(end.right <= width - insets.right)
            layout(View.LAYOUT_DIRECTION_RTL)
            assertEquals(endLeft, start.left)
            assertEquals(startLeft, end.left)
            assertEquals(centerLeft, mark.left)
            assertSame(mark, slots.getChildAt(0))
        }
    }
}
