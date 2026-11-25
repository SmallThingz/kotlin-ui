package smallthingz.ui.gallery

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Detached geometry/local focus only; no media, activity launch or global configuration changes. */
@RunWith(AndroidJUnit4::class)
class GalleryViewerActionsIntegrationTest {
    @Test fun equalSlotsRetainActionsAcrossFontDirectionCompactAndNarrowViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (font in listOf(1f, 2f)) {
                val base = instrumentation.targetContext
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = font })
                fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
                val row = GalleryViewerActions(context, GalleryDesign.palette(context, true).pageControls)
                val names = listOf("Share", "Edit", "Unavailable", "More")
                val actions = names.mapIndexed { index, label ->
                    row.addAction(ColorDrawable(Color.WHITE), label, selected = index == 2,
                        enabled = index != 2) { true }
                }
                val parents = actions.map { it.parent }
                fun layout(width: Int) {
                    row.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                }
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    row.layoutDirection = direction
                    for (compact in listOf(false, true, false)) {
                        row.setCompact(compact)
                        for (width in listOf(320, 480, 144, 320)) {
                            layout(width)
                            assertEquals(dp(width), row.width)
                            assertTrue(row.getChildAt(0).width >= maxOf(dp(width), 4 * dp(48)))
                            if (compact) assertEquals(maxOf(dp(width), 4 * dp(48)), row.getChildAt(0).width)
                            assertTrue(actions.maxOf { it.width } - actions.minOf { it.width } <= 1)
                            assertTrue(actions.all { it.width >= dp(48) && it.height >= dp(48) })
                            assertEquals(1, actions.map { it.top }.distinct().size)
                            assertEquals(parents, actions.map { it.parent })
                            assertEquals(names, actions.map { it.contentDescription.toString() })
                            assertEquals(names.map { if (compact) "" else it }, actions.map { it.text.toString() })
                            if (!compact) actions.forEach { action ->
                                val textLayout = checkNotNull(action.layout)
                                assertEquals("An unbroken action name must not split", 1, textLayout.lineCount)
                                assertEquals(action.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                                for (line in 0 until textLayout.lineCount) assertEquals(0, textLayout.getEllipsisCount(line))
                                assertTrue(textLayout.height <= action.height - action.compoundPaddingTop - action.compoundPaddingBottom)
                            }
                            assertFalse(actions[2].isEnabled)
                            assertTrue(actions[2].isSelected)
                            if (width == 144) {
                                row.scrollTo(Int.MAX_VALUE, 0)
                                assertTrue(row.scrollX > 0)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun favoriteStaysWholeAndMoreRemainsReachableAcrossRetainedCompactTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (font in listOf(2f, 3f)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val base = instrumentation.targetContext
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    fontScale = font
                    setLocale(Locale.US)
                })
                fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
                var activations = 0
                val row = GalleryViewerActions(context, GalleryDesign.palette(context, true).pageControls).apply {
                    layoutDirection = direction
                }
                val names = listOf("Share", "Edit", "Favorite", "More")
                val actions = names.mapIndexed { index, name ->
                    row.addAction(ColorDrawable(Color.WHITE), name, selected = index == 2, enabled = true) {
                        activations++
                        true
                    }.apply { id = View.generateViewId() }
                }
                val parents = actions.map { it.parent }
                val ids = actions.map { it.id }
                val textSizes = actions.map { it.textSize }
                val more = actions.last()
                try {
                    assertTrue(more.requestFocus())
                    for (compact in listOf(false, true, false)) {
                        row.setCompact(compact)
                        for (width in listOf(384, 240, 800, 384)) {
                            row.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                            val content = row.getChildAt(0) as ViewGroup
                            assertEquals(parents, actions.map { it.parent })
                            assertEquals(ids, actions.map { it.id })
                            assertEquals("Fitting labels must not shrink typography", textSizes, actions.map { it.textSize })
                            actions.forEachIndexed { index, action ->
                                assertSame(action, content.getChildAt(index))
                                assertEquals(names[index], action.contentDescription.toString())
                                assertEquals(names[index], action.tooltipText.toString())
                                assertTrue(action.width >= dp(48) && action.height >= dp(48))
                                assertTrue(action.isEnabled && action.isFocusable && action.isClickable)
                                val node = action.createAccessibilityNodeInfo()
                                try {
                                    assertEquals("android.widget.Button", node.className.toString())
                                    assertFalse(node.isCheckable)
                                } finally { node.recycle() }
                                if (compact) {
                                    assertEquals("", action.text.toString())
                                    assertEquals(dp(48), action.height)
                                } else {
                                    val text = checkNotNull(action.layout)
                                    assertEquals(names[index], action.text.toString())
                                    assertEquals("$font/$width: ${names[index]} split inside a word", 1, text.lineCount)
                                    assertEquals(0, text.getEllipsisCount(0))
                                    assertEquals(action.text.length, text.getLineEnd(0))
                                    assertTrue(text.getLineWidth(0) <= action.width -
                                        action.compoundPaddingLeft - action.compoundPaddingRight + .5f)
                                    assertTrue(text.height <= action.height - action.compoundPaddingTop - action.compoundPaddingBottom)
                                }
                            }
                            assertTrue(actions[2].isSelected)
                            assertTrue(actions.maxOf { it.width } - actions.minOf { it.width } <= 1)
                            assertEquals(1, actions.map { it.top }.distinct().size)
                            assertTrue("Retained More loses local focus", more.isFocused)
                            if (!compact && width == 240) {
                                assertTrue("Stress fixture must exercise overflow", content.width > row.width)
                            }
                            if (compact) assertEquals(4 * dp(48), row.minimumRowWidth)
                            if (width == 800) assertEquals("A fitting row must clear old overflow", 0, row.scrollX)
                            // Exercise the scroller's reveal path without a window or injected input.
                            row.requestChildRectangleOnScreen(content,
                                Rect(more.left, more.top, more.right, more.bottom), true)
                            val visible = Rect()
                            more.getDrawingRect(visible)
                            row.offsetDescendantRectToMyCoords(more, visible)
                            visible.offset(-row.scrollX, -row.scrollY)
                            assertTrue("More must be completely revealable", Rect(0, 0, row.width, row.height).contains(visible))
                            assertEquals("Layout/reveal must not activate an action", 0, activations)
                        }
                    }
                } finally { actions.forEach { it.close() } }
            }
        }
    }

    @Test fun multiwordLabelsCanStillWrapAtTheirLegalWordBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                fontScale = 2f
                setLocale(Locale.US)
            })
            val row = GalleryViewerActions(context, GalleryDesign.palette(context, true).pageControls)
            val save = row.addAction(ColorDrawable(Color.WHITE), "Save copy", selected = false, enabled = true) { true }
            val more = row.addAction(ColorDrawable(Color.WHITE), "More", selected = false, enabled = true) { true }
            try {
                val slot = maxOf((48 * context.resources.displayMetrics.density).roundToInt(),
                    ceil(maxOf(save.paint.measureText("Save"), save.paint.measureText("copy"),
                        more.paint.measureText("More"))).toInt() +
                        save.compoundPaddingLeft + save.compoundPaddingRight)
                row.measure(View.MeasureSpec.makeMeasureSpec(2 * slot, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                assertEquals("Do not require the entire phrase on one line", row.width, row.getChildAt(0).width)
                val text = checkNotNull(save.layout)
                assertEquals(2, text.lineCount)
                assertEquals("Save", save.text.subSequence(0, text.getLineEnd(0)).toString().trim())
                assertEquals("copy", save.text.subSequence(text.getLineStart(1), text.getLineEnd(1)).toString().trim())
                assertEquals(0, text.getEllipsisCount(0))
                assertEquals(0, text.getEllipsisCount(1))
                assertTrue(text.height <= save.height - save.compoundPaddingTop - save.compoundPaddingBottom)
            } finally { save.close(); more.close() }
        }
    }

    @Test fun retainedLocaleChangesKeepHebrewWordsAndTrailingPunctuationTogether() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                fontScale = 2f
                setLocale(Locale.US)
            })
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
            val hebrew = Locale.forLanguageTag("he-IL")
            // Hebrew "Save, copy" and "More": preserve the comma with its word
            // and measure shaped non-Latin text rather than estimating character widths.
            val words = listOf("שמירה,", "עותק")
            val names = listOf(words.joinToString(" "), "עוד")
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                var activations = 0
                val row = GalleryViewerActions(context, GalleryDesign.palette(context, true).pageControls).apply {
                    layoutDirection = direction
                }
                val actions = names.map { name ->
                    row.addAction(ColorDrawable(Color.WHITE), name, selected = false, enabled = true) {
                        activations++
                        true
                    }.apply { id = View.generateViewId() }
                }
                val parents = actions.map { it.parent }
                val ids = actions.map { it.id }
                val sizes = actions.map { it.textSize }
                try {
                    assertTrue(actions.last().requestFocus())
                    // First populate the cache, then change only the retained views'
                    // text locale, then return. No resource or system locale mutation.
                    for (locale in listOf(Locale.US, hebrew, Locale.US)) {
                        actions.forEach { it.textLocale = locale }
                        val save = actions.first()
                        val widestWord = maxOf(
                            android.text.Layout.getDesiredWidth(words[0], save.paint),
                            android.text.Layout.getDesiredWidth(words[1], save.paint),
                            android.text.Layout.getDesiredWidth(names[1], actions.last().paint))
                        val slot = maxOf(dp(48), ceil(widestWord).toInt() +
                            save.compoundPaddingLeft + save.compoundPaddingRight)
                        // One complete slot fits, but two require horizontal reveal.
                        val viewport = slot + dp(16)
                        row.measure(View.MeasureSpec.makeMeasureSpec(viewport, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                        val content = row.getChildAt(0) as ViewGroup
                        assertEquals(2 * slot, content.width)
                        assertTrue(content.width > row.width)
                        assertEquals(parents, actions.map { it.parent })
                        assertEquals(ids, actions.map { it.id })
                        assertEquals(sizes, actions.map { it.textSize })
                        val text = checkNotNull(save.layout)
                        assertEquals("Wrap the phrase, never detach its comma or split a word", 2, text.lineCount)
                        words.forEachIndexed { line, word ->
                            assertEquals(word, save.text.subSequence(text.getLineStart(line), text.getLineEnd(line)).toString().trim())
                            assertEquals(0, text.getEllipsisCount(line))
                            assertTrue(text.getLineWidth(line) <= save.width - save.compoundPaddingLeft - save.compoundPaddingRight + .5f)
                        }
                        assertTrue(text.height <= save.height - save.compoundPaddingTop - save.compoundPaddingBottom)
                        actions.forEachIndexed { index, action ->
                            assertSame(action, content.getChildAt(index))
                            assertEquals(locale, action.textLocale)
                            assertEquals(names[index], action.text.toString())
                            assertEquals(names[index], action.contentDescription.toString())
                            assertTrue(action.width >= dp(48) && action.height >= dp(48))
                            val rendered = checkNotNull(action.layout)
                            assertEquals(names[index].length, rendered.getLineEnd(rendered.lineCount - 1))
                            for (line in 0 until rendered.lineCount) assertEquals(0, rendered.getEllipsisCount(line))
                            if (index == 1) assertEquals("Localized More must stay whole", 1, rendered.lineCount)
                            row.requestChildRectangleOnScreen(content,
                                Rect(action.left, action.top, action.right, action.bottom), true)
                            val visible = Rect()
                            action.getDrawingRect(visible)
                            row.offsetDescendantRectToMyCoords(action, visible)
                            visible.offset(-row.scrollX, -row.scrollY)
                            assertTrue("Both localized actions must be revealable", Rect(0, 0, row.width, row.height).contains(visible))
                        }
                        assertTrue(actions.last().isFocused)
                        assertEquals(0, activations)
                    }
                } finally { actions.forEach { it.close() } }
            }
        }
    }
}
