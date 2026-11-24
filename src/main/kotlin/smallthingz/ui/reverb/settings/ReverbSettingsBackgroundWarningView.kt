package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.R
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.exact
import smallthingz.ui.components.unspecified
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbTextSurface
import kotlin.math.roundToInt

/** Missing background permissions share one compact review surface. */
class ReverbSettingsBackgroundWarningView(
    context: Context,
    private var environment: ReverbEnvironment,
    inputRoot: () -> View?,
    batteryRestricted: Boolean,
    notificationBlocked: Boolean,
    restartAccessMissing: Boolean,
    reviewBattery: () -> Unit,
    reviewNotifications: () -> Unit,
    reviewRestart: () -> Unit,
) : ViewGroup(context), AutoCloseable {
    private val battery = ReverbSettingsPermissionReviewRow(
        context,
        environment,
        inputRoot,
        ReverbIcon.BackgroundWarning,
        R.string.reverb_settings_background_use_restricted,
        R.string.reverb_settings_background_review,
        R.string.reverb_settings_background_review,
        reviewBattery
    )
    private val notifications = ReverbSettingsPermissionReviewRow(
        context,
        environment,
        inputRoot,
        ReverbIcon.BackgroundWarning,
        R.string.reverb_settings_settings_resume_notification_blocked,
        R.string.reverb_settings_background_review,
        R.string.reverb_settings_settings_notification_access,
        reviewNotifications
    )
    private val notificationLayout = NativeGroupLayout.LayoutParams(-1, -2)
    private val restart = ReverbSettingsPermissionReviewRow(
        context,
        environment,
        inputRoot,
        ReverbIcon.BackgroundWarning,
        R.string.reverb_settings_settings_restart_access_missing,
        R.string.reverb_settings_background_review,
        R.string.reverb_settings_settings_restart_access,
        reviewRestart
    )
    private val restartLayout = NativeGroupLayout.LayoutParams(-1, -2)
    private val groupRecipe = environment.appearance.groupSurfaces.settingsWarning
    private val group = ReverbTextSurface.ownGroupInk(groupRecipe.create(context))
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        group.addRow(battery, NativeGroupLayout.LayoutParams(-1, -2))
        group.addRow(notifications, notificationLayout)
        group.addRow(restart, restartLayout)
        addView(group)
        bind(environment, batteryRestricted, notificationBlocked, restartAccessMissing)
    }

    fun bind(next: ReverbEnvironment, batteryRestricted: Boolean, notificationBlocked: Boolean,
        restartAccessMissing: Boolean) {
        environment = next
        groupRecipe.bind(group, next.palette)
        battery.visibility = if (batteryRestricted) VISIBLE else GONE
        notifications.visibility = if (notificationBlocked) VISIBLE else GONE
        restart.visibility = if (restartAccessMissing) VISIBLE else GONE
        notificationLayout.dividerTop = batteryRestricted && notificationBlocked
        restartLayout.dividerTop = batteryRestricted || notificationBlocked
        battery.bind(next, batteryRestricted)
        notifications.bind(next, notificationBlocked)
        restart.bind(next, restartAccessMissing)
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        group.measure(exact((width - 2 * dp(16)).coerceAtLeast(0)), unspecified())
        setMeasuredDimension(resolveSize(width, w), resolveSize(group.measuredHeight + 2 * dp(12), h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        group.layout(dp(16), dp(12), width - dp(16), dp(12) + group.measuredHeight)
    }

    override fun close() {
        battery.close()
        notifications.close()
        restart.close()
    }

    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}
