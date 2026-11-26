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

    @Test fun credentialEyesMatchPinnedRoundedPathsAtIntegerAndFractionalSizes() {
        // Independent SVG command input from pinned AndroidX Rounded1.7.8, including reflective curves.
        for ((icon, data) in listOf(MaterialRoundedIcon.Visibility to VISIBILITY_PATH,
            MaterialRoundedIcon.VisibilityOff to VISIBILITY_OFF_PATH)) {
            val expected = requireNotNull(PathParser.createPathFromPathData(data))
            for (size in listOf(24, 44, 63, 96)) {
                val actual = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val reference = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                try {
                    icon.mask.draw(Canvas(actual).apply { scale(size / 24f, size / 24f) }, Paint(Paint.ANTI_ALIAS_FLAG))
                    Canvas(reference).apply { scale(size / 24f, size / 24f) }
                        .drawPath(expected, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
                    val actualPixels = IntArray(size * size)
                    val expectedPixels = IntArray(size * size)
                    actual.getPixels(actualPixels, 0, size, 0, 0, size, size)
                    reference.getPixels(expectedPixels, 0, size, 0, 0, size, size)
                    assertTrue(actualPixels.any { it != 0 })
                    assertArrayEquals("$icon canonical path at $size", expectedPixels, actualPixels)
                } finally { actual.recycle(); reference.recycle() }
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
        const val VISIBILITY_PATH = "M12.0 4.0C7.0 4.0 2.73 7.11 1.0 11.5C2.73 15.89 7.0 19.0 12.0 19.0s9.27 -3.11 11.0 -7.5C21.27 7.11 17.0 4.0 12.0 4.0zM12.0 16.5c-2.76 0.0 -5.0 -2.24 -5.0 -5.0s2.24 -5.0 5.0 -5.0s5.0 2.24 5.0 5.0s-2.24 5.0 -5.0 5.0zM12.0 8.5c-1.66 0.0 -3.0 1.34 -3.0 3.0s1.34 3.0 3.0 3.0s3.0 -1.34 3.0 -3.0s-1.34 -3.0 -3.0 -3.0z"
        const val VISIBILITY_OFF_PATH = "M12.0 6.5c2.76 0.0 5.0 2.24 5.0 5.0c0.0 0.51 -0.1 1.0 -0.24 1.46l3.06 3.06c1.39 -1.23 2.49 -2.77 3.18 -4.53C21.27 7.11 17.0 4.0 12.0 4.0c-1.27 0.0 -2.49 0.2 -3.64 0.57l2.17 2.17c0.47 -0.14 0.96 -0.24 1.47 -0.24zM2.71 3.16c-0.39 0.39 -0.39 1.02 0.0 1.41l1.97 1.97C3.06 7.83 1.77 9.53 1.0 11.5C2.73 15.89 7.0 19.0 12.0 19.0c1.52 0.0 2.97 -0.3 4.31 -0.82l2.72 2.72c0.39 0.39 1.02 0.39 1.41 0.0c0.39 -0.39 0.39 -1.02 0.0 -1.41L4.13 3.16c-0.39 -0.39 -1.03 -0.39 -1.42 0.0zM12.0 16.5c-2.76 0.0 -5.0 -2.24 -5.0 -5.0c0.0 -0.77 0.18 -1.5 0.49 -2.14l1.57 1.57c-0.03 0.18 -0.06 0.37 -0.06 0.57c0.0 1.66 1.34 3.0 3.0 3.0c0.2 0.0 0.38 -0.03 0.57 -0.07L14.14 16.0c-0.65 0.32 -1.37 0.5 -2.14 0.5zM14.97 11.17c-0.15 -1.4 -1.25 -2.49 -2.64 -2.64l2.64 2.64z"
        const val BROWSER_PATH = "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2 0 .68.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.9-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2 0-.68.07-1.35.16-2h4.68c.09.65.16 1.32.16 2 0 .68-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2 0-.68-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z"
    }
}
