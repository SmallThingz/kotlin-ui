package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import smallthingz.ui.components.NativeMaterialButtonView
import kotlin.math.roundToInt

/**
 * Retained Control page slots. The host owns routes, input admission and system-inset policy;
 * this frame owns their placement. Settings align to canonical section edges; the primary action
 * docks at the window bottom. No editor, transcript or permission content is recreated here.
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
    val primaryActionDock = ReverbBottomActionDock(context, theme, primaryAction)
    private val transition = ReverbScreenTransition(content)
    private var safeInsets = Insets.NONE
    /** Remaining browser bottom inset; this frame owns left/top/right once. */
    val browserSafeInsets: Insets get() = Insets.of(0, 0, 0, safeInsets.bottom)
    val isTransitionRunning: Boolean get() = transition.isRunning
    private val backdrop = appearance.backdrops.attachPage(this, theme.palette) { ReverbTextSurface.invalidate(this) }
    private val statusBarProtection = ReverbDesign.statusBarProtection(context)

    init {
        orientation = VERTICAL
        foreground = statusBarProtection
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
        addView(content, LayoutParams(-1, 0, 1f))
        addView(primaryActionDock, row())
    }

    fun applySafeInsets(insets: Insets) {
        safeInsets = insets
        bindSafeInsets()
    }

    private fun bindSafeInsets() {
        val dockVisible = primaryActionDock.visibility == VISIBLE
        val bottom = if (dockVisible || browserHost.visibility == VISIBLE) 0 else safeInsets.bottom
        if (paddingLeft != safeInsets.left || paddingTop != safeInsets.top ||
            paddingRight != safeInsets.right || paddingBottom != bottom) {
            setPadding(safeInsets.left, safeInsets.top, safeInsets.right, bottom)
        }
        primaryActionDock.applySafeInsets(Insets.of(0, 0, 0, safeInsets.bottom))
    }

    /** Call once after the host has bound route visibility. Equal/initial keys do not animate. */
    fun transitionTo(routeKey: Any) {
        bindSafeInsets()
        transition.transitionTo(routeKey)
    }

    fun cancelTransition() = transition.cancel()

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) transition.cancel()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) transition.cancel()
    }

    /** Reverb keeps status icons fixed over its protection; navigation follows the selected theme. */
    @Suppress("DEPRECATION")
    fun bindWindow(window: Window, dark: Boolean) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        statusBarProtection.applyWindowAppearance(window)
        WindowCompat.getInsetsController(window, this).isAppearanceLightNavigationBars = !dark
    }

    fun updateSystemBarInsets(insets: WindowInsetsCompat) = statusBarProtection.updateInsets(insets)

    fun showPrimaryAction(show: Boolean) {
        val visible = if (show) VISIBLE else GONE
        primaryAction.visibility = visible
        primaryActionDock.visibility = visible
        bindSafeInsets()
    }

    fun bindTheme(theme: ReverbTheme) {
        appearance.backdrops.bindPage(backdrop, theme.palette)
    }
}
