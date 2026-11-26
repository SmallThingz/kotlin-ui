package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
import kotlin.math.roundToInt

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
                val gutter = (theme.appearance.settingsSelectors.retentionSection.sideInsetDp *
                    theme.density.density).roundToInt()
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
                        assertEquals(width - 20 - 2 * gutter, button.width)
                        assertEquals(8 + gutter, button.left)
                        assertEquals(12 + gutter, dock.width - button.right)
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

    @Test fun parentConsumedAndRemainingSafeInsetsProduceTheSameActionBounds() = main {
        val base = InstrumentationRegistry.getInstrumentation().context
        for (densityDpi in listOf(160, 240, 420)) for (legacy in listOf(false, true)) {
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    this.densityDpi = densityDpi
                    fontScale = 1f
                })
                val theme = ReverbDesign.theme(context, dark = false)
                fun dp(value: Int) = (value * theme.density.density).roundToInt()
                val gutter = dp(if (legacy) theme.appearance.settingsSelectors.retentionSection.sideInsetDp
                    else NativeScreenSpacing.HORIZONTAL_DP)
                val leftSafe = dp(8)
                val rightSafe = dp(12)
                val bottomSafe = dp(24)
                val width = dp(320)
                val height = dp(240)
                val buttons = List(2) {
                    NativeMaterialButtonView(context, ReverbBottomActionDock.buttonStyle(theme),
                        theme.density, theme.fontResolver) {}
                }
                try {
                    val docks = buttons.map { button ->
                        button.bind("Start", true, theme.palette.buttonPalette)
                        button.layoutDirection = direction
                        if (legacy) ReverbBottomActionDock(context, theme, button)
                        else NativeBottomActionDock(context, theme.density, button)
                    }
                    val parents = docks.mapIndexed { index, dock ->
                        FrameLayout(context).apply {
                            layoutDirection = direction
                            // Index 0 consumes side safe insets, as the Home frame does.
                            if (index == 0) setPadding(leftSafe, 0, rightSafe, 0)
                            addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                        }
                    }
                    // Reapply and then clear safe insets on the same retained views. Neither
                    // design spacing nor system insets may accumulate or survive removal.
                    for (withSafeInsets in listOf(true, true, false)) {
                        val left = if (withSafeInsets) leftSafe else 0
                        val right = if (withSafeInsets) rightSafe else 0
                        val bottom = if (withSafeInsets) bottomSafe else 0
                        parents[0].setPadding(left, 0, right, 0)
                        docks[0].applySafeInsets(Insets.of(0, 0, 0, bottom))
                        docks[1].applySafeInsets(Insets.of(left, 0, right, bottom))
                        val bounds = parents.mapIndexed { index, parent ->
                            parent.measure(exact(width), exact(height))
                            parent.layout(0, 0, width, height)
                            val dock = docks[index]
                            val button = buttons[index]
                            assertEquals(height, dock.bottom)
                            assertEquals(bottom, dock.safeBottomInset)
                            assertEquals(bottom, dock.height - button.bottom)
                            assertEquals(maxOf(dock.minimumActionHeightPx, button.intrinsicHeight(button.width)) +
                                bottom, dock.height)
                            Rect(0, 0, button.width, button.height).also {
                                parent.offsetDescendantRectToMyCoords(button, it)
                                assertEquals(left + gutter, it.left)
                                assertEquals(width - right - gutter, it.right)
                                assertEquals(height - bottom, it.bottom)
                            }
                        }
                        assertEquals("Safe insets consumed by either owner exactly once", bounds[0], bounds[1])
                        assertEquals(docks[0].height, docks[1].height)
                    }
                } finally { buttons.forEach { it.close() } }
            }
        }
    }

    @Test fun parentHeightBudgetStillBoundsActionAndNavigationFill() = main {
        val context = InstrumentationRegistry.getInstrumentation().context
        val theme = ReverbDesign.theme(context, dark = false)
        val gutter = (theme.appearance.settingsSelectors.retentionSection.sideInsetDp *
            theme.density.density).roundToInt()
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
                assertEquals((180 - 2 * gutter).coerceAtLeast(0), button.width)
                assertEquals(gutter.coerceAtMost(180), button.left)
                assertEquals((dock.height - 24).coerceAtLeast(0), button.height)
                assertTrue(button.bottom <= dock.height)
            }
        } finally { button.close() }
    }

    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun atMost(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)
    private fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
}
