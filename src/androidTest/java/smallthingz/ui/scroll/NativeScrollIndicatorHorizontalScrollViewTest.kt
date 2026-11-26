package smallthingz.ui.scroll

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeGrainPreset
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeScrollIndicatorHorizontalScrollViewTest {
    @Test fun parentDrawingKeepsThumbPhysicalAtNonzeroOffsetsAndRetainsPlatformState() = withShader { context ->
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val f = Fixture(context, direction)
            f.child.visibility = View.INVISIBLE
            f.scroll.bindIndicator(f.flat, f.style)
            f.scroll.bindIndicatorUnderlay(textSurfaceRange(Color.WHITE))
            f.scroll.scrollTo(0, 0)
            assertEquals(0, f.scroll.scrollX)
            assertFalse(f.scroll.willNotDraw())
            val first = f.thumbBounds()
            assertFalse(first.isEmpty)
            f.scroll.scrollTo(200, 0)
            assertEquals(200, f.scroll.scrollX)
            val middle = f.thumbBounds()
            assertTrue(middle.left > first.left)
            // Keep the over-end request finite: the platform clamp adds the viewport extent.
            val end = f.child.width - (f.scroll.width - f.scroll.paddingLeft - f.scroll.paddingRight)
            f.scroll.scrollTo(f.child.width, 0)
            assertEquals(end, f.scroll.scrollX)
            val last = f.thumbBounds()
            assertTrue(last.left > middle.left)
            assertEquals(first.top, last.top)
            assertTrue(last.right <= 120)
            assertTrue(last.bottom <= 80)
            val offset = f.scroll.scrollX
            f.scroll.bindIndicator(f.flat.copy(muted = Color.WHITE), f.style)
            f.scroll.bindIndicatorUnderlay(textSurfaceRange(Color.BLACK))
            assertEquals(offset, f.scroll.scrollX)
            assertTrue(f.pixels().any { it == Color.WHITE })
            f.scroll.visibility = View.GONE
            assertTrue(f.pixels().all { it == Color.TRANSPARENT })
            f.scroll.visibility = View.VISIBLE
            // Padded near-fit overflow must paint, and larger content must not reach the
            // thumb endpoint before the actual platform scroll endpoint (in either direction).
            f.scroll.setPadding(7, 4, 11, 12)
            for (childWidth in listOf(105, 600)) {
                f.child.layoutParams = f.child.layoutParams.apply { width = childWidth }
                f.place()
                assertEquals(direction, f.scroll.layoutDirection)
                val overflow = childWidth - (f.scroll.width - f.scroll.paddingLeft - f.scroll.paddingRight)
                assertTrue(overflow > 0)
                f.scroll.scrollTo(0, 0)
                assertEquals(0, f.scroll.scrollX)
                val left = f.thumbBounds()
                assertFalse("Padded overflow must have a thumb", left.isEmpty)
                assertEquals(0, left.left)
                // The inherited scrollbar metrics previously exhausted this range early.
                val beforeEnd = if (overflow > f.scroll.paddingRight) overflow - f.scroll.paddingRight else overflow / 2
                f.scroll.scrollTo(beforeEnd, 0)
                assertEquals(beforeEnd, f.scroll.scrollX)
                val approaching = f.thumbBounds()
                f.scroll.scrollTo(childWidth, 0)
                assertEquals(overflow, f.scroll.scrollX)
                val right = f.thumbBounds()
                assertTrue("Physical offset must advance the thumb", approaching.left > left.left)
                assertTrue("Thumb must not reach its endpoint early", approaching.left < right.left)
                assertEquals(f.scroll.width, right.right)
                assertEquals(left.top, right.top)
                assertEquals(left.bottom, right.bottom)
            }
            f.child.layoutParams = f.child.layoutParams.apply { width = 20 }
            f.place()
            assertFalse(f.scroll.canScrollHorizontally(1))
            assertFalse(f.scroll.canScrollHorizontally(-1))
            assertTrue(f.pixels().all { it == Color.TRANSPARENT })
        }
    }

    @Test fun bottomOnlyClipProtectsAaGutterWithoutChangingHorizontalBleedOrOffsets() = withShader { context ->
        for (dpi in listOf(180, 213, 420)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val configured = context.createConfigurationContext(Configuration(context.resources.configuration).apply { densityDpi = dpi })
            for (style in listOf(NativeScrollIndicatorStyle.Default, NativeScrollIndicatorStyle(2.75f, 1.125f, 31.5f), NativeScrollIndicatorStyle(5.5f, 3.25f, 31.5f))) {
                val f = Fixture(configured, direction, style)
                f.root.setBackgroundColor(Color.WHITE)
                val bottom = maxOf(12, style.horizontalContentGutterPx(configured.resources.displayMetrics.density))
                f.scroll.setPadding(16, 4, 16, bottom)
                f.place()
                f.scroll.scrollTo(150, 0)
                f.scroll.bindIndicator(f.flat, style)
                f.scroll.bindIndicatorUnderlay(textSurfaceRange(Color.WHITE))
                val before = f.pixels()
                val offset = f.scroll.scrollX
                f.scroll.clipIndicatorContentToBottomPadding = true
                val clipped = f.pixels()
                assertEquals(offset, f.scroll.scrollX)
                assertFalse("Existing horizontal bleed policy must remain unchanged", f.scroll.clipToPadding)
                assertEquals(Color.MAGENTA, clipped[20*120])
                for (y in 0 until 80-bottom) for (x in 0 until 120)
                    assertEquals("Bottom clip may not alter other viewport pixels", before[y*120+x], clipped[y*120+x])
                f.child.visibility = View.INVISIBLE
                val withoutChild = f.pixels()
                for (y in 80-bottom until 80) for (x in 0 until 120)
                    assertEquals("Child paint including overdraw must not reach gutter/AA", withoutChild[y*120+x], clipped[y*120+x])
                assertTrue("Actual thumb must still paint", (80-bottom until 80).any { y -> (0 until 120).any { x -> clipped[y*120+x] != Color.WHITE } })
                assertEquals(16, f.scroll.paddingLeft)
                assertEquals(16, f.scroll.paddingRight)
                assertEquals(4, f.scroll.paddingTop)
            }
        }
    }

    @Test fun grainRebindPreservesSilhouetteAlphaAndFlatRecovery() = withShader { context ->
        val f = Fixture(context, View.LAYOUT_DIRECTION_LTR)
        f.child.visibility = View.INVISIBLE
        f.scroll.scrollTo(170, 0)
        f.scroll.bindIndicatorUnderlay(textSurfaceRange(Color.BLACK))
        for (alpha in listOf(0, 31, 97, 255)) {
            val palette = f.flat.copy(muted = (alpha shl 24) or 0x808080)
            f.scroll.bindIndicator(palette, f.style)
            val flat = f.pixels()
            f.scroll.bindIndicator(palette.copy(surfacePreset = NativeGrainPreset(backgroundGrain = 0f, foregroundGrain = 1f)), f.style)
            val grain = f.pixels()
            val alphaDiffCount = flat.indices.count { Color.alpha(flat[it]) != Color.alpha(grain[it]) }
            val rgbDiffCount = flat.indices.count { (flat[it] and 0x00ffffff) != (grain[it] and 0x00ffffff) }
            val detail = "alpha=$alpha alphaDiffCount=$alphaDiffCount rgbDiffCount=$rgbDiffCount"
            for (i in flat.indices) assertEquals("$detail pixel=$i", Color.alpha(flat[i]), Color.alpha(grain[i]))
            if (alpha == 0) assertArrayEquals(detail, flat, grain)
            else assertTrue("Grain must alter actual indicator pixels: $detail", flat.indices.any { flat[it] != grain[it] })
            f.scroll.bindIndicator(palette, f.style)
            assertArrayEquals("Flat recovery: $detail", flat, f.pixels())
        }
    }

    private class Fixture(context: Context, direction: Int,
        val style: NativeScrollIndicatorStyle = NativeScrollIndicatorStyle.Default) {
        val root = FrameLayout(context)
        val scroll = NativeScrollIndicatorHorizontalScrollView(context).apply {
            layoutDirection = direction
            clipToPadding = false
            setPadding(16, 4, 16, 12)
        }
        val child = object : View(context) {
            private val paint = Paint().apply { color = Color.MAGENTA }
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                // HorizontalScrollView supplies UNSPECIFIED width. Give this paint-only fixture
                // a desired width, including subsequent LayoutParams resize probes.
                setMeasuredDimension(resolveSize(layoutParams.width.coerceAtLeast(0), widthMeasureSpec), measuredHeight)
            }
            override fun onDraw(canvas: Canvas) {
                // Deliberate bottom overpaint makes the clip test independent of natural padding.
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat()+100f, paint)
            }
        }
        val flat = NativeColorScheme.fallback(false).pageControls.copy(muted = Color.BLACK, surfacePreset = null)
        init {
            scroll.addView(child, FrameLayout.LayoutParams(600, 80))
            root.addView(scroll, FrameLayout.LayoutParams(120, 80))
            place()
        }
        fun place() {
            root.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 120, 80)
            assertEquals("Fixture viewport width", 120, scroll.width)
            assertEquals("Fixture child measured width", child.layoutParams.width, child.measuredWidth)
            assertEquals("Fixture child laid-out width", child.layoutParams.width, child.width)
            val expectedOverflow = child.layoutParams.width > scroll.width - scroll.paddingLeft - scroll.paddingRight
            assertEquals("Fixture must exercise the intended overflow/fit state", expectedOverflow,
                scroll.canScrollHorizontally(-1) || scroll.canScrollHorizontally(1))
        }
        fun pixels(): IntArray {
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            return try {
                root.draw(Canvas(bitmap))
                IntArray(120*80).also { bitmap.getPixels(it, 0, 120, 0, 0, 120, 80) }
            } finally { bitmap.recycle() }
        }
        fun thumbBounds(): Rect {
            val bounds = Rect()
            pixels().forEachIndexed { i, pixel -> if (Color.alpha(pixel) > 0) bounds.union(i%120, i/120, i%120+1, i/120+1) }
            return bounds
        }
    }

    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }

    private fun withShader(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var observer: NoiseDrawable
        val callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { if (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null) ready.countDown() }
            override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        main {
            observer = NoiseDrawable().apply { this.callback = callback }
            if (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null) ready.countDown()
        }
        try {
            assertTrue("Shared shader did not become ready", ready.await(5, TimeUnit.SECONDS))
            main { block(instrumentation.targetContext) }
        } finally { main { observer.callback = null } }
    }
}
