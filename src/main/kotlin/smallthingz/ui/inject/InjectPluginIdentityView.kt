package smallthingz.ui.inject

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import smallthingz.ui.components.NativeForeground
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

/** Decorative plugin identity with optional heading; callers supply artwork and resolved copy. */
class InjectPluginIdentityView(context: Context, theme: InjectTheme, @DrawableRes iconResource: Int,
    title: String? = null, buttonSurface: Boolean = false) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val recipe = InjectPluginVisuals
        val density = theme.density.density
        val size = (recipe.identitySizeDp * density).roundToInt()
        val image = ImageView(context).apply {
            setImageResource(iconResource)
            setColorFilter(recipe.identityInk(theme.palette, buttonSurface))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (!buttonSurface) background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(recipe.identityFill(theme.palette, false))
            }
            val inset = (recipe.identitySizeDp * density / 4).roundToInt()
            setPadding(inset, inset, inset, inset)
            minimumWidth = size
            minimumHeight = size
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val mark = if (!buttonSurface) image else {
            val style = NativeSurfaceStyle(fillColor = recipe.identityFill(theme.palette, true),
                cornerRadiusDp = recipe.identitySizeDp / 2f,
                outlineColor = recipe.identityOutline(theme.palette))
            NativeSurface(context, style).apply {
                NativeForeground.bindSurface(this, theme.palette.settingsControls, style)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        }
        addView(mark, LayoutParams(size, size))
        title?.let {
            val heading = ParagraphView(context, recipe.identityTitleStyle, theme.density,
                theme.fontResolver, theme.palette.ink).apply {
                setText(it)
                isAccessibilityHeading = true
            }
            addView(heading, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (recipe.identityTitleGapDp * density).roundToInt()
            })
        }
    }
}
