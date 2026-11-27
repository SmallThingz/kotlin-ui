package smallthingz.ui.gallery

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.withRetiringTestActivity
import kotlin.math.roundToInt

/** Production card pixels plus an attached owned RecyclerView; no media or global-setting changes. */
@RunWith(AndroidJUnit4::class)
class GalleryAlbumCoverIntegrationTest {
    @Test fun captionSeparatesCoverAndRebindsThemeWithoutReplacingNodes() = main {
        for (font in listOf(1f, 2f)) for (density in listOf(160, 240))
            for (dark in listOf(false, true)) for (rtl in listOf(false, true)) {
                val f = Fixture(font, density, dark, rtl)
                f.cover.title.text = "Owned\nSuffix 37"
                f.layout(180)
                assertEquals(2, f.cover.title.layout.lineCount)
                assertEquals("The identifying second line fits", 0, f.cover.title.layout.getEllipsisCount(1))
                f.assertCaptionFits()
                val white = f.render()
                try {
                    assertEquals("Cover stays unshaded up to the caption", Color.WHITE,
                        white.getPixel(white.width / 2, f.cover.image.bottom - 1))
                    f.assertCaptionContrast(white)
                    val captionPixels = f.captionPixels(white)
                    // A caller may replace an asynchronously loaded cover at any time. Its
                    // pixels must have no influence on caption ink or background pixels.
                    val blackCover = ColorDrawable(Color.BLACK)
                    f.cover.image.setImageDrawable(blackCover)
                    val black = f.render()
                    try {
                        assertEquals(Color.BLACK, black.getPixel(black.width / 2, f.cover.image.bottom - 1))
                        assertArrayEquals("Caption is independent of the cover", captionPixels, f.captionPixels(black))
                    } finally { black.recycle() }
                    val image = f.cover.image
                    val title = f.cover.title
                    val count = f.cover.count
                    val height = f.cover.height
                    val palette = GalleryDesign.palette(f.context, !dark)
                    f.cover.paint(palette)
                    f.layout(180)
                    val rebound = f.render()
                    try {
                        assertEquals(palette.surfaceContainerHigh,
                            rebound.getPixel(f.caption.paddingLeft / 2, f.caption.top + title.top))
                        assertEquals(palette.onSurface, title.currentTextColor)
                        assertEquals(palette.onSurfaceVariant, count.currentTextColor)
                        f.assertCaptionContrast(rebound)
                    } finally { rebound.recycle() }
                    f.cover.paint(palette)
                    f.layout(180)
                    assertSame(image, f.cover.image)
                    assertSame(blackCover, image.drawable)
                    assertSame(title, f.cover.title)
                    assertSame(count, f.cover.count)
                    assertEquals(height, f.cover.height)
                    assertFalse("Repeated palette/measure settles", f.cover.isLayoutRequested)
                } finally { white.recycle() }
            }
    }

