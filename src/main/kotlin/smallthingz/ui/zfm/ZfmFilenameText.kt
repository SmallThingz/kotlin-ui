package smallthingz.ui.zfm

import android.content.Context
import android.content.res.Configuration
import android.text.Layout
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.controls.ControlPalette

/**
 * A filename label with at most two naturally measured lines. Short names occupy one line.
 * Use [bindName] to supply the basename and directory flag. Keep the original basename for
 * file actions, since the displayed text can contain a synthetic newline and ellipsis.
 * Use a separate full-name control for selection or copying.
 *
 * Overflowing files keep their final extension when it fits with an ellipsis on the last line.
 * Directories, dotfiles, names without an eligible extension and names with explicit line
 * separators keep ordinary END ellipsis. A row that supplies its own full-name semantics can
 * mark this child as decorative.
 */
class ZfmFilenameText(
    context: Context,
    palette: ControlPalette,
    style: NativeTextStyle,
) : NativeText(context, palette, style) {
    private var name = ""
    private var directory = false
    private var fittedLayout: WeakReference<Layout>? = null
    private var fittedWidthSpec = 0
    private var fittedTextWidth = 0

    init {
        setSingleLine(false)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    fun bindName(name: String, directory: Boolean) {
        if (this.name == name && this.directory == directory) return
        this.name = name
        this.directory = directory
        fittedLayout = null
        if (!TextUtils.equals(text, name)) setDisplayText(name)
        // A directory/file change can keep the same raw text.
        requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fittedLayout = null
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (info.contentDescription == null && !TextUtils.equals(text, name)) {
            info.contentDescription = name
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // NativeText refreshes typography before TextView measures. TextView replaces the
        // layout when the text direction actually changes. Unresolved inherited direction
        // can dispatch repeated RTL callbacks without replacing the layout.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val measured = layout ?: return
        if (fittedLayout?.get() === measured && fittedWidthSpec == widthMeasureSpec &&
            fittedTextWidth == measured.ellipsizedWidth) return

        if (!TextUtils.equals(text, name)) {
            setDisplayText(name)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        val raw = layout ?: return
        if (directory || raw.lineCount != 2 || raw.getEllipsisCount(1) == 0) return

        val fitted = suffixText(raw)
        if (fitted != null) {
            setDisplayText(fitted)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val result = layout
            val direction = raw.getParagraphDirection(0)
            // Shaping and wrapping can change after inserting a paragraph boundary.
            if (result == null || result.lineCount != 2 ||
                result.getLineStart(1) != fitted.indexOf('\n') + 1 ||
                result.getLineEnd(1) != fitted.length || result.getEllipsisCount(1) != 0 ||
                result.getParagraphDirection(0) != direction ||
                result.getParagraphDirection(1) != direction ||
                result.getLineMax(1) > result.ellipsizedWidth) {
                setDisplayText(name)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        layout?.let {
            fittedLayout = WeakReference(it)
            fittedWidthSpec = widthMeasureSpec
            fittedTextWidth = it.ellipsizedWidth
        }
    }

    private fun setDisplayText(value: String) {
        if (layoutParams != null || layout == null) {
            text = value
            return
        }
        // TextView's text-replacement fast path needs LayoutParams when a layout exists.
        // Discard that layout for standalone measurement before replacing its text.
        val mode = ellipsize
        ellipsize = if (mode == null) TextUtils.TruncateAt.END else null
        try {
            text = value
        } finally {
            ellipsize = mode
        }
    }

    private fun suffixText(raw: Layout): String? {
        val split = raw.getLineEnd(0)
        val extension = name.lastIndexOf('.')
        val width = raw.ellipsizedWidth
        if (width <= 0 || split !in 1 until name.length || name.startsWith('.') ||
            extension !in split until name.lastIndex ||
            name.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' }) return null

        // Preserve the original paragraph direction for shaping and for the new second line.
        val mark = if (raw.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT) '\u200f' else '\u200e'
        val remainder = buildString(name.length - split + 1) {
            append(mark)
            append(name, split, name.length)
        }
        val tail = TextUtils.ellipsize(remainder, paint, width.toFloat(), TextUtils.TruncateAt.START)
        if (!tail.startsWith('…') || tail.length - 1 < name.length - extension) return null
        return name.substring(0, split).trimEnd() + "\n" + mark + tail
    }
}
