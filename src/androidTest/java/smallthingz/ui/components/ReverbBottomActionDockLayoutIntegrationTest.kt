package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import androidx.core.graphics.Insets
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbBottomActionDock
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbIconDrawable
import smallthingz.ui.text.view.ParagraphView

/** Real paragraph and button measurement; no window, device configuration change, or input. */
@RunWith(AndroidJUnit4::class)
class ReverbBottomActionDockLayoutIntegrationTest {
    @Test fun completeLabelsFitWithIconsLargeFontsAndNarrowWidths() = main {
        var wrappingBeyondTheOldBudget = 0
        for (fontScale in listOf(1f, 2f)) for (icon in listOf(false, true)) {
            for (assigned in listOf(false, true)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val base = InstrumentationRegistry.getInstrumentation().context
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    densityDpi = 160
                    this.fontScale = fontScale
                })
                val theme = ReverbDesign.theme(context, dark = false)
                val recipe = ReverbBottomActionDock.buttonStyle(theme).let {
                    it.copy(surface = it.surface.copy(assignedSize = assigned))
                }
                val label = "Create a new browser tab"
                val button = NativeMaterialButtonView(context, recipe, theme.density, theme.fontResolver) {}
                try {
                    button.bind(label, true, theme.palette.buttonPalette)
                    if (icon) button.setLeadingIcon(ReverbIconDrawable(ReverbIcon.Add, theme.density.density, theme.palette.ink))
                    val dock = ReverbBottomActionDock(context, theme, button).apply {
                        layoutDirection = direction
                        applySafeInsets(Insets.of(8, 0, 12, 24))
                    }
                    button.layoutDirection = direction
                    // An independent paragraph measures all text at the ACTUAL laid-out label width.
                    val reference = ParagraphView(context, recipe.labelStyle, theme.density, theme.fontResolver, theme.palette.ink)
                    reference.setText(label)
                    reference.layoutDirection = direction
                    for (width in listOf(120, 180, 320)) {
                        dock.measure(exact(width), unspecified())
                        dock.layout(0, 0, dock.measuredWidth, dock.measuredHeight)
                        reference.measure(exact(button.label.width), unspecified())
                        assertTrue("Full label clipped: width=$width font=$fontScale icon=$icon assigned=$assigned",
                            button.label.height >= reference.measuredHeight)
                        assertEquals(reference.currentParagraph!!.lineCount, button.label.currentParagraph!!.lineCount)
                        val bounds = Rect(0, 0, button.label.width, button.label.height)
                        button.offsetDescendantRectToMyCoords(button.label, bounds)
                        assertTrue(Rect(0, 0, button.width, button.height).contains(bounds))
                        assertTrue(button.height >= dock.minimumActionHeightPx)
                        assertEquals(width - 20, button.width)
                        assertEquals(8, button.left)
                        assertEquals(24, dock.height - button.bottom)

                        if (icon) {
                            val fullHeight = reference.measuredHeight
                            // Prove that at least one sample distinguishes reserved-icon wrapping
                            // from the old padded-width-only sizing, rather than merely testing 64dp.
                            reference.measure(atMost((button.width - 2 * recipe.surface.horizontalPaddingDp).coerceAtLeast(0)), unspecified())
                            val oldBudget = maxOf(dock.minimumActionHeightPx,
                                reference.measuredHeight + 2 * recipe.surface.verticalPaddingDp)
                            if (fullHeight + 2 * recipe.surface.verticalPaddingDp > oldBudget) wrappingBeyondTheOldBudget++
                        }
                    }
                } finally { button.close() }
            }
        }
        assertTrue("Fixture must exercise the reported wrapping regression", wrappingBeyondTheOldBudget > 0)
    }

    @Test fun parentHeightBudgetStillBoundsActionAndNavigationFill() = main {
        val context = InstrumentationRegistry.getInstrumentation().context
        val theme = ReverbDesign.theme(context, dark = false)
        val button = NativeMaterialButtonView(context, ReverbBottomActionDock.buttonStyle(theme), theme.density, theme.fontResolver) {}
        try {
            button.bind("Create a new browser tab", true, theme.palette.buttonPalette)
            button.setLeadingIcon(ReverbIconDrawable(ReverbIcon.Add, theme.density.density, theme.palette.ink))
            val dock = ReverbBottomActionDock(context, theme, button)
            dock.applySafeInsets(Insets.of(0, 0, 0, 24))
            for (budget in listOf(0, 16, 48, 96)) {
                dock.measure(exact(180), atMost(budget))
                dock.layout(0, 0, dock.measuredWidth, dock.measuredHeight)
                assertTrue(dock.height <= budget)
                assertEquals((dock.height - 24).coerceAtLeast(0), button.height)
                assertTrue(button.bottom <= dock.height)
            }
        } finally { button.close() }
    }

    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun atMost(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)
    private fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
}
