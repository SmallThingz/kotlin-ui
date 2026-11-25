package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.alldoc.AllDocHomeNavigation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Detached Android layout/semantics fixtures. No Activity, device input or physical UI claim. */
@RunWith(AndroidJUnit4::class)
class AllDocHomeNavigationLayoutTest {
    @Test fun wholeLabelsAt240dpFont1() = checkHome(240, 1f)
    @Test fun wholeLabelsAt240dpFont2() = checkHome(240, 2f)
    @Test fun wholeLabelsAt280dpFont1() = checkHome(280, 1f)
    @Test fun wholeLabelsAt280dpFont2() = checkHome(280, 2f)
    @Test fun wholeLabelsAt320dpFont1() = checkHome(320, 1f)
    @Test fun wholeLabelsAt320dpFont2() = checkHome(320, 2f)

    private fun checkHome(viewportDp: Int, scale: Float) = main {
        for (densityDpi in listOf(160, 420)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val context = context(scale, densityDpi)
            val bar = AllDocHomeNavigation.create(context, palette)
            val choices = choices()
            var requests = 0
            bar.onSelectionRequest = { requests++; true }
            bar.layoutDirection = direction
            try {
                bar.bind(choices, "Recents")
                val retained = choices.associate { it.id to checkNotNull(bar.choiceView(it.id)) }
                for (selected in choices.map { it.id }) {
                    bar.bind(choices, selected)
                    bar.applyPalette(NativeColorScheme.fallback(selected == "Library").pageControls)
                    // Match Home's 14dp margins on each side, rounded independently.
                    layout(bar, dp(context, viewportDp) - 2 * dp(context, 14))
                    val bounds = Rect(0, 0, bar.width, bar.height)
                    val nodes = choices.map { choice ->
                        val chip = checkNotNull(bar.choiceView(choice.id))
                        assertSame(retained.getValue(choice.id), chip)
                        assertEquals(choice.id == selected, chip.isChecked)
                        assertEquals(choice.label.toString(), chip.contentDescription.toString())
                        assertTrue(chip.width >= dp(context, 48))
                        assertTrue(chip.height >= dp(context, 48))
                        assertTrue(chip.width >= chip.minimumCellWidth())
                        assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f,
                            context.resources.displayMetrics), chip.textSize, .01f)
                        assertEquals(1f, chip.textScaleX, .001f)
                        assertWholeLabel(chip, choice.label.toString())
                        val node = AccessibilityNodeInfo.obtain()
                        chip.onInitializeAccessibilityNodeInfo(node)
                        assertEquals("android.app.ActionBar\$Tab", node.className.toString())
                        assertTrue(node.isEnabled)
                        assertTrue(node.isClickable)
                        assertTrue(node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
                        assertEquals(choice.id == selected, node.isSelected)
                        val item = checkNotNull(node.collectionItemInfo)
                        assertEquals(choice.id == selected, item.isSelected)
                        node.recycle()
                        Rect(chip.left, chip.top, chip.right, chip.bottom).also { assertTrue(bounds.contains(it)) }
                    }
                    for (i in nodes.indices) for (j in 0 until i) assertFalse(Rect.intersects(nodes[i], nodes[j]))
                    val collection = AccessibilityNodeInfo.obtain()
                    bar.onInitializeAccessibilityNodeInfo(collection)
                    assertEquals("android.widget.TabWidget", collection.className.toString())
                    val info = checkNotNull(collection.collectionInfo)
                    assertEquals(nodes.map { it.top }.distinct().size, info.rowCount)
                    assertEquals(AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE, info.selectionMode)
                    collection.recycle()
                    for (row in nodes.groupBy { it.top }.values) for (i in 1 until row.size) {
                        assertTrue(if (direction == View.LAYOUT_DIRECTION_RTL) row[i].left < row[i - 1].left
                            else row[i].left > row[i - 1].left)
                    }
                    assertEquals("Binding, palette and measuring cannot activate navigation", 0, requests)
                }
                assertFalse(bar.activateOnFocusNavigation)
                assertFalse(bar.choiceStyle.selectedFill)
                assertEquals(2f, bar.choiceStyle.indicatorHeightDp, 0f)
                assertEquals(24f, checkNotNull(bar.choiceStyle.indicatorWidthDp), 0f)
            } finally { bar.close() }
        }
    }

    @Test fun defaultPolicyRetainsGlyphMinimumAndOptInRemovesNarrowOrphanLines() = main {
        val context = context(2f)
        val whole = AllDocHomeNavigation.create(context, palette)
        val legacy = NativeTabBar(context, palette,
            whole.choiceStyle.copy(minimumLabelWidth = NativeChoiceLabelWidth.GlyphMinimum),
            fixedColumns = 3, fillRowHeight = true)
        try {
            assertEquals(NativeChoiceLabelWidth.GlyphMinimum, NativeChoiceStyle().minimumLabelWidth)
            for (bar in listOf(legacy, whole)) { bar.bind(choices(), "Recents"); layout(bar, 212) }
            assertEquals(1, (0 until legacy.childCount).map { legacy.getChildAt(it).top }.distinct().size)
            assertTrue((0 until legacy.childCount).any { (legacy.getChildAt(it) as NativeChip).layout.lineCount > 1 })
            assertTrue((0 until whole.childCount).map { whole.getChildAt(it).top }.distinct().size > 1)
            for (i in 0 until legacy.childCount) {
                val chip = legacy.getChildAt(i) as NativeChip
                val glyph = max(1, chip.paint.measureText("M").roundToInt())
                assertEquals(max(48, chip.paddingLeft + chip.paddingRight + max(20, glyph)), chip.minimumCellWidth())
                assertWholeLabel(whole.getChildAt(i) as NativeChip, choices()[i].label.toString())
            }
        } finally { legacy.close(); whole.close() }
    }

    @Test fun retainedLabelAndSelectedTypographyRemeasureTheWholeWidth() = main {
        val context = context(1f)
        val style = NativeChoiceStyle(iconAbove = true, horizontalPaddingDp = 10f,
            textStyle = NativeTextStyle(12f), selectedTextStyle = NativeTextStyle(24f, weight = 700),
            minimumLabelWidth = NativeChoiceLabelWidth.WholeLabel)
        val bar = NativeTabBar(context, palette, style, fixedColumns = 3)
        var choices = listOf(NativeChoice("a", "Recents"), NativeChoice("b", "Library"), NativeChoice("c", "Folders"))
        try {
            bar.bind(choices, "b"); layout(bar, 212)
            val recents = checkNotNull(bar.choiceView("a"))
            val normalMinimum = recents.minimumCellWidth()
            bar.bind(choices, "a"); layout(bar, 212)
            assertSame(recents, bar.choiceView("a"))
            assertEquals(24f, recents.textSize, .01f)
            assertTrue(recents.minimumCellWidth() > normalMinimum)
            assertEquals(max(48, 20 + ceil(Layout.getDesiredWidth(recents.text, recents.paint)).toInt()),
                recents.minimumCellWidth())
            assertWholeLabel(recents, "Recents")
            choices = choices.map { if (it.id == "a") it.copy(label = "A") else it }
            bar.bind(choices, "b"); layout(bar, 212)
            assertEquals(12f, recents.textSize, .01f)
            assertTrue(recents.minimumCellWidth() < normalMinimum)
            assertWholeLabel(recents, "A")
            bar.bind(choices.map { if (it.id == "a") it.copy(label = "Recents") else it }, "a")
            layout(bar, 212)
            assertWholeLabel(recents, "Recents")
        } finally { bar.close() }
    }

    @Test fun wholeLabelMeasurementIncludesMetricSpansAndBothInlineIcons() = main {
        val context = context(1f)
        val chip = NativeChip(context, palette, NativeChoiceStyle(horizontalPaddingDp = 10f,
            iconSizeDp = 20f, trailingIconSizeDp = 18f, iconGapDp = 6f,
            minimumLabelWidth = NativeChoiceLabelWidth.WholeLabel))
        try {
            val label = SpannableString("Folder-2").apply {
                setSpan(RelativeSizeSpan(1.5f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            chip.setIcons(ColorDrawable(Color.WHITE))
            chip.setTrailingIcon(ColorDrawable(Color.WHITE))
            chip.bind(label, false)
            chip.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val displayed = checkNotNull(chip.layout).text
            assertTrue(Layout.getDesiredWidth(displayed, chip.paint) > chip.paint.measureText(label.toString()))
            val expected = 20 + 20 + 6 + ceil(Layout.getDesiredWidth(displayed, chip.paint)).toInt() + 6 + 18
            assertEquals(expected, chip.minimumCellWidth())
            chip.measure(View.MeasureSpec.makeMeasureSpec(expected, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            chip.layout(0, 0, chip.measuredWidth, chip.measuredHeight)
            assertWholeLabel(chip, label.toString())
        } finally { chip.close() }
    }

    @Test fun reflowRetainsControlledSelectionAndRejectedActivation() = main {
        val bar = AllDocHomeNavigation.create(context(2f), palette)
        val options = choices()
        val requests = mutableListOf<String>()
        var admitted = true
        bar.onSelectionRequest = { requests += it; admitted }
        try {
            bar.bind(options, "Recents")
            val library = checkNotNull(bar.choiceView("Library"))
            for (width in listOf(383, 212, 292, 212)) {
                layout(bar, width)
                assertSame(library, bar.choiceView("Library"))
                assertTrue(library.performClick())
                assertEquals("Recents", bar.selectedId)
                assertFalse(library.isChecked)
                assertWholeLabel(library, "Library")
            }
            assertEquals(List(4) { "Library" }, requests)
            bar.bind(options, "Library"); layout(bar, 212)
            assertTrue(library.isChecked)
            admitted = false
            assertFalse(checkNotNull(bar.choiceView("Folders")).performClick())
            assertEquals("Library", bar.selectedId)
            bar.close()
            val count = requests.size
            assertFalse(library.performClick())
            assertEquals(count, requests.size)
        } finally { bar.close() }
    }

    @Test fun aPreviouslyEllipsizedLabelCannotReduceItsWholeLabelMinimum() = main {
        val chip = NativeChip(context(2f), palette, NativeChoiceStyle(horizontalPaddingDp = 10f,
            minimumLabelWidth = NativeChoiceLabelWidth.WholeLabel))
        try {
            chip.maxLines = 1
            chip.ellipsize = TextUtils.TruncateAt.END
            chip.bind("Recents", false)
            chip.measure(View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertTrue(checkNotNull(chip.layout).getEllipsisCount(0) > 0)
            val expected = 20 + ceil(Layout.getDesiredWidth(chip.text, chip.paint)).toInt()
            assertEquals(expected, chip.minimumCellWidth())
            chip.measure(View.MeasureSpec.makeMeasureSpec(expected, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            chip.layout(0, 0, chip.measuredWidth, chip.measuredHeight)
            assertWholeLabel(chip, "Recents")
        } finally { chip.close() }
    }

    private fun assertWholeLabel(chip: NativeChip, expected: String) {
        val text = checkNotNull(chip.layout)
        assertEquals(expected, chip.text.toString())
        assertEquals("No midword wrapping: $expected in ${chip.width}px", 1, text.lineCount)
        assertEquals(expected.length, text.getLineVisibleEnd(0))
        assertEquals(0, text.getEllipsisCount(0))
        assertTrue(text.getLineLeft(0) >= -.5f)
        assertTrue(text.getLineRight(0) <= chip.width - chip.compoundPaddingLeft - chip.compoundPaddingRight + .5f)
        assertTrue("Full text height including font padding must fit",
            text.height <= chip.height - chip.compoundPaddingTop - chip.compoundPaddingBottom)
    }

    private fun choices() = listOf("Recents", "Library", "Folders").map {
        NativeChoice(it, it, ColorDrawable(Color.WHITE), contentDescription = it)
    }
    private val palette get() = NativeColorScheme.fallback(false).pageControls
    private fun context(scale: Float, densityDpi: Int = 160): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            this.fontScale = scale
            this.densityDpi = densityDpi
        })
    }
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun layout(bar: NativeTabBar, width: Int) {
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
    }
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
