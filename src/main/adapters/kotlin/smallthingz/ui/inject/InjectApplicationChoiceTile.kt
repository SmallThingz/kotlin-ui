package smallthingz.ui.inject

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeChoiceSemantics
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.components.applicationGrid
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

private val progressScope by lazy { CoroutineScope(Dispatchers.Main.immediate) }

/**
 * Generic application choice surface for a joined grid. Selection, availability, icon loading
 * and explanation ownership stay with the caller. Requests never optimistically change selection.
 * An unavailable choice exposes one explanation Button, while its checkbox paints retained state.
 */
open class InjectApplicationChoiceTile(context: Context, ui: InjectAppearance) : FrameLayout(context), AutoCloseable {
    var onSelectionRequest: (() -> Boolean)? = null
    var onExplanationRequest: (() -> Boolean)? = null
    private val palette = ui.scheme.settingsControls
    private val recipe = ui.defaults.applicationGrid
    private var closed = false
    private var bound = false
    // A bound tile must retain an availability cue while artwork loads or lookup returns null.
    // Own one platform application glyph; never tint supplied/cached app artwork or dim labels.
    private val fallbackIcon by lazy { context.packageManager.defaultActivityIcon.mutate() }
    private val choice = NativeChip(context, palette, recipe.choiceStyle).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        setPadding(dp(recipe.horizontalInsetDp), dp(recipe.labelTopInsetDp),
            dp(recipe.horizontalInsetDp), dp(recipe.contentInsetDp))
        maxLines = recipe.labelMaxLines
        minLines = recipe.labelMaxLines
        ellipsize = TextUtils.TruncateAt.END
        onActivationRequest = { !closed && bound && onSelectionRequest?.invoke() == true }
    }
    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val check = ImageView(context).apply {
        setImageDrawable(ui.icon(smallthingz.ui.icons.MaterialRoundedIcon.CheckCircle, palette.onAccent))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val progress = NativeCircularProgress(context, palette.accent,
        AndroidUiFrameClock(Choreographer.getInstance()), progressScope,
        { readCachedAnimatorDurationScale(context) }).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    // The disabled checkbox supplies pixels only in info mode. This shared, unselected
    // Button has a transparent resting surface and retains native focus/ripple handling.
    private val info = NativeChip(context, palette, recipe.choiceStyle, NativeChoiceSemantics.BUTTON).apply {
        bindButton("", enabled = false)
        visibility = GONE
        onActivationRequest = { !closed && bound && onExplanationRequest?.invoke() == true }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(choice, LayoutParams(-1, -1))
        addView(icon, LayoutParams(dp(recipe.iconSizeDp), dp(recipe.iconSizeDp), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(check, LayoutParams(dp(recipe.markerSizeDp), dp(recipe.markerSizeDp)))
        addView(progress, LayoutParams(dp(recipe.markerSizeDp), dp(recipe.markerSizeDp)))
        addView(info, LayoutParams(-1, -1))
        clearBinding()
    }

    /** Revoke input before cancellation; callers revoke their model/icon binding separately. */
    fun clearBinding() {
        bound = false
        var failure = closePreservingPrimaryFailure(null, choice::cancelPendingInputEvents)
        failure = closePreservingPrimaryFailure(failure, info::cancelPendingInputEvents)
        failure = closePreservingPrimaryFailure(failure) {
            choice.isEnabled = false
            choice.isFocusable = false
            choice.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        failure = closePreservingPrimaryFailure(failure) {
            info.isEnabled = false
            info.isFocusable = false
            info.visibility = GONE
        }
        failure = closePreservingPrimaryFailure(failure) {
            check.visibility = GONE
            progress.visibility = GONE
        }
        closePreservingPrimaryFailure(failure) { icon.setImageDrawable(null) }?.let { throw it }
    }

    fun bindIcon(drawable: Drawable?) {
        if (!closed) icon.setImageDrawable(drawable ?: if (bound) fallbackIcon else null)
    }

    /** Null [unavailableDescription] exposes a checkbox; non-null exposes the explanation action. */
    fun bindChoice(
        label: String,
        description: String,
        selected: Boolean,
        pending: Boolean = false,
        stateDescription: String? = null,
        unavailableDescription: String? = null,
        explanationDescription: String = description,
    ) {
        if (closed) return
        bound = true
        if (icon.drawable == null) icon.setImageDrawable(fallbackIcon)
        val available = unavailableDescription == null
        choice.bind(label, selected, enabled = available)
        choice.isFocusable = available
        choice.importantForAccessibility = if (available) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        info.bindButton("", enabled = !available)
        info.isFocusable = !available
        info.visibility = if (available) GONE else VISIBLE
        info.contentDescription = explanationDescription
        ViewCompat.setStateDescription(info, unavailableDescription)
        check.visibility = if (selected && !pending) VISIBLE else GONE
        progress.visibility = if (pending) VISIBLE else GONE
        progress.setColor(if (selected) palette.onAccent else palette.accent)
        choice.contentDescription = description
        ViewCompat.setStateDescription(choice, stateDescription?.takeIf(String::isNotBlank))
        icon.alpha = if (available) 1f else recipe.unavailableIconAlpha
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // NativeChip refreshes its style padding on configuration changes; retain the icon's space.
        choice.setPadding(dp(recipe.horizontalInsetDp), dp(recipe.labelTopInsetDp),
            dp(recipe.horizontalInsetDp), dp(recipe.contentInsetDp))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val textLayout = choice.layout ?: return
        // Anchor the icon to the centered label block, including enlarged and two-line names.
        val iconTop = choice.baseline - textLayout.getLineBaseline(0) - dp(recipe.iconToLabelDp)
        val iconLeft = (width - icon.measuredWidth) / 2
        icon.layout(iconLeft, iconTop, iconLeft + icon.measuredWidth, iconTop + icon.measuredHeight)
        // Physical top-right in both LTR and RTL, independent of centered label height.
        val markerSize = dp(recipe.markerSizeDp)
        val markerLeft = width - dp(recipe.markerInsetDp) - markerSize
        val markerTop = dp(recipe.markerInsetDp)
        check.layout(markerLeft, markerTop, markerLeft + markerSize, markerTop + markerSize)
        progress.layout(markerLeft, markerTop, markerLeft + markerSize, markerTop + markerSize)
    }

    override fun close() {
        if (closed) return
        closed = true
        onSelectionRequest = null
        onExplanationRequest = null
        var failure = closePreservingPrimaryFailure(null, ::clearBinding)
        failure = closePreservingPrimaryFailure(failure, choice::close)
        closePreservingPrimaryFailure(failure, info::close)?.let { throw it }
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()
}
