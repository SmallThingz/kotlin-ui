package smallthingz.ui.injector

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeBadge
import smallthingz.ui.components.NativeRangeSlider
import smallthingz.ui.components.NativeSlider

@RunWith(RobolectricTestRunner::class)
@Config(application = InjectCompositeHostApplication::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InjectLabeledSliderLayoutTest {
    @Test fun bindingReadoutKeepsSliderFocusAndDoesNotPublishAnEdit() = withActivity { activity ->
        val ui = InjectAppearance(activity, InjectDesign.theme(activity, false))
        val slider = NativeSlider(activity, ui.scheme.settingsControls).apply { max = 100; progress = 40 }
        var commits = 0
        slider.onValueCommitted = { commits++ }
        val content = InjectLabeledSliderLayout(activity, ui, "Volume", slider)
        activity.setContentView(content)
        layout(content)
        assertTrue(slider.requestFocus())
        content.bindReadout("40%")
        content.bindReadout("Pending value")
        layout(content)
        assertTrue(slider.hasFocus())
        assertSame(slider, content.getChildAt(1))
        assertEquals(40, slider.progress)
        assertEquals(0, commits)
        assertEquals("Volume", slider.contentDescription)
        assertEquals("Pending value", ViewCompat.getStateDescription(slider))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            content.getChildAt(0).importantForAccessibility)
    }

    @Test fun titleReadoutStacksAtLargeFontWithoutReparentingTheControlInRtl() = withActivity { activity ->
        for (font in listOf(1f, 1.4f, 2f)) for (rtl in listOf(false, true)) {
            val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                fontScale = font
                densityDpi = 160
            })
            val ui = InjectAppearance(context, InjectDesign.theme(context, false))
            val slider = NativeSlider(context, ui.scheme.settingsControls)
            val content = InjectLabeledSliderLayout(context, ui, "Long slider عنوان", slider).apply {
                layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                bindReadout("100%")
            }
            // Resolve inherited direction in the same RTL-capable, attached host as the app.
            // Do not force the production header/readout direction to make this assertion pass.
            activity.setContentView(content)
            assertTrue(content.isAttachedToWindow)
            layout(content)
            val header = content.getChildAt(0) as LinearLayout
            val title = header.getChildAt(0)
            val readout = header.getChildAt(1) as NativeBadge
            val direction = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            assertEquals("Requested direction resolves on the outer layout", direction, content.layoutDirection)
            assertEquals("Header inherits the host direction", direction, header.layoutDirection)
            assertEquals("Readout inherits the header direction", direction, readout.layoutDirection)
            assertEquals("Slider retains inherited direction", direction, slider.layoutDirection)
            assertEquals(14, content.paddingStart)
            assertEquals(10, content.paddingTop)
            assertSame(slider, content.getChildAt(1))
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, header.importantForAccessibility)
            if (font >= 1.4f) {
                assertEquals(LinearLayout.VERTICAL, header.orientation)
                assertEquals(title.bottom + 4, readout.top)
                assertEquals(header.width, title.width)
            } else {
                assertEquals(LinearLayout.HORIZONTAL, header.orientation)
                if (rtl) assertEquals(readout.right + 8, title.left)
                else assertEquals(title.right + 8, readout.left)
            }
        }
    }

    @Test fun hiddenReadoutStillSpeaksSingleValueWhileRangeOnlyExposesItsTwoThumbs() = withActivity { activity ->
        val ui = InjectAppearance(activity, InjectDesign.theme(activity, false))
        val slider = NativeSlider(activity, ui.scheme.settingsControls)
        val single = InjectLabeledSliderLayout(activity, ui, "Volume", slider, showValue = false)
        single.bindReadout("50%")
        val header = single.getChildAt(0) as LinearLayout
        assertEquals(View.GONE, header.getChildAt(1).visibility)
        assertEquals("50%", ViewCompat.getStateDescription(slider))

        val range = NativeRangeSlider(activity, ui.scheme.settingsControls, 0, 100, 5, 20, 80,
            InjectLabeledSliderRecipe.endpointLabel("Brightness", "Minimum", " % "),
            InjectLabeledSliderRecipe.endpointLabel("Brightness", "Brightness", " % "))
        val content = InjectLabeledSliderLayout(activity, ui, "Brightness", range, readoutSemanticsTarget = null)
        activity.setContentView(content)
        layout(content)
        var commits = 0
        range.onRangeCommitted = { _, _ -> commits++ }
        val low = range.getChildAt(0)
        val high = range.getChildAt(1)
        assertTrue(low.requestFocus())
        content.bindReadout("20–80%")
        assertTrue(low.hasFocus())
        assertNull(range.contentDescription)
        assertNull(ViewCompat.getStateDescription(range))
        assertEquals("Brightness, Minimum, %", low.contentDescription)
        assertEquals("Brightness, %", high.contentDescription)
        assertEquals(20f, low.createAccessibilityNodeInfo().rangeInfo.current, 0f)
        assertEquals(80f, high.createAccessibilityNodeInfo().rangeInfo.current, 0f)
        assertEquals(0, commits)
        assertTrue(low.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(1, commits)
        assertEquals(25, range.lower)
        assertEquals(80, range.upper)
    }

    private fun withActivity(block: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        try { block(controller.get()) }
        finally { controller.pause().stop().destroy() }
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
