package smallthingz.ui.components

import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.icons.MaterialRoundedIconDrawable
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeDialogActionsIntegrationTest {
    @Test fun shortLabelsShareWidthAndLongLabelsGrow() = onMain {
        withActions("OK", "OK", 420, 1f) { row, first, second ->
            assertEquals(LinearLayout.HORIZONTAL, row.orientation)
            assertEquals(first.button.measuredWidth, second.button.measuredWidth)
            // Compare the mounted standalone pair to the existing joined Trim recipe for the same label and glyph.
            val density = Density(row.context)
            val appearance = NativeUiAppearance.Default
            val colors = appearance.resolveColorScheme(row.context, false)
            val reference = NativeMaterialButtonView(row.context, appearance.buttons.trimActions.cancel,
                density, createFontFamilyResolver(row.context), onClick = {})
            try {
                reference.setLeadingIcon(MaterialRoundedIconDrawable(MaterialRoundedIcon.Close, density.density, colors.ink))
                reference.bind("OK", true, colors.buttonPalette)
                reference.joinActionSurface()
                assertEquals(reference.preferredWidth(false), first.button.measuredWidth)
            } finally { reference.close() }
            assertTrue(first.measuredHeight >= dp(first, 48))
        }
        withActions("Cancel", "Replace installed plugin", 600, 1f) { _, first, second ->
            assertTrue(second.button.measuredWidth > first.button.measuredWidth)
        }
    }

    @Test fun narrowAndLargeTextStackWithoutReducingPaddingOrTargets() = onMain {
        for (scale in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            withActions("Cancel", "Replace installed plugin", 136, scale, rtl) { row, first, second ->
                assertEquals(LinearLayout.VERTICAL, row.orientation)
                assertFalse(first.button.compactPadding)
                assertFalse(second.button.compactPadding)
                assertTrue(first.measuredWidth <= row.measuredWidth)
                assertTrue(second.measuredWidth <= row.measuredWidth)
                assertTrue(second.top >= first.bottom + dp(row, NativeScreenSpacing.CONTROL_GAP_DP))
                assertTrue(first.measuredHeight >= dp(row, 48))
            }
        }
    }

    @Test fun themeAwareSwitchKeepsTheSharedThinOutline() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) {
            val colors = NativeUiAppearance.Default.resolveColorScheme(context, dark)
            val mark = NativeMaterialSwitchView(context, colors)
            mark.measure(View.MeasureSpec.makeMeasureSpec(dp(mark, 100), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(mark, 60), View.MeasureSpec.AT_MOST))
            mark.layout(0, 0, mark.measuredWidth, mark.measuredHeight)
            val field = NativeMaterialSwitchView::class.java.getDeclaredField("borderWidth").apply { isAccessible = true }
            assertEquals(context.resources.displayMetrics.density, field.getFloat(mark), .01f)
        }
    }

    private fun onMain(action: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).roundToInt()

    private fun withActions(firstLabel: String, secondLabel: String, widthDp: Int, fontScale: Float,
        rtl: Boolean = false, check: (NativeDialogActionLayout, NativeButtonTouchTarget, NativeButtonTouchTarget) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { this.fontScale = fontScale; densityDpi = 200 })
        val appearance = NativeUiAppearance.Default
        val colors = appearance.resolveColorScheme(context, false)
        val density = Density(context)
        val fonts = createFontFamilyResolver(context)
        fun button(label: String, recipe: NativeButtonRecipe, icon: MaterialRoundedIcon) =
            NativeButtonTouchTarget(NativeMaterialButtonView(context, recipe.forDialogAction(), density, fonts, onClick = {}).apply {
                setLeadingIcon(MaterialRoundedIconDrawable(icon, density.density, colors.ink))
                bind(label, true, colors.buttonPalette)
            })
        val first = button(firstLabel, appearance.buttons.text, MaterialRoundedIcon.Close)
        val second = button(secondLabel, appearance.buttons.destructive, MaterialRoundedIcon.Delete)
        try {
            val row = NativeDialogActionLayout(first, second)
            row.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            val host = FrameLayout(context)
            val hostBottom = dp(host, 11)
            host.setPaddingRelative(0, 0, 0, hostBottom)
            host.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            host.measure(View.MeasureSpec.makeMeasureSpec(dp(row, widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            host.layout(0, 0, host.measuredWidth, host.measuredHeight)
            val paintedBottom = maxOf(first.top + first.button.bottom, second.top + second.button.bottom)
            val clearance = (NativeMergedActionLayout.ACTION_FOOTER_BOTTOM_PADDING_DP *
                context.resources.displayMetrics.density).roundToInt()
            assertEquals(clearance, row.height - paintedBottom)
            assertEquals(hostBottom, host.height - row.bottom)
            check(row, first, second)
        } finally { first.button.close(); second.button.close() }
    }
}
