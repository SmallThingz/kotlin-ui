package smallthingz.ui.reverb

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import smallthingz.ui.components.NativeClickableRow
import smallthingz.ui.components.NativeSettingsRowStyle
import smallthingz.ui.components.acceptsNativeInput
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Installed identity is content, never a drawable tint or an application-owned row. */
data class ReverbAppChoice(
    val id: String,
    val label: String,
    val packageName: String? = null,
    val icon: Drawable? = null,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
)

/** Rich identity composition using the shared settings-row geometry and paragraph roles. */
internal class ReverbAppChoiceRow(
    context: Context,
    theme: ReverbTheme,
    private val trigger: Boolean,
    private val checkbox: Boolean = false,
    private val showIdentityIcon: Boolean = true,
    action: (String) -> Unit,
) : NativeClickableRow(context, { null }), AutoCloseable {
    private val recipe = NativeSettingsRowStyle()
    private val content = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    internal val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    internal val label = theme.paragraph(context, "", NativeTypographyRole.BODY_MEDIUM)
    internal val packageLabel = createReverbSupportingParagraph(context, "", NativeTypographyRole.BODY_SMALL, theme)
    internal val reason = createReverbSupportingParagraph(context, "", NativeTypographyRole.BODY_SMALL, theme)
    private val text = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label, LinearLayout.LayoutParams(-1, -2))
        addView(packageLabel, LinearLayout.LayoutParams(-1, -2))
        addView(reason, LinearLayout.LayoutParams(-1, -2))
    }
    internal val trailing = ReverbGlyphView(context, ReverbIcon.ArrowDropDown, theme.palette.muted)
    private var choice: ReverbAppChoice? = null
    private var boundTheme: ReverbTheme? = null
    private var boundSupportingText: String? = null
    private var action: ((String) -> Unit)? = action
    private var closed = false
    override val hasRowAction get() = !closed && action != null

    init {
        filterTouchesWhenObscured = true
        isFocusableInTouchMode = true
        ViewCompat.setScreenReaderFocusable(this, true)
        content.addView(icon)
        content.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(trailing)
        addView(content, LayoutParams(-1, -2))
    }

    fun bind(value: ReverbAppChoice, selected: Boolean, theme: ReverbTheme, supportingText: String? = null) {
        if (closed || choice == value && isSelected == selected && boundTheme === theme &&
            boundSupportingText == supportingText) return
        boundTheme = theme
        boundSupportingText = supportingText
        val density = theme.density.density
        fun dp(value: Float) = (value * density).roundToInt()
        minimumHeight = dp(recipe.minHeightDp)
        content.setPaddingRelative(dp(recipe.horizontalPaddingDp), dp(recipe.verticalPaddingDp),
            dp(recipe.horizontalPaddingDp), dp(recipe.verticalPaddingDp))
        icon.layoutParams = LinearLayout.LayoutParams(dp(recipe.iconSizeDp), dp(recipe.iconSizeDp)).apply {
            marginEnd = dp(recipe.gapDp)
        }
        trailing.layoutParams = LinearLayout.LayoutParams(dp(recipe.iconSizeDp), dp(recipe.iconSizeDp)).apply {
            marginStart = dp(recipe.gapDp)
        }
        choice = value
        label.setText(value.label)
        theme.bindParagraph(label, NativeTypographyRole.BODY_MEDIUM)
        // Picker choices follow the shared menu's selection emphasis; triggers and checkboxes do not.
        val menuChoice = !trigger && !checkbox
        if (!value.enabled || (menuChoice && !selected)) label.animateTextColor(theme.palette.muted)
        val packageName = value.packageName?.takeIf { it.isNotBlank() }
        val detail = if (supportingText == null) packageName else supportingText.takeIf { it.isNotBlank() }
        packageLabel.setText(if (supportingText != null) detail.orEmpty() else packageName?.let {
            BidiFormatter.getInstance().unicodeWrap(it, TextDirectionHeuristics.LTR)
        }.orEmpty())
        packageLabel.visibility = if (detail == null) GONE else VISIBLE
        theme.bindSupportingParagraph(packageLabel, NativeTypographyRole.BODY_SMALL)
        val explanation = value.disabledReason?.takeIf { !value.enabled && it.isNotBlank() }
        reason.setText(explanation.orEmpty())
        reason.visibility = if (explanation == null) GONE else VISIBLE
        theme.bindSupportingParagraph(reason, NativeTypographyRole.BODY_SMALL)
        // Installed icons retain their original pixels; only the shared fallback is themed.
        icon.imageTintList = null
        icon.visibility = if (showIdentityIcon) VISIBLE else GONE
        icon.setImageDrawable(if (showIdentityIcon)
            value.icon ?: ReverbIconDrawable(ReverbIcon.Browser, density, theme.palette.muted) else null)
        val glyph = when {
            trigger -> ReverbIcon.ArrowDropDown
            checkbox -> if (selected) ReverbIcon.Checked else ReverbIcon.Unchecked
            else -> ReverbIcon.Check
        }
        val emphasizedCheck = selected && (checkbox || (menuChoice && value.enabled))
        trailing.bind(glyph, if (emphasizedCheck) theme.palette.primary else theme.palette.muted)
        trailing.visibility = if (trigger || checkbox || selected) VISIBLE else GONE
        isSelected = selected
        contentDescription = listOfNotNull(value.label, detail, explanation).joinToString(", ")
        bindAction(value.enabled, theme.palette.ink)
        isClickable = value.enabled
        requestLayout()
    }

    override fun activateRow() {
        if (acceptsNativeInput() && hasWindowFocus()) choice?.takeIf { it.enabled }?.let { action?.invoke(it.id) }
    }

    override fun performClick(): Boolean = acceptsNativeInput() && hasWindowFocus() && super.performClick()

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK &&
            (!canActivate() || !acceptsNativeInput() || !hasWindowFocus())) return false
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = when {
            trigger -> "android.widget.Spinner"
            checkbox -> "android.widget.CheckBox"
            else -> "android.widget.RadioButton"
        }
        info.isCheckable = !trigger
        info.isChecked = !trigger && isSelected
        if (checkbox) info.isSelected = false
        if (!isEnabled) info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChild(content, widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(resolveSize(content.measuredWidth, widthMeasureSpec),
            resolveSize(maxOf(content.measuredHeight, suggestedMinimumHeight), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = (height - content.measuredHeight) / 2
        content.layout(0, top, width, top + content.measuredHeight)
        layoutRowIndication()
    }

    override fun close() {
        if (closed) return
        closed = true
        action = null
        choice = null
        retireRow()
    }
}
