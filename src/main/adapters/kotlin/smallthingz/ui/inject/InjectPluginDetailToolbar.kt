package smallthingz.ui.inject

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import smallthingz.ui.components.NativeTopBarAction
import smallthingz.ui.components.compactToolbarActionTop
import smallthingz.ui.reverb.ReverbTopBarLayout
import smallthingz.ui.text.style.TextOverflow
import kotlin.math.roundToInt

/** Inject's established wide switch/status slot over the current shared compact toolbar recipe. */
object InjectPluginToolbarRecipe {
    val toolbar get() = InjectDesign.appearance.toolbars
    val titleRole get() = toolbar.centeredTitleRole
    const val toggleWidthDp = 64f
    const val statusSizeDp = 20f
}

/**
 * Presentation only: the host supplies the navigation/menu actions, toggle and status indicator.
 * Their callbacks, enabled/pending state and disposal stay with that host.
 */
class InjectPluginDetailToolbar(
    context: Context,
    theme: InjectTheme,
    navigation: View,
    private val toggle: View,
    private val status: View,
) : ReverbTopBarLayout(context, theme.density.density, theme.palette) {
    private val density = theme.density.density
    private val style = InjectPluginToolbarRecipe.toolbar
    private val target = dp(maxOf(style.action.sizeDp, style.action.minimumTargetDp))
    internal val titleView = theme.paragraph(context, "", InjectPluginToolbarRecipe.titleRole).apply {
        setTextLayout(maxLines = 1, overflow = TextOverflow.Ellipsis)
        isAccessibilityHeading = true
        visibility = GONE
    }
    private val endActions = FrameLayout(context).apply {
        addView(toggle, FrameLayout.LayoutParams(dp(InjectPluginToolbarRecipe.toggleWidthDp), target))
        addView(status, FrameLayout.LayoutParams(dp(InjectPluginToolbarRecipe.statusSizeDp),
            dp(InjectPluginToolbarRecipe.statusSizeDp), Gravity.CENTER))
    }
    private var identityAction: NativeTopBarAction? = null
    private var boundShowIdentity: Boolean? = null

    init {
        // Retain NativeTopBarLayout's expanded hit routing and each action's standalone semantics.
        addView(navigation, ActionLayoutParams(Slot.START))
        addView(endActions, ActionLayoutParams(Slot.END))
        addView(titleView, ActionLayoutParams(Slot.CENTER))
    }

    fun setIdentity(action: NativeTopBarAction) {
        if (identityAction === action) return
        identityAction?.let(::removeView)
        addView(action, ActionLayoutParams(Slot.CENTER))
        identityAction = action
        updateCenterPresentation()
    }

    fun bindPresentation(label: String, showIdentity: Boolean, showToggle: Boolean,
        showStatus: Boolean, showEnd: Boolean, statusDescription: String?) {
        boundShowIdentity = showIdentity
        titleView.setText(label)
        updateCenterPresentation()
        ViewCompat.setAccessibilityPaneTitle(this, label)
        endActions.visibility = if (showEnd) VISIBLE else GONE
        toggle.visibility = if (showToggle) VISIBLE else GONE
        status.visibility = if (showStatus) VISIBLE else GONE
        status.contentDescription = statusDescription
    }

    private fun updateCenterPresentation() {
        val showIdentity = boundShowIdentity ?: return
        val identityVisible = showIdentity && identityAction != null
        identityAction?.visibility = if (identityVisible) VISIBLE else GONE
        titleView.visibility = if (identityVisible) GONE else VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        endActions.measure(exact(dp(InjectPluginToolbarRecipe.toggleWidthDp)), exact(target))
        val sideWidth = if (endActions.visibility == GONE) target else maxOf(target, endActions.measuredWidth)
        val safeWidth = (measuredWidth - leftInsetPx - rightInsetPx).coerceAtLeast(0)
        val titleWidth = (safeWidth - 2 * (dp(style.compactEdgeInsetDp) + sideWidth +
            dp(style.leadingTitleGapDp))).coerceAtLeast(0)
        val contentHeight = (measuredHeight - compactToolbarTopInsetPx).coerceAtLeast(0)
        titleView.measure(MeasureSpec.makeMeasureSpec(titleWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) leftInsetPx + dp(style.compactEdgeInsetDp)
            else width - rightInsetPx - dp(style.compactEdgeInsetDp) - endActions.measuredWidth
        val contentTop = compactToolbarTopInsetPx
        val contentHeight = height - contentTop
        val y = contentTop + compactToolbarActionTop(contentHeight, endActions.measuredHeight)
        endActions.layout(x, y, x + endActions.measuredWidth, y + endActions.measuredHeight)
        val tx = leftInsetPx + (width - leftInsetPx - rightInsetPx - titleView.measuredWidth) / 2
        val ty = contentTop + compactToolbarActionTop(contentHeight, titleView.measuredHeight)
        titleView.layout(tx, ty, tx + titleView.measuredWidth, ty + titleView.measuredHeight)
    }

    private fun dp(value: Float) = (value * density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}
