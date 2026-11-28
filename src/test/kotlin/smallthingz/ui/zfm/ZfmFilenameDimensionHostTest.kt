package smallthingz.ui.zfm

import android.app.Application
import android.content.res.Configuration
import android.text.Layout
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeText

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZfmFilenameDimensionHostTest {
    private val context = RuntimeEnvironment.getApplication().let { app ->
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { fontScale = 1f })
    }
    private val theme = ZfmTheme.resolve(context, dark = true)

    private fun ordinary(name: String) = NativeText(context, theme.palette, theme.fileGrid.titleStyle).apply {
        setSingleLine(false)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        text = name
    }

    private fun filename(name: String) = ZfmFilenameText(context, theme.palette, theme.fileGrid.titleStyle).apply {
        bindName(name, directory = false)
    }

    private fun place(view: NativeText, width: Int): Layout {
        if (view.layoutParams == null) view.layoutParams = ViewGroup.LayoutParams(-1, -2)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return checkNotNull(view.layout)
    }

    @Test fun capturedGridKeepsDimensionsTogetherAtSameHeight() {
        val name = "GalleryColorPortrait-600x1080-v1.png"
        val raw = ordinary(name)
        val before = place(raw, 318)
        assertEquals("Captured ordinary break", "GalleryColorPortrait-6", name.take(before.getLineEnd(0)))
        assertEquals(2, before.lineCount)
        assertEquals(0, before.getEllipsisCount(1))

        val view = filename(name)
        val after = place(view, 318)
        assertEquals(2, after.lineCount)
        assertEquals("GalleryColorPortrait-\n\u200e600x1080-v1.png", view.text.toString())
        assertEquals(view.text.indexOf('\n') + 1, after.getLineStart(1))
        for (line in 0..1) {
            assertEquals(0, after.getEllipsisCount(line))
            assertTrue(after.getLineMax(line) <= after.ellipsizedWidth)
            assertEquals(before.getParagraphDirection(line), after.getParagraphDirection(line))
        }
        assertEquals(raw.height, view.height)
        assertEquals(name, checkNotNull(view.createAccessibilityNodeInfo()).contentDescription?.toString())
    }

    @Test fun suffixTooWideForSecondLineKeepsOrdinaryCompleteWrap() {
        val name = "Grid-600000000x108000000-v1.png"
        val raw = ordinary(name)
        val start = name.indexOf("600")
        val end = name.indexOf("-v1")
        // Select a native wrap where the token straddles the break and moving the
        // whole suffix to line two would lose characters.
        val width = (200..420).first { w ->
            val layout = place(raw, w)
            layout.lineCount == 2 && layout.getEllipsisCount(1) == 0 &&
                layout.getLineEnd(0) in start + 1 until end &&
                Layout.getDesiredWidth(name.substring(start), raw.paint) > w
        }
        val view = filename(name)
        val after = place(view, width)
        assertEquals(name, view.text.toString())
        assertEquals(2, after.lineCount)
        assertEquals(checkNotNull(raw.layout).getLineEnd(0), after.getLineEnd(0))
        assertEquals(0, after.getEllipsisCount(1))
        assertEquals(raw.height, view.height)
    }
}
