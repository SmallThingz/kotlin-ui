package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.View
import android.view.ViewConfiguration
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.common.ToggleState
import smallthingz.ui.components.NativeArcDialRange
import smallthingz.ui.components.NativeArcValueDial
import smallthingz.ui.components.NativeCalibrationRecipe
import smallthingz.ui.components.NativeExpandableSettingsSectionLayout
import smallthingz.ui.components.NativeExpansionContent
import smallthingz.ui.components.NativeExpansionView
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.reverb.ReverbEnvironment

/** The app supplies graph data and edits. The library owns graph ink, layout and controls. */
interface ReverbCalibrationGraph : AutoCloseable {
    val view: View
    fun bind(palette: ReverbCalibrationGraphPalette, editable: Boolean)
}

data class ReverbCalibrationGraphPalette(val accent: Int, val ink: Int, val muted: Int)

interface ReverbSilenceCalibrationState {
    val checked: Boolean
    val enabled: Boolean
    val clearance: Float
    val calibrationAllowed: Boolean
    val calibrating: Boolean
    val holdingCalibration: Boolean
    val calibrationProgress: Float?
    val calibrationError: String?
    val calibrationUnavailableReason: String?
}

private fun ReverbSilenceCalibrationState.sameControls(other: ReverbSilenceCalibrationState) =
    checked == other.checked && enabled == other.enabled && clearance == other.clearance &&
        calibrationAllowed == other.calibrationAllowed && calibrating == other.calibrating &&
        holdingCalibration == other.holdingCalibration && calibrationError == other.calibrationError &&
        calibrationUnavailableReason == other.calibrationUnavailableReason

data class ReverbSilenceCalibrationLabels(
    val title: String,
    val calibrate: String,
    val instructions: String,
    val releaseToFinish: String,
    val cancel: String,
    val sampling: String,
    val fitDescription: String,
    val fitResetActionId: Int,
    val fitReset: () -> String,
    val formatFit: (Int) -> String,
    val describeFit: (Int) -> String,
)

interface ReverbSilenceCalibrationActions {
    fun checkedChanged(checked: Boolean)
    fun clearanceChanged(clearance: Float)
    fun calibrate()
    fun beginHeld(): Boolean
    fun completeTap()
    fun finishHeld()
    fun cancel()
}

/** Compact disclosure shared by Settings and Onboarding. */
open class ReverbSilenceSettingsView(
    context: Context,
    initial: ReverbSilenceCalibrationState,
    private var environment: ReverbEnvironment,
    inputRoot: () -> View?,
    parentScope: CoroutineScope,
    private val labels: ReverbSilenceCalibrationLabels,
    fitRange: NativeArcDialRange,
    graphFactory: (NativeCalibrationRecipe) -> ReverbCalibrationGraph,
    actions: ReverbSilenceCalibrationActions,
) : NativeExpandableSettingsSectionLayout(context), AutoCloseable {
    private var state = initial
    private var actions: ReverbSilenceCalibrationActions? = actions
    private val toggle = ReverbSettingsToggle(context, environment.theme, toggleState(initial),
        ReverbSettingsToggleRole.SILENCE_DETECTION, inputRoot) { checked ->
        if (state.calibrating) this.actions?.cancel()
        this.actions?.checkedChanged(checked)
    }
    private val expansion = NativeExpansionView(context, initial.checked, parentScope, alignBottom = true) {
        SilenceBody(context, state, environment, inputRoot, labels, fitRange,
            graphFactory(environment.appearance.calibration)) { this.actions }
    }

    val calibrationPointerNode: NativeViewPhasePointerNode? get() = body?.pointerNode
    private val body get() = expansion.content as? SilenceBody

    init { installExpansion(toggle.view, expansion) }

    protected fun bind(next: ReverbSilenceCalibrationState, colors: ReverbEnvironment, graphChanged: Boolean) {
        val progressOnly = !graphChanged && colors === environment && state.sameControls(next) &&
            (state.calibrationProgress == null) == (next.calibrationProgress == null)
        state = next
        environment = colors
        if (progressOnly) {
            body?.bindProgress(next)
            return
        }
        toggle.bind(toggleState(next), colors.theme)
        body?.bind(next, colors)
        expansion.visible(next.checked)
        requestLayout()
    }

    private fun toggleState(state: ReverbSilenceCalibrationState) = ToggleState(labels.title, state.checked, state.enabled)

    override fun close() {
        try { toggle.close(); expansion.close() } finally { actions = null }
    }
}

