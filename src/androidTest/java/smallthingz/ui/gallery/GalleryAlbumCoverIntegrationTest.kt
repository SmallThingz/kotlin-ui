package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeText
import kotlin.math.roundToInt

/** Detached production views and in-memory pixels only; no Activity, media or device input. */
@RunWith(AndroidJUnit4::class)
class GalleryAlbumCoverIntegrationTest {
    @Test fun whiteCoverProtectsBothTitleLinesAndTranslucentCount() = main {
        for (font in listOf(1f, 2f)) for (density in listOf(160, 240))
            for (dark in listOf(false, true)) for (rtl in listOf(false, true)) {
                val f = Fixture(font, density, dark, rtl)
                f.layout(180)
                assertEquals(2, f.cover.title.layout.lineCount)
                f.assertCaptionFits()
                val bitmap = f.render()
                try {
                    // Sample untouched horizontal padding alongside EVERY text row. A white
                    // image is the worst background; checking only the bottom misses this bug.
                    for (label in listOf(f.cover.title, f.cover.count)) {
                        val x = f.caption.left + f.caption.paddingLeft / 2
                        val top = f.caption.top + label.top
                        for (y in top until top + label.height) {
                            val background = bitmap.getPixel(x, y)
                            assertTrue("font=$font density=$density dark=$dark rtl=$rtl y=$y ink=${label.currentTextColor} bg=$background",
                                ColorUtils.calculateContrast(label.currentTextColor, background) >= 4.5)
                        }
                    }
                    if (f.caption.top > f.dp(32)) {
                        assertEquals("Upper image remains unchanged", Color.WHITE, bitmap.getPixel(bitmap.width / 2, f.dp(1)))
                        val fade = bitmap.getPixel(bitmap.width / 2, f.caption.top - f.dp(12))
                        val band = bitmap.getPixel(f.caption.paddingLeft / 2, f.caption.top)
                        assertTrue("Fade is above the protected band", Color.red(fade) > Color.red(band))
                        assertTrue("Fade has no abrupt opaque box edge", Color.red(fade) < 255)
                    }
                } finally { bitmap.recycle() }
            }
    }

    @Test fun compactMatchedRowsAndRestorationRetainNodesAndCaptionGeometry() = main {
        for (font in listOf(1f, 2f)) {
            val f = Fixture(font, 240, dark = true, rtl = false)
            val image = f.cover.image
            val drawable = image.drawable
            val placeholder = f.cover.placeholder
            val title = f.cover.title
            val count = f.cover.count
            f.layout(320)
            assertEquals(f.dp(320), f.cover.height)
            assertEquals(2, title.maxLines)
            f.budget = f.dp(128)
            f.layout(320)
            assertEquals(f.dp(128), f.cover.height)
            assertEquals(1, title.layout.lineCount)
            assertEquals(1, title.maxLines)
            f.assertCaptionFits()
            val restored = f.cover.readableRestoredOffset(-f.cover.height - f.dp(50))
            assertTrue("Restoration exposes the whole caption", f.caption.top + restored >= 0)
            assertTrue("Restoration retains a 48dp target", f.cover.height + restored >= f.dp(48))
            assertTrue(restored <= 0)
            assertEquals(-f.dp(4), f.cover.readableRestoredOffset(-f.dp(4)))

            // A matched grid row is authoritative even when its budget differs.
            f.budget = f.dp(80)
            f.layout(320, matchedHeight = 144)
            assertEquals(f.dp(144), f.cover.height)
            f.assertCaptionFits()
            f.budget = f.dp(1)
            f.layout(320)
            assertEquals("An unreadably short budget falls back to a square", f.dp(320), f.cover.height)
            f.budget = null
            f.layout(320)
            assertEquals(2, title.maxLines)
            f.assertCaptionFits()
            assertEquals(-f.dp(400), f.cover.readableRestoredOffset(-f.dp(400)))

            f.cover.paint(GalleryDesign.palette(f.context, dark = false))
            f.cover.title.text = "Replacement owned album name"
            f.layout(320)
            f.assertCaptionFits()
            assertSame(image, f.cover.image)
            assertSame(drawable, image.drawable)
            assertSame(placeholder, f.cover.placeholder)
            assertSame(title, f.cover.title)
            assertSame(count, f.cover.count)
            val height = f.cover.height
            f.layout(320)
            assertEquals(height, f.cover.height)
            assertFalse("Unchanged layout settles", f.cover.isLayoutRequested)
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
        fun layout(width: Int, matchedHeight: Int? = null) {
            // The app explicitly invalidates cached covers when its external budget changes.
            cover.forceLayout()
            cover.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                if (matchedHeight == null) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                else View.MeasureSpec.makeMeasureSpec(dp(matchedHeight), View.MeasureSpec.EXACTLY))
            cover.layout(0, 0, cover.measuredWidth, cover.measuredHeight)
        }
        fun assertCaptionFits() {
            assertEquals(cover.height, caption.bottom)
            assertTrue(caption.top >= 0)
            assertTrue(cover.height >= dp(48))
            for (label: NativeText in listOf(cover.title, cover.count)) {
                assertTrue(label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                assertTrue(label.left >= caption.paddingLeft)
                assertTrue(label.right <= caption.width - caption.paddingRight)
                assertTrue(label.top >= caption.paddingTop)
                assertTrue(label.bottom <= caption.height - caption.paddingBottom)
            }
            assertTrue(cover.title.bottom <= cover.count.top)
        }
        fun render() = Bitmap.createBitmap(cover.width, cover.height, Bitmap.Config.ARGB_8888).also {
            cover.draw(Canvas(it))
        }
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
