package smallthingz.ui.zfm

import android.content.Context
import android.content.res.Configuration
import android.text.PrecomputedText
import android.text.TextUtils
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.controls.ControlPalette

/**
 * A filename label with at most two natural lines; short names occupy one line.
 * Bind the unmodified basename through [bindName], not through inherited text setters.
 * The owner retains row interaction and the full-name accessibility description: rendered
 * text can contain a synthetic newline/ellipsis and is not the file's identity.
 *
 * Uses ordinary END ellipsis, with the existing ZFM Grid suffix rule for overflowing files.
 * Directories, dotfiles, names without an eligible extension, and names containing explicit
 * line separators keep ordinary TextView wrapping/ellipsis; their text is not rewritten.
 * The caller retains typography, palette, gravity and row accessibility policy.
 */
class ZfmFilenameText(
    context: Context,
    palette: ControlPalette,
    style: NativeTextStyle,
) : NativeText(context, palette, style) {
    private var name = ""
    private var directory = false
    private var fittedGeometry: Geometry? = null

    private data class Geometry(
        val widthSpec: Int,
        val heightSpec: Int,
        val textMetrics: PrecomputedText.Params,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val gravity: Int,
        val layoutDirection: Int,
        val maxLines: Int,
        val minLines: Int,
        val lineSpacingExtra: Float,
        val lineSpacingMultiplier: Float,
        val includeFontPadding: Boolean,
        val ellipsize: TextUtils.TruncateAt?,
    )

    private fun geometry(widthSpec: Int, heightSpec: Int) = Geometry(
        widthSpec, heightSpec, textMetricsParams,
        compoundPaddingLeft, compoundPaddingTop, compoundPaddingRight, compoundPaddingBottom,
        gravity, layoutDirection, maxLines, minLines,
        lineSpacingExtra, lineSpacingMultiplier, includeFontPadding, ellipsize,
    )

    init {
        setSingleLine(false)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    fun bindName(name: String, directory: Boolean) {
        if (this.name == name && this.directory == directory) return
        this.name = name
        this.directory = directory
        fittedGeometry = null
        if (!TextUtils.equals(text, name)) text = name
        // Directory/file changes can keep the same raw text but change suffix policy.
        requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fittedGeometry = null
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        fittedGeometry = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // NativeText refreshes typography in onMeasure. Compare only after that refresh;
        // a stable geometry does not rewrite text or repeat suffix fitting.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (fittedGeometry == geometry(widthMeasureSpec, heightMeasureSpec)) return
        if (!TextUtils.equals(text, name)) {
            text = name
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        suffixText()?.let { fitted ->
            text = fitted
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        // Capture final metrics, not metrics from before a possible text/layout refresh.
        fittedGeometry = if (layout != null) geometry(widthMeasureSpec, heightMeasureSpec) else null
    }

    private fun suffixText(): String? {
        if (directory) return null
        val measured = layout ?: return null
        val available = measured.ellipsizedWidth
        if (measured.lineCount != 2 || measured.getEllipsisCount(1) == 0 || available <= 0 ||
            name.startsWith('.') || name.lastIndexOf('.') !in 1 until name.lastIndex ||
            name.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' }) return null
        // Keep Android's first line. Only the overflowing second line needs a visible suffix.
        val split = measured.getLineEnd(0)
        if (split !in 1 until name.length) return null
        val remainder = name.substring(split)
        val tail = TextUtils.ellipsize(remainder, paint, available.toFloat(), TextUtils.TruncateAt.START)
        if (tail.length <= 1 || TextUtils.equals(tail, remainder)) return null
        return name.substring(0, split).trimEnd() + "\n" + tail
    }
}
