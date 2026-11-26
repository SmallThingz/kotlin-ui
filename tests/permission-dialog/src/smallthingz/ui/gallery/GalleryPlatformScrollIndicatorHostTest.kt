package smallthingz.ui.gallery

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import smallthingz.ui.controls.textSurfaceRange

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class GalleryPlatformScrollIndicatorHostTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun insetModesPreserveStockScrollbarAndContentGeometry() {
        for (mode in listOf(View.SCROLLBARS_INSIDE_INSET, View.SCROLLBARS_OUTSIDE_INSET)) {
            val scroll = scrollingView()
            val thumb = stockDrawable(Color.BLUE)
            val track = stockDrawable(Color.GRAY)
            scroll.verticalScrollbarThumbDrawable = thumb
            scroll.verticalScrollbarTrackDrawable = track
            scroll.scrollBarStyle = mode
            scroll.verticalScrollbarPosition = View.SCROLLBAR_POSITION_LEFT
            scroll.isVerticalScrollBarEnabled = true
            scroll.isScrollbarFadingEnabled = true
            scroll.setPadding(7, 5, 20, 9)
            place(scroll)
            assertTrue("Platform inset must reserve space for the stock scrollbar", scroll.paddingLeft > 7)
            assertTrue(scroll.canScrollVertically(1))
            scroll.scrollTo(0, 73)
            assertEquals(73, scroll.scrollY)
            val before = geometry(scroll)

            GalleryPlatformScrollIndicator(
                scroll,
                palette = { error("Inset mode must not sample the palette") },
                paintedSurface = { error("Inset mode must not sample the underlay") },
                style = { error("Inset mode must leave the stock configuration alone") },
            ).use { binding ->
                binding.refresh()
                place(scroll)
                assertEquals(mode, scroll.scrollBarStyle)
                assertEquals(View.SCROLLBAR_POSITION_LEFT, scroll.verticalScrollbarPosition)
                assertTrue(scroll.isVerticalScrollBarEnabled)
                assertTrue(scroll.isScrollbarFadingEnabled)
                assertSame(thumb, scroll.verticalScrollbarThumbDrawable)
                assertSame(track, scroll.verticalScrollbarTrackDrawable)
                assertEquals(before, geometry(scroll))
                assertEquals(73, scroll.scrollY)
                scroll.isScrollbarFadingEnabled = false
            }
            assertFalse("Passive close must preserve the caller's later setting", scroll.isScrollbarFadingEnabled)
            assertEquals(before, geometry(scroll))
        }
    }

    @Test fun styleSupplierCanCloseWithoutReenablingTheScrollbar() {
        val scroll = scrollingView()
        val palette = GalleryDesign.palette(context, false).pageControls
        var retireOnSample = false
        lateinit var owner: GalleryPlatformScrollIndicator
        owner = GalleryPlatformScrollIndicator(
            scroll,
            palette = { palette },
            paintedSurface = { textSurfaceRange(Color.WHITE) },
            style = {
                if (retireOnSample) owner.close()
                GalleryDesign.appearance.scrollIndicator
            },
        )
        owner.use {
            assertTrue(scroll.isVerticalScrollBarEnabled)
            retireOnSample = true
            it.refresh()
            assertFalse(scroll.isVerticalScrollBarEnabled)
        }
    }

    @Test fun paintSuppliersCanCloseWithoutReplacingRestoredThumbOrTrack() {
        for (closeFromUnderlay in listOf(true, false)) {
            val scroll = scrollingView()
            val thumb = stockDrawable(Color.BLUE)
            val track = stockDrawable(Color.GRAY)
            scroll.verticalScrollbarThumbDrawable = thumb
            scroll.verticalScrollbarTrackDrawable = track
            val palette = GalleryDesign.palette(context, false).pageControls
            var style = GalleryDesign.appearance.scrollIndicator
            var retireOnSample = false
            lateinit var owner: GalleryPlatformScrollIndicator
            owner = GalleryPlatformScrollIndicator(
                scroll,
                palette = {
                    if (retireOnSample && !closeFromUnderlay) owner.close()
                    palette
                },
                paintedSurface = {
                    if (retireOnSample && closeFromUnderlay) owner.close()
                    textSurfaceRange(Color.WHITE)
                },
                style = { style },
            )
            owner.use {
                assertTrue(scroll.isVerticalScrollBarEnabled)
                assertNotSame(thumb, scroll.verticalScrollbarThumbDrawable)
                style = style.copy(widthDp = style.widthDp + 4f)
                retireOnSample = true
                it.refresh()
                assertFalse(scroll.isVerticalScrollBarEnabled)
                assertSame(thumb, scroll.verticalScrollbarThumbDrawable)
                assertSame(track, scroll.verticalScrollbarTrackDrawable)
            }
        }
    }

    private fun scrollingView() = ScrollView(context).apply {
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        val body = View(context).apply { minimumHeight = 720 }
        addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun stockDrawable(color: Int) = GradientDrawable().apply {
        setSize(9, 0)
        setColor(color)
    }

    private fun place(scroll: ScrollView) {
        scroll.measure(MeasureSpec.makeMeasureSpec(240, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(180, MeasureSpec.EXACTLY))
        scroll.layout(0, 0, 240, 180)
    }

    private fun geometry(scroll: ScrollView): List<Int> {
        val body = scroll.getChildAt(0)
        return listOf(scroll.measuredWidth, scroll.measuredHeight, scroll.paddingLeft, scroll.paddingTop,
            scroll.paddingRight, scroll.paddingBottom, body.left, body.top, body.right, body.bottom)
    }
}
