package smallthingz.ui.zfm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.components.withRetiringTestActivity

/**
 * Detached filename geometry and accessibility nodes in an owned test Activity.
 */
@RunWith(AndroidJUnit4::class)
class ZfmFilenameTextIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private val longName = "quarterly_report_" + "customer_delivery_revision_".repeat(8) + "final.txt"

    private fun context(fontScale: Float, base: Context = instrumentation.targetContext): Context {
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = 160
            this.fontScale = fontScale
        })
    }

    private fun label(context: Context, style: NativeTextStyle, name: String,
                      directory: Boolean = false) = ZfmFilenameText(
        context, ZfmTheme.resolve(context, dark = false).palette, style,
    ).apply {
        // Match a filename view created by a row.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        bindName(name, directory)
    }

    private data class Frame(
        val text: String,
        val width: Int,
        val height: Int,
        val lineStarts: List<Int>,
        val lineEnds: List<Int>,
        val lineBottoms: List<Int>,
        val ellipsisCounts: List<Int>,
    )

    private fun measure(label: ZfmFilenameText, width: Int): Frame {
        // Exercise the component's onMeasure rather than View.measure's unchanged-spec shortcut.
        label.forceLayout()
        label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        label.layout(0, 0, label.measuredWidth, label.measuredHeight)
        val layout = checkNotNull(label.layout)
        assertEquals("Respect the parent width", width, label.measuredWidth)
        assertTrue("No hidden third filename line", layout.lineCount in 1..2)
        return Frame(label.text.toString(), label.measuredWidth, label.measuredHeight,
            (0 until layout.lineCount).map { layout.getLineStart(it) },
            (0 until layout.lineCount).map { layout.getLineEnd(it) },
            (0 until layout.lineCount).map { layout.getLineBottom(it) },
            (0 until layout.lineCount).map { layout.getEllipsisCount(it) })
    }

    private fun assertVisibleSuffix(label: ZfmFilenameText) {
        val layout = checkNotNull(label.layout)
        assertEquals("Long filenames use two lines", 2, layout.lineCount)
        assertTrue("Keep the filename extension", label.text.toString().endsWith(".txt"))
        assertEquals("The extension-bearing last line must not be ellipsized", 0,
            layout.getEllipsisCount(1))
        assertTrue("The extension-bearing line fits its content width",
            layout.getLineWidth(1) <= label.width - label.compoundPaddingLeft -
                label.compoundPaddingRight + 1f)
    }

    @Test fun repeatedMeasurementsDoNotRewriteFittedText() = main {
        for (font in listOf(1f, 2f)) for (size in listOf(12f, 14f)) {
            val view = label(context(font), NativeTextStyle(size), longName)
            val baseline = measure(view, 220)
            assertVisibleSuffix(view)
            var changes = 0
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changes++ }
                override fun afterTextChanged(s: Editable?) = Unit
            }
            view.addTextChangedListener(watcher)
            try {
                repeat(5) { assertEquals(baseline, measure(view, 220)) }
                assertEquals("Stable measurements must not reset/rewrite the filename", 0, changes)
            } finally { view.removeTextChangedListener(watcher) }
        }
    }

    @Test fun widthRoundTripAndStyleChangesMatchFreshInstances() = main {
        for (font in listOf(1f, 2f)) {
            val context = context(font)
            val normal = NativeTextStyle(14f)
            val enlarged = NativeTextStyle(22f, letterSpacing = .03f)
            val retained = label(context, normal, longName)
            val baseline = measure(retained, 180)
            for (width in listOf(320, 180)) {
                assertEquals("Retained width change matches fresh construction",
                    measure(label(context, normal, longName), width), measure(retained, width))
                assertVisibleSuffix(retained)
            }
            assertEquals("Width round trip restores the original layout", baseline, measure(retained, 180))
            retained.applyStyle(enlarged)
            val larger = measure(retained, 180)
            assertEquals("Same-width typography change must refit",
                measure(label(context, enlarged, longName), 180), larger)
            assertTrue("The changed typography has a measurable effect", larger.height > baseline.height)
            assertVisibleSuffix(retained)
            retained.applyStyle(normal)
            assertEquals("Style round trip restores the original layout", baseline, measure(retained, 180))
        }
    }

    @Test fun sameBasenameDirectoryToFileRequestsLayoutAndMatchesFreshBinding() = main {
        val context = context(1f)
        val style = NativeTextStyle(14f)
        val retained = label(context, style, longName, directory = true)
        measure(retained, 220)
        assertEquals("Directories retain the raw basename", longName, retained.text.toString())
        assertFalse("Precondition: the measured directory has no pending layout", retained.isLayoutRequested)
        retained.bindName(longName, directory = false)
        // Must inspect this before measure(), which intentionally forces component measurement.
        assertTrue("Same-text directory/file transition must schedule refitting", retained.isLayoutRequested)
        assertEquals(measure(label(context, style, longName), 220), measure(retained, 220))
        assertVisibleSuffix(retained)
        retained.bindName(longName, directory = true)
        assertEquals(measure(label(context, style, longName, directory = true), 220), measure(retained, 220))
        assertEquals("No synthetic suffix survives file-to-directory reuse", longName, retained.text.toString())
        retained.bindName(longName, directory = false)
        assertEquals(measure(label(context, style, longName), 220), measure(retained, 220))
        assertVisibleSuffix(retained)
    }

    @Test fun shortNamesUseOneLineAndLongNamesKeepVisibleSuffixAfterReuse() = main {
        for (font in listOf(1f, 2f)) {
            val context = context(font)
            val style = NativeTextStyle(14f)
            val retained = label(context, style, "a.txt")
            val short = measure(retained, 220)
            assertEquals(1, checkNotNull(retained.layout).lineCount)
            assertEquals("a.txt", retained.text.toString())
            assertEquals(0, checkNotNull(retained.layout).getEllipsisCount(0))
            retained.bindName(longName, directory = false)
            val long = measure(retained, 220)
            assertEquals(measure(label(context, style, longName), 220), long)
            assertVisibleSuffix(retained)
            assertTrue("Two lines grow naturally beyond a short name", long.height > short.height)
            retained.bindName("a.txt", directory = false)
            assertEquals("Short reuse removes the synthetic suffix and extra line", short, measure(retained, 220))
        }
    }

    @Test fun detachedMeasurementAndRebindDoNotRequireLayoutParams() = main {
        val context = context(1f)
        val view = ZfmFilenameText(
            context, ZfmTheme.resolve(context, dark = false).palette, NativeTextStyle(14f),
        )
        assertNull(view.layoutParams)
        view.bindName(longName, directory = false)
        measure(view, 220)
        assertVisibleSuffix(view)
        view.bindName("a.txt", directory = false)
        assertEquals("a.txt", measure(view, 220).text)
        assertNull(view.layoutParams)
    }

    @Test fun mixedRtlFilenameKeepsParagraphDirection() = main {
        val name = "אבגדה".repeat(32) + "customer_delivery_revision_final.txt"
        val view = label(context(1f), NativeTextStyle(14f), name).apply {
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val frame = measure(view, 160)
        assertTrue(frame.text.contains('\n'))
        assertVisibleSuffix(view)
        for (line in 0..1) {
            assertEquals(Layout.DIR_RIGHT_TO_LEFT, checkNotNull(view.layout).getParagraphDirection(line))
        }
    }

    @Test fun extensionWiderThanContentKeepsOrdinaryEllipsis() = main {
        val name = "recording_".repeat(28) + "." + "abcdefghij".repeat(10)
        val view = label(context(2f), NativeTextStyle(22f), name)
        assertEquals(name, measure(view, 120).text)
        assertTrue(checkNotNull(view.layout).getEllipsisCount(1) > 0)
    }

    @Test fun attachedFilenameKeepsFullAccessibleNameAndHostDescription() = withWindow { activity, root ->
        val width = 160
        val view = label(context(1f, activity), NativeTextStyle(14f), longName)
        root.addView(view, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        assertTrue("Filename view must be attached to the Activity window", view.isAttachedToWindow)
        val fitted = measure(view, width)
        assertTrue("The attached filename must use the fitted display text", fitted.text.contains('\n'))
        assertVisibleSuffix(view)
        assertEquals(longName, nodeDescription(view))

        view.contentDescription = "Host description"
        assertEquals("Host description", nodeDescription(view))

        view.contentDescription = null
        view.bindName("a.txt", directory = false)
        measure(view, width)
        val shortNode = checkNotNull(view.createAccessibilityNodeInfo())
        try {
            assertNull(shortNode.contentDescription)
            assertEquals("a.txt", shortNode.text.toString())
        } finally { shortNode.recycle() }
    }

    private fun nodeDescription(view: ZfmFilenameText): String? {
        val node = checkNotNull(view.createAccessibilityNodeInfo())
        return try { node.contentDescription?.toString() } finally { node.recycle() }
    }

    private fun withWindow(assertions: (Activity, FrameLayout) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var root: FrameLayout? = null
        withRetiringTestActivity(activity, root = { root }) {
            main { root = FrameLayout(activity).also { activity.setContentView(it) } }
            instrumentation.waitForIdleSync()
            main { assertions(activity, checkNotNull(root)) }
        }
    }
}
