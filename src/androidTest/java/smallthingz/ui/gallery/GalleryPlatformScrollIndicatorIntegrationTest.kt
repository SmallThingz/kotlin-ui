package smallthingz.ui.gallery

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.withRetiringTestActivity
import smallthingz.ui.controls.textSurfaceRange
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real platform scroll/layout/raster tests. No media, global settings or injected input.
 * API28 stock rendering is qualified only by its dedicated test on an actual API28 runtime.
 */
@RunWith(AndroidJUnit4::class)
class GalleryPlatformScrollIndicatorIntegrationTest {
    @Test fun overflowMovesPaintAtFiniteOffsetsAndFitRemovesIt() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        main {
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val f = Fixture(context(), direction)
                val before = f.geometry()
                assertTrue("Blank unbound fixture isolates indicator pixels", f.pixels().all { it == f.background })
                val binding = f.bind()
                try {
                    f.place()
                    assertEquals(before, f.geometry())
                    assertTrue(f.scroll.canScrollVertically(1))
                    val first = f.inkBounds()
                    assertFalse("Overflow must produce actual thumb pixels", first.isEmpty)
                    f.scroll.scrollTo(0, f.maximum() / 2)
                    val middle = f.inkBounds()
                    f.scroll.scrollTo(0, f.content.height) // Finite over-end request.
                    assertEquals(f.maximum(), f.scroll.scrollY)
                    val last = f.inkBounds()
                    assertTrue(first.top < middle.top)
                    assertTrue(middle.top < last.top)
                    assertEquals(first.left, last.left)
                    assertEquals(first.right, last.right)
                    assertTrue("Physical-right gutter, including RTL", first.left >= f.width - f.scroll.paddingRight)
                    assertTrue(last.right <= f.width && last.bottom <= f.height)
                    val settled = f.pixels()
                    assertArrayEquals("Repeated stationary draw retains cue", settled, f.pixels())

                    f.content.desiredHeight = 20
                    f.place()
                    assertFalse(f.scroll.canScrollVertically(-1))
                    assertFalse(f.scroll.canScrollVertically(1))
                    assertTrue("Fit draws neither thumb nor track", f.pixels().all { it == f.background })
                    f.content.desiredHeight = 720
                    f.place()
                    assertTrue(f.scroll.canScrollVertically(1))
                    assertFalse("Overflow restores paint after resize", f.inkBounds().isEmpty)
                } finally { binding.close() }
            }
        }
    }

    @Test fun bindingRetainsGeometryFocusAndPlatformKeyboardScroll() = main {
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val stock = Fixture(context(), direction)
            val bound = Fixture(context(), direction)
            val stockButton = stock.addBottomAction()
            val boundButton = bound.addBottomAction()
            assertTrue(stockButton.requestFocus())
            assertTrue(boundButton.requestFocus())
            stock.place()
            bound.place()
            stock.scroll.scrollTo(0, 73)
            bound.scroll.scrollTo(0, 73)
            val geometry = bound.geometry()
            val binding = bound.bind()
            try {
                repeat(3) { binding.refresh(); bound.place() }
                assertEquals(geometry, bound.geometry())
                assertEquals(stock.scroll.scrollY, bound.scroll.scrollY)
                assertSame(boundButton, bound.scroll.findFocus())
                assertSame(bound.content, boundButton.parent)
                assertSame(bound.scroll, bound.content.parent)
                assertEquals(stock.scroll.fullScroll(View.FOCUS_DOWN), bound.scroll.fullScroll(View.FOCUS_DOWN))
                assertEquals(stock.scroll.scrollY, bound.scroll.scrollY)
                assertEquals(stock.actionBounds(stockButton), bound.actionBounds(boundButton))
                val action = bound.actionBounds(boundButton)
                val viewport = Rect()
                bound.scroll.getDrawingRect(viewport)
                viewport.top += bound.scroll.paddingTop
                viewport.bottom -= bound.scroll.paddingBottom
                assertTrue("Full action reachable in platform content coordinates", viewport.contains(action))
                assertEquals(0, bound.clicks)
                assertTrue(boundButton.performClick())
                assertEquals("Binding must not duplicate action dispatch", 1, bound.clicks)
                val offset = bound.scroll.scrollY
                binding.close()
                binding.close()
                bound.place()
                assertEquals(geometry, bound.geometry())
                assertEquals(offset, bound.scroll.scrollY)
                assertSame(boundButton, bound.scroll.findFocus())
            } finally { binding.close() }
        }
    }

    @Test fun localDensityStyleAndPaintedUnderlayChangeOnlyIndicatorPixels() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        main {
            val widths = mutableListOf<Int>()
            for (dpi in listOf(180, 213, 420)) {
                val base = context()
                val configured = base.createConfigurationContext(Configuration(base.resources.configuration).apply { densityDpi = dpi })
                val f = Fixture(configured)
                val binding = f.bind()
                try {
                    f.scroll.scrollTo(0, 90)
                    val geometry = f.geometry()
                    val first = f.pixels()
                    widths += f.inkBounds().width()
                    f.style = f.style.copy(widthDp = 5.5f)
                    binding.refresh()
                    assertTrue(f.inkBounds().width() > widths.last())
                    assertEquals(geometry, f.geometry())
                    assertEquals(90, f.scroll.scrollY)
                    f.style = GalleryDesign.appearance.scrollIndicator
                    binding.refresh()
                    assertArrayEquals("No accumulated style geometry", first, f.pixels())
                    f.palette = f.palette.copy(muted = Color.BLUE)
                    binding.refresh()
                    assertTrue("Retained binding follows a new palette", f.pixels().any { it == Color.BLUE })
                    f.background = Color.BLACK // Same preferred blue palette now needs contrasting ink.
                    f.root.setBackgroundColor(f.background)
                    binding.refresh()
                    val dark = f.pixels()
                    assertTrue("Actual dark underlay receives light ink", dark.any { it == Color.WHITE })
                    assertEquals(geometry, f.geometry())
                    assertEquals(90, f.scroll.scrollY)
                    for (y in 0 until f.height) for (x in 0 until f.width - f.scroll.paddingRight)
                        assertEquals("No custom paint outside reserved right gutter", Color.BLACK, dark[y * f.width + x])
                    binding.close()
                    assertTrue("Original disabled scrollbar restored in actual raster", f.pixels().all { it == Color.BLACK })
                } finally { binding.close() }
            }
            assertTrue("Same dp thumb responds to distinct local densities", widths.last() > widths.first())
        }
    }

    @Test fun attachedRefreshRetiresOnDetachAndCloseWithoutRearming() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: FrameLayout? = null
        var fixture: Fixture? = null
        var binding: GalleryPlatformScrollIndicator? = null
        var samples = 0
        val attached = CountDownLatch(1)
        withRetiringTestActivity(activity, root = { host }, cleanup = {
            binding?.close()
            host?.removeAllViews()
        }) {
            main {
                val f = Fixture(activity)
                fixture = f
                val ownedHost = FrameLayout(activity)
                host = ownedHost
                f.scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) { attached.countDown() }
                    override fun onViewDetachedFromWindow(view: View) = Unit
                })
                binding = GalleryPlatformScrollIndicator(f.scroll, { f.palette }, { textSurfaceRange(f.background) },
                    style = { samples++; f.style })
                ownedHost.addView(f.root, FrameLayout.LayoutParams(f.width, f.height))
                activity.setContentView(ownedHost)
            }
            assertTrue("Owned fixture did not attach", attached.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            main {
                val f = checkNotNull(fixture)
                val owner = checkNotNull(host)
                assertTrue(f.scroll.isAttachedToWindow)
                val oldObserver = owner.viewTreeObserver
                samples = 0
                oldObserver.dispatchOnPreDraw()
                assertEquals("One sample per attached pre-draw", 1, samples)
                f.background = Color.BLACK
                f.root.setBackgroundColor(f.background)
                oldObserver.dispatchOnPreDraw() // No explicit binding.refresh().
                if (Build.VERSION.SDK_INT >= 29)
                    assertTrue("Attached underlay refresh changes real paint", f.pixels().any { it == Color.WHITE })
                owner.removeView(f.root)
                assertFalse(f.scroll.isAttachedToWindow)
                samples = 0
                oldObserver.dispatchOnPreDraw()
                assertEquals("Detached observer retired", 0, samples)
                owner.addView(f.root, FrameLayout.LayoutParams(f.width, f.height))
                assertTrue(f.scroll.isAttachedToWindow)
                samples = 0
                owner.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("Reattach registers once", 1, samples)
                checkNotNull(binding).close()
                checkNotNull(binding).close()
                owner.removeView(f.root)
                owner.addView(f.root, FrameLayout.LayoutParams(f.width, f.height))
                samples = 0
                owner.viewTreeObserver.dispatchOnPreDraw()
                checkNotNull(binding).refresh()
                assertEquals("Closed binding cannot rearm on attach or explicit refresh", 0, samples)
                assertTrue(f.pixels().all { it == f.background })
            }
        }
    }

    @Test fun api28FallbackMatchesStockRenderingAndRestoresStockBehavior() {
        assumeTrue("Requires actual API28; later runtimes do not qualify fallback", Build.VERSION.SDK_INT == 28)
        main {
            val f = Fixture(context())
            f.scroll.isVerticalScrollBarEnabled = true
            f.scroll.isScrollbarFadingEnabled = false
            f.scroll.verticalScrollbarPosition = View.SCROLLBAR_POSITION_RIGHT
            f.scroll.scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            val stockOverflow = f.pixels()
            val geometry = f.geometry()
            val binding = f.bind()
            try {
                assertArrayEquals("No replacement artwork on28", stockOverflow, f.pixels())
                assertEquals(geometry, f.geometry())
                f.scroll.scrollTo(0, f.content.height)
                assertEquals(f.maximum(), f.scroll.scrollY)
                val end = f.pixels()
                binding.close()
                assertArrayEquals("Stock end rendering survives release", end, f.pixels())
                f.content.desiredHeight = 20
                f.place()
                val stockFit = f.pixels()
                val fitting = f.bind()
                try { assertArrayEquals("Fit fallback adds no custom ink", stockFit, f.pixels()) }
                finally { fitting.close() }
            } finally { binding.close() }
        }
    }

    private class Content(context: Context) : FrameLayout(context) {
        var desiredHeight = 720
            set(value) { field = value; requestLayout() }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            // ScrollView supplies UNSPECIFIED height: this fixture has real intrinsic content.
            setMeasuredDimension(measuredWidth, resolveSize(desiredHeight, heightMeasureSpec))
        }
    }

    private class Fixture(context: Context, direction: Int = View.LAYOUT_DIRECTION_LTR) {
        val width = 240
        val height = 180
        var background = Color.WHITE
        var palette = GalleryDesign.palette(context, false).pageControls.copy(muted = Color.BLACK, surfacePreset = null)
        var style = GalleryDesign.appearance.scrollIndicator
        val root = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        val scroll = ScrollView(context).apply {
            layoutDirection = direction
            setPadding(7, 5, 20, 9)
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isSmoothScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = Content(context)
        var clicks = 0
        init {
            scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
            root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            place()
        }
        fun bind() = GalleryPlatformScrollIndicator(scroll, { palette }, { textSurfaceRange(background) }, { style })
        fun place() {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertEquals(content.desiredHeight, content.height)
        }
        fun maximum() = (content.height - (scroll.height - scroll.paddingTop - scroll.paddingBottom)).coerceAtLeast(0)
        fun geometry() = listOf(scroll.left, scroll.top, scroll.right, scroll.bottom,
            scroll.measuredWidth, scroll.measuredHeight, scroll.paddingLeft, scroll.paddingTop,
            scroll.paddingRight, scroll.paddingBottom, content.left, content.top, content.right, content.bottom)
        fun addBottomAction(): Button = Button(root.context).apply {
            text = "Retained action"
            isFocusableInTouchMode = true
            setOnClickListener { clicks++ }
            content.addView(this, FrameLayout.LayoutParams(-1, 60, Gravity.BOTTOM))
            place()
        }
        fun actionBounds(view: View): Rect = Rect(0, 0, view.width, view.height).also {
            scroll.offsetDescendantRectToMyCoords(view, it)
        }
        fun pixels(): IntArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return try {
                root.draw(Canvas(bitmap))
                IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
            } finally { bitmap.recycle() }
        }
        fun inkBounds(): Rect = Rect().also { rect ->
            pixels().forEachIndexed { i, pixel -> if (pixel != background) rect.union(i % width, i / width, i % width + 1, i / width + 1) }
        }
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
