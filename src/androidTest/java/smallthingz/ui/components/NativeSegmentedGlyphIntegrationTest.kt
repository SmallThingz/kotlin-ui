package smallthingz.ui.components

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.widget.CheckBox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.test.R
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeSegmentedGlyphIntegrationTest {
    @Test fun segmentedCreationSkipsDiscardedGlyphAndKeepsCheckboxStyle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync {
            result = runCatching {
                val context = ContextThemeWrapper(instrumentation.context, R.style.NativeSegmentedTestTheme)
                val palette = ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
                    0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt())
                val style = NativeChoiceStyle(minHeightDp = 56f)
                val expectedHeight = (style.minHeightDp * context.resources.displayMetrics.density).roundToInt()
                val featureSettings = "'kern' 0"

                val initialInflations = NativeSegmentedTestCheckboxGlyph.inflations
                val checkBox = CheckBox(context)
                assertTrue(checkBox.buttonDrawable is NativeSegmentedTestCheckboxGlyph)
                assertEquals(featureSettings, checkBox.fontFeatureSettings)
                assertEquals(initialInflations + 1, NativeSegmentedTestCheckboxGlyph.inflations)

                val standalone = NativeChip(context, palette, style)
                try {
                    assertEquals(initialInflations + 2, NativeSegmentedTestCheckboxGlyph.inflations)
                    assertEquals(featureSettings, standalone.fontFeatureSettings)
                    assertNull(standalone.buttonDrawable)
                } finally {
                    standalone.close()
                }

                val beforeBind = NativeSegmentedTestCheckboxGlyph.inflations
                val group = NativeSegmentedChoice(context, palette, style)
                try {
                    val options = listOf(NativeChoice("first", "First"), NativeChoice("second", "Second"))
                    group.bind(options, "first")
                    val first = checkNotNull(group.choiceView("first"))
                    val second = checkNotNull(group.choiceView("second"))

                    for (chip in listOf(first, second)) {
                        assertSame(context, chip.context)
                        assertEquals(featureSettings, chip.fontFeatureSettings)
                        assertEquals(expectedHeight, chip.minimumHeight)
                        assertEquals(expectedHeight, chip.minHeight)
                        assertNull(chip.buttonDrawable)
                    }
                    assertEquals("Fresh children inflated an unused checkbox glyph",
                        beforeBind, NativeSegmentedTestCheckboxGlyph.inflations)

                    group.bind(options, "second")
                    assertSame(first, group.choiceView("first"))
                    assertSame(second, group.choiceView("second"))
                    assertFalse(first.isChecked)
                    assertTrue(second.isChecked)
                    assertEquals(beforeBind, NativeSegmentedTestCheckboxGlyph.inflations)
                } finally {
                    group.close()
                }
            }
        }
        checkNotNull(result).getOrThrow()
    }
}

/** Installed only in the instrumentation APK. No ConstantState keeps each inflation observable. */
class NativeSegmentedTestCheckboxGlyph : Drawable() {
    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?,
    ) {
        super.inflate(r, parser, attrs, theme)
        inflations++
    }

    override fun getIntrinsicHeight(): Int = 4096
    override fun draw(canvas: Canvas) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT

    companion object {
        var inflations: Int = 0
            private set
    }
}
