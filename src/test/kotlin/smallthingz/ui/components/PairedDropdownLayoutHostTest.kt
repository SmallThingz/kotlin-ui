package smallthingz.ui.components

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.common.PairedDropdownLayout
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairedDropdownLayoutHostTest {
    @Test fun recordingPairUsesItsNarrowerChildWidthForHeight() = verifyPair(::PairedDropdownLayout)

    @Test fun nativePairUsesItsNarrowerChildWidthForHeight() = verifyPair(::NativeDropdownPair)

    private fun verifyPair(create: (Context, NativeDropdown<*>, NativeDropdown<*>) -> ViewGroup) {
        val context = RuntimeEnvironment.getApplication()
        val applicationInfo = context.applicationInfo
        val flags = applicationInfo.flags
        applicationInfo.flags = flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        try {
            val density = context.resources.displayMetrics.density
            val controls = ReverbDesign.theme(context, dark = false).controls
            val theme = controls.withTypography(Density(density, 1.5f), controls.fontResolver)
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                DropdownGroup(theme, { null }, { false }).use { group ->
                    group.field(context, DropdownContent("Aufnahmeformat wählen", 16, listOf(16, 24)) {
                        "$it-bit PCM"
                    }) {}.use { format ->
                        group.field(context, DropdownContent("Rate", 44, listOf(44, 48)) {
                            "$it kHz"
                        }) {}.use { rate ->
                            val first = format.native
                            val second = rate.native
                            val pair = create(context, first, second).apply { layoutDirection = direction }
                            verifyWidths(pair, first, second, direction, density)
                        }
                    }
                }
            }
        } finally {
            applicationInfo.flags = flags
        }
    }

    private fun verifyWidths(pair: ViewGroup, first: NativeDropdown<*>, second: NativeDropdown<*>,
        direction: Int, density: Float) {
        var narrow = (96 * density).roundToInt()
        var wide = (400 * density).roundToInt()
        place(pair, 2 * wide)
        assertEquals(direction, pair.layoutDirection)
        val compactHeight = first.intrinsicHeight(wide)
        assertTrue("The first caption must have a wrapping threshold", first.intrinsicHeight(narrow) > compactHeight)
        while (wide - narrow > 1) {
            val middle = narrow + (wide - narrow) / 2
            if (first.intrinsicHeight(middle) > compactHeight) narrow = middle else wide = middle
        }

        val narrowHeight = first.intrinsicHeight(narrow)
        val evenHeight = maxOf(first.intrinsicHeight(wide), second.intrinsicHeight(wide))
        assertTrue("The short second field must leave the first field's one-pixel difference observable",
            narrowHeight > evenHeight)
        first.measure(exactly(narrow), unspecified())
        val caption = first.getChildAt(0) as ParagraphView
        val value = first.getChildAt(1) as ParagraphView
        val captionHeight = caption.measuredHeight
        val valueHeight = value.measuredHeight
        val captionLines = checkNotNull(caption.currentParagraph).lineCount
        assertTrue(captionLines > 1)
        assertTrue(valueHeight > 0)

        val oddWidth = narrow + wide
        val rtl = direction == View.LAYOUT_DIRECTION_RTL
        place(pair, oddWidth)
        assertEquals("The row must fit the narrower first field's intrinsic height", narrowHeight, pair.measuredHeight)
        assertEquals(narrowHeight, first.measuredHeight)
        assertEquals(narrowHeight, second.measuredHeight)
        assertColumn(first, if (rtl) wide else 0, narrow)
        assertColumn(second, if (rtl) 0 else narrow, wide)
        assertEquals(captionHeight, caption.height)
        assertEquals(captionLines, checkNotNull(caption.currentParagraph).lineCount)
        assertEquals(valueHeight, value.height)
        assertTrue(caption.top >= 0 && caption.bottom <= value.top && value.bottom <= first.height)
        if (pair is NativeDropdownPair) assertTrue(second.dividerAtStart)

        place(pair, 2 * wide)
        assertEquals(evenHeight, pair.measuredHeight)
        assertColumn(first, if (rtl) wide else 0, wide)
        assertColumn(second, if (rtl) 0 else wide, wide)

        first.visibility = View.GONE
        place(pair, oddWidth)
        assertEquals(second.intrinsicHeight(oddWidth), pair.measuredHeight)
        assertColumn(second, 0, oddWidth)
        if (pair is NativeDropdownPair) assertFalse(second.dividerAtStart)

        first.visibility = View.VISIBLE
        second.visibility = View.GONE
        place(pair, oddWidth)
        assertEquals(first.intrinsicHeight(oddWidth), pair.measuredHeight)
        assertColumn(first, 0, oddWidth)
    }

    private fun place(view: View, width: Int) {
        view.measure(exactly(width), unspecified())
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun assertColumn(view: View, left: Int, width: Int) {
        assertEquals(width, view.measuredWidth)
        assertEquals(left, view.left)
        assertEquals(left + width, view.right)
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
    private fun unspecified() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
}
