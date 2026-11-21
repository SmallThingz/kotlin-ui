package smallthingz.ui.components

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import java.util.IdentityHashMap

/** Vertically joined labeled fields with one surround and an internal divider per seam. */
data class NativeJoinedFieldRecipe(
    val cornerRadiusDp: Float = 23f,
    val fieldStyle: NativeLabeledTextFieldStyle = NativeLabeledTextFieldStyle(errorColor = Color.TRANSPARENT),
) {
    fun createField(
        context: Context,
        palette: NativeColorScheme,
        label: CharSequence,
        editor: NativeTextField? = null,
    ): NativeLabeledTextField {
        val style = joinedStyle(palette)
        return if (editor == null) NativeLabeledTextField(context, palette.settingsControls, label, style)
        else NativeLabeledTextField(context, palette.settingsControls, label, style, editor)
    }

    fun createGroup(
        context: Context,
        palette: NativeColorScheme,
        vararg fields: NativeLabeledTextField,
        onPaintedSurfaceChanged: (View) -> Unit = {},
    ): NativeJoinedFieldGroup {
        require(fields.isNotEmpty())
        val identities = IdentityHashMap<NativeLabeledTextField, Boolean>(fields.size)
        for (field in fields) {
            require(field.parent == null && identities.put(field, true) == null) { "Each joined field must be unparented and unique" }
        }
        val ownedFields = fields.copyOf()
        val group = NativeGroupLayout(context, cornerRadiusDp, onPaintedSurfaceChanged = { view ->
            (view as NativeGroupLayout).publishJoinedUnderlay(ownedFields)
            onPaintedSurfaceChanged(view)
        })
        val style = joinedStyle(palette)
        for (field in fields) field.applyStyle(style)
        for ((index, field) in fields.withIndex()) {
            group.addRow(field, NativeGroupLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                dividerTop = index > 0
            })
        }
        return NativeJoinedFieldGroup(group, ownedFields).also {
            it.bindPalette(palette)
            // An initial black fill can already match the group's Paint default.
            group.publishJoinedUnderlay(ownedFields)
        }
    }

    private fun joinedStyle(palette: NativeColorScheme) = fieldStyle.copy(
        errorColor = palette.error,
        containerColor = Color.TRANSPARENT,
        outlineWidthDp = 0f,
        focusedOutlineWidthDp = 0f,
    )

    companion object {
        val Default = NativeJoinedFieldRecipe()
    }
}

private fun NativeGroupLayout.publishJoinedUnderlay(fields: Array<out NativeLabeledTextField>) {
    val underlay = paintedTextSurface
    for (field in fields) {
        if (field.parent === pointerContent) field.bindJoinedUnderlay(underlay)
    }
}

/** Fixed-membership group; rebinding paint keeps existing field and editor identities. */
class NativeJoinedFieldGroup internal constructor(
    val view: NativeGroupLayout,
    private val fields: Array<out NativeLabeledTextField>,
) {
    fun bindPalette(palette: NativeColorScheme) {
        view.setPalette(palette.field, palette.border)
        for (field in fields) field.applyPalette(palette.settingsControls, palette.error)
    }
}
