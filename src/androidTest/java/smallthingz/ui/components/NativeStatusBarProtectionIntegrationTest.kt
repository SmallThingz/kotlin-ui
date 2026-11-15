package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bitmap checks for clipping only; these do not claim SystemUI or window-transition parity. */
@RunWith(AndroidJUnit4::class)
class NativeStatusBarProtectionIntegrationTest {
    @Suppress("DEPRECATION")
    @Test fun opaqueChildDoesNotAdvertiseAnOpaqueWindowOrFullOutline() {
        val child = ColorDrawable(Color.BLACK)
        val protection = NativeStatusBarProtection(child)
        protection.setBounds(0, 0, 20, 20)
        protection.updateInsets(statusInsets(Insets.of(0, 4, 0, 0)))
        assertEquals(PixelFormat.OPAQUE, child.opacity)
        assertEquals(PixelFormat.TRANSLUCENT, protection.opacity)
        val outline = Outline().apply { setRect(0, 0, 20, 20); alpha = 1f }
        protection.getOutline(outline)
        assertTrue(outline.isEmpty)
        assertEquals(0f, outline.alpha, 0f)
        assertNull(protection.transparentRegion)
        assertPixels(protection, 20, 20) { _, y -> y < 4 }
    }

    @Test fun drawsOnlyPhysicalStatusEdgesInEitherLayoutDirection() {
        val protection = NativeStatusBarProtection(ColorDrawable(Color.BLACK))
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(3, 4, 5, 6))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 12))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 9, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 14))
            .build()
        protection.updateInsets(insets)
        protection.setBounds(0, 0, 20, 20)
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            protection.setLayoutDirection(direction)
            assertPixels(protection, 20, 20) { x, y -> x < 3 || x >= 15 || y < 4 || y >= 14 }
        }
    }

    @Test fun hiddenBarDoesNotUseIgnoringVisibilityOrCutoutInsets() {
        val protection = NativeStatusBarProtection(ColorDrawable(Color.BLACK))
        protection.setBounds(0, 0, 20, 20)
        protection.updateInsets(WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 8, 0, 0))
            .setVisible(WindowInsetsCompat.Type.statusBars(), false)
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 8, 0, 0))
            .build())
        assertPixels(protection, 20, 20) { _, _ -> false }
    }

    @Test fun resizeAndInsetProgressCannotPaintOutsideCurrentBounds() {
        val protection = NativeStatusBarProtection(ColorDrawable(Color.BLACK))
        protection.setBounds(2, 1, 12, 9)
        protection.updateInsets(statusInsets(Insets.of(1, 2, 0, 0)))
        assertPixels(protection, 20, 20) { x, y -> x in 2 until 12 && y in 1 until 9 && (y < 3 || x == 2) }
        protection.setBounds(2, 1, 6, 4)
        protection.updateInsets(statusInsets(Insets.of(0, 100, 0, 0)))
        assertPixels(protection, 20, 20) { x, y -> x in 2 until 6 && y in 1 until 4 }
        protection.updateInsets(statusInsets(Insets.NONE))
        assertPixels(protection, 20, 20) { _, _ -> false }
    }

    private fun statusInsets(insets: Insets) = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), insets).build()

    private fun assertPixels(
        protection: NativeStatusBarProtection,
        width: Int,
        height: Int,
        isProtected: (Int, Int) -> Boolean,
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            protection.draw(Canvas(bitmap))
            for (y in 0 until height) for (x in 0 until width) {
                assertEquals("pixel($x,$y)", if (isProtected(x, y)) Color.BLACK else Color.WHITE,
                    bitmap.getPixel(x, y))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
