package smallthingz.ui.zfm

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.math.abs
import kotlin.math.ceil
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
@Config(application = Application::class, sdk = [35], qualifiers = "en-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZfmFilenameDimensionHostTest {
    private fun scaledContext(scale: Float) = RuntimeEnvironment.getApplication().let { app ->
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { fontScale = scale })
    }
    private val context = scaledContext(1f)
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

    private fun renderIfRequested(view: View, name: String) {
        val directory = System.getProperty("zfm.filename.renderDir") ?: return
        require(directory.isNotBlank())
        val target = Files.createDirectories(Paths.get(directory)).resolve(name)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(theme.surface)
            view.draw(canvas)
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun nativeDimensionSplitKeepsTokenTogetherAtSameHeight() {
        val name = "GalleryColorPortrait-600x1080-v1.png"
        val raw = ordinary(name).apply { gravity = Gravity.CENTER_VERTICAL }
        val view = filename(name).apply { gravity = Gravity.CENTER_VERTICAL }
        val tokenStart = name.indexOf("600x1080")
        val tokenEnd = tokenStart + "600x1080".length
        val capturedWidth = 318
        val atCaptureWidth = place(raw, capturedWidth)
        // The SDK 35 control previously wrapped before 600 at 318 px. The SDK 36
        // phone showed a break after the 6. Record what this host actually shapes.
        println("SDK 35 control at 318 px: first line=${name.take(atCaptureWidth.getLineEnd(0))}, " +
            "textSizePx=${raw.paint.textSize}, densityDpi=${context.resources.displayMetrics.densityDpi}, " +
            "padding=${raw.compoundPaddingLeft}+${raw.compoundPaddingRight}")

        // Choose from ordinary native layouts alone. Both complete halves must
        // fit at that width, but the actual native line break must divide the token.
        val headWidth = Layout.getDesiredWidth(name.take(tokenStart), raw.paint)
        val tailWidth = Layout.getDesiredWidth(name.drop(tokenStart), raw.paint)
        val lastWidth = ceil(Layout.getDesiredWidth(name.take(tokenEnd), raw.paint).toDouble()).toInt() + 1
        val firstWidth = ceil(maxOf(headWidth, tailWidth).toDouble()).toInt()
        val width = (firstWidth..lastWidth).sortedBy { abs(it - capturedWidth) }.firstOrNull { w ->
            val layout = place(raw, w)
            layout.lineCount == 2 && layout.getEllipsisCount(0) == 0 &&
                layout.getEllipsisCount(1) == 0 && layout.getLineEnd(1) == name.length &&
                layout.getLineEnd(0) in (tokenStart + 1) until tokenEnd
        } ?: error("SDK 35 native layout did not split this dimension at any viable width: $firstWidth..$lastWidth")
        val before = place(raw, width)
        val after = place(view, width)
        println("Native dimension case: widthPx=$width, beforeFirstLine=${name.take(before.getLineEnd(0))}")
        renderIfRequested(raw, "before-${width}px.png")
        renderIfRequested(view, "after-${width}px.png")
        assertEquals(440, context.resources.displayMetrics.densityDpi)
        assertEquals(raw.width, view.width)
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

        // At large font, the long sibling names need the existing overflow treatment.
        // Rebind the same view, as a recycled grid cell would.
        val large = scaledContext(2f)
        val largeTheme = ZfmTheme.resolve(large, dark = true)
        val reused = ZfmFilenameText(large, largeTheme.palette, largeTheme.fileGrid.titleStyle)
        for (version in listOf(3, 4)) {
            val original = "turnbull-heal-preview-861084e2_edited_${version}.jpg"
            reused.bindName(original, directory = false)
            val layout = place(reused, 318)
            assertEquals(2, layout.lineCount)
            assertEquals(0, layout.getEllipsisCount(1))
            assertTrue(reused.text.contains('…') && reused.text.endsWith("_${version}.jpg"))
            assertEquals(original, checkNotNull(reused.createAccessibilityNodeInfo()).contentDescription?.toString())
        }
    }
}
