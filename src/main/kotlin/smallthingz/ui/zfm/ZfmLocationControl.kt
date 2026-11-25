package smallthingz.ui.zfm

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import smallthingz.ui.components.NativeAccessoryForeground
import smallthingz.ui.components.NativeSettingsRow
import smallthingz.ui.components.NativeSettingsRowStyle
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Retained location action with folder and parent context in one accessible target.
 * Measure with a wrapping height: 48 dp is a floor, not a cap on enlarged text.
 * The host owns navigation, full location descriptions and compact-mode placement.
 */
class ZfmLocationControl(
    context: Context,
    theme: ZfmTheme,
    disclosure: Drawable,
) : NativeSettingsRow(context, theme.palette, theme.locationRowStyle()) {
    private var boundTitle: String? = null
    private var boundParent: String? = null
    private var locationAction: (() -> Unit)? = null
    private val guardedAction: () -> Unit = {
        if (isAttachedToWindow && isShown && isEnabled && hasWindowFocus()) {
            locationAction?.invoke()
        }
    }
    private val disclosureView = ImageView(context).apply {
        setImageDrawable(disclosure)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        titleView.setSingleLine(true)
        titleView.ellipsize = TextUtils.TruncateAt.END
        detailView.setSingleLine(true)
        detailView.ellipsize = TextUtils.TruncateAt.MIDDLE
        val glyphSize = pixels(style.iconSizeDp)
        val gap = pixels(style.gapDp)
        disclosureView.setPaddingRelative(gap, 0, 0, 0)
        setAccessory(disclosureView, FrameLayout.LayoutParams(glyphSize + gap, glyphSize),
            NativeAccessoryForeground { color -> disclosureView.setColorFilter(color) })
    }

    /**
     * Readable inline width in pixels, recomputed from the current title typography.
     * Use the stable role label rather than a long folder name to choose compact mode.
     * This is a host layout hint, not a forced minimum width on the view.
     */
    val minimumReadableWidth: Int
        get() = maxOf(pixels(140f), ceil(titleView.paint.measureText("Location")).toInt() +
            titleView.compoundPaddingLeft + titleView.compoundPaddingRight +
            paddingLeft + paddingRight + pixels(style.iconSizeDp) +
            pixels(style.gapDp))

    fun bindLocation(
        title: String,
        parent: String,
        description: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        locationAction = onClick
        // The common bind requests layout; callback/description-only changes must not.
        if (boundTitle != title || boundParent != parent) {
            cancelPendingInputEvents()
            isPressed = false
            bind(title = title, detail = parent, onClick = guardedAction)
            boundTitle = title
            boundParent = parent
        }
        if (contentDescription?.toString() != description) contentDescription = description
        if (isEnabled != enabled) isEnabled = enabled
        if (isClickable != enabled) isClickable = enabled
        if (isFocusable != enabled) isFocusable = enabled
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val glyphSize = pixels(style.iconSizeDp)
        val gap = pixels(style.gapDp)
        disclosureView.setPaddingRelative(gap, 0, 0, 0)
        val params = disclosureView.layoutParams
        if (params.width != glyphSize + gap || params.height != glyphSize) {
            params.width = glyphSize + gap
            params.height = glyphSize
            disclosureView.layoutParams = params
        }
    }

    private fun pixels(dp: Float): Int = (dp * resources.displayMetrics.density).roundToInt()
}

private fun ZfmTheme.locationRowStyle(): NativeSettingsRowStyle {
    val toolbar = appearance.toolbars
    return NativeSettingsRowStyle(
        minHeightDp = toolbar.action.minimumTargetDp,
        horizontalPaddingDp = toolbar.leadingTitleGapDp,
        verticalPaddingDp = toolbar.centeredPaddingDp,
        iconSizeDp = toolbar.action.glyphDp,
        gapDp = toolbar.leadingTitleGapDp,
        // The host moves the whole control; the disclosure must stay beside its labels.
        // Put its gap inside the bounded accessory. A separate gap can leave negative
        // text width and trigger the common row's stacking even with a zero minimum.
        trailingGapDp = 0f,
        minTextWidthDp = 0f,
        cornerRadiusDp = toolbar.action.radiusDp.roundToInt(),
        titleTextStyle = nativeTextStyle(NativeTypographyRole.TITLE_SMALL),
        detailTextStyle = nativeTextStyle(NativeTypographyRole.BODY_SMALL),
    )
}
