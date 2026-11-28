package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.ChoiceArrangement
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeChoiceSemantics
import java.util.Locale
import kotlin.math.roundToInt

/** Real detached Android layout and activation APIs; no physical touch or pixel qualification. */
@RunWith(AndroidJUnit4::class)
class GalleryEditorChoicesIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private fun context(font: Float, dpi: Int = 160): Context {
        val base = instrumentation.targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            fontScale = font
            densityDpi = dpi
            setLocale(Locale.US)
        })
    }

    private fun options(kind: GalleryEditorChoiceKind): List<NativeChoice> {
        val labels = when (kind) {
            GalleryEditorChoiceKind.MENU -> listOf("Looks", "Adjust", "Crop")
            GalleryEditorChoiceKind.RETOUCH -> listOf(
                "Heal small spot", "Selective", "Local brush", "Lens blur", "Motion blur", "Draw")
            GalleryEditorChoiceKind.FINISH -> listOf("Edit steps", "Frames", "Document")
            GalleryEditorChoiceKind.AI -> listOf("Remove / Fill", "Expand", "Upscale", "Edit image")
        }
        return labels.mapIndexed { index, label ->
            NativeChoice("choice-$index", label, icon = ColorDrawable(Color.WHITE))
        }
    }

    private fun View.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun measure(group: GalleryEditorChoices, widthDp: Int) {
        // No forceLayout or manual child placement: retained measurement must invalidate itself.
        group.measure(View.MeasureSpec.makeMeasureSpec(group.dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        group.layout(0, 0, group.measuredWidth, group.measuredHeight)
    }

    private fun chips(group: GalleryEditorChoices, options: List<NativeChoice>) =
        options.map { checkNotNull(group.choiceView(it.id)) }

    private fun assertReadable(group: GalleryEditorChoices, options: List<NativeChoice>) {
        assertEquals(options.size, group.childCount)
        val bounds = chips(group, options).mapIndexed { index, chip ->
            val detail = "${options[index].label}, font=${group.resources.configuration.fontScale}, " +
                "direction=${group.layoutDirection}, group=${group.width}x${group.height}, " +
                "cell=${chip.width}x${chip.height}"
            assertEquals(detail, View.VISIBLE, chip.visibility)
            assertTrue("48dp target: $detail", chip.width >= group.dp(48) && chip.height >= group.dp(48))
            assertEquals(detail, options[index].label.toString(), chip.text.toString())
            val text = checkNotNull(chip.layout)
            assertTrue(detail, text.lineCount > 0)
            assertEquals(detail, chip.text.length, text.getLineEnd(text.lineCount - 1))
            val contentWidth = chip.width - chip.compoundPaddingLeft - chip.compoundPaddingRight
            assertTrue("Text height: $detail",
                text.height <= chip.height - chip.compoundPaddingTop - chip.compoundPaddingBottom)
            for (line in 0 until text.lineCount) {
                assertEquals("Ellipsis: $detail", 0, text.getEllipsisCount(line))
                assertTrue("Line bounds: $detail", text.getLineLeft(line) >= -1f &&
                    text.getLineRight(line) <= contentWidth + 1f)
            }
            Rect(chip.left, chip.top, chip.right, chip.bottom).also {
                assertTrue("Overflow: $detail", Rect(0, 0, group.width, group.height).contains(it))
            }
        }
        for (i in bounds.indices) for (j in i + 1 until bounds.size) {
            assertFalse("Choices $i and $j overlap", Rect.intersects(bounds[i], bounds[j]))
        }
    }

    private fun assertGrid(group: GalleryEditorChoices, options: List<NativeChoice>, gapDp: Int) {
        val cells = chips(group, options)
        val rows = cells.groupBy { it.top }.toSortedMap().values.toList()
        val rtl = group.layoutDirection == View.LAYOUT_DIRECTION_RTL
        assertTrue("Equal cell widths, allowing integer remainder",
            cells.maxOf { it.width } - cells.minOf { it.width } <= 1)
        val columns = rows.first()
        for (row in rows) {
            assertEquals("Row cells have equal height", 1, row.map { it.height }.distinct().size)
            row.forEachIndexed { index, chip ->
                assertEquals("Aligned column start", columns[index].left, chip.left)
                assertEquals("Aligned column end", columns[index].right, chip.right)
            }
            for ((previous, next) in row.zipWithNext()) {
                assertEquals("Horizontal gap and logical order", group.dp(gapDp),
                    if (rtl) previous.left - next.right else next.left - previous.right)
            }
        }
        for ((previous, next) in rows.zipWithNext()) {
            assertEquals("Vertical gap", group.dp(gapDp), next.first().top - previous.first().bottom)
        }
        val fullRow = rows.first().sortedBy { it.left }
        assertEquals("Cells fill the row start", group.paddingLeft, fullRow.first().left)
        assertEquals("Cells fill the row end", group.width - group.paddingRight, fullRow.last().right)
    }

    @Test fun sixUnequalRetouchLabelsFillAlignedColumnsAtNormalWidths() = onMain {
        val context = context(1f)
        val group = GalleryEditorChoices(context, GalleryDesign.palette(context, true).pageControls,
            GalleryEditorChoiceKind.RETOUCH)
        val options = options(GalleryEditorChoiceKind.RETOUCH)
        try {
            group.bind(options, null)
            val cells = chips(group, options)
            assertTrue("Fixture must contain unequal natural label widths",
                cells.map { it.paint.measureText(it.text.toString()) }.distinct().size > 1)
            assertEquals(ChoiceArrangement.Wrap, group.arrangement)
            for (width in listOf(320, 392)) {
                measure(group, width)
                assertReadable(group, options)
                val rows = cells.groupBy { it.top }.values
                assertTrue("Exercise multiple columns, not a vacuous single-column grid",
                    rows.first().size in 2..3)
                assertTrue("Exercise cross-row alignment", rows.size >= 2)
                assertGrid(group, options, 4)
            }
        } finally { group.close() }
    }

    @Test fun allKindsKeepFullLabelsAndTargetsAtNarrowLargeFontAndRtl() = onMain {
        for (dpi in listOf(160, 421)) for (font in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            val context = context(font, dpi)
            for (kind in GalleryEditorChoiceKind.values()) {
                val group = GalleryEditorChoices(context, GalleryDesign.palette(context, true).pageControls, kind)
                val options = options(kind)
                try {
                    group.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    group.bind(options, options.first().id)
                    assertEquals(if (kind == GalleryEditorChoiceKind.MENU) NativeChoiceSemantics.RADIO
                        else NativeChoiceSemantics.BUTTON, group.semantics)
                    assertEquals(if (kind == GalleryEditorChoiceKind.MENU) 3 else null, group.fixedColumns)
                    assertEquals(if (kind == GalleryEditorChoiceKind.RETOUCH || kind == GalleryEditorChoiceKind.FINISH)
                        ChoiceArrangement.Wrap else ChoiceArrangement.EqualWidth, group.arrangement)
                    for (width in listOf(320, 220, 392)) {
                        measure(group, width)
                        assertReadable(group, options)
                        assertGrid(group, options, if (kind == GalleryEditorChoiceKind.MENU) 8 else 4)
                        if (kind == GalleryEditorChoiceKind.MENU && font == 1f && width >= 320) {
                            assertEquals("Ordinary menu retains three fixed columns", 1,
                                chips(group, options).map { it.top }.distinct().size)
                        }
                    }
                    for (chip in chips(group, options)) {
                        val node = AccessibilityNodeInfo.obtain()
                        try {
                            chip.onInitializeAccessibilityNodeInfo(node)
                            val radio = kind == GalleryEditorChoiceKind.MENU
                            assertEquals(if (radio) "android.widget.RadioButton" else "android.widget.Button",
                                node.className.toString())
                            assertEquals(radio, node.isCheckable)
                            assertEquals(chip.text.toString(), node.text.toString())
                        } finally { node.recycle() }
                    }
                } finally { group.close() }
            }
        }
    }

    @Test fun rebindPaletteAndResizeRetainNodesWithoutActivating() = onMain {
        val context = context(2f)
        for (kind in GalleryEditorChoiceKind.values()) {
            val group = GalleryEditorChoices(context, GalleryDesign.palette(context, true).pageControls, kind)
            val options = options(kind)
            var requests = 0
            var changes = 0
            group.onSelectionRequest = { requests++; true }
            group.onSelectionChange = { changes++ }
            try {
                group.bind(options, options.first().id)
                measure(group, 320)
                val retained = chips(group, options)
                val ids = retained.map { it.id }
                for (width in listOf(220, 392, 320)) for (dark in listOf(false, true)) {
                    group.applyPalette(GalleryDesign.palette(context, dark).pageControls)
                    group.bind(options.map { it.copy() }, options.last().id)
                    measure(group, width)
                    retained.forEachIndexed { index, chip ->
                        assertSame(chip, group.choiceView(options[index].id))
                        assertSame(group, chip.parent)
                        assertEquals(ids[index], chip.id)
                        assertEquals(kind == GalleryEditorChoiceKind.MENU && index == options.lastIndex, chip.isChecked)
                    }
                    assertEquals(if (kind == GalleryEditorChoiceKind.MENU) options.last().id else null, group.selectedId)
                    assertReadable(group, options)
                    assertGrid(group, options, if (kind == GalleryEditorChoiceKind.MENU) 8 else 4)
                    assertEquals("Binding/layout must not request activation", 0, requests)
                    assertEquals("Binding/layout must not publish user selection", 0, changes)
                }
            } finally { group.close() }
        }
    }

    @Test fun menuLongLabelsUseFewerColumnsAtLargeFontWithoutBreakingWords() = onMain {
        val labels = listOf("Exposure", "Brightness", "Contrast", "Highlights", "Shadows", "Saturation",
            "Warmth", "Tint", "Vignette", "Detail", "Bloom", "Curves", "Color mix", "Color grading", "Color steps")
        for (dpi in listOf(160, 421)) for (font in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            val context = context(font, dpi)
            val group = GalleryEditorChoices(context, GalleryDesign.palette(context, true).pageControls,
                GalleryEditorChoiceKind.MENU)
            val options = labels.map { NativeChoice(it, it, ColorDrawable(Color.WHITE),
                contentDescription = it, semantics = NativeChoiceSemantics.BUTTON) }
            try {
                group.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                group.bind(options, null)
                val retained = chips(group, options)
                val textSizes = retained.map { it.textSize }
                // Re-measure the same nodes at wide/narrow/wide widths; no forced layout/cache reset.
                for (width in listOf(363, 220, 392, 363)) {
                    measure(group, width)
                    assertReadable(group, options)
                    assertGrid(group, options, 8)
                    retained.forEachIndexed { index, chip ->
                        assertSame(chip, group.choiceView(options[index].id))
                        assertEquals("Do not shrink scaled typography", textSizes[index], chip.textSize, 0f)
                        assertEquals("Full short label must not break: ${options[index].label}, font=$font width=$width",
                            1, checkNotNull(chip.layout).lineCount)
                    }
                    val columns = retained.count { it.top == retained.first().top }
                    if (width == 363) {
                        if (font == 1f) assertEquals("Ordinary editor grid keeps three columns", 3, columns)
                        else assertTrue("200% labels must reduce the observed three-column layout", columns < 3)
                    }
                }
            } finally { group.close() }
        }
    }

    @Test fun disabledOptionsAndDisabledGroupsRejectClickAndAccessibilityActivation() = onMain {
        val context = context(1f)
        for (kind in GalleryEditorChoiceKind.values()) {
            val group = GalleryEditorChoices(context, GalleryDesign.palette(context, true).pageControls, kind)
            val options = options(kind)
            val requests = mutableListOf<String>()
            group.onSelectionRequest = { requests += it; true }
            try {
                group.bind(options, options.first().id)
                measure(group, 320)
                val target = checkNotNull(group.choiceView(options[1].id))
                val selection = group.selectedId
                // Positive controls use the same retained node and API as the disabled checks.
                assertTrue(target.performClick())
                assertTrue(target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(listOf(options[1].id, options[1].id), requests)
                val disabled = options.map { if (it.id == options[1].id) it.copy(enabled = false) else it }
                group.bind(disabled, options.first().id)
                assertSame(target, group.choiceView(options[1].id))
                assertFalse(target.isEnabled)
                assertRejected(target)
                group.isEnabled = false
                for (chip in chips(group, options)) {
                    assertFalse(chip.isEnabled)
                    assertRejected(chip)
                }
                group.isEnabled = true
                assertFalse("Group re-enable must respect option-level disable", target.isEnabled)
                assertRejected(target)
                assertEquals(2, requests.size)
                assertEquals(selection, group.selectedId)
                group.bind(options, options.first().id)
                assertSame(target, group.choiceView(options[1].id))
                assertTrue(target.isEnabled)
                assertTrue(target.performClick())
                assertEquals(listOf(options[1].id, options[1].id, options[1].id), requests)
            } finally { group.close() }
        }
    }

    private fun assertRejected(chip: NativeChip) {
        assertFalse(chip.performClick())
        assertFalse(chip.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
    }
}
