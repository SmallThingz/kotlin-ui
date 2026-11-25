package smallthingz.ui.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.PathParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.CachedPathIconDrawable
import smallthingz.ui.components.NativeThemeIconDrawable
import smallthingz.ui.components.NativeThemeMode
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbIconDrawable

@RunWith(AndroidJUnit4::class)
class MaterialRoundedIconIntegrationTest {
    @Test fun compatibilityAdaptersShareMasksAndKeepThemePixels() {
        for (legacy in ReverbIcon.entries) {
            legacy.materialIcon?.let { assertSame(it.mask, legacy.mask) }
        }
        for ((mode, icon) in listOf(
            NativeThemeMode.SYSTEM to MaterialRoundedIcon.Devices,
            NativeThemeMode.LIGHT to MaterialRoundedIcon.LightMode,
            NativeThemeMode.DARK to MaterialRoundedIcon.DarkMode,
        )) {
            assertArrayEquals(
                pixels(NativeThemeIconDrawable(mode, 1.75f, Color.CYAN), 44),
                pixels(MaterialRoundedIconDrawable(icon, 1.75f, Color.CYAN), 44),
            )
        }
        for (icon in ReverbIcon.entries.filter { it.materialIcon != null }) {
            val common = MaterialRoundedIconDrawable(icon.materialIcon!!, 1.75f, Color.CYAN)
            val legacy = ReverbIconDrawable(icon, 1.75f, Color.CYAN)
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                common.layoutDirection = direction
                legacy.layoutDirection = direction
                assertArrayEquals(pixels(legacy, 44), pixels(common, 44))
            }
        }
    }

    @Test fun directBrowserPathMatchesHistoricalParserAtFractionalAndIntegerSizes() {
        // Independent historical input: production does not call a vector/path parser.
        val expected = requireNotNull(PathParser.createPathFromPathData(BROWSER_PATH))
        for (size in listOf(24, 44, 63, 96)) {
            val actual = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val reference = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(actual).apply { scale(size / 24f, size / 24f) }
                ReverbIcon.Browser.mask.draw(canvas, Paint(Paint.ANTI_ALIAS_FLAG))
                val referenceCanvas = Canvas(reference).apply { scale(size / 24f, size / 24f) }
                referenceCanvas.drawPath(expected, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
                val actualPixels = IntArray(size * size)
                val expectedPixels = IntArray(size * size)
                actual.getPixels(actualPixels, 0, size, 0, 0, size, size)
                reference.getPixels(expectedPixels, 0, size, 0, 0, size, size)
                assertTrue("Non-empty glyph at $size", actualPixels.any { it != 0 })
                assertArrayEquals("Browser path at $size", expectedPixels, actualPixels)
            } finally {
                actual.recycle()
                reference.recycle()
            }
        }
    }

    private fun pixels(drawable: CachedPathIconDrawable, size: Int): IntArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
        } finally { bitmap.recycle() }
    }

    private companion object {
        const val BROWSER_PATH = "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2 0 .68.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.9-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2 0-.68.07-1.35.16-2h4.68c.09.65.16 1.32.16 2 0 .68-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2 0-.68-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z"
    }
}
