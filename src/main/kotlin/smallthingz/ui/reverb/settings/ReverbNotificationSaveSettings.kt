package smallthingz.ui.reverb.settings

import android.content.Context
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.R
import smallthingz.ui.animation.SpringValue
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.view.ViewSpringDriver
import smallthingz.ui.common.ToggleState
import smallthingz.ui.components.NativeViewAnimatorScale
import smallthingz.ui.components.exact
import smallthingz.ui.components.unspecified
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.input.NativeAndroidInteropRequests
import smallthingz.ui.reverb.ReverbEnvironment
import kotlin.math.roundToInt

data class ReverbNotificationSaveState(
    val enabled: Boolean,
    val seconds: Int,
    val maximumSeconds: Int,
    val interactionReady: Boolean,
    val editRevision: Long,
)

/** The host owns duration admission and commits, including invalidating a held gesture. */
interface ReverbNotificationDurationEditor : AutoCloseable {
    val view: View
    fun bind(seconds: Int, maximumSeconds: Int, interactionReady: Boolean, editRevision: Long, environment: ReverbEnvironment)
    fun cancelInteraction()
}

/** A notification toggle and its retained, animated duration editor. */
open class ReverbNotificationSaveSettings(
    context: Context,
    initial: ReverbNotificationSaveState,
    private var environment: ReverbEnvironment,
    inputRoot: () -> View?,
    onEnabledChange: (Boolean) -> Unit,
    private val createWheel: () -> ReverbNotificationDurationEditor,
) : ViewGroup(context), AutoCloseable, NativeAndroidInteropHost {
    override val nativeInteropRequests = NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private var state = initial
    private var boundLocales: LocaleList? = null
    private val toggle = ReverbSettingsToggle(context, environment.theme, toggleState(initial),
        ReverbSettingsToggleRole.SAVE_NOTIFICATION, inputRoot, onEnabledChange)
    private var wheel: ReverbNotificationDurationEditor? = null
    private val expansion = SpringValue.float(if (initial.enabled) 1f else 0f)
    private val durationScale = NativeViewAnimatorScale(context)
    private var expansionDriver: ViewSpringDriver<Float, AnimationVector1D>? = null
    private var closed = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(toggle.view)
        bind(initial, environment)
    }

    private fun toggleState(value: ReverbNotificationSaveState) = ToggleState(
        context.getString(R.string.reverb_settings_feature_notification_save), value.enabled, value.interactionReady)

    fun bind(next: ReverbNotificationSaveState, colors: ReverbEnvironment) {
        check(!closed)
        val locales = resources.configuration.locales
        val firstBind = boundLocales == null
        val localesChanged = boundLocales != locales
        if (!localesChanged && state == next && environment === colors) return
        boundLocales = locales
        val prev = state
        val densityChanged = environment.textDensity != colors.textDensity
        val environmentChanged = environment !== colors
        val targetChanged = prev.enabled != next.enabled
        state = next
        environment = colors
        if (localesChanged || environmentChanged || targetChanged || prev.interactionReady != next.interactionReady)
            toggle.bind(toggleState(next), colors.theme)
        if (next.enabled) {
            val current = wheel ?: createWheel().also { wheel = it; addView(it.view) }
            current.bind(next.seconds, next.maximumSeconds, next.interactionReady, next.editRevision, colors)
        } else wheel?.let {
            it.bind(next.seconds, next.maximumSeconds, false, next.editRevision, colors)
            if (targetChanged) it.cancelInteraction()
        }
        if (firstBind || targetChanged) {
            val target = if (next.enabled) 1f else 0f
            if (!isAttachedToWindow || durationScale.target(target) == 0f) {
                expansion.snapTo(target)
                publishExpansion()
            } else {
                val driver = expansionDriver ?: ViewSpringDriver(this, expansion, { publishExpansion() },
                    durationScale::read).also { expansionDriver = it }
                driver.retarget(target)
                publishExpansion()
            }
        } else if (densityChanged) requestLayout()
    }

    private fun publishExpansion() {
        wheel?.view?.let {
            it.alpha = expansion.value.coerceIn(0f, 1f)
            it.importantForAccessibility = if (state.enabled) IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        if (!state.enabled && !expansion.isRunning) retireWheel()
        requestLayout()
    }

    private fun retireWheel() {
        val old = wheel ?: return
        wheel = null
        try { old.close() } finally { removeView(old.view) }
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val style = environment.appearance.durationWheel
        toggle.view.measure(exact(width), unspecified())
        val current = wheel?.view
        current?.measure(exact(width - 2 * dp(style.settingsInsetDp)), exact(dp(style.heightDp)))
        val body = current?.let {
            ((2 * dp(style.settingsVerticalInsetDp) + it.measuredHeight) * expansion.value.coerceIn(0f, 1f)).roundToInt()
        } ?: 0
        setMeasuredDimension(resolveSize(width, w), resolveSize(toggle.view.measuredHeight + body, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        toggle.view.layout(0, 0, width, toggle.view.measuredHeight)
        wheel?.view?.let { view ->
            val style = environment.appearance.durationWheel
            val y = toggle.view.measuredHeight + dp(style.settingsVerticalInsetDp)
            val inset = dp(style.settingsInsetDp)
            view.layout(inset, y, width - inset, y + view.measuredHeight)
        }
    }

    override fun onDetachedFromWindow() {
        expansionDriver?.close()
        expansionDriver = null
        expansion.snapTo(if (state.enabled) 1f else 0f)
        publishExpansion()
        super.onDetachedFromWindow()
    }

    override fun close() {
        if (closed) return
        closed = true
        toggle.close()
        expansionDriver?.close()
        expansionDriver = null
        retireWheel()
    }

    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
}