    @Test fun compactMatchedRowsAndRestorationRetainNodesAndCaptionGeometry() = main {
        for (font in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            val f = Fixture(font, 240, dark = true, rtl = rtl)
            val image = f.cover.image
            val drawable = image.drawable
            val placeholder = f.cover.placeholder
            val title = f.cover.title
            val count = f.cover.count
            title.text = "Owned album\nSuffix 37"
            f.cover.contentDescription = "Owned album Suffix 37, 12 items, local"
            f.cover.setOnClickListener { fail("Measurement must never dispatch the card action") }
            f.layout(320)
            assertEquals("Default total row remains square when the caption fits", f.dp(320), f.cover.height)
            assertEquals(2, title.layout.lineCount)
            val naturalCaption = f.caption.height
            f.budget = f.dp(128)
            f.layout(320)
            assertEquals("Both title lines survive a compact budget", 2, title.layout.lineCount)
            assertEquals(0, title.layout.getEllipsisCount(1))
            f.assertCaptionFits()
            assertTrue(f.cover.height >= f.dp(128))
            assertTrue("Prefer the budget, grow only for measured text plus cover", f.cover.height <= maxOf(f.dp(128), naturalCaption + f.dp(48)))
            f.assertRestoration()

            // Real GridLayoutManager matches every card to the tallest natural row.
            // Exercise differently measured siblings, then its authoritative EXACTLY pass.
            val short = Fixture(font, 240, dark = true, rtl = rtl)
            short.budget = f.budget
            short.cover.title.text = "Short"
            short.layout(320)
            val rowHeight = maxOf(f.cover.height, short.cover.height)
            f.layoutPx(320, rowHeight)
            short.layoutPx(320, rowHeight)
            assertEquals(f.cover.height, short.cover.height)
            assertEquals(rowHeight, f.cover.height)
            f.assertCaptionFits()
            short.assertCaptionFits()
            assertTrue("Short caption leaves more room for its cover", short.cover.image.height > image.height)

            f.budget = f.dp(1)
            f.layout(320)
            f.assertCaptionFits()
            assertEquals("An impossible soft budget yields to measured caption and cover", naturalCaption + f.dp(48), f.cover.height)
            f.budget = null
            f.layout(320)
            assertEquals(-f.dp(400), f.cover.readableRestoredOffset(-f.dp(400)))
            assertFalse(f.cover.isLayoutRequested)
            f.cover.singleColumnLandscape = true
            assertTrue(f.cover.isLayoutRequested)
            f.layout(320, force = false)
            assertEquals("Single-column preference yields only to real caption height",
                maxOf(f.dp(180), f.caption.height + f.dp(48)), f.cover.height)
            if (font == 1f) assertEquals("Exercise the positive 16:9 fit", f.dp(180), f.cover.height)
            f.assertCaptionFits()
            f.assertRestoration()
            f.layoutPx(320, f.dp(208))
            assertEquals(f.dp(208), f.cover.height)
            f.assertCaptionFits()

            title.text = "Owned\nSuffix"
            f.layout(160)
            f.assertCaptionFits()
            assertEquals(2, title.layout.lineCount)
            assertTrue("Caption and recognizable media override a too-short aspect preference", f.cover.height > f.dp(90))
            f.assertRestoration()
            f.cover.singleColumnLandscape = false
            title.text = "Owned album\nSuffix 37"
            f.layout(320, force = false)
            assertEquals(f.dp(320), f.cover.height)
            f.assertCaptionFits()

            // Placeholder stays inside the media, never centered over the separate caption.
            image.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
            f.layout(320)
            assertTrue(placeholder.bottom <= f.caption.top)
            assertEquals((image.height - placeholder.height) / 2, placeholder.top)
            image.visibility = View.VISIBLE
            placeholder.visibility = View.GONE
            f.cover.paint(GalleryDesign.palette(f.context, dark = false))
            f.layout(320)
            assertSame(image, f.cover.image)
            assertSame(drawable, image.drawable)
            assertSame(placeholder, f.cover.placeholder)
            assertSame(title, f.cover.title)
            assertSame(count, f.cover.count)
            assertTrue(f.cover.hasOnClickListeners())
            assertTrue(f.cover.isFocusable)
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, f.cover.descendantFocusability)
            for (index in 0 until f.cover.childCount) {
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    f.cover.getChildAt(index).importantForAccessibility)
            }
            val node = f.cover.createAccessibilityNodeInfo()
            try {
                assertEquals(Button::class.java.name, node.className.toString())
                assertEquals("Owned album Suffix 37, 12 items, local", node.contentDescription.toString())
                assertTrue(node.isClickable)
            } finally { @Suppress("DEPRECATION") node.recycle() }
            val height = f.cover.height
            f.layout(320)
            assertEquals(height, f.cover.height)
            assertFalse("Unchanged layout settles", f.cover.isLayoutRequested)
        }
    }

    @Test fun attachedMixedCaptionsSurviveResizeRestoreAndRecycling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: AttachedAlbums? = null
        withRetiringTestActivity(activity, root = { fixture?.root }, cleanup = {
            fixture?.list?.stopScroll()
            fixture?.list?.adapter = null
        }) {
            attachedMain {
                fixture = AttachedAlbums(activity)
                activity.setContentView(checkNotNull(fixture).root)
            }
            instrumentation.waitForIdleSync()
            attachedMain {
                val f = checkNotNull(fixture)
                assertTrue(f.root.isAttachedToWindow)
                assertTrue(f.root.width >= f.dp(360) && f.root.height >= f.dp(440))
                for (rtl in listOf(false, true)) {
                    f.list.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    f.resize(320, 440, compact = false)
                    f.scrollTo(0)
                    f.assertRow(0)
                    // More than a viewport away, twice: these must really recycle/rebind,
                    // rather than inspect two manually measured unattached siblings.
                    val recycled = f.rows.recycled
                    val rebound = f.rows.rebound
                    val switched = f.rows.placeholderSwitches
                    f.scrollTo(22)
                    f.assertRow(22)
                    f.scrollTo(44)
                    f.assertRow(44)
                    assertTrue("RecyclerView recycled holders", f.rows.recycled > recycled)
                    assertTrue("A holder bound a different stable identity", f.rows.rebound > rebound)
                    assertTrue("A reused holder switched image/placeholder presentation",
                        f.rows.placeholderSwitches > switched)

                    f.scrollTo(22)
                    val saved = checkNotNull(f.manager.onSaveInstanceState())
                    // Restore into a new real layout manager after a narrower-height,
                    // wider-width viewport change. Font2 remains the actual text context.
                    f.resize(360, 240, compact = true, saved = saved)
                    f.assertRow(22, requireVisible = true, compact = true)
                    val compactSaved = checkNotNull(f.manager.onSaveInstanceState())
                    f.scrollTo(44)
                    f.assertRow(44, requireVisible = true, compact = true)
                    f.resize(320, 440, compact = false, saved = compactSaved)
                    f.assertRow(22, requireVisible = true)
                    val restored = f.manager.findViewByPosition(23) as GalleryAlbumCover
                    assertTrue(restored.performClick())
                    assertEquals(23L, f.rows.clicked)
                }
            }
        }
    }

    private fun attachedMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    /** Attached production cards; no media loader, global settings or new test Activity. */
    private class AttachedAlbums(activity: Context) {
        private val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            fontScale = 2f
        })
        val root = FrameLayout(activity)
        var budget: Int? = null
        val rows = AlbumRows(context) { budget }
        val list = RecyclerView(context).apply {
            itemAnimator = null
            setItemViewCacheSize(0)
            layoutManager = GridLayoutManager(context, 2)
            adapter = rows
        }
        val manager get() = list.layoutManager as GridLayoutManager
        init { root.addView(list, FrameLayout.LayoutParams(dp(320), dp(440))) }
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        private fun layout() {
            root.forceLayout()
            root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
            root.layout(root.left, root.top, root.right, root.bottom)
        }
        fun resize(width: Int, height: Int, compact: Boolean, saved: Parcelable? = null) {
            budget = if (compact) dp(48) else null
            list.layoutParams = FrameLayout.LayoutParams(dp(width), dp(height))
            // The external budget is caller-owned. Rebind attached/cached cards as the
            // production owner does; do not impose individual heights or match rows here.
            rows.notifyItemRangeChanged(0, rows.itemCount)
            if (saved != null) {
                list.layoutManager = GridLayoutManager(context, 2).apply { onRestoreInstanceState(saved) }
            }
            layout()
        }
        fun scrollTo(position: Int) {
            manager.scrollToPositionWithOffset(position, 0)
            layout()
        }
        fun assertRow(position: Int, requireVisible: Boolean = false, compact: Boolean = false) {
            assertTrue(list.isAttachedToWindow && list.isShown)
            assertEquals(position, manager.findFirstVisibleItemPosition())
            val cards = (position..position + 1).map { manager.findViewByPosition(it) as GalleryAlbumCover }
            assertEquals("GridLayoutManager matches mixed-caption row heights", cards[0].height, cards[1].height)
            assertEquals(cards[0].top, cards[1].top)
            assertEquals(1, cards[0].title.layout.lineCount)
            assertEquals(2, cards[1].title.layout.lineCount)
            for ((index, card) in cards.withIndex()) {
                val id = position + index
                val holder = list.getChildViewHolder(card)
                assertEquals(id.toLong(), holder.itemId)
                assertEquals(AlbumRows.name(id), card.title.text.toString())
                assertEquals("$id", card.count.text.toString())
                assertEquals(AlbumRows.description(id), card.contentDescription.toString())
                assertTrue(card.width >= dp(48) && card.height >= dp(48))
                val caption = card.title.parent as ViewGroup
                assertTrue(caption.left >= 0 && caption.right <= card.width)
                assertTrue(caption.top >= 0 && caption.bottom <= card.height)
                assertEquals(card.image.bottom, caption.top)
                assertTrue(card.image.height >= dp(48))
                for (label in listOf(card.title, card.count)) {
                    assertTrue(label.left >= caption.paddingLeft && label.right <= caption.width - caption.paddingRight)
                    assertTrue(label.top >= caption.paddingTop && label.bottom <= caption.height - caption.paddingBottom)
                    assertTrue(label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                    for (line in 0 until label.layout.lineCount) assertEquals(0, label.layout.getEllipsisCount(line))
                }
                assertTrue(card.title.bottom <= card.count.top)
                val empty = AlbumRows.empty(id)
                assertEquals(if (empty) View.VISIBLE else View.GONE, card.placeholder.visibility)
                assertEquals(if (empty) View.GONE else View.VISIBLE, card.image.visibility)
                if (empty) {
                    assertNull(card.image.drawable)
                    assertTrue(card.placeholder.width > 0 && card.placeholder.height > 0)
                    assertTrue(card.placeholder.left >= card.image.left && card.placeholder.right <= card.image.right)
                    assertTrue(card.placeholder.top >= card.image.top && card.placeholder.bottom <= card.image.bottom)
                } else assertNotNull(card.image.drawable)
                if (requireVisible) {
                    assertTrue("Restored full caption is inside the viewport", card.top + caption.top >= 0)
                    assertTrue(card.top + caption.bottom <= list.height)
                }
                val node = card.createAccessibilityNodeInfo()
                try {
                    assertEquals(Button::class.java.name, node.className.toString())
                    assertEquals(AlbumRows.description(id), node.contentDescription.toString())
                    assertTrue(node.isClickable)
                } finally { @Suppress("DEPRECATION") node.recycle() }
            }
            if (compact) assertEquals("Long caption leaves the minimum recognizable media",
                dp(48), cards[1].image.height)
        }
    }

    private class AlbumRows(private val context: Context, private val budget: () -> Int?) :
        RecyclerView.Adapter<AlbumRows.Holder>() {
        class Holder(val cover: GalleryAlbumCover) : RecyclerView.ViewHolder(cover) { var bound = -1 }
        var recycled = 0
        var rebound = 0
        var placeholderSwitches = 0
        var clicked = -1L
        init { setHasStableIds(true) }
        override fun getItemCount() = 60
        override fun getItemId(position: Int) = position.toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(GalleryAlbumCover(context, GalleryDesign.palette(context, dark = false), budget).apply {
                val margin = (4 * resources.displayMetrics.density).roundToInt()
                layoutParams = GridLayoutManager.LayoutParams(-1, -2).apply { setMargins(margin, margin, margin, margin) }
                placeholder.setImageDrawable(ColorDrawable(Color.GRAY))
            })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (holder.bound >= 0 && holder.bound != position) {
                rebound++
                if (empty(holder.bound) != empty(position)) placeholderSwitches++
            }
            holder.bound = position
            holder.cover.apply {
                title.text = name(position)
                count.text = "$position"
                contentDescription = description(position)
                image.setImageDrawable(if (empty(position)) null else ColorDrawable(Color.BLUE))
                image.visibility = if (empty(position)) View.GONE else View.VISIBLE
                placeholder.visibility = if (empty(position)) View.VISIBLE else View.GONE
                setOnClickListener { clicked = position.toLong() }
                forceLayout()
            }
        }
        override fun onViewRecycled(holder: Holder) { recycled++; super.onViewRecycled(holder) }
        companion object {
            fun name(position: Int) = if (position % 2 == 0) "One" else "Owned album"
            fun empty(position: Int) = position / 2 % 2 != 0
            fun description(position: Int) = "${name(position)}, item $position"
        }
    }

    private class Fixture(font: Float, density: Int, dark: Boolean, rtl: Boolean) {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context: Context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            fontScale = font
            densityDpi = density
        })
        var budget: Int? = null
        val cover = GalleryAlbumCover(context, GalleryDesign.palette(context, dark)) { budget }.apply {
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            image.setImageDrawable(ColorDrawable(Color.WHITE))
            placeholder.visibility = View.GONE
            title.text = "Gallery owned landscape album"
            count.text = "12 · local"
        }
        val caption get() = cover.title.parent as ViewGroup
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        fun layout(width: Int, force: Boolean = true) = layoutPx(width, null, force)
        fun layoutPx(width: Int, matchedHeight: Int?, force: Boolean = true) {
            // App invalidates retained covers when the externally owned height budget changes.
            if (force) cover.forceLayout()
            cover.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                if (matchedHeight == null) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                else View.MeasureSpec.makeMeasureSpec(matchedHeight, View.MeasureSpec.EXACTLY))
            cover.layout(0, 0, cover.measuredWidth, cover.measuredHeight)
        }
        fun assertCaptionFits() {
            assertEquals(cover.height, caption.bottom)
            assertEquals("Media and text never overlap", cover.image.bottom, caption.top)
            assertTrue(cover.image.height >= dp(48))
            assertTrue(cover.height >= dp(48))
            assertEquals(2, cover.title.maxLines)
            for (label: NativeText in listOf(cover.title, cover.count)) {
                assertTrue(label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                assertTrue(label.left >= caption.paddingLeft)
                assertTrue(label.right <= caption.width - caption.paddingRight)
                assertTrue(label.top >= caption.paddingTop)
                assertTrue(label.bottom <= caption.height - caption.paddingBottom)
            }
            assertTrue(cover.title.bottom <= cover.count.top)
        }
        fun assertRestoration() {
            val restored = cover.readableRestoredOffset(-cover.height - dp(50))
            assertTrue("Restoration exposes the full caption", caption.top + restored >= 0)
            assertTrue("Restoration exposes at least 48dp of the same card", cover.height + restored >= dp(48))
            assertTrue(restored <= 0)
            assertEquals(-dp(4), cover.readableRestoredOffset(-dp(4)))
        }
        fun assertCaptionContrast(bitmap: Bitmap) {
            for (label in listOf(cover.title, cover.count)) {
                for (y in caption.top + label.top until caption.top + label.bottom) {
                    val background = bitmap.getPixel(caption.paddingLeft / 2, y)
                    assertTrue("Caption contrast at y=$y", ColorUtils.calculateContrast(label.currentTextColor, background) >= 4.5)
                }
            }
        }
        fun captionPixels(bitmap: Bitmap) = IntArray(caption.width * caption.height).also {
            bitmap.getPixels(it, 0, caption.width, 0, caption.top, caption.width, caption.height)
        }
        fun render() = Bitmap.createBitmap(cover.width, cover.height, Bitmap.Config.ARGB_8888).also {
            cover.draw(Canvas(it))
        }
    }

    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
