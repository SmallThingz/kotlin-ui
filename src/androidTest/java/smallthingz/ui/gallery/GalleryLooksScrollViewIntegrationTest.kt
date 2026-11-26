package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.scroll.NativeScrollIndicatorStyle
import smallthingz.ui.scroll.horizontalContentGutterPx

/** Detached presentation checks; no activity, media, global configuration or input injection. */
@RunWith(AndroidJUnit4::class)
class GalleryLooksScrollViewIntegrationTest {
    @Test fun gutterFollowsStyleAtFractionalDensitiesWithoutAccumulatingOrMovingContent() = main {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (dpi in listOf(180, 213, 420)) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { densityDpi = dpi })
            val palette = GalleryDesign.palette(context, false).pageControls.copy(surfacePreset = null)
            val f = Fixture(context, palette)
            f.scroll.setPadding(7, 3, 11, f.scroll.paddingBottom)
            f.place()
            f.scroll.scrollTo(120, 0)
            val offset = f.scroll.scrollX
            val styles = listOf(GalleryDesign.appearance.scrollIndicator,
                NativeScrollIndicatorStyle(5.5f, 3.25f, 31.5f), GalleryDesign.appearance.scrollIndicator)
            for (style in styles) repeat(2) {
                // Same palette: style/density measurement cannot hide behind palette equality.
                f.scroll.bindAppearance(palette, style)
                f.place()
                assertEquals(style.horizontalContentGutterPx(context.resources.displayMetrics.density), f.scroll.paddingBottom)
                assertEquals(7, f.scroll.paddingLeft)
                assertEquals(11, f.scroll.paddingRight)
                assertEquals(3, f.scroll.paddingTop)
                assertEquals(offset, f.scroll.scrollX)
                assertSame(f.scroll, f.child.parent)
                assertEquals(1, f.scroll.childCount)
                assertFalse(f.scroll.isHorizontalScrollBarEnabled)
                assertFalse(f.scroll.isVerticalScrollBarEnabled)
                assertTrue(f.scroll.clipIndicatorContentToBottomPadding)
            }
        }
    }

    @Test fun indicatorUsesPaintedPanelAndProtectedGutterAndDisappearsWhenContentFits() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val palette = GalleryDesign.palette(context, dark).pageControls.copy(surfacePreset = null)
            val f = Fixture(context, palette)
            f.scroll.layoutDirection = direction
            f.scroll.clipToPadding = false
            f.place()
            f.scroll.scrollTo(120, 0)
            val offset = f.scroll.scrollX
            val painted = f.pixels()
            f.child.visibility = View.INVISIBLE
            val withoutChild = f.pixels()
            val firstGutterRow = f.scroll.height - f.scroll.paddingBottom
            for (y in firstGutterRow until f.scroll.height) for (x in 0 until f.scroll.width) {
                assertEquals("Child overpaint must not enter the indicator gutter", withoutChild[y * 240 + x], painted[y * 240 + x])
            }
            val expectedInk = textSurfaceForeground(f.panel.paintedTextSurface, palette.muted)
            assertNotEquals("Fixture forces contrast against actual paint, not palette.surface", palette.muted, expectedInk)
            assertTrue(withoutChild.any { it == expectedInk })
            assertEquals(offset, f.scroll.scrollX)

            // Change the actual panel while retaining exactly the same indicator palette.
            f.panel.applyStyle(NativeSurfaceStyle(palette.surface, cornerRadiusDp = 0f))
            val rebound = f.pixels()
            assertTrue(rebound.any { it == textSurfaceForeground(f.panel.paintedTextSurface, palette.muted) })
            assertEquals(offset, f.scroll.scrollX)

            f.child.layoutParams = f.child.layoutParams.apply { width = 20 }
            f.place()
            assertFalse(f.scroll.canScrollHorizontally(-1))
            assertFalse(f.scroll.canScrollHorizontally(1))
            assertTrue("No thumb when content fits", f.pixels().all { it == f.panel.paintedFillColor })
            f.child.layoutParams = f.child.layoutParams.apply { width = 720 }
            f.place()
            assertTrue("Overflow restores the shared thumb", f.pixels().any { it != f.panel.paintedFillColor })
        }
    }

    private class Fixture(context: Context, palette: ControlPalette) {
        // Deliberately differ from palette.surface: the adapter must sample the actual panel.
        val panel = NativeSurface(context, NativeSurfaceStyle(palette.muted, cornerRadiusDp = 0f))
        val scroll = GalleryLooksScrollView(context, palette) { panel.paintedTextSurface }
        val child = object : View(context) {
            private val paint = Paint().apply { color = palette.accent }
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                // HorizontalScrollView supplies UNSPECIFIED width. Give this paint-only fixture
                // a desired width, including subsequent LayoutParams resize probes.
                setMeasuredDimension(resolveSize(layoutParams.width.coerceAtLeast(0), widthMeasureSpec), measuredHeight)
            }
            override fun onDraw(canvas: Canvas) {
                canvas.drawRect(0f, 0f, width.toFloat(), height + 100f, paint)
            }
        }
        init {
            scroll.addView(child, FrameLayout.LayoutParams(720, 80))
            panel.addView(scroll, FrameLayout.LayoutParams(240, 100))
        }
        fun place() {
            panel.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, 240, 100)
            assertEquals("Fixture viewport width", 240, scroll.width)
            assertEquals("Fixture child measured width", child.layoutParams.width, child.measuredWidth)
            assertEquals("Fixture child laid-out width", child.layoutParams.width, child.width)
            val expectedOverflow = child.layoutParams.width > scroll.width - scroll.paddingLeft - scroll.paddingRight
            assertEquals("Fixture must exercise the intended overflow/fit state", expectedOverflow,
                scroll.canScrollHorizontally(-1) || scroll.canScrollHorizontally(1))
        }
        fun pixels(): IntArray {
            val bitmap = Bitmap.createBitmap(240, 100, Bitmap.Config.ARGB_8888)
            return try {
                panel.draw(Canvas(bitmap))
                IntArray(240 * 100).also { bitmap.getPixels(it, 0, 240, 0, 0, 240, 100) }
            } finally { bitmap.recycle() }
        }
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
