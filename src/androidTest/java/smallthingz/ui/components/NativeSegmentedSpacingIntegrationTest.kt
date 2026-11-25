package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.Rect
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.settings.ReverbLabeledSegmentedSelector
import smallthingz.ui.reverb.settings.ReverbSelectorHelp
import smallthingz.ui.reverb.settings.ReverbThemeSelector
import java.util.Locale
import kotlin.math.roundToInt

/** Real, unattached View measurements; no window-input or rendered-pixel qualification. */
@RunWith(AndroidJUnit4::class)
class NativeSegmentedSpacingIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun providerKeepsTextInsetsWhenCompactReflowedAndRebound() = instrumentation.runOnMainSync {
        for (fontScale in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            val context = instrumentation.targetContext.createConfigurationContext(
                Configuration(instrumentation.targetContext.resources.configuration).apply {
                    densityDpi = 421
                    this.fontScale = fontScale
                    setLocales(LocaleList(Locale.ENGLISH))
                })
            val environment = ReverbEnvironment(context, false).forSettings()
            val options = listOf(SegmentOption("chatgpt", "ChatGPT"), SegmentOption("cloudflare", "Cloudflare"))
            val selector = ReverbLabeledSegmentedSelector(context, environment, "Provider", options, "chatgpt",
                help = ReverbSelectorHelp("Provider help") { error("No Help input expected") },
                inputAllowed = { true }) { error("Layout and binding must not request selection") }
            selector.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            try {
                val retained = selector.chips()
                assertEquals(2, retained.size)
                for (widthDp in listOf(380, 220, 380)) for (enabled in listOf(true, false)) {
                    selector.bind("Provider", options, "cloudflare", enabled, environment)
                    selector.layoutAtWidth((widthDp * context.resources.displayMetrics.density).roundToInt())
                    assertEquals(retained, selector.chips())
                    assertEquals(listOf(false, true), retained.map { it.isChecked })
                    assertTrue(retained.all { it.isEnabled == enabled })
                    assertReadable(selector, retained, context.resources.displayMetrics.density)
                    assertChoiceOrder(selector, retained, rtl)
                    val titleRow = selector.getChildAt(0) as ViewGroup
                    val title = titleRow.getChildAt(0)
                    val help = titleRow.getChildAt(1)
                    val capsule = selector.getChildAt(1)
                    val rowBounds = selector.containedBounds(titleRow)
                    val titleBounds = selector.containedBounds(title)
                    val helpBounds = selector.containedBounds(help)
                    assertTrue("Title remains inside its row", rowBounds.contains(titleBounds))
                    assertTrue("Help remains inside its row", rowBounds.contains(helpBounds))
                    assertTrue("Title and Help follow layout direction without overlap",
                        if (rtl) helpBounds.right <= titleBounds.left else titleBounds.right <= helpBounds.left)
                    assertLabelAndCapsule(selector, titleRow, capsule, rtl)
                }
            } finally { selector.close() }
        }
    }

    @Test fun themeAlsoKeepsContentReadableAcrossCompactWidths() = instrumentation.runOnMainSync {
        for (fontScale in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            val context = instrumentation.targetContext.createConfigurationContext(
                Configuration(instrumentation.targetContext.resources.configuration).apply {
                    densityDpi = 421
                    this.fontScale = fontScale
                    setLocales(LocaleList(Locale.ENGLISH))
                })
            val environment = ReverbEnvironment(context, false).forSettings()
            val selector = ReverbThemeSelector(context, environment.theme, NativeThemeMode.SYSTEM,
                inputAllowed = { true }) { error("Measuring must not request selection") }
            try {
                val view = selector.view as ViewGroup
                view.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                for (widthDp in listOf(380, 220, 380)) {
                    view.layoutAtWidth((widthDp * context.resources.displayMetrics.density).roundToInt())
                    val chips = view.chips()
                    assertEquals("All three Theme choices must exist", 3, chips.size)
                    assertReadable(view, chips, context.resources.displayMetrics.density)
                    assertChoiceOrder(view, chips, rtl)
                    assertLabelAndCapsule(view, view.getChildAt(0), view.getChildAt(1), rtl)
                }
            } finally { selector.close() }
        }
    }

    private fun assertReadable(root: ViewGroup, chips: List<NativeChip>, density: Float) {
        val inset = (12 * density).roundToInt()
        val bounds = chips.map { chip ->
            assertTrue("Start text inset: ${chip.text}", chip.paddingStart >= inset)
            assertTrue("End text inset: ${chip.text}", chip.paddingEnd >= inset)
            val text = requireNotNull(chip.layout)
            assertTrue(text.lineCount > 0)
            assertEquals(chip.text.length, text.getLineEnd(text.lineCount - 1))
            for (line in 0 until text.lineCount) {
                assertEquals(0, text.getEllipsisCount(line))
                assertTrue(text.getLineLeft(line) >= -1f)
                assertTrue(text.getLineRight(line) <= text.width + 1f)
            }
            assertTrue(text.height <= chip.height - chip.compoundPaddingTop - chip.compoundPaddingBottom)
            root.containedBounds(chip)
        }
        for (i in bounds.indices) for (j in i + 1 until bounds.size)
            assertFalse("Retained choice cells overlap", Rect.intersects(bounds[i], bounds[j]))
    }

    private fun ViewGroup.containedBounds(child: View) = Rect(0, 0, child.width, child.height).also {
        assertEquals(View.VISIBLE, child.visibility)
        assertTrue("Visible content has positive area", child.width > 0 && child.height > 0)
        offsetDescendantRectToMyCoords(child, it)
        assertTrue(it.left >= 0 && it.right <= width && it.top >= 0 && it.bottom <= height)
    }

    private fun assertLabelAndCapsule(root: ViewGroup, label: View, capsule: View, rtl: Boolean) {
        val title = root.containedBounds(label)
        val choices = root.containedBounds(capsule)
        assertTrue("Label and capsule remain separate in inline or stacked layout",
            choices.top >= title.bottom || if (rtl) choices.right <= title.left else title.right <= choices.left)
    }

    private fun assertChoiceOrder(root: ViewGroup, chips: List<NativeChip>, rtl: Boolean) {
        val bounds = chips.map { root.containedBounds(it) }
        for ((previous, next) in bounds.zipWithNext()) {
            if (previous.top == next.top) {
                assertTrue("Same-row choices mirror their logical order in RTL",
                    if (rtl) next.right <= previous.left else previous.right <= next.left)
            } else {
                assertTrue("Wrapped choices retain top-to-bottom order", previous.bottom <= next.top)
            }
        }
    }

    private fun View.layoutAtWidth(width: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun View.chips(): List<NativeChip> = when (this) {
        is NativeChip -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).chips() }
        else -> emptyList()
    }
}
