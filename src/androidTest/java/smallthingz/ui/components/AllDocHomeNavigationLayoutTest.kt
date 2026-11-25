package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.widget.HorizontalScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.alldoc.AllDocHomeNavigation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Six whole-label cases mount a test Activity for base node metadata; other geometry/raster cases stay detached. */
@RunWith(AndroidJUnit4::class)
class AllDocHomeNavigationLayoutTest {
    @Test fun wholeLabelsAt240dpFont1() = checkHome(240, 1f)
    @Test fun wholeLabelsAt240dpFont2() = checkHome(240, 2f)
    @Test fun wholeLabelsAt280dpFont1() = checkHome(280, 1f)
    @Test fun wholeLabelsAt280dpFont2() = checkHome(280, 2f)
    @Test fun wholeLabelsAt320dpFont1() = checkHome(320, 1f)
    @Test fun wholeLabelsAt320dpFont2() = checkHome(320, 2f)

    private fun checkHome(viewportDp: Int, scale: Float) {
        for (densityDpi in listOf(160, 420)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            withMountedHome(scale, densityDpi) { context, bar ->
                val choices = choices()
                var requests = 0
                bar.onSelectionRequest = { requests++; true }
                bar.layoutDirection = direction
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
            }
        }
    }

    // Only whole-label cases inspect real base View node metadata; other geometry cases stay detached.
    private fun withMountedHome(scale: Float, densityDpi: Int, block: (Context, NativeTabBar) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var ownedBar: NativeTabBar? = null
        var listener: View.OnAttachStateChangeListener? = null
        val attached = java.util.concurrent.CountDownLatch(1)
        withRetiringTestActivity(activity, root = { ownedBar }, cleanup = {
            var failure: Throwable? = null
            failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure) {
                listener?.let { ownedBar?.removeOnAttachStateChangeListener(it) }
            }
            failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure) { ownedBar?.close() }
            failure?.let { throw it }
        }) {
            main {
                val bar = AllDocHomeNavigation.create(context(scale, densityDpi), palette).also { ownedBar = it }
                listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) { attached.countDown() }
                    override fun onViewDetachedFromWindow(view: View) = Unit
                }.also(bar::addOnAttachStateChangeListener)
                activity.setContentView(bar)
                if (bar.isAttachedToWindow) attached.countDown()
            }
            check(attached.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Home node fixture did not attach" }
            main {
                val bar = checkNotNull(ownedBar)
                check(bar.isAttachedToWindow) { "Home node fixture detached before assertions" }
                block(bar.context, bar)
            }
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
            textStyle = NativeTextStyle(12f), selectedTextStyle = NativeTextStyle(28f, weight = 700),
            minimumLabelWidth = NativeChoiceLabelWidth.WholeLabel)
        val bar = NativeTabBar(context, palette, style, fixedColumns = 3)
        var choices = listOf(NativeChoice("a", "Recents"), NativeChoice("b", "B"), NativeChoice("c", "Folders"))
        try {
            bar.bind(choices, "b"); layout(bar, 212)
            val recents = checkNotNull(bar.choiceView("a"))
            val normalMinimum = recents.minimumCellWidth()
            assertEquals(1, rowCount(bar))
            bar.bind(choices, "a"); layout(bar, 212)
            assertSame(recents, bar.choiceView("a"))
            assertEquals(28f, recents.textSize, .01f)
            assertEquals(3, rowCount(bar))
            assertTrue(recents.minimumCellWidth() > normalMinimum)
            assertEquals(max(48, 20 + ceil(Layout.getDesiredWidth(recents.text, recents.paint)).toInt()),
                recents.minimumCellWidth())
            assertWholeLabel(recents, "Recents")
            choices = choices.map { if (it.id == "a") it.copy(label = "A") else it }
            bar.bind(choices, "b"); layout(bar, 212)
            assertEquals(12f, recents.textSize, .01f)
            assertTrue(recents.minimumCellWidth() < normalMinimum)
            assertEquals(1, rowCount(bar))
            assertWholeLabel(recents, "A")
            bar.bind(choices.map { if (it.id == "a") it.copy(label = "Recents") else it }, "a")
            layout(bar, 212)
            assertEquals(3, rowCount(bar))
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

    @Test fun cappedProbeMeasuresTheFullTransformedLabelAndReflows() = main {
        val context = context(1f)
        val bar = NativeTabBar(context, palette, NativeChoiceStyle(horizontalPaddingDp = 10f,
            minimumLabelWidth = NativeChoiceLabelWidth.WholeLabel), fixedColumns = 3)
        val source = "wwwwwwwwww"
        val transformed = "WWWWWWWWWW"
        try {
            bar.bind(listOf(NativeChoice("long", source), NativeChoice("b", "B"), NativeChoice("c", "C")), "b")
            val chip = checkNotNull(bar.choiceView("long"))
            chip.isAllCaps = true
            chip.maxLines = 1
            chip.maxWidth = 60
            chip.ellipsize = TextUtils.TruncateAt.END
            chip.measure(View.MeasureSpec.makeMeasureSpec(212, View.MeasureSpec.AT_MOST), unspecified)
            val truncated = checkNotNull(chip.layout)
            assertTrue(truncated.getEllipsisCount(0) > 0)
            val expected = 20 + ceil(Layout.getDesiredWidth(transformed, chip.paint)).toInt()
            assertTrue(expected in 105..212)
            assertTrue(expected > 20 + ceil(Layout.getDesiredWidth(source, chip.paint)).toInt())
            assertEquals(expected, chip.minimumCellWidth())

            layout(bar, 212)
            assertEquals(3, rowCount(bar))
            assertWholeLabel(chip, source, transformed)
            layout(bar, 3 * expected + 8)
            assertEquals(1, rowCount(bar))
            assertWholeLabel(chip, source, transformed)
            bar.measure(unspecified, unspecified)
            assertEquals(3 * expected + 8, bar.measuredWidth)
            bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
            assertEquals(1, rowCount(bar))
            assertWholeLabel(chip, source, transformed)
        } finally { bar.close() }
    }

    @Test fun scrollHostFitsReservedAndCompactHeightAndRevealsWholeTargets() = main {
        for (scale in listOf(1f, 2f)) for (densityDpi in listOf(160, 420)) {
            val context = context(scale, densityDpi)
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                for (inlineIcons in listOf(false, true)) {
                    val bar = AllDocHomeNavigation.create(context, palette, inlineIcons = inlineIcons)
                    val viewport = AllDocHomeNavigation.scrollHost(context, bar).apply { layoutDirection = direction }
                    val options = choices()
                    val requests = mutableListOf<String>()
                    bar.onSelectionRequest = { requests += it; true }
                    try {
                        val compactHeight = dp(context, 48)
                        for (homeWidth in listOf(240, 280, 320)) {
                            val width = dp(context, homeWidth) - 2 * dp(context, 14)
                            bar.bind(options, "Folders")
                            viewport.measure(exact(width), unspecified)
                            val rowHeight = viewport.measuredHeight
                            if (inlineIcons || scale == 1f) assertEquals(compactHeight, rowHeight)
                            else assertTrue(rowHeight > compactHeight)
                            layoutViewport(viewport, width, exact(if (inlineIcons) compactHeight else rowHeight))
                            assertEquals(rowHeight, viewport.height)
                            assertEquals(0, viewport.measuredHeightAndState and View.MEASURED_STATE_TOO_SMALL)
                            assertEquals(direction, bar.layoutDirection)
                            assertEquals(1, rowCount(bar))
                            val before = requests.size
                            val targets = options.map { checkNotNull(bar.choiceView(it.id)) }
                            val visibleArea = Rect(0, 0, viewport.width, viewport.height)
                            assertTrue(targets.last().isChecked)
                            if (inlineIcons && scale == 2f && homeWidth == 240) {
                                assertFalse(visibleArea.contains(targetBounds(viewport, bar, targets.last())))
                            }
                            for ((option, chip) in options.zip(targets)) {
                                assertTrue(chip.height >= compactHeight && chip.width >= dp(context, 48))
                                assertWholeLabel(chip, option.label.toString())
                                chip.requestRectangleOnScreen(Rect(0, 0, chip.width, chip.height), true)
                                val visible = targetBounds(viewport, bar, chip)
                                assertTrue(visibleArea.contains(visible))
                                if (chip.isChecked) assertIndicatorPaint(viewport, visible)
                            }
                            assertEquals(before, requests.size)
                            assertTrue(targets.last().performClick())
                            assertEquals(before + 1, requests.size)
                            assertEquals("Folders", requests.last())
                        }
                    } finally { bar.close() }
                }
            }
        }
    }

    @Test fun stackedScrollHostReportsAnInsufficient48dpHeight() = main {
        val context = context(2f)
        val bar = AllDocHomeNavigation.create(context, palette)
        val viewport = AllDocHomeNavigation.scrollHost(context, bar)
        try {
            bar.bind(choices(), "Recents")
            viewport.measure(exact(212), unspecified)
            val naturalHeight = viewport.measuredHeight
            assertTrue(naturalHeight > 48)
            val shortAtMost = View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.AT_MOST)
            for (heightSpec in listOf(exact(48), shortAtMost)) {
                layoutViewport(viewport, 212, heightSpec)
                assertEquals(48, viewport.height)
                assertEquals(View.MEASURED_STATE_TOO_SMALL, viewport.measuredHeightAndState and View.MEASURED_STATE_TOO_SMALL)
                assertEquals(naturalHeight, bar.height)
                assertEquals(1, rowCount(bar))
            }
        } finally { bar.close() }
    }

    private fun targetBounds(viewport: HorizontalScrollView, bar: NativeTabBar, chip: NativeChip) =
        Rect(chip.left, chip.top, chip.right, chip.bottom).apply {
            offset(bar.left - viewport.scrollX, bar.top - viewport.scrollY)
        }

    private fun assertIndicatorPaint(viewport: HorizontalScrollView, target: Rect) {
        val bitmap = Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(-viewport.scrollX.toFloat(), -viewport.scrollY.toFloat())
            viewport.draw(canvas)
            assertEquals(palette.accent, bitmap.getPixel(target.centerX(), target.bottom - 1))
        } finally { bitmap.recycle() }
    }

    private fun rowCount(bar: NativeTabBar) = (0 until bar.childCount).map { bar.getChildAt(it).top }.distinct().size

    private val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun layoutViewport(viewport: HorizontalScrollView, width: Int, heightSpec: Int) {
        viewport.measure(exact(width), heightSpec)
        viewport.layout(0, 0, viewport.measuredWidth, viewport.measuredHeight)
    }

    private fun assertWholeLabel(chip: NativeChip, expected: String, displayed: String = expected) {
        val text = checkNotNull(chip.layout)
        assertEquals(expected, chip.text.toString())
        assertEquals("No midword wrapping: $expected in ${chip.width}px", 1, text.lineCount)
        assertEquals(displayed.length, text.getLineVisibleEnd(0))
        assertEquals(displayed, text.text.toString())
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
