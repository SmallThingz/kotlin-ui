package smallthingz.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.text.Spanned
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import smallthingz.ui.controls.ControlPalette

/** Decorative retained component; never admitted to the editor's input/accessibility tree. */
internal class NativeTextFieldPlaceholder(context: Context, palette: ControlPalette) {
    private val text = NativeText(context, palette, role = NativeTextRole.MUTED).apply {
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setSingleLine(true)
        // The decoration has a fixed viewport and no attached pre-draw scroll coordinator.
        setHorizontallyScrolling(false)
    }

    val measuredHeight: Int get() = text.measuredHeight

    fun onConfigurationChanged(configuration: Configuration) {
        // This retained View has no parent to dispatch configuration for it.
        text.dispatchConfigurationChanged(configuration)
    }

    fun prepare(editor: NativeTextField, palette: ControlPalette, style: NativeTextStyle,
        hintColors: ColorStateList?, fieldWidth: Int) {
        if (!TextUtils.equals(text.text, editor.hint)) text.text = editor.hint?.toString().orEmpty()
        if (text.layoutDirection != editor.layoutDirection) text.layoutDirection = editor.layoutDirection
        if (text.textDirection != editor.textDirection) text.textDirection = editor.textDirection
        if (text.textAlignment != editor.textAlignment) text.textAlignment = editor.textAlignment
        if (text.textScaleX != editor.textScaleX) text.textScaleX = editor.textScaleX
        val gravity = (editor.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) or Gravity.TOP
        if (text.gravity != gravity) text.gravity = gravity
        if (text.ellipsize != editor.ellipsize) text.ellipsize = editor.ellipsize
        if (text.isEnabled != editor.isEnabled) text.isEnabled = editor.isEnabled
        text.applyStyle(style)
        text.applyPalette(palette)
        val color = hintColors?.getColorForState(editor.drawableState, hintColors.defaultColor) ?: editor.currentTextColor
        if (text.currentTextColor != color) text.setTextColor(color)
        val contentWidth = (fieldWidth - editor.compoundPaddingLeft - editor.compoundPaddingRight).coerceAtLeast(0)
        text.measure(View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        if (text.isLayoutRequested || text.width != contentWidth || text.height != text.measuredHeight)
            text.layout(0, 0, contentWidth, text.measuredHeight)
    }

    fun draw(canvas: Canvas, editor: NativeTextField) {
        val left = editor.compoundPaddingLeft
        val top = editor.extendedPaddingTop
        val right = editor.width - editor.compoundPaddingRight
        val bottom = editor.height - editor.extendedPaddingBottom
        if (right <= left || bottom <= top) return
        val room = (bottom - top - text.measuredHeight).coerceAtLeast(0)
        val vertical = when (editor.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> room
            Gravity.CENTER_VERTICAL -> room / 2
            else -> 0
        }
        val save = canvas.save()
        try {
            // The empty decoration belongs to the viewport, not the query's scroll position.
            // Cancelling the existing View scroll transform prevents stale scroll after clear,
            // without changing the retained editor's scroll state or input connection.
            canvas.clipRect(left + editor.scrollX, top + editor.scrollY,
                right + editor.scrollX, bottom + editor.scrollY)
            canvas.translate((left + editor.scrollX).toFloat(), (top + vertical + editor.scrollY).toFloat())
            text.draw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    companion object {
        fun supports(editor: NativeTextField): Boolean {
            val hint = editor.hint
            return editor.maxLines == 1 && hint !is Spanned &&
                (hint == null || (TextUtils.indexOf(hint, '\n') < 0 && TextUtils.indexOf(hint, '\r') < 0)) &&
                editor.ellipsize != TextUtils.TruncateAt.MARQUEE &&
                editor.textScaleX.isFinite() && editor.textScaleX > 0f &&
                editor.compoundPaddingLeft == editor.paddingLeft && editor.compoundPaddingRight == editor.paddingRight &&
                editor.compoundPaddingTop == editor.paddingTop && editor.compoundPaddingBottom == editor.paddingBottom
        }
    }
}
