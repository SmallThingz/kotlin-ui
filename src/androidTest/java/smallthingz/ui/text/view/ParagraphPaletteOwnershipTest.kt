package smallthingz.ui.text.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.function.LongSupplier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp

/** Equal ARGB can change glyph ink when surface-contrast ownership changes. No animation needed. */
@RunWith(AndroidJUnit4::class)
class ParagraphPaletteOwnershipTest {
    @Test fun equalFirstPaletteTargetInvalidatesContrastAdjustedInk() = checkOwnershipChange(false)

    @Test fun equalExplicitColorInvalidatesWhenRetiringPaletteInk() = checkOwnershipChange(true)

    private fun checkOwnershipChange(paletteInitially: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val label = ParagraphView(context, TextStyle(fontSize = 32.sp), Density(1f),
                createFontFamilyResolver(context), Color.BLACK)
            val before = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
            val after = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
            try {
                label.setText("Ink")
                label.paintedTextSurface = LongSupplier { textSurfaceRange(Color.BLACK) }
                if (paletteInitially) label.animateTextColor(Color.BLACK)
                label.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
                label.layout(0, 0, 200, 80)
                val paragraph = checkNotNull(label.currentParagraph)
                // View.draw clears its dirty bits even with a software canvas. This establishes
                // a settled draw without a platform frame, a window, or animator-scale assumptions.
                label.draw(Canvas(before))
                assertFalse("The initial draw must leave the label clean", label.isDirty)
                assertContainsInk(before, if (paletteInitially) Color.WHITE else Color.BLACK)

                if (paletteInitially) label.setTextColor(Color.BLACK)
                else label.animateTextColor(Color.BLACK)

                assertTrue("Changing contrast ownership must invalidate equal-ARGB text", label.isDirty)
                assertSame("A paint ownership change must retain paragraph layout", paragraph, label.currentParagraph)
                label.draw(Canvas(after))
                assertContainsInk(after, if (paletteInitially) Color.BLACK else Color.WHITE)
                assertFalse("Equal raw ARGB has different painted glyph pixels", before.sameAs(after))
                assertFalse(label.isDirty)

                // Once ownership is settled, repeating the identical request remains a no-op.
                if (paletteInitially) label.setTextColor(Color.BLACK)
                else label.animateTextColor(Color.BLACK)
                assertFalse("An unchanged ownership and target must stay clean", label.isDirty)
            } finally {
                label.setTextColor(Color.BLACK) // Retire the optional motion owner's listener.
                before.recycle()
                after.recycle()
            }
        }
    }

    private fun assertContainsInk(bitmap: Bitmap, ink: Int) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Expected visible glyph ink ${Integer.toHexString(ink)}",
            pixels.any { Color.alpha(it) != 0 && (it and 0x00ffffff) == (ink and 0x00ffffff) })
    }
}
