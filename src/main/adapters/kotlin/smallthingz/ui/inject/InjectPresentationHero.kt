package smallthingz.ui.inject

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

/** Accent hero with one readout or description. Formatting, validation and draft state stay in the app. */
class InjectPresentationHero(private val context: Context, private val theme: InjectTheme,
    @DrawableRes iconResource: Int,
    headlineKind: InjectHeadlineKind? = null, description: String? = null) {
    private val recipe = InjectPluginVisuals
    private fun paragraph(text: String, role: NativeTypographyRole) = ParagraphView(context,
        InjectDesign.appearance.typography[role], theme.density, theme.fontResolver,
        recipe.heroContent(theme.palette)).apply { setText(text) }
    private val headline = headlineKind?.let {
        paragraph("", it.role).apply {
            setTextLayout(maxLines = recipe.heroHeadlineMaxLines, overflow = TextOverflow.Ellipsis)
        }
    }
    val view = recipe.heroSurface.create(context).apply {
        recipe.heroSurface.bindImmediately(this, theme.palette)
        setPaletteImmediately(recipe.heroFill(theme.palette), recipe.heroBorder(theme.palette))
    }

    init {
        fun dp(value: Int) = (value * theme.density.density).roundToInt()
        val icon = ImageView(context).apply {
            setImageResource(iconResource)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(theme.palette.onPrimary)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.palette.primary)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val text = headline ?: description?.let { paragraph(it, NativeTypographyRole.BODY_MEDIUM) }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            addView(icon, LinearLayout.LayoutParams(dp(recipe.heroIconSizeDp), dp(recipe.heroIconSizeDp)))
            text?.let {
                addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    marginStart = dp(recipe.heroTextGapDp)
                })
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(recipe.heroHorizontalPaddingDp), dp(recipe.heroVerticalPaddingDp),
                dp(recipe.heroHorizontalPaddingDp), dp(recipe.heroVerticalPaddingDp))
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        view.addRow(content, NativeGroupLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun bindHeadline(text: String, description: CharSequence? = null) {
        headline?.apply {
            setText(text)
            contentDescription = description
        }
    }
}
