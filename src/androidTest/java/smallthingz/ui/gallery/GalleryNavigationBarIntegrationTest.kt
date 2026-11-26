package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeTabBar
import kotlin.math.roundToInt

/** Detached geometry and direct callback checks: no Activity, real navigation or injected input. */
@RunWith(AndroidJUnit4::class)
class GalleryNavigationBarIntegrationTest {
    @Test fun fourDestinationsRemainReachableIn144By64BarInBothDirections() = main {
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            withBar(fontScale = 1f, labels = listOf("A", "B", "C", "D"), direction = direction) { f ->
                f.layout(144, height = 64)
                assertEquals(f.dp(144), f.bar.width)
                assertEquals(f.dp(64), f.bar.height)
                assertEquals(f.dp(64), f.viewport.height)
                assertTrue(f.viewport.canScrollHorizontally(-1) || f.viewport.canScrollHorizontally(1))
                assertSingleRow(f)
                assertOrdering(f, direction)
                for (id in f.ids) {
                    val chip = f.chips.getValue(id)
                    reveal(f, chip)
                    val before = f.requests.size
                    assertTrue(chip.performClick())
                    assertEquals(before + 1, f.requests.size)
                    assertEquals(id, f.requests.last())
                }
                assertEquals(f.ids, f.requests)
            }
        }
    }

    @Test fun largeFontsKeepCompleteLabelsAndUseActualMinimumCellWidth() = main {
        var largerThanTouchMinimum = false
        // The stress scale distinguishes actual glyph/padding minima from a hard-coded 48dp.
        for (fontScale in listOf(2f, 6f)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            withBar(fontScale, listOf("Photos", "Albums", "Favorites", "Trash"), direction) { f ->
                for (width in listOf(144, 320, 800, 320, 144)) {
                    f.layout(width)
                    assertEquals(minOf(f.dp(width), f.dp(600)), f.viewport.width)
                    val viewportBounds = boundsIn(f.bar, f.viewport)
                    assertTrue(Rect(0, 0, f.bar.width, f.bar.height).contains(viewportBounds))
                    assertTrue(kotlin.math.abs(viewportBounds.left - (f.bar.width - f.viewport.width) / 2) <= 1)
                    assertSingleRow(f)
                    assertOrdering(f, direction)
                    val minimumCell = f.chips.values.maxOf { it.minimumCellWidth() }
                    largerThanTouchMinimum = largerThanTouchMinimum || minimumCell > f.dp(48)
                    assertEquals(maxOf(f.viewport.width, f.ids.size * minimumCell), f.viewport.getChildAt(0).width)
                    for (chip in f.chips.values) {
                        val text = checkNotNull(chip.layout)
                        assertEquals(chip.text.length, text.getLineEnd(text.lineCount - 1))
                        for (line in 0 until text.lineCount) assertEquals(0, text.getEllipsisCount(line))
                        assertTrue("Complete label must fit the grown navigation row",
                            text.height <= chip.height - chip.compoundPaddingTop - chip.compoundPaddingBottom)
                        reveal(f, chip)
                    }
                }
                assertTrue("Measurement and scrolling must not navigate", f.requests.isEmpty())
            }
        }
        assertTrue("Fixture must exercise an actual cell minimum above 48dp", largerThanTouchMinimum)
    }

    @Test fun resizeAndPaletteRebindRetainNodesParentsAndControlledCallbacks() = main {
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            withBar(1f, listOf("A", "B", "C", "D"), direction) { f ->
                val original = f.chips
                val parent = original.mapValues { it.value.parent }
                val nodeIds = original.mapValues { it.value.id }
                val viewport = f.viewport
                for ((index, width) in listOf(320, 144, 800, 144, 320).withIndex()) {
                    val selected = f.ids[index % f.ids.size]
                    val beforeBind = f.requests.size
                    f.bar.bind(selected, GalleryDesign.palette(f.context, dark = index % 2 == 0).pageControls)
                    f.layout(width, height = 64)
                    assertEquals("Rebind/resize must not dispatch selection", beforeBind, f.requests.size)
                    assertSame(viewport, f.viewport)
                    assertSingleRow(f)
                    if (width >= 320) {
                        assertEquals(0, f.viewport.scrollX)
                        assertFalse(f.viewport.canScrollHorizontally(-1))
                        assertFalse(f.viewport.canScrollHorizontally(1))
                    }
                    for (id in f.ids) {
                        val chip = f.chips.getValue(id)
                        assertSame(original.getValue(id), chip)
                        assertSame(parent.getValue(id), chip.parent)
                        assertEquals(nodeIds.getValue(id), chip.id)
                        assertEquals(id == selected, chip.isChecked)
                        assertEquals("Navigate $id", chip.contentDescription.toString())
                        reveal(f, chip)
                        val before = f.requests.size
                        assertTrue(chip.performClick())
                        assertEquals(before + 1, f.requests.size)
                        assertEquals(id, f.requests.last())
                        // A request cannot commit a different selection before the host rebinds.
                        assertEquals(listOf(selected), f.chips.filterValues { it.isChecked }.keys.toList())
                    }
                    f.acceptRequests = false
                    val rejected = f.ids[(index + 1) % f.ids.size]
                    val rejectedChip = f.chips.getValue(rejected)
                    reveal(f, rejectedChip)
                    val beforeReject = f.requests.size
                    assertFalse(rejectedChip.performClick())
                    assertEquals(beforeReject + 1, f.requests.size)
                    assertEquals(rejected, f.requests.last())
                    assertEquals(listOf(selected), f.chips.filterValues { it.isChecked }.keys.toList())
                    f.acceptRequests = true
                }
            }
        }
    }

    @Test fun selectedSurfaceRetainsBoundsNodesAndSettledContrastAcrossThemes() = main {
        for (fontScale in listOf(1f, 2f))
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                withBar(fontScale, listOf("Photos", "Search", "Albums"), direction) { f ->
                    f.layout(600)
                    val nodes = f.chips
                    val parents = nodes.mapValues { it.value.parent }
                    val ids = nodes.mapValues { it.value.id }
                    val backgrounds = nodes.mapValues { it.value.background }
                    val bounds = nodes.mapValues { boundsIn(f.bar, it.value) }
                    val barSize = f.bar.width to f.bar.height
                    // Detached ViewColorTransition.retarget snaps to its target. No sleep,
                    // animator-scale override or mid-transition color assumption is needed.
                    assertFalse(f.bar.isAttachedToWindow)
                    for (dark in listOf(false, true, false)) for (selected in f.ids) {
                        val palette = GalleryDesign.palette(f.context, dark).pageControls
                        f.bar.bind(selected, palette)
                        f.layout(600)
                        assertEquals(barSize, f.bar.width to f.bar.height)
                        assertTrue("Theme/selection binding must not navigate", f.requests.isEmpty())
                        for ((id, chip) in f.chips) {
                            assertSame(nodes.getValue(id), chip)
                            assertSame(parents.getValue(id), chip.parent)
                            assertEquals(ids.getValue(id), chip.id)
                            assertSame(backgrounds.getValue(id), chip.background)
                            assertEquals(bounds.getValue(id), boundsIn(f.bar, chip))
                            assertEquals("Navigate $id", chip.contentDescription.toString())
                            assertEquals(id == selected, chip.isChecked)
                            assertTrue(chip.isEnabled)
                            assertTrue(chip.width >= f.dp(48) && chip.height >= f.dp(64))
                            val text = checkNotNull(chip.layout)
                            assertEquals(chip.text.length, text.getLineEnd(text.lineCount - 1))
                            for (line in 0 until text.lineCount) assertEquals(0, text.getEllipsisCount(line))
                            assertTrue(text.height <= chip.height - chip.compoundPaddingTop - chip.compoundPaddingBottom)
                            val surface = renderNavigationChip(chip, palette.surface, backgroundOnly = true)
                            try {
                                val middle = surface.getPixel(chip.width / 2, chip.height / 2)
                                if (id == selected) {
                                    assertNotEquals("Selection needs a container behind its content", palette.surface, middle)
                                    assertEquals("Rounded corner keeps the footer underlay", palette.surface, surface.getPixel(0, 0))
                                    val outline = Outline()
                                    (chip.background as RippleDrawable).getDrawable(0).getOutline(outline)
                                    assertEquals("Existing16dp recipe radius", f.dp(16).toFloat(), outline.radius, .01f)
                                } else assertEquals("Unselected cell has no filled container", palette.surface, middle)
                                val iconInk = checkNotNull(chip.compoundDrawableTintList)
                                    .getColorForState(chip.drawableState, Color.TRANSPARENT)
                                // Sample the actual painted surface, with its configured theme grain.
                                // Background-only drawing avoids accidentally sampling a text glyph.
                                for (y in listOf(f.dp(8), chip.height / 2, chip.height - f.dp(8))) {
                                    val background = surface.getPixel(chip.width / 2, y)
                                    val textInk = ColorUtils.compositeColors(chip.currentTextColor, background)
                                    val displayedIcon = ColorUtils.compositeColors(iconInk, background)
                                    assertTrue("Settled label contrast dark=$dark selected=${id == selected}",
                                        ColorUtils.calculateContrast(textInk, background) >= 4.5)
                                    assertTrue("Settled icon contrast dark=$dark selected=${id == selected}",
                                        ColorUtils.calculateContrast(displayedIcon, background) >= 3.0)
                                }
                                val complete = renderNavigationChip(chip, palette.surface, backgroundOnly = false)
                                try {
                                    assertEquals("No separate underline at the cell bottom",
                                        surface.getPixel(chip.width / 2, chip.height - 2),
                                        complete.getPixel(chip.width / 2, chip.height - 2))
                                } finally { complete.recycle() }
                            } finally { surface.recycle() }
                        }
                    }
                }
            }
    }

    private fun renderNavigationChip(chip: NativeChip, underlay: Int, backgroundOnly: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(chip.width, chip.height, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(underlay)
            if (backgroundOnly) {
                chip.background.setBounds(0, 0, chip.width, chip.height)
                chip.background.draw(canvas)
            } else chip.draw(canvas)
            bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    private class Fixture(val context: Context, labels: List<String>, direction: Int) {
        val ids = labels.indices.map { "destination-$it" }
        val requests = mutableListOf<String>()
        var acceptRequests = true
        val bar = GalleryNavigationBar(context, GalleryDesign.palette(context, dark = false).pageControls,
            ids.mapIndexed { index, id -> NativeChoice(id, labels[index], ColorDrawable(Color.WHITE),
                contentDescription = "Navigate $id") }, ids.first()) { id ->
            requests += id
            acceptRequests
        }.apply { layoutDirection = direction }

        // No assumptions about intermediate layout wrappers or child indexes in the public bar.
        val viewport: HorizontalScrollView get() = descendants(bar).filterIsInstance<HorizontalScrollView>().single()
        val chips: Map<String, NativeChip> get() = descendants(bar).filterIsInstance<NativeChip>()
            .associateBy { it.contentDescription.toString().removePrefix("Navigate ") }

        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        fun layout(width: Int, height: Int? = null) {
            bar.measure(exact(dp(width)), height?.let { exact(dp(it)) } ?: unspecified())
            bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
        }
    }

    private fun withBar(fontScale: Float, labels: List<String>, direction: Int, assertions: (Fixture) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = 160
            this.fontScale = fontScale
        })
        val fixture = Fixture(context, labels, direction)
        // Compile-time coverage of the existing FrameLayout-facing API.
        val frame: FrameLayout = fixture.bar
        assertSame(frame, fixture.bar)
        try { assertions(fixture) }
        finally { descendants(fixture.bar).filterIsInstance<NativeTabBar>().forEach { it.close() } }
    }

    private fun assertSingleRow(f: Fixture) {
        val chips = f.chips.values.toList()
        assertEquals(f.ids.toSet(), f.chips.keys)
        assertEquals(1, chips.map { it.top }.distinct().size)
        assertEquals(1, chips.map { it.height }.distinct().size)
        assertTrue(chips.maxOf { it.width } - chips.minOf { it.width } <= 1)
        for (chip in chips) {
            assertTrue(chip.width >= chip.minimumCellWidth())
            assertTrue(chip.height >= f.dp(64))
            val bounds = boundsIn(f.viewport, chip)
            assertTrue("No second row may be clipped below the viewport", bounds.top >= 0 && bounds.bottom <= f.viewport.height)
        }
    }

    private fun assertOrdering(f: Fixture, direction: Int) {
        val lefts = f.ids.map { boundsIn(f.viewport, f.chips.getValue(it)).left }
        for (index in 1 until lefts.size) {
            assertTrue(if (direction == View.LAYOUT_DIRECTION_RTL) lefts[index - 1] > lefts[index]
                else lefts[index - 1] < lefts[index])
        }
    }

    private fun reveal(f: Fixture, chip: NativeChip) {
        val viewport = f.viewport
        val contentBounds = boundsIn(viewport, chip).apply { offset(viewport.scrollX, viewport.scrollY) }
        viewport.scrollTo(contentBounds.left, 0)
        val visible = boundsIn(viewport, chip)
        assertTrue("Whole destination target $visible must be reachable in ${viewport.width}x${viewport.height}",
            Rect(0, 0, viewport.width, viewport.height).contains(visible))
    }

    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private companion object {
        fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        fun boundsIn(root: ViewGroup, child: View): Rect = Rect().also {
            child.getDrawingRect(it)
            root.offsetDescendantRectToMyCoords(child, it)
            it.offset(-root.scrollX, -root.scrollY)
        }
    }
}
