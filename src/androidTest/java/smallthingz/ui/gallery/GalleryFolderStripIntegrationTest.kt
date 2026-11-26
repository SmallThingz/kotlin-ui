package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeText
import smallthingz.ui.graphics.noiseSurfaceRange
import kotlin.math.roundToInt

/** Detached production geometry only: no Activity, device input or global configuration changes. */
@RunWith(AndroidJUnit4::class)
class GalleryFolderStripIntegrationTest {
    @Test fun largeLabelsLeaveNextPeekAndRemainReachableInBothDirections() = main {
        for (font in listOf(1f, 2f)) for (density in listOf(160, 240))
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val f = Fixture(font, density, direction, listOf(longLabel, "A", longLabel + " last"))
                f.strip.setPadding(f.dp(5), 0, f.dp(7), 0)
                f.strip.isHorizontalFadingEdgeEnabled = false
                f.strip.isSmoothScrollingEnabled = false
                f.layout(320)
                val scenario = "font=$font density=$density direction=$direction"
                val usable = f.strip.width - f.strip.paddingLeft - f.strip.paddingRight
                assertTrue("Adaptive cap: $scenario", f.chips.first().width <= (usable * .6f).roundToInt())
                assertTrue("Absolute cap: $scenario", f.chips.first().width <= f.dp(240))
                assertTrue("Next-folder reserve: $scenario", f.chips.first().width <= usable - f.dp(8) - f.dp(24))
                assertEquals(2, f.labels.first().layout.lineCount)
                assertTrue(f.labels.first().layout.let { it.getEllipsisCount(it.lineCount - 1) } > 0)
                val first = f.chips[0]
                val second = f.chips[1]
                assertTrue("Short second chip keeps natural width: $scenario", second.width < first.width)
                assertEquals(0, f.labels[1].layout.getEllipsisCount(0))
                assertEquals(f.dp(8), if (direction == View.LAYOUT_DIRECTION_RTL)
                    first.left - second.right else second.left - first.right)
                // Observe native initial placement before any focus or scrolling request.
                val viewport = Rect(f.strip.paddingLeft, 0,
                    f.strip.width - f.strip.paddingRight, f.strip.height)
                assertTrue("Initial first chip must be visible: $scenario scrollX=${f.strip.scrollX} bounds=${f.visible(first)}",
                    viewport.contains(f.visible(first)))
                val secondBounds = f.visible(second)
                val visibleWidth = minOf(secondBounds.right, f.strip.width - f.strip.paddingRight) -
                    maxOf(secondBounds.left, f.strip.paddingLeft)
                assertTrue("Initial next-folder peek: $scenario scrollX=${f.strip.scrollX} bounds=$secondBounds",
                    visibleWidth >= f.dp(24))
                f.assertLabels()
                for (chip in f.chips) {
                    chip.isFocusableInTouchMode = true
                    assertTrue("Focus after initial layout: $scenario", chip.requestFocus())
                    assertSame(chip, f.strip.findFocus())
                    // No explicit reveal or extra layout: requestFocus must reveal the target itself.
                    assertTrue("Native focus reveal: $scenario scrollX=${f.strip.scrollX} bounds=${f.visible(chip)}",
                        viewport.contains(f.visible(chip)))
                }
                assertEquals(0, f.calls)
            }
    }

    @Test fun twoLinesExposeMoreTextWithoutWideningMixedTargets() = main {
        for (font in listOf(1f, 2f)) for (density in listOf(160, 240))
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL))
                for (width in listOf(180, 320)) {
                    val f = Fixture(font, density, direction, listOf(longLabel, "A"))
                    // Measure the previous one-line policy using this same production component.
                    f.labels.first().maxLines = 1
                    f.layout(width)
                    val before = f.labels.first().layout
                    val beforeVisibleEnd = before.getLineEnd(0) - before.getEllipsisCount(0)
                    val beforeWidth = f.chips.first().width
                    val beforeHeight = f.strip.height
                    val shortHeight = f.chips[1].height
                    f.labels.first().maxLines = 2
                    f.layout(width)
                    val after = f.labels.first().layout
                    assertEquals(2, after.lineCount)
                    val last = after.lineCount - 1
                    assertTrue(after.getLineEnd(last) - after.getEllipsisCount(last) > beforeVisibleEnd)
                    assertEquals(beforeWidth, f.chips.first().width)
                    assertEquals(shortHeight, f.chips[1].height)
                    assertTrue("Two lines require intrinsic row height", f.strip.height > beforeHeight)
                    f.assertLabels()
                    assertEquals(0, f.calls)
                }
    }

    @Test fun singleFolderUsesViewportAndShortFoldersKeepNaturalWidth() = main {
        val single = Fixture(2f, 160, View.LAYOUT_DIRECTION_LTR, listOf(longLabel))
        single.layout(320)
        val palette = single.palette
        val grainAlpha = (checkNotNull(palette.surfacePreset).foregroundGrain * 255f).roundToInt()
        assertEquals(noiseSurfaceRange(palette.surface, grainAlpha), single.chips.single().paintedTextSurface)
        assertEquals(single.dp(320), single.chips.single().width)
        assertEquals(2, single.labels.single().layout.lineCount)
        assertTrue(single.labels.single().layout.let { it.getEllipsisCount(it.lineCount - 1) } > 0)
        single.assertLabels()
        val short = Fixture(2f, 160, View.LAYOUT_DIRECTION_LTR, listOf("A", "B"))
        short.layout(320)
        assertTrue(short.chips.all { it.width < short.dp(192) })
        assertTrue(short.labels.all { it.layout.getEllipsisCount(0) == 0 })
        short.assertLabels()
    }

    @Test fun resizeRefreshesCapsWithoutReplacingNodesOrDispatchingActions() = main {
        val f = Fixture(2f, 160, View.LAYOUT_DIRECTION_RTL, listOf(longLabel, "Camera"))
        val nodes = f.chips.toList()
        for ((width, expectedCap) in listOf(320 to 192, 180 to 108, 480 to 240, 320 to 192)) {
            f.layout(width)
            assertEquals(f.dp(expectedCap), f.chips.first().width)
            f.chips.forEachIndexed { i, chip -> assertSame(nodes[i], chip) }
            f.assertLabels()
            val oldHeight = f.strip.height
            f.layout(width)
            assertEquals(oldHeight, f.strip.height)
            assertFalse("An unchanged measure/layout must settle", f.strip.isLayoutRequested)
        }
        assertEquals(0, f.calls)
    }

    @Test fun zeroNarrowAndUnboundedWidthsHaveDefinedGeometry() = main {
        val f = Fixture(2f, 160, View.LAYOUT_DIRECTION_LTR, listOf(longLabel, "A"))
        for (width in listOf(0, 20, 48)) {
            f.layout(width)
            assertEquals(f.dp(width), f.strip.width)
            assertTrue("Narrow viewports must not compress targets", f.chips.all { it.width >= f.dp(48) })
            assertTrue(f.strip.height >= f.dp(48))
            f.assertVerticalGeometry()
        }
        f.layout(null)
        assertTrue(f.chips.first().width > f.dp(320))
        assertEquals(1, f.labels.first().layout.lineCount)
        assertEquals(0, f.labels.first().layout.getEllipsisCount(0))
        f.assertLabels()
        f.layout(320)
        assertEquals(f.dp(192), f.chips.first().width)
        f.strip.measure(View.MeasureSpec.makeMeasureSpec(f.dp(240), View.MeasureSpec.AT_MOST), unspecified())
        f.strip.layout(0, 0, f.strip.measuredWidth, f.strip.measuredHeight)
        assertTrue(f.strip.width <= f.dp(240))
        assertEquals(f.dp(144), f.chips.first().width)
        assertTrue(f.strip.width - f.chips.first().width - f.dp(8) >= f.dp(24))
        f.assertLabels()
        val empty = Fixture(2f, 160, View.LAYOUT_DIRECTION_LTR, emptyList())
        empty.layout(0)
        assertEquals(0, empty.strip.width)
        assertEquals(0, empty.strip.height)
        empty.layout(null)
        assertEquals(0, empty.strip.width)
        assertEquals(0, empty.strip.height)
    }

    private class Fixture(font: Float, density: Int, direction: Int, private val names: List<String>) {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context: Context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = density
            fontScale = font
        })
        var calls = 0
        val palette = GalleryDesign.palette(context, dark = true).pageControls
        val strip = GalleryFolderStrip(context, palette,
            names.map { GalleryFolderEntry(it) { calls++ } }).apply { layoutDirection = direction }
        val row get() = strip.getChildAt(0) as LinearLayout
        val chips get() = (0 until row.childCount).map { row.getChildAt(it) as NativeSurface }
        val labels get() = chips.map { it.getChildAt(0) as NativeText }
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        fun layout(width: Int?) {
            strip.measure(width?.let { View.MeasureSpec.makeMeasureSpec(dp(it), View.MeasureSpec.EXACTLY) }
                ?: unspecified(), unspecified())
            strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
        }
        fun visible(chip: View) = Rect(0, 0, chip.width, chip.height).also {
            strip.offsetDescendantRectToMyCoords(chip, it)
            it.offset(-strip.scrollX, -strip.scrollY)
        }
        fun assertVerticalGeometry() {
            if (chips.isEmpty()) return
            assertEquals(chips.maxOf { it.height }, row.height)
            assertTrue(row.height <= strip.height)
            chips.forEachIndexed { index, chip ->
                // Native LinearLayout centers with integer division; no pixel tolerance.
                assertEquals((row.height - chip.height) / 2, chip.top)
                val label = labels[index]
                assertTrue(label.layout.lineCount in 1..2)
                assertTrue(label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                assertTrue(label.top >= chip.paddingTop && label.bottom <= chip.height - chip.paddingBottom)
            }
        }
        fun assertLabels() {
            assertVerticalGeometry()
            chips.forEachIndexed { index, chip ->
                val label = labels[index]
                val layout = checkNotNull(label.layout)
                val detail = "font=${context.resources.configuration.fontScale} chip=${chip.width}x${chip.height} " +
                    "label=${label.width}x${label.height} layout=${layout.width}x${layout.height}"
                assertEquals(names[index], chip.contentDescription.toString())
                assertEquals(names[index], chip.tooltipText.toString())
                assertEquals(names[index], label.text.toString())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, label.importantForAccessibility)
                assertTrue(detail, layout.lineCount in 1..2)
                if (names[index] == "A" || names[index] == "B") assertEquals(detail, 1, layout.lineCount)
                assertTrue(detail, layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                for (line in 0 until layout.lineCount)
                    assertTrue(detail, layout.getLineMax(line) <= label.width - label.compoundPaddingLeft - label.compoundPaddingRight + .5f)
                assertTrue(detail, chip.width >= dp(48) && chip.height >= dp(48))
                assertTrue(detail, label.left >= chip.paddingLeft && label.right <= chip.width - chip.paddingRight)
                assertTrue(detail, label.top >= chip.paddingTop && label.bottom <= chip.height - chip.paddingBottom)
            }
        }
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private companion object {
        const val longLabel = "GalleryVideoControlsQA-20261125 · a deliberately long folder location"
        fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    }
}
