package smallthingz.ui.gallery

import android.content.Context
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LineBreakConfigSpan
import androidx.annotation.RequiresApi
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypographyRole

/**
 * Selectable full filename for Gallery metadata. Supply the unmodified basename with [bindName].
 * No characters, ellipsis, direction marks or line separators are inserted; native selection,
 * copying and accessibility retain the original name. No line limit is imposed.
 *
 * On API35+, a native no-break span prefers keeping the final dot and extension together.
 * Android can still use emergency grapheme breaks to avoid clipping, particularly when the
 * extension itself is wider than the available line. Before API35 this is ordinary natural
 * wrapping, with no equivalent compatibility promise. Measurement stays entirely in NativeText.
 */
class GalleryFilenameText(
    context: Context,
    palette: ControlPalette,
    style: NativeTextStyle = GalleryDesign.textStyle(NativeTypographyRole.TITLE_MEDIUM),
) : NativeText(context, palette, style) {
    private var boundName: String? = null

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
        val hasExtension = dot > 0 && dot < name.lastIndex &&
            name.substring(dot + 1).none { it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' }
        text = if (Build.VERSION.SDK_INT >= 35 && hasExtension) {
            ExtensionSpan.apply(name, dot)
        } else name
    }

    @RequiresApi(35)
    private object ExtensionSpan {
        fun apply(name: String, dot: Int): CharSequence = SpannableString(name).apply {
            setSpan(LineBreakConfigSpan.createNoBreakSpan(), dot, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
