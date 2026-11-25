package smallthingz.ui.zfm

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeChoiceSemantics
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.text.NativeTypographyRole

/** Retained browser controls use the same toolbar geometry and type as the other apps. */
fun ZfmTheme.navigationChip(context: Context, label: String, icon: Drawable? = null,
                            selected: Boolean = false, iconOnly: Boolean = false,
                            onClick: () -> Unit = {}): NativeChip {
    val toolbar = appearance.toolbars
    return NativeChip(context, palette, NativeChoiceStyle(
        radiusDp = toolbar.action.radiusDp,
        outlined = false, iconAbove = iconOnly,
        iconSizeDp = toolbar.action.glyphDp,
        horizontalPaddingDp = if (iconOnly) 0f else toolbar.leadingTitleGapDp,
        textStyle = nativeTextStyle(NativeTypographyRole.TITLE_SMALL),
    ), NativeChoiceSemantics.BUTTON).apply {
        bindButton(label, selected)
        setIcons(icon)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { onClick() }
    }
}

/** Navigation never trades Search away for a secondary action. */
data class ZfmBrowserNavigationLayout(val stacked: Boolean, val rowWidth: Int) {
    companion object {
        fun measure(availableWidth: Int, leadingWidth: Int, searchWidth: Int,
                    menuWidth: Int, titleWidth: Int): ZfmBrowserNavigationLayout {
            require(listOf(availableWidth, leadingWidth, searchWidth, menuWidth, titleWidth).all { it >= 0 })
            val actions = leadingWidth.toLong() + searchWidth + menuWidth
            return ZfmBrowserNavigationLayout(actions + titleWidth > availableWidth,
                maxOf(availableWidth.toLong(), actions).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }
}