private class SilenceBody(
    context: Context,
    initial: ReverbSilenceCalibrationState,
    private var environment: ReverbEnvironment,
    inputRoot: () -> View?,
    private val labels: ReverbSilenceCalibrationLabels,
    fitRange: NativeArcDialRange,
    private val graph: ReverbCalibrationGraph,
    private val actions: () -> ReverbSilenceCalibrationActions?,
) : ReverbCalibrationBodyLayout(context, environment), NativeExpansionContent {
    override val view: View get() = this
    private var state = initial
    private val configuration = ViewConfiguration.get(context)
    private var closed = false
    private val calibrate: ReverbSettingsActionView = ReverbSettingsActionView(context, environment, inputRoot,
        stableLabels = listOf(labels.calibrate, labels.releaseToFinish, labels.cancel),
        progressSurface = true, samplingLabel = { labels.sampling }) {
        if (!gesture.pointerDown) {
            if (canCancel()) actions()?.cancel()
            else if (canBegin()) actions()?.calibrate()
        }
    }
    private val fit = NativeArcValueDial(context, environment, fitRange, labels.fitDescription, labels.fitResetActionId,
        labels.fitReset, labels.formatFit, labels.describeFit) { clearance ->
        if (canEdit()) actions()?.clearanceChanged(clearance)
    }
    private val gesture: ReverbCalibrationGesture = ReverbCalibrationGesture(::canBegin,
        { actions()?.beginHeld() == true }, { actions()?.completeTap() }, { actions()?.finishHeld() },
        { actions()?.cancel() }, { refreshControls() }, { calibrate.width }, { calibrate.height },
        { configuration.scaledTouchSlop.toFloat() }, { ViewConfiguration.getLongPressTimeout().toLong() })
    val pointerNode = object : NativeViewPhasePointerNode {
        override val view: View get() = calibrate
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = gesture.dispatch(event, pass)
        override fun cancel() = gesture.cancel()
    }

    init {
        installControls(graph.view, calibrate, fit)
        bind(initial, environment)
    }

    private fun canBegin() = canEdit() && state.calibrationAllowed
    private fun canCancel() = state.calibrating && !state.holdingCalibration
    private fun canEdit() = state.enabled && state.checked && !state.calibrating

    fun bind(next: ReverbSilenceCalibrationState, colors: ReverbEnvironment) {
        state = next
        environment = colors
        val palette = colors.palette
        graph.bind(ReverbCalibrationGraphPalette(palette.primary, palette.ink, palette.muted), canEdit())
        fit.bind(next.clearance, canEdit(), colors)
        refreshControls()
        bindContent(colors, next.calibrationError ?: next.calibrationUnavailableReason?.takeIf { !next.calibrationAllowed })
    }

    fun bindProgress(next: ReverbSilenceCalibrationState) {
        if (state.calibrationProgress != next.calibrationProgress) calibrate.bindProgress(next.calibrationProgress, next.calibrating)
        state = next
    }

    private fun refreshControls() {
        if (closed) return
        val pointerDown = gesture.pointerDown
        val enabled = canCancel() || state.enabled && state.checked &&
            (state.calibrationAllowed || pointerDown) && (!state.calibrating || pointerDown)
        val label = when {
            state.holdingCalibration -> labels.releaseToFinish
            canCancel() -> labels.cancel
            else -> labels.calibrate
        }
        calibrate.bind(label, enabled, environment)
        calibrate.setActionDescription(if (canBegin()) "${labels.calibrate}. ${labels.instructions}" else null)
        calibrate.bindProgress(state.calibrationProgress, state.calibrating)
    }

    override fun onDetachedFromWindow() {
        gesture.cancel()
        super.onDetachedFromWindow()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { gesture.cancel() } finally {
            calibrate.close()
            fit.close()
            graph.close()
        }
    }
}
