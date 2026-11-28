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
 * A natural two-line break at or inside that extension first tries a meaningful basename
 * tail with the extension, then the extension alone, accepting only a measured two-line fit.
 * A natural break inside a delimited dimension such as 600x1080 can move to the preceding
 * separator when both lines still fit at their original height. Other natural wrapping is unchanged. Explicit bidi embedding/override/isolate controls exclude
 * this natural-break adjustment, since adding a paragraph can change their ordering.
 * Directories, dotfiles, names without an eligible extension and names with explicit line
 * separators keep ordinary END ellipsis. A row that supplies its own full-name semantics can
 * mark this child as decorative.
 */
class ZfmFilenameText(
    context: Context,
    palette: ControlPalette,
    style: NativeTextStyle,
) : NativeText(context, palette, style) {
    private companion object {
        val dimensionToken = Regex("""(?<=[_ -])[0-9]+[xX×][0-9]+(?=[._ -])""")
    }

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
        // DynamicLayout can retain backing lines past TextView's two visible lines.
        val rawLineCount = raw.lineCount
        if (directory || rawLineCount < 2) return
        val atomicExtension = atomicExtensionText(raw)
        val naturalWrap = rawLineCount == 2 && raw.getEllipsisCount(1) == 0
        val originalHeight = measuredHeight
        // Snapshot candidates before changing text: a watched TextView can reuse its layout.
        val candidates = when {
            atomicExtension != null -> listOfNotNull(meaningfulTailText(raw), atomicExtension)
            naturalWrap -> listOf(dimensionWrapText(raw) ?: return)
            else -> listOfNotNull(suffixText(raw))
        }
        if (candidates.isNotEmpty()) {
            val direction = raw.getParagraphDirection(0)
            var accepted = false
            // At most two candidates. Failure of the tail must still try the previous
            // atomic-extension rendering before reverting to the original filename.
            for (candidate in candidates) {
                setDisplayText(candidate)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                if (fitsTwoLines(candidate, direction) &&
                    (!naturalWrap || atomicExtension != null || measuredHeight == originalHeight)) {
                    accepted = true
                    break
                }
            }
            if (!accepted) {
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

    private fun fitsTwoLines(candidate: String, direction: Int): Boolean {
        val result = layout ?: return false
        // Shaping and wrapping can change after inserting a paragraph boundary.
        return result.lineCount == 2 &&
            result.getLineStart(1) == candidate.indexOf('\n') + 1 &&
            result.getLineEnd(1) == candidate.length && result.getEllipsisCount(0) == 0 &&
            result.getEllipsisCount(1) == 0 &&
            result.getParagraphDirection(0) == direction &&
            result.getParagraphDirection(1) == direction &&
            result.getLineMax(0) <= result.ellipsizedWidth &&
            result.getLineMax(1) <= result.ellipsizedWidth
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

    private fun atomicExtensionText(raw: Layout): String? {
        if (directory || raw.lineCount != 2 || raw.getEllipsisCount(0) != 0 ||
            raw.getEllipsisCount(1) != 0) return null
        val extension = name.lastIndexOf('.')
        val split = raw.getLineEnd(0)
        // Preserve the existing exclusion of all leading-dot names, not just extensionless ones.
        if (name.startsWith('.') || extension !in 1 until name.lastIndex ||
            split < extension || split >= name.length ||
            excludesNaturalWrap()) return null

        val mark = if (raw.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT) '\u200f' else '\u200e'
        // Unlike overflow fitting, natural wrapping must retain every basename character.
        return name.substring(0, extension) + "\n" + mark + name.substring(extension)
    }

    private fun excludesNaturalWrap() = name.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' ||
                it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }

    private fun dimensionWrapText(raw: Layout): String? {
        val split = raw.getLineEnd(0)
        if (name.startsWith('.') || split >= name.lastIndexOf('.') || raw.getEllipsisCount(0) != 0) return null
        val token = dimensionToken.findAll(name).firstOrNull { split > it.range.first && split <= it.range.last }
            ?: return null
        if (name.lastIndexOf('.') == name.lastIndex || excludesNaturalWrap()) return null

        // The inserted boundary is between an ASCII separator and an ASCII digit,
        // never inside a combining sequence or a surrogate pair.
        val start = token.range.first
        val mark = if (raw.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT) '\u200f' else '\u200e'
        return name.substring(0, start) + "\n" + mark + name.substring(start)
    }

    // Called only after atomicExtensionText admits a natural extension orphan.
    // Keep the nearest separator on line one; skip numeric-only tails such as _3.
    // Do not rebalance arbitrary two-line names or split a Unicode code point.
    private fun meaningfulTailText(raw: Layout): String? {
        val extension = name.lastIndexOf('.')
        var cursor = extension
        var hasLetter = false
        while (cursor > 0) {
            val codePoint = Character.codePointBefore(name, cursor)
            val split = cursor
            cursor -= Character.charCount(codePoint)
            if ((codePoint == '-'.code || codePoint == '_'.code || codePoint == ' '.code) &&
                hasLetter && split < extension &&
                Character.isLetterOrDigit(Character.codePointAt(name, split))) {
                val mark = if (raw.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT) '\u200f' else '\u200e'
                return name.substring(0, split) + "\n" + mark + name.substring(split)
            }
            hasLetter = hasLetter || Character.isLetter(codePoint)
        }
        return null
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
