package smallthingz.ui.email

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.roundToInt
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeLabeledTextField
import smallthingz.ui.components.NativeTextField

/** Joined recipient rows and Subject presentation. The caller owns each editor and its input. */
class EmailComposerFields(
    context: Context,
    theme: ControlTheme,
    subjectLabel: CharSequence,
    subjectTextSizeSp: Float = 0f,
) {
    private val recipe = theme.appearance.groupSurfaces.settingsGroup
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val subject = createSubject(context, theme, subjectLabel, subjectTextSizeSp)

    val view: NativeGroupLayout = recipe.create(context)
    val subjectEditor: NativeTextField get() = subject.editor

    init {
        column.addView(subject, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (6f * theme.density.density).roundToInt()
        })
        view.addRow(column, NativeGroupLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        bindTheme(theme)
    }

    /** Adds retained app-owned rows before the Subject without taking their callbacks or IDs. */
    fun addRecipientRow(row: View, layout: ViewGroup.LayoutParams) {
        column.addView(row, column.childCount - 1, layout)
    }

    fun bindTheme(theme: ControlTheme) {
        val colors = theme.colors
        view.setPaletteImmediately(recipe.fillColor(colors), recipe.borderColor(colors))
        subject.applyPalette(colors.settingsControls, colors.error)
    }

    private fun createSubject(
        context: Context,
        theme: ControlTheme,
        label: CharSequence,
        textSizeSp: Float,
    ): NativeLabeledTextField {
        val field = theme.appearance.joinedFields.fieldStyle
        val editor = field.editorStyle
        val text = editor.textStyle
        val effectiveText = if (textSizeSp > 0f) {
            text.copy(sizeSp = textSizeSp, lineHeightSp = text.lineHeightSp?.let { it * textSizeSp / text.sizeSp })
        } else {
            text
        }
        return NativeLabeledTextField(
            context, theme.colors.settingsControls, label, field.copy(
                errorColor = theme.colors.error,
                minimumWidthDp = 0f,
                outlineWidthDp = 0f,
                focusedOutlineWidthDp = 0f,
                editorStyle = editor.copy(
                    horizontalPaddingDp = 12f,
                    verticalPaddingDp = 8f,
                    minHeightDp = 48f,
                    textStyle = effectiveText,
                ),
            ),
        )
    }
}
