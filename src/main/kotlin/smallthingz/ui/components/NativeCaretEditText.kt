package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.widget.EditText
import smallthingz.ui.R
import smallthingz.ui.text.NativeEditorTextMetrics
import smallthingz.ui.text.Paragraph
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.text.font.FontSynthesis
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.unit.Constraints
import smallthingz.ui.unit.Density
import kotlin.math.ceil

/** EditText remains the native IME and selection owner, including placement and blink. */
abstract class NativeCaretEditText private constructor(
    context: Context,
    thicknessPx: Float,
    cursorColor: Int,
    private val legacyCursor: LegacyNativeCursorBinding?,
) : EditText(legacyCursor?.context ?: context, null, 0,
    if (legacyCursor != null) R.style.NativeEditableCursor else 0) {
    protected constructor(context: Context, thicknessPx: Float, cursorColor: Int) :
        this(context, thicknessPx, cursorColor, legacyNativeCursorBinding(context, thicknessPx, cursorColor))

    private val cursor = legacyCursor?.cursor ?: NativeEditableCursorDrawable(thicknessPx, cursorColor)

    protected fun installCaret(tintHandles: Boolean = false) {
        if (Build.VERSION.SDK_INT < 29) return
        textCursorDrawable = cursor
        if (tintHandles) {
            textSelectHandle?.mutate()
            textSelectHandleLeft?.mutate()
            textSelectHandleRight?.mutate()
        }
    }

    protected fun bindCaret(thicknessPx: Float, color: Int, highlight: Int, handleTint: Int? = null) {
        highlightColor = highlight
        if (Build.VERSION.SDK_INT >= 29) {
            cursor.bind(thicknessPx, color)
            if (handleTint != null) {
                textSelectHandle?.setTint(handleTint)
                textSelectHandleLeft?.setTint(handleTint)
                textSelectHandleRight?.setTint(handleTint)
            }
        } else legacyCursor?.bind(thicknessPx, color)
    }

    protected fun editorTypeface(style: TextStyle, resolver: FontFamily.Resolver): Typeface = resolver.resolve(
        style.fontFamily, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All,
    ).value as Typeface

    protected fun applyEditorTypography(face: Typeface, metrics: NativeEditorTextMetrics) {
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.fontSizePx)
        letterSpacing = metrics.letterSpacingEm
        setLineSpacing(0f, 1f)
    }

    protected fun applyEditorMinimum(style: TextStyle, density: Density, resolver: FontFamily.Resolver): Paragraph {
        val paragraph = Paragraph("HHHHHHHHHH", style, spanStyles = emptyList(),
            maxLines = 1, overflow = TextOverflow.Clip, density = density,
            fontFamilyResolver = resolver, constraints = Constraints())
        minimumWidth = ceil(paragraph.minIntrinsicWidth).toInt()
        minimumHeight = ceil(paragraph.height).toInt()
        return paragraph
    }

    protected open fun beforeEditorTextDraw() = Unit

    final override fun onDraw(canvas: Canvas) {
        beforeEditorTextDraw()
        cursor.beginTextDraw()
        try {
            super.onDraw(canvas)
            cursor.drawAfterText(canvas, compoundPaddingLeft, extendedPaddingTop)
        } finally {
            cursor.endTextDraw()
        }
    }
}
