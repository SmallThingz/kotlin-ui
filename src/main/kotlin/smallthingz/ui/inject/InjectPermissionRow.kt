package smallthingz.ui.inject

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import smallthingz.ui.components.NativeDetailCardMarker
import smallthingz.ui.components.NativeDetailCardStyle
import smallthingz.ui.components.NativePermissionRow
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.icons.MaterialRoundedIconDrawable

/** Icon-only permission marker and status paint. Capability/permission observation stays in the app. */
open class InjectPermissionRow(
    context: Context,
    theme: InjectTheme,
    @DrawableRes iconResource: Int,
    style: NativeDetailCardStyle = InjectPluginVisuals.permissionStyle,
    inputRoot: () -> View? = { null },
) : NativePermissionRow(
    context, InjectPluginVisuals.permissionTitleStyle, InjectPluginVisuals.permissionBodyStyle,
    theme.density, theme.fontResolver,
    NativeDetailCardMarker(context, InjectPluginVisuals.permissionTitleStyle, theme.density,
        theme.fontResolver, style, glyph = ImageView(context).apply {
            setImageResource(iconResource)
            imageTintList = ColorStateList.valueOf(theme.palette.muted)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }),
    ImageView(context), inputRoot = inputRoot, style = style,
) {
    private val rowPalette = InjectPluginVisuals.permissionPalette(theme.palette)
    private val grantedIcon by lazy {
        MaterialRoundedIconDrawable(MaterialRoundedIcon.CheckCircle, theme.density.density, theme.palette.primary)
    }
    private val ungrantedIcon by lazy {
        MaterialRoundedIconDrawable(MaterialRoundedIcon.RadioButtonUnchecked, theme.density.density, theme.palette.muted)
    }

    fun bindStatus(title: String, status: String, showStatus: Boolean, granted: Boolean,
        enabled: Boolean, requestEnabled: Boolean, requestLabel: String, onRequest: () -> Unit) {
        (trailing as ImageView).apply {
            visibility = if (showStatus) GONE else VISIBLE
            setImageDrawable(if (granted) grantedIcon else ungrantedIcon)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = InjectPluginVisuals.permissionStatusAlpha(enabled, granted)
        }
        bindPermission(title, status.takeIf { showStatus }, granted, status, rowPalette,
            requestEnabled = requestEnabled, requestLabel = requestLabel, onRequest = onRequest)
    }
}
