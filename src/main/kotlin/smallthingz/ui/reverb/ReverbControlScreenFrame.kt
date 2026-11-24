package smallthingz.ui.reverb

import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.Insets
import smallthingz.ui.components.NativeButtonTouchTarget
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.components.nativeTextStyle
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/**
 * Retained Control page slots. The host owns routes, input admission and system-inset policy;
 * this frame owns their placement. Settings and the primary action align to the canonical
 * settings section edges. No editor, transcript or permission content is recreated here.
 *
 * Window-scoped: density/font/theme changes recreate the host, while viewport changes remeasure.
 */
class ReverbControlScreenFrame(
    context: Context,
    theme: ReverbTheme,
    productHeader: View,
    settingsHeader: View,
    controls: ScrollView,
    transcript: View,
    onboarding: View,
    private val primaryAction: NativeMaterialButtonView,
) : LinearLayout(context), ReverbTextSurfaceScope {
    private val appearance = theme.appearance
    private val sections = appearance.settingsSelectors.retentionSection
    private val density = theme.density.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun row() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    val controlsContent: LinearLayout = column().apply {
        setPaddingRelative(dp(sections.sideInsetDp), dp(sections.stackedGapDp),
            dp(sections.sideInsetDp), dp(sections.trailingInsetDp))
    }
    val browserHost = FrameLayout(context)
    val content = FrameLayout(context)
    val status = NativeText(context, theme.palette.pageControls,
        appearance.typography.nativeTextStyle(NativeTypographyRole.BODY_SMALL), NativeTextRole.MUTED).apply {
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        visibility = GONE
        setPaddingRelative(dp(sections.sideInsetDp), 0, dp(sections.sideInsetDp), 0)
    }
    private val primaryTarget = NativeButtonTouchTarget(primaryAction)
    private val backdrop = appearance.backdrops.attachPage(this, theme.palette) { ReverbTextSurface.invalidate(this) }

    init {
        orientation = VERTICAL
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(productHeader, row())
        addView(settingsHeader, row())
        controls.addView(controlsContent)
        content.addView(browserHost, FrameLayout.LayoutParams(-1, -1))
        content.addView(controls, FrameLayout.LayoutParams(-1, -1))
        // Padding belongs to the slot, not to the host's transcript implementation.
        content.addView(FrameLayout(context).apply {
            setPaddingRelative(controlsContent.paddingStart, controlsContent.paddingTop,
                controlsContent.paddingEnd, controlsContent.paddingBottom)
            addView(transcript, FrameLayout.LayoutParams(-1, -1))
        }, FrameLayout.LayoutParams(-1, -1))
        content.addView(onboarding, FrameLayout.LayoutParams(-1, -1))
        addView(column().apply {
            addView(content, LayoutParams(-1, 0, 1f))
            addView(status, row())
        }, LayoutParams(-1, 0, 1f))
        addView(primaryTarget, row().apply {
            marginStart = dp(sections.sideInsetDp)
            marginEnd = dp(sections.sideInsetDp)
            bottomMargin = dp(sections.trailingInsetDp)
        })
    }

    fun applySafeInsets(insets: Insets) {
        if (paddingLeft != insets.left || paddingTop != insets.top ||
            paddingRight != insets.right || paddingBottom != insets.bottom) {
            setPadding(insets.left, insets.top, insets.right, insets.bottom)
        }
    }

    fun showPrimaryAction(show: Boolean) {
        val visible = if (show) VISIBLE else GONE
        primaryAction.visibility = visible
        primaryTarget.visibility = visible
    }

    /** A pending decision must expose its complete instruction, including the discard action. */
    fun showStatus(show: Boolean, actionRequired: Boolean) {
        val lines = if (actionRequired) Int.MAX_VALUE else 2
        if (status.maxLines != lines) status.maxLines = lines
        val truncation = if (actionRequired) null else TextUtils.TruncateAt.END
        if (status.ellipsize != truncation) status.ellipsize = truncation
        status.visibility = if (show && status.text.isNotEmpty()) VISIBLE else GONE
    }

    fun bindTheme(theme: ReverbTheme) {
        appearance.backdrops.bindPage(backdrop, theme.palette)
        status.applyPalette(theme.palette.pageControls)
    }
}
