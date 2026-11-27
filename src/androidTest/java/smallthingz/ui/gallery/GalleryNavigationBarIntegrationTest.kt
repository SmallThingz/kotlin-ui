package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.DrawableWrapper
import android.os.SystemClock
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
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeTabBar
import smallthingz.ui.components.NativeSegmentedChoice
import smallthingz.ui.components.ChoiceArrangement
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
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

    @Test fun selectedSurfaceRetainsBoundsNodesAndSettledContrastAcrossThemes() {
        // As in GalleryVideoAudioToggleIntegrationTest: await shared grain off the UI thread.
        NoiseTexture.prepare()
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(10)
        }
        checkNotNull(NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND)) { "Shared noise texture did not become ready" }
        main {
            assertSelectedSurfacePaint()
            assertMovingSurfaceUsesTheSameContentBounds()
        }
    }

    private fun assertSelectedSurfacePaint() {
        for (fontScale in listOf(1f, 2f))
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                withBar(fontScale, listOf("Photos", "Search", "Albums"), direction) { f ->
                    f.layout(600)
                    val nodes = f.chips
                    val parents = nodes.mapValues { it.value.parent }
                    val ids = nodes.mapValues { it.value.id }
                    val backgrounds = nodes.mapValues { it.value.background }
                    val ripples = nodes.mapValues { retainedRipple(it.value) }
                    val bounds = nodes.mapValues { boundsIn(f.bar, it.value) }
                    val barSize = f.bar.width to f.bar.height
                    // Detached ViewColorTransition.retarget snaps to its target. No sleep,
                    // animator-scale override or mid-transition color assumption is needed.
                    assertFalse(f.bar.isAttachedToWindow)
                    for (dark in listOf(false, true, false)) for (selected in f.ids)
                        for (withGrain in listOf(true, false)) {
                        val pagePalette = GalleryDesign.palette(f.context, dark).pageControls
                        // Keep real-theme grain contrast coverage. The no-grain companion
                        // isolates the actual alpha composite without a pixel tolerance.
                        val palette = if (withGrain) pagePalette else pagePalette.copy(surfacePreset = null)
                        f.bar.bind(selected, palette)
                        f.layout(600)
                        assertEquals(barSize, f.bar.width to f.bar.height)
                        assertTrue("Theme/selection binding must not navigate", f.requests.isEmpty())
                        for ((id, chip) in f.chips) {
                            assertSame(nodes.getValue(id), chip)
                            assertSame(parents.getValue(id), chip.parent)
                            assertEquals(ids.getValue(id), chip.id)
                            assertSame(backgrounds.getValue(id), chip.background)
                            assertSame(ripples.getValue(id), retainedRipple(chip))
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
                                    if (!withGrain) {
                                        val expectedFill = quietSelectionComposite(palette.selection, palette.surface)
                                        assertEquals("One quieter selection layer over the unchanged footer",
                                            expectedFill, middle)
                                        // Rebind the ORIGINAL page palette, not the derived selection palette.
                                        // The rendered surface and retained nodes must not drift or rebuild.
                                        f.bar.bind(selected, palette)
                                        f.layout(600)
                                        val rebound = renderNavigationChip(chip, palette.surface, backgroundOnly = true)
                                        try {
                                            assertTrue("Same original palette yields byte-identical settled paint",
                                                surface.sameAs(rebound))
                                        } finally { rebound.recycle() }
                                        assertEquals(barSize, f.bar.width to f.bar.height)
                                        for ((retainedId, retainedChip) in f.chips) {
                                            assertSame(nodes.getValue(retainedId), retainedChip)
                                            assertSame(parents.getValue(retainedId), retainedChip.parent)
                                            assertEquals(ids.getValue(retainedId), retainedChip.id)
                                            assertSame(backgrounds.getValue(retainedId), retainedChip.background)
                                            assertSame(ripples.getValue(retainedId), retainedRipple(retainedChip))
                                            assertEquals(bounds.getValue(retainedId), boundsIn(f.bar, retainedChip))
                                        }
                                        assertTrue("Repeated palette binding cannot navigate", f.requests.isEmpty())
                                    }
                                    assertEquals("Rounded corner keeps the footer underlay", palette.surface, surface.getPixel(0, 0))
                                    val outline = Outline()
                                    chip.background.getOutline(outline)
                                    val painted = Rect()
                                    assertTrue(outline.getRect(painted))
                                    assertTrue("Short content must not paint the entire200dp target", painted.left > 0)
                                    assertTrue(painted.right < chip.width)
                                    assertEquals("Only horizontal paint bounds change", 0, painted.top)
                                    assertEquals(chip.height, painted.bottom)
                                    assertTrue(kotlin.math.abs(painted.left - (chip.width - painted.right)) <= 1)
                                    assertEquals("Empty target margin retains the footer", palette.surface,
                                        surface.getPixel(painted.left / 2, chip.height / 2))
                                    assertTrue("Complete centered label fits the surface",
                                        text.getLineWidth(0) <= painted.width())
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
                                if (id == selected) {
                                    val focusableInTouchMode = chip.isFocusableInTouchMode
                                    try {
                                        chip.isFocusableInTouchMode = true
                                        assertTrue(chip.requestFocus())
                                        assertTrue(chip.isFocused)
                                        val focused = renderNavigationChip(chip, palette.surface, backgroundOnly = true)
                                        try {
                                            val stroke = focused.getPixel(chip.width / 2, f.dp(1))
                                            val fill = focused.getPixel(chip.width / 2, f.dp(8))
                                            assertEquals("Focused selected outline retains default accent", palette.accent, stroke)
                                            assertTrue("Focused selected outline contrasts with selected fill",
                                                ColorUtils.calculateContrast(ColorUtils.compositeColors(stroke, fill), fill) >= 3.0)
                                        } finally { focused.recycle() }
                                        assertEquals(bounds.getValue(id), boundsIn(f.bar, chip))
                                        assertTrue(chip.isChecked)
                                        assertTrue("Focus must not navigate", f.requests.isEmpty())
                                    } finally {
                                        chip.clearFocus()
                                        chip.isFocusableInTouchMode = focusableInTouchMode
                                    }
                                }
                            } finally { surface.recycle() }
                        }
                    }
                }
            }
    }

    @Test fun contentSurfaceOutlineDoesNotReenterFromDelegateBoundsInvalidation() = main {
        for (fontScale in listOf(1f, 2f))
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                withBar(fontScale, listOf("Photos", "Search", "Albums"), direction) { f ->
                    f.layout(600)
                    val chip = f.chips.getValue(f.ids.first())
                    val background = chip.background as DrawableWrapper
                    val delegate = checkNotNull(background.drawable)
                    val cell = Rect(0, 0, chip.width, chip.height)
                    background.setBounds(cell)
                    assertEquals("Start with nonempty full-cell delegate bounds before any draw",
                        cell, delegate.bounds)
                    assertSame(background, delegate.callback)
                    val owner = background.callback
                    var outlineDepth = 0
                    var outlineQueries = 0
                    var forwardedInvalidations = 0
                    fun queryOutline(): Rect {
                        outlineDepth++
                        try {
                            // Fail deterministically before exhausting the instrumentation stack.
                            assertEquals("setBounds invalidation must not reenter the outline query",
                                1, outlineDepth)
                            outlineQueries++
                            val outline = Outline()
                            background.getOutline(outline)
                            return Rect().also { assertTrue(outline.getRect(it)) }
                        } finally { outlineDepth-- }
                    }
                    // Model the attached View's synchronous rebuildOutline callback. This uses
                    // real Android Drawable.setBounds (which invalidates BEFORE storing bounds),
                    // not a bitmap draw that settles the delegate bounds before the first query.
                    val synchronousOwner = object : Drawable.Callback {
                        override fun invalidateDrawable(who: Drawable) {
                            assertSame(background, who)
                            forwardedInvalidations++
                            queryOutline()
                        }
                        override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) {
                            owner?.scheduleDrawable(who, what, at)
                        }
                        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                            owner?.unscheduleDrawable(who, what)
                        }
                    }
                    try {
                        background.callback = synchronousOwner
                        val content = queryOutline()
                        assertTrue("The first outline must actually narrow the full-cell bounds",
                            content.left > 0 && content.right < cell.right)
                        assertEquals(cell.top, content.top)
                        assertEquals(cell.bottom, content.bottom)
                        assertEquals(content, delegate.bounds)
                        assertEquals(1, outlineQueries)
                        assertEquals("Internal bounds invalidation must not escape", 0, forwardedInvalidations)
                        repeat(2) { index ->
                            // Suppression must end after setBounds: ordinary ripple/color/frame
                            // invalidations still reach the owner and synchronously query outline.
                            delegate.invalidateSelf()
                            assertEquals(index + 1, forwardedInvalidations)
                            assertEquals(index + 2, outlineQueries)
                            assertEquals(content, delegate.bounds)
                        }
                        assertEquals(0, outlineDepth)
                        assertTrue("Outline refresh cannot navigate", f.requests.isEmpty())
                    } finally { background.callback = owner }
                }
            }
    }

    @Test fun contentSurfaceLeavesDefaultGeometryAndWrappedFallbackIntact() = main {
        for (fontScale in listOf(1f, 2f))
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                withBar(fontScale, listOf("Photos", "Search", "Albums"), direction) { f ->
                    val palette = GalleryDesign.palette(f.context, dark = true).pageControls
                    val style = NativeChoiceStyle(iconAbove = true, animateSelection = false)
                    val original = NativeChip(f.context, palette, style)
                    val fitted = NativeChip(f.context, palette,
                        style.copy(contentSurfacePaddingDp = style.horizontalPaddingDp))
                    try {
                        for (chip in listOf(original, fitted)) {
                            // TextView rebinding after measure requires real LayoutParams even detached.
                            chip.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT)
                            chip.layoutDirection = direction
                            chip.maxLines = Int.MAX_VALUE
                            chip.ellipsize = null
                            chip.setIcons(ColorDrawable(Color.WHITE))
                        }
                        val retained = fitted.background
                        for ((label, width) in listOf("Search" to 200,
                            "Shared photographs and videos" to 48, "Search" to 200)) {
                            for (chip in listOf(original, fitted)) {
                                chip.bind(label, checked = true)
                                chip.measure(exact(f.dp(width)), unspecified())
                                chip.layout(0, 0, chip.measuredWidth, chip.measuredHeight)
                            }
                            assertSame(retained, fitted.background)
                            assertEquals(original.width, fitted.width)
                            assertEquals(original.height, fitted.height)
                            assertEquals(original.paddingLeft, fitted.paddingLeft)
                            assertEquals(original.paddingRight, fitted.paddingRight)
                            val originalText = checkNotNull(original.layout)
                            val fittedText = checkNotNull(fitted.layout)
                            assertEquals(originalText.lineCount, fittedText.lineCount)
                            assertEquals(label.length, fittedText.getLineEnd(fittedText.lineCount - 1))
                            for (line in 0 until fittedText.lineCount)
                                assertEquals(0, fittedText.getEllipsisCount(line))
                            // The old null/default mode still exposes the original native background.
                            assertTrue(original.background is RippleDrawable)
                            val oldBounds = surfaceBounds(original)
                            val newBounds = surfaceBounds(fitted)
                            assertEquals(Rect(0, 0, original.width, original.height), oldBounds)
                            if (width == 48) {
                                assertTrue("Fixture must exercise actual wrapping", fittedText.lineCount > 1)
                                assertEquals("Wrapped labels retain full surface; no fixed cap", oldBounds, newBounds)
                            } else {
                                assertEquals(1, fittedText.lineCount)
                                assertTrue(newBounds.width() < oldBounds.width())
                                assertTrue(newBounds.width() >= fittedText.getLineWidth(0))
                            }
                        }
                    } finally { original.close(); fitted.close() }
                }
            }
    }

    private fun assertMovingSurfaceUsesTheSameContentBounds() {
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            withBar(2f, listOf("Photos", "Search", "Albums"), direction) { f ->
                val pagePalette = GalleryDesign.palette(f.context, dark = true).pageControls.copy(surfacePreset = null)
                val palette = GalleryDesign.navigationPalette(pagePalette)
                val style = NativeChoiceStyle(iconAbove = true, verticalPaddingDp = 8f,
                    contentSurfacePaddingDp = NativeChoiceStyle().horizontalPaddingDp)
                val group = NativeSegmentedChoice(f.context, palette, style, gapDp = 0f,
                    arrangement = ChoiceArrangement.EqualWidth, fixedColumns = 2, fillRowHeight = true,
                    movingSelectionBackground = true).apply { layoutDirection = direction }
                val choices = listOf("one", "two").map { NativeChoice(it, "Search", ColorDrawable(Color.WHITE)) }
                try {
                    var retained: List<View>? = null
                    for (selected in listOf("one", "two", "one")) {
                        group.bind(choices, selected)
                        group.measure(exact(f.dp(400)), unspecified())
                        group.layout(0, 0, group.measuredWidth, group.measuredHeight)
                        val children = choices.map { checkNotNull(group.choiceView(it.id)) }
                        retained?.let { previous -> children.forEachIndexed { index, child -> assertSame(previous[index], child) } }
                        retained = children
                        val chip = checkNotNull(group.choiceView(selected))
                        val painted = surfaceBounds(chip)
                        assertTrue(painted.left > 0 && painted.right < chip.width)
                        val bitmap = Bitmap.createBitmap(group.width, group.height, Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(palette.surface)
                            group.draw(canvas)
                            // Empty target margin: neither a full-cell parent fill nor child ripple
                            // may leave a second container outside the shared measured surface.
                            assertEquals(palette.surface,
                                bitmap.getPixel(chip.left + painted.left / 2, chip.top + f.dp(4)))
                            assertEquals("Moving parent paints exactly one translucent layer, not a second child fill",
                                quietSelectionComposite(pagePalette.selection, pagePalette.surface),
                                bitmap.getPixel(chip.left + chip.width / 2, chip.top + f.dp(4)))
                        } finally { bitmap.recycle() }
                    }
                } finally { group.close() }
            }
        }
    }

    /** Independent single-layer Android composite; avoid a channel-rounding approximation. */
    private fun quietSelectionComposite(selection: Int, underlay: Int): Int {
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(pixel)
            canvas.drawColor(underlay)
            canvas.drawColor(ColorUtils.setAlphaComponent(selection, 143))
            pixel.getPixel(0, 0)
        } finally { pixel.recycle() }
    }

    private fun surfaceBounds(chip: NativeChip): Rect {
        chip.background.setBounds(0, 0, chip.width, chip.height)
        val outline = Outline()
        chip.background.getOutline(outline)
        return Rect().also { assertTrue(outline.getRect(it)) }
    }

    private fun retainedRipple(chip: NativeChip): RippleDrawable {
        var drawable = chip.background
        while (drawable is DrawableWrapper) drawable = checkNotNull(drawable.drawable)
        return drawable as RippleDrawable
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
