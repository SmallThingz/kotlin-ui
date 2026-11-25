package smallthingz.ui.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ReplacementSpan
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.ceil

/**
 * Noneditable, selectable full filename for Gallery metadata. Supply the unmodified basename
 * with [bindName]. No characters, ellipsis, direction marks or line separators are inserted;
 * full selection, copying and accessibility retain the exact name. No line limit is imposed.
 *
 * A fitting final dot and extension form one native replacement/selection unit, not separate
 * character caret positions. Android still owns paragraph layout, bidi placement and selection.
 * The suffix is drawn with native text shaping. An extension wider than the available content
 * width uses ordinary wrapping instead. This contract applies on all supported API levels.
 */
class GalleryFilenameText(
    context: Context,
    palette: ControlPalette,
    style: NativeTextStyle = GalleryDesign.textStyle(NativeTypographyRole.TITLE_MEDIUM),
) : NativeText(context, palette, style) {
    private var boundName: String? = null
    private var extensionStart = -1
    private var extensionSpan: ExtensionSpan? = null

    init {
        setSingleLine(false)
        ellipsize = null
        setTextIsSelectable(true)
    }

    fun bindName(name: String) {
        // Same-name rebinding must not reset selection or replace the native text buffer.
        if (boundName == name && TextUtils.equals(text, name)) return
        boundName = name
        val dot = name.lastIndexOf('.')
        extensionStart = if (dot > 0 && dot < name.lastIndex &&
            name.substring(dot + 1).none { it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' }
        ) dot else -1
        extensionSpan = if (extensionStart >= 0) ExtensionSpan() else null
        text = name
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // NativeText refreshes configuration/font metrics first. Do not cache suffix widths:
        // applyStyle, textScaleX and native font changes must use the current measurement paint.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val span = extensionSpan ?: return
        val buffer = text as? Spannable ?: return
        if (!TextUtils.equals(buffer, boundName)) return
        val available = (measuredWidth - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(0)
        val fits = available > 0 && span.getSize(paint, buffer, extensionStart, buffer.length, null) <= available
        val installed = buffer.getSpanStart(span) >= 0
        if (fits == installed) return
        // Mutate only our span, not the buffer or selection. One conditional reconciliation
        // pass lets NativeText refresh its owned line-height span after this metric change.
        if (fits) buffer.setSpan(span, extensionStart, buffer.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        else buffer.removeSpan(span)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private class ExtensionSpan : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
            fm: Paint.FontMetricsInt?): Int = ceil(paint.measureText(text, start, end).toDouble()).toInt()

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
            top: Int, y: Int, bottom: Int, paint: Paint) {
            canvas.drawText(text, start, end, x, y.toFloat(), paint)
        }
    }
}
