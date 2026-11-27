package smallthingz.ui.reverb

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.components.NativeDurationWheelStyle
import smallthingz.ui.components.NativeDurationWheelView

/**
 * Connects the duration wheel to the range editor's current limit and pinned boundary edit.
 */
internal class ReverbRangeDurationWheel(
    context: Context,
    private val actions: ReverbRangeDurationActions,
    descriptionRes: Int,
    style: NativeDurationWheelStyle,
) : AutoCloseable {
    val view = NativeDurationWheelView(context, style)
    private var alive = true
    private var currentEnabled = false
    private var exportLimit = 0.0

    init {
        view.setAccessibilityLabel(context.getString(descriptionRes))
        view.onInteractionStart = actions.begin
        view.onDurationChanged = { actions.change(it.toFloat()) }
        view.onInteractionEnd = actions.end
        view.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> step(true)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> step(false)
                else -> false
            }
        }
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.SeekBar::class.java.name
                val maximum = maximum().coerceAtLeast(0.0).toFloat()
                val minimum = minOf(.05f, maximum)
                info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                    minimum, maximum, actions.durationSeconds().toFloat().coerceIn(minimum, maximum),
                )
                if (actionReady()) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean =
                when (action) {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> commit(
                        (arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN)
                            ?: Float.NaN).toDouble(),
                    )
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> step(true)
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> step(false)
                    else -> super.performAccessibilityAction(host, action, arguments)
                }
        }
    }

    fun bind(maxExportDurationSeconds: Double, enabled: Boolean, inputVisible: Boolean,
        durationSeconds: Double, reachableMaximum: Double,
        environment: ReverbEnvironment) {
        exportLimit = maxExportDurationSeconds
        currentEnabled = enabled && inputVisible
        view.isEnabled = enabled
        view.setAppearance(environment.appearance.durationWheel, environment.palette)
        view.setMaximumDurationSeconds(reachableMaximum)
        view.setDurationSeconds(durationSeconds)
    }

    private fun actionReady(): Boolean = alive && currentEnabled && view.isEnabled &&
        view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
        actions.actionReady()

    private fun maximum() = actions.maximumSeconds(exportLimit)

    private fun commit(seconds: Double): Boolean {
        if (!actionReady() || !actions.commit(seconds, exportLimit)) return false
        view.setDurationSeconds(actions.durationSeconds())
        return true
    }

    private fun step(forward: Boolean) =
        commit(actions.durationSeconds() + if (forward) 1.0 else -1.0)

    override fun close() {
        if (!alive) return
        alive = false
        view.setOnKeyListener(null)
        view.accessibilityDelegate = null
        view.clearFocus()
        view.onInteractionStart = null
        view.onDurationChanged = null
        view.onInteractionEnd = null
        actions.end()
    }
}

/** User actions and exact range bounds supplied by the active editor. */
data class ReverbRangeDurationActions(
    val begin: () -> Unit,
    val change: (Float) -> Unit,
    val end: () -> Unit,
    val actionReady: () -> Boolean,
    val durationSeconds: () -> Double,
    val maximumSeconds: (exportLimitSeconds: Double) -> Double,
    val commit: (requestedSeconds: Double, exportLimitSeconds: Double) -> Boolean,
)
