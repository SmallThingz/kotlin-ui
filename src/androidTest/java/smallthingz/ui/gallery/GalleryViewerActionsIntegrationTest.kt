package smallthingz.ui.gallery

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.text.Spanned
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeChip
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Detached geometry/local focus only; no media, activity launch or global configuration changes. */
@RunWith(AndroidJUnit4::class)
class GalleryViewerActionsIntegrationTest {
    private fun assertLabelFits(row: GalleryViewerActions, action: TextView, scenario: String) {
        val text = checkNotNull(action.layout)
        val content = row.getChildAt(0)
        assertTrue("Complete label must fit: $scenario label=${action.text} " +
            "font=${action.resources.configuration.fontScale} direction=${row.layoutDirection} " +
            "row=${row.width}x${row.height} measured=${row.measuredWidth}x${row.measuredHeight} " +
            "content=${content.width}x${content.height} measured=${content.measuredWidth}x${content.measuredHeight} " +
            "action=${action.width}x${action.height} measured=${action.measuredWidth}x${action.measuredHeight} " +
            "text=${text.width}x${text.height} lines=${text.lineCount} textSize=${action.textSize} " +
            "padding=${action.paddingLeft},${action.paddingTop},${action.paddingRight},${action.paddingBottom} " +
            "compoundPadding=${action.compoundPaddingLeft},${action.compoundPaddingTop}," +
            "${action.compoundPaddingRight},${action.compoundPaddingBottom} " +
            "layoutRequested=${row.isLayoutRequested}/${content.isLayoutRequested}/${action.isLayoutRequested}",
            text.height <= action.height - action.compoundPaddingTop - action.compoundPaddingBottom)
        val available = action.width - action.compoundPaddingLeft - action.compoundPaddingRight
        for (line in 0 until text.lineCount) {
            val start = text.getLineStart(line)
            val end = text.getLineEnd(line)
            val visibleEnd = text.getLineVisibleEnd(line)
            val lineText = action.text.subSequence(start, end).toString()
                .replace(" ", "\\u0020").replace("\n", "\\n").replace("\t", "\\t")
            // getLineWidth includes the separator space retained at a legal wrap.
            // getLineMax measures the visible line, including punctuation, without that space.
            val visibleMax = text.getLineMax(line)
            val left = text.getLineLeft(line)
            val right = text.getLineRight(line)
            assertTrue("Visible line must fit: $scenario label=${action.text} line=$line " +
                "text=\"$lineText\" start=$start end=$end visibleEnd=$visibleEnd " +
                "width=${text.getLineWidth(line)} visibleMax=$visibleMax left=$left right=$right " +
                "available=$available layoutWidth=${text.width} actionWidth=${action.width} " +
                "padding=${action.compoundPaddingLeft},${action.compoundPaddingRight} " +
                "font=${action.resources.configuration.fontScale} locale=${action.textLocale} " +
                "direction=${row.layoutDirection} paragraphDirection=${text.getParagraphDirection(line)}",
                visibleMax <= available + .5f && left >= -.5f && right <= available + .5f)
        }
    }

    private fun labelMetricInputs(action: TextView): Map<String, Any?> {
        val fm = action.paint.fontMetricsInt
        return mapOf(
            "text" to action.text.toString(), "textSize" to action.textSize, "typeface" to action.typeface,
            "textScaleX" to action.textScaleX, "textSkewX" to action.paint.textSkewX,
            "letterSpacing" to action.letterSpacing, "paintFlags" to action.paint.flags,
            "locale" to action.textLocales.toLanguageTags(), "includeFontPadding" to action.includeFontPadding,
            "spacing" to listOf(action.lineSpacingExtra, action.lineSpacingMultiplier),
            "fontMetrics" to listOf(fm.top, fm.ascent, fm.descent, fm.bottom, fm.leading),
            "padding" to listOf(action.paddingLeft, action.paddingTop, action.paddingRight, action.paddingBottom),
            "compoundPadding" to listOf(action.compoundPaddingLeft, action.compoundPaddingTop,
                action.compoundPaddingRight, action.compoundPaddingBottom),
            "drawableGap" to action.compoundDrawablePadding,
            "drawables" to action.compoundDrawablesRelative.map { it?.bounds?.let { r -> listOf(r.width(), r.height()) } },
            "minimumHeight" to action.minimumHeight, "minHeight" to action.minHeight,
            "gravity" to action.gravity, "textDirection" to action.textDirection)
    }

    private fun labelLayoutMetrics(action: TextView): String {
        val layout = checkNotNull(action.layout)
        val spans = (action.text as? Spanned)?.let { text ->
            text.getSpans(0, text.length, Any::class.java)
                .filter { it is LineHeightSpan || it is MetricAffectingSpan }
                .map { "${it.javaClass.simpleName}:${text.getSpanStart(it)}..${text.getSpanEnd(it)} flags=${text.getSpanFlags(it)}" }
        }
        val lines = (0 until layout.lineCount).map { line ->
            "${layout.getLineStart(line)}..${layout.getLineEnd(line)} " +
                "top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} " +
                "baseline=${layout.getLineBaseline(line)} ascent=${layout.getLineAscent(line)} descent=${layout.getLineDescent(line)} " +
                "visibleMax=${layout.getLineMax(line)} left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)}"
        }
        return "inputs=${labelMetricInputs(action)} view=${action.width}x${action.height} " +
            "layout=${layout.javaClass.simpleName}:${layout.width}x${layout.height} " +
            "layoutPadding=${layout.topPadding},${layout.bottomPadding} viewBaseline=${action.baseline} " +
            "layoutPaintSize=${layout.paint.textSize} spans=$spans lines=$lines"
    }

