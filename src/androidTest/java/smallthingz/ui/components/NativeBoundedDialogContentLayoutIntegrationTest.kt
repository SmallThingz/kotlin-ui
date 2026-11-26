package smallthingz.ui.components

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Real Android measurement only; no dialog is shown and no window/input authorization is exercised. */
@RunWith(AndroidJUnit4::class)
class NativeBoundedDialogContentLayoutIntegrationTest {
    @Test fun extractedPermissionGeometryKeepsHeaderAndFooterAheadOfLongBody() = onMain {
        for (rtl in listOf(false, true)) {
            val context = context()
            val header = NaturalView(context, 40, 20)
            val body = NaturalView(context, 152, 80)
            val footer = NaturalView(context, 152, 48)
            val panel = NativeBoundedDialogContentLayout(context, body, footer, 24,
                availableHeight = { error("Bounded measurement must use its supplied height") }, header = header)
            panel.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            layout(panel, 200, 120)
            val bodyViewport = body.parent as ScrollView
            val footerViewport = footer.parent as ScrollView
            // Fixed reference coordinates from the existing permission layout, not a second algorithm.
            assertEquals(Rect(80, 15, 120, 35), bounds(header))
            assertEquals(Rect(24, 35, 176, 72), bounds(bodyViewport))
            assertEquals(Rect(24, 72, 176, 120), bounds(footerViewport))
            assertEquals(80, body.height)
            assertEquals(48, footer.height)
            bodyViewport.scrollTo(0, Int.MAX_VALUE)
            assertEquals(body.height, bodyViewport.scrollY + bodyViewport.height)
            assertEquals(Rect(24, 72, 176, 120), bounds(footerViewport))

            body.naturalHeight = 32
            body.requestLayout()
            layout(panel, 200, 240)
            assertEquals(124, panel.height)
            assertEquals(Rect(80, 24, 120, 44), bounds(header))
            assertEquals(Rect(24, 44, 176, 76), bounds(bodyViewport))
            assertEquals(Rect(24, 76, 176, 124), bounds(footerViewport))
            assertSame(bodyViewport, body.parent)
            assertSame(footerViewport, footer.parent)
        }
    }

    @Test fun oversizedFooterScrollsAtNaturalHeightAndRecoversWithoutReparenting() = onMain {
        val context = context()
        val body = NaturalView(context, 152, 100)
        val footer = NaturalView(context, 152, 200)
        val panel = NativeBoundedDialogContentLayout(context, body, footer, 24, { 400 })
        layout(panel, 200, 120)
        val bodyViewport = body.parent as ScrollView
        val footerViewport = footer.parent as ScrollView
        assertEquals(120, panel.height)
        assertEquals(24, bodyViewport.height)
        assertEquals(81, footerViewport.height)
        assertEquals(200, footer.height)
        footerViewport.scrollTo(0, Int.MAX_VALUE)
        assertEquals(footer.height, footerViewport.scrollY + footerViewport.height)

        layout(panel, 200, 400)
        assertEquals(324, panel.height)
        assertEquals(200, footerViewport.height)
        assertEquals(0, footerViewport.scrollY)
        assertSame(bodyViewport, body.parent)
        assertSame(footerViewport, footer.parent)
        assertSame(body, bodyViewport.getChildAt(0))
        assertSame(footer, footerViewport.getChildAt(0))
    }

    @Test fun shortContentUsesNaturalHeightAndUnspecifiedHeightUsesCurrentHostBudget() = onMain {
        val context = context()
        var available = 300
        var fallbackReads = 0
        val body = NaturalView(context, 152, 20)
        val footer = NaturalView(context, 152, 48)
        val panel = NativeBoundedDialogContentLayout(context, body, footer, 24,
            { fallbackReads++; available })
        layout(panel, 200, 640)
        assertEquals(92, panel.height)
        assertEquals(0, fallbackReads)
        layout(panel, 200, 0, View.MeasureSpec.UNSPECIFIED)
        assertEquals(92, panel.height)
        assertEquals(1, fallbackReads)
        available = 80
        panel.requestLayout()
        layout(panel, 200, 0, View.MeasureSpec.UNSPECIFIED)
        assertEquals(77, panel.height)
        assertEquals(2, fallbackReads)
        assertEquals(48, footer.height)
        available = 300
        panel.requestLayout()
        layout(panel, 200, 0, View.MeasureSpec.UNSPECIFIED)
        assertEquals(92, panel.height)
        assertEquals(3, fallbackReads)
    }

    @Test fun nonActivityDialogContextKeepsSharedSingleActionReachableWithLargeTextAndRtl() = onMain {
        for (scale in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            val appearance = ReverbDesign.appearance
            val dialog = Dialog(context(scale), appearance.modals.permissionDialog.windowTheme)
            val context = dialog.context
            val palette = appearance.resolveColorScheme(context, false)
            val density = Density(context)
            val button = NativeMaterialButtonView(context, appearance.buttons.filled.forDialogAction(),
                density, createFontFamilyResolver(context), onClick = {})
            try {
                button.bind("OK", true, palette.buttonPalette)
                val target = NativeButtonTouchTarget(button)
                val body = NativeText(context, palette.pageControls,
                    appearance.modals.typography.nativeTextStyle(NativeTypographyRole.BODY_MEDIUM)).apply {
                    text = "Failure details remain readable. ".repeat(80)
                }
                val footer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(target, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                val panel = NativeBoundedDialogContentLayout(context, body, footer, 24, { 160 })
                panel.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                layout(panel, 168, 160)
                val bodyViewport = body.parent as ScrollView
                val footerViewport = footer.parent as ScrollView
                assertTrue(panel.height <= 160)
                assertTrue(body.height > bodyViewport.height)
                assertTrue(bodyViewport.height > 0)
                assertTrue(target.height >= 48)
                assertEquals(footer.height, footerViewport.height)
                assertTrue(footerViewport.bottom <= panel.height)
                assertTrue(target.left >= 0 && target.right <= footer.width)
                assertTrue(target.bottom <= footerViewport.height)
                val footerBounds = bounds(footerViewport)
                bodyViewport.scrollTo(0, Int.MAX_VALUE)
                assertEquals(body.height, bodyViewport.scrollY + bodyViewport.height)
                assertEquals(footerBounds, bounds(footerViewport))
                assertSame(target, footer.getChildAt(0))
                assertSame(button, target.getChildAt(0))
            } finally {
                button.close()
                dialog.dismiss()
            }
        }
    }

    private fun context(scale: Float = 1f): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = 160
            fontScale = scale
        }).also { assertEquals(1f, it.resources.displayMetrics.density, 0f) }
    }

    private class NaturalView(context: Context, private val naturalWidth: Int, var naturalHeight: Int) : View(context) {
        override fun onMeasure(w: Int, h: Int) =
            setMeasuredDimension(resolveSize(naturalWidth, w), resolveSize(naturalHeight, h))
    }

    private fun layout(view: View, width: Int, height: Int, mode: Int = View.MeasureSpec.AT_MOST) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, mode))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)

    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { action() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