    private fun assertRestoredHeightMatchesFresh(row: GalleryViewerActions, actions: List<NativeChip>, scenario: String) {
        val fresh = GalleryViewerActions(row.context, GalleryDesign.palette(row.context, true).pageControls).apply {
            layoutDirection = row.layoutDirection
        }
        val freshActions = actions.map { action ->
            fresh.addAction(ColorDrawable(Color.WHITE), action.text.toString(), action.isSelected, action.isEnabled) {
                throw AssertionError("Fresh geometry comparison must not activate an action")
            }.apply { textLocale = action.textLocale }
        }
        try {
            fresh.measure(View.MeasureSpec.makeMeasureSpec(row.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            fresh.layout(0, 0, fresh.measuredWidth, fresh.measuredHeight)
            val detail = "$scenario font=${row.resources.configuration.fontScale} direction=${row.layoutDirection} " +
                "width=${row.width} freshRow=${fresh.height} restoredRow=${row.height} " +
                "freshActions=${freshActions.map { it.height }} restoredActions=${actions.map { it.height }} " +
                actions.indices.joinToString("\n") { index ->
                    "action[$index] fresh={${labelLayoutMetrics(freshActions[index])}} restored={${labelLayoutMetrics(actions[index])}}"
                }
            // Establish actual fit on BOTH paths before testing exact height parity.
            // A height-only mismatch must not obscure clipped restored text.
            actions.forEach { assertLabelFits(row, it, "restored $detail") }
            freshActions.forEach { assertLabelFits(fresh, it, "fresh $detail") }
            actions.forEachIndexed { index, action ->
                assertEquals("Same slot width is required for height comparison: $detail", freshActions[index].width, action.width)
                assertEquals("Same font and padding are required for height comparison: $detail",
                    labelMetricInputs(freshActions[index]), labelMetricInputs(action))
                val freshText = checkNotNull(freshActions[index].layout)
                val restoredText = checkNotNull(action.layout)
                assertEquals("Same visible line widths are required for height comparison: $detail",
                    (0 until freshText.lineCount).map { freshText.getLineMax(it) },
                    (0 until restoredText.lineCount).map { restoredText.getLineMax(it) })
            }
            assertEquals("Restored labelled row must match fresh height after both fit: $detail", fresh.height, row.height)
            assertEquals("Restored content must match fresh height: $detail", fresh.getChildAt(0).height, row.getChildAt(0).height)
            actions.forEachIndexed { index, action ->
                assertEquals("Restored ${action.text} must match fresh height: $detail", freshActions[index].height, action.height)
            }
        } finally { freshActions.forEach { it.close() } }
    }

    private fun focusLaidOutDetachedAction(action: View) {
        assertFalse("Focus fixture must stay detached", action.isAttachedToWindow)
        assertTrue("Production action must already support focus", action.isFocusable && action.isEnabled)
        assertTrue("Focus requires initial fixture geometry", action.isLaidOut && action.width > 0 && action.height > 0)
        // Detached views can inherit the device's default touch mode. Opt in only on
        // this fixture node: test retained local focus without changing system input mode.
        action.isFocusableInTouchMode = true
        assertTrue("Laid-out detached action must acquire local focus", action.requestFocus())
    }

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
                    for ((phase, compact) in listOf(false, true, false).withIndex()) {
                        row.setCompact(compact)
                        for (width in listOf(320, 480, 144, 320)) {
                            layout(width)
                            if (phase == 2) assertRestoredHeightMatchesFresh(row, actions, "after compact widthDp=$width")
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
                                assertLabelFits(row, action, "phase=$phase compact=$compact widthDp=$width")
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
                    row.measure(View.MeasureSpec.makeMeasureSpec(dp(384), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                    focusLaidOutDetachedAction(more)
                    for ((phase, compact) in listOf(false, true, false).withIndex()) {
                        row.setCompact(compact)
                        for (width in listOf(384, 240, 800, 384)) {
                            row.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                            if (phase == 2) assertRestoredHeightMatchesFresh(row, actions, "after compact widthDp=$width")
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
                                    assertEquals("Compact ${names[index]} height: font=$font direction=$direction " +
                                        "widthDp=$width row=${row.height} content=${content.height} " +
                                        "action=${action.height} measured=${action.measuredHeight}", dp(48), action.height)
                                } else {
                                    val text = checkNotNull(action.layout)
                                    assertEquals(names[index], action.text.toString())
                                    assertEquals("$font/$width: ${names[index]} split inside a word", 1, text.lineCount)
                                    assertEquals(0, text.getEllipsisCount(0))
                                    assertEquals(action.text.length, text.getLineEnd(0))
                                    assertLabelFits(row, action, "phase=$phase compact=$compact widthDp=$width")
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
                assertLabelFits(row, save, "compact=false widthPx=${2 * slot} multiword")
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
                    var focusInitialized = false
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
                        if (!focusInitialized) {
                            focusLaidOutDetachedAction(actions.last())
                            focusInitialized = true
                        }
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
                        }
                        assertLabelFits(row, save, "compact=false widthPx=$viewport locale=$locale")
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
