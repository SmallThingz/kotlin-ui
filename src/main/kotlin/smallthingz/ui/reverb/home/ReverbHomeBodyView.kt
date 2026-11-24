package smallthingz.ui.reverb.home

import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbTextRole
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.interaction.view.NativeClickEnvironment
import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.R
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope

enum class ReverbHomeCaptureState { READY, RECORDING, FILLED, DISABLED }

data class ReverbHomeBodyState<S : Any, M : Any>(
    val activeBuffer: S?,
    val oneShotMetrics: ReverbHomeBufferMetrics,
    val loopingMetrics: ReverbHomeBufferMetrics,
    val oneShotEnabled: Boolean,
    val oneShotFull: Boolean,
    val loopingEnabled: Boolean,
    val retentionMode: M?,
    val listening: Boolean,
    val saving: Boolean,
    val serviceConnected: Boolean,
    val serviceStateHydrated: Boolean,
    val preparingRange: Boolean,
    val oneShotController: ReverbHomeBlobAttachment,
    val loopingController: ReverbHomeBlobAttachment,
    val visualizerVisible: Boolean,
    val skipSilenceEnabled: Boolean = false,
    val serviceBindingRejected: Boolean = false,
)

/**
 * Normal MainCaptureContent branch only. The shell owns the source buffer transition state,
 * source-equivalent horizontal pointer detector, range branch, window/root and save-status overlay.
 *
 * No recorder methods, coroutine workers, data operations, permission APIs or Settings writes.
 * Native input root and blob click environment are mandatory: there is no silent no-swipe default.
 */
class ReverbHomeBodyView<S : Any, M : Any> internal constructor(
    context: Context,
    initialEnvironment: ReverbEnvironment,
    nativeInputRoot: () -> View?,
    blobInputEnvironment: NativeClickEnvironment,
    private val animationScope: CoroutineScope,
    private val oneShot: S,
    looping: S,
    private val readoutCache: ReverbHomeReadoutCache<M>,
    private val resolveCaptureState: (S, Boolean, Boolean, Boolean, S?) -> ReverbHomeCaptureState,
    swipeSurfaceFactory: (ReverbHomeBlobControl) -> ViewGroup,
    private val onBoundsChanged: (body: View, bodyLocalBounds: RectF, blob: View) -> Unit,
) : ViewGroup(context), AutoCloseable {
    private var environment = initialEnvironment
    private var state: ReverbHomeBodyState<S, M>? = null
    private var renderedBuffer = oneShot
    private var flip = ReverbHomeFlipFrame()
    private var actions: ReverbHomeActions<S>? = null
    private var contentDirty = true
    private var renderedDisabled = false
    private val activateRenderedBuffer: () -> Unit = {
        val currentActions = actions
        if (renderedDisabled) currentActions?.openBufferSettings(renderedBuffer)
        else currentActions?.activateBuffer(renderedBuffer)
    }
    private var disabledBufferLabel = context.getString(R.string.buffer_disabled)
    private val blob = ReverbHomeBlobControl(context, initialEnvironment, blobInputEnvironment, animationScope) {}
    private var progress: NativeCircularProgress? = null
    private var unavailable: ParagraphView? = null
    private val controls = ReverbHomeControls(context, initialEnvironment, nativeInputRoot, animationScope, oneShot, looping)
    /** Attach the source-equivalent horizontal detector to this exact weighted Box only. */
    val swipeSurface: ViewGroup = swipeSurfaceFactory(blob)
    val blobInputView: View get() = blob
    private val bodyBounds = RectF()
    private var geometry = reverbHomeGeometry(0, 0, initialEnvironment.textDensity.density,
        initialEnvironment.appearance.captureControls)

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        swipeSurface.addView(blob)
        addView(swipeSurface)
        addView(controls)
    }

    fun bind(value: ReverbHomeBodyState<S, M>, renderedBuffer: S,
        frame: ReverbHomeFlipFrame, actions: ReverbHomeActions<S>) {
        val frameOnly = !contentDirty && state === value && this.renderedBuffer == renderedBuffer &&
            this.actions === actions
        state = value
        this.renderedBuffer = renderedBuffer
        flip = frame
        this.actions = actions
        if (frameOnly) {
            blob.updateFrame(frame)
            controls.updateFrame(frame)
            return
        }
        // All content dependencies are immutable fields of value, actions or the environment.
        // Keep invalidation set until the complete bind succeeds, including optional children.
        contentDirty = true
        val metrics = if (renderedBuffer == oneShot) value.oneShotMetrics else value.loopingMetrics
        val enabled = if (renderedBuffer == oneShot) value.oneShotEnabled else value.loopingEnabled
        val ui = resolveCaptureState(renderedBuffer, enabled, value.oneShotFull, value.listening, value.activeBuffer)
        val captureStateKnown = value.serviceStateHydrated
        val recording = captureStateKnown && ui == ReverbHomeCaptureState.RECORDING
        val disabled = captureStateKnown && ui == ReverbHomeCaptureState.DISABLED
        renderedDisabled = disabled
        val full = captureStateKnown && ui == ReverbHomeCaptureState.FILLED
        val serviceReady = value.serviceConnected && captureStateKnown && !value.preparingRange
        val captureEnabled = serviceReady &&
            (ui == ReverbHomeCaptureState.READY || recording)
        val clickable = serviceReady && !value.saving && (disabled || captureEnabled)
        val readout = readoutCache.read(value.retentionMode.takeIf { captureStateKnown }, disabled, metrics.seconds, metrics.bytes,
            disabledBufferLabel)
        blob.bind(ReverbHomeBlobState(
            if (renderedBuffer == oneShot) value.oneShotController else value.loopingController,
            recording, value.saving, captureEnabled, clickable, full,
            disabled || (value.listening && value.activeBuffer != null && value.activeBuffer != renderedBuffer),
            readout.primary, readout.secondary, value.visualizerVisible, value.skipSilenceEnabled,
            captureStateKnown = captureStateKnown,
        ), frame, activateRenderedBuffer)
        controls.bind(ReverbHomeControlsState(renderedBuffer, value.activeBuffer, value.listening,
            value.oneShotEnabled, value.oneShotFull, value.loopingEnabled, serviceReady, value.saving,
            metrics.seconds > 0f, recording, captureStateKnown), frame, actions)
        val showUnavailable = value.visualizerVisible && value.serviceBindingRejected
        if (showUnavailable) {
            val label = context.getString(R.string.recorder_connection_unavailable)
            val message = unavailable ?: environment.paragraph(
                context, label, ReverbTextRole.BODY_MEDIUM, environment.palette.muted,
            ).also {
                unavailable = it
                swipeSurface.addView(it)
            }
            message.setText(label)
        } else unavailable?.let {
            swipeSurface.removeView(it)
            unavailable = null
        }
        val showProgress = value.visualizerVisible && !showUnavailable &&
            (!value.serviceStateHydrated || value.preparingRange)
        if (showProgress) {
            val indicator = progress ?: NativeCircularProgress(
                context, environment.palette.primary,
                requireNotNull(animationScope.coroutineContext[smallthingz.ui.runtime.MonotonicFrameClock]),
                animationScope,
                { animationScope.coroutineContext[smallthingz.ui.MotionDurationScale]?.scaleFactor ?: 1f },
            ).also {
                progress = it
                swipeSurface.addView(it)
            }
            indicator.contentDescription = context.getString(if (value.preparingRange) R.string.preparing_audio_selection
                else R.string.loading_audio_history)
        } else progress?.let {
            swipeSurface.removeView(it)
            progress = null
        }
        contentDirty = false
    }

    fun setEnvironment(value: ReverbEnvironment) {
        contentDirty = true
        environment = value
        disabledBufferLabel = context.getString(R.string.buffer_disabled)
        readoutCache.invalidate()
        blob.setEnvironment(value)
        controls.setEnvironment(value)
        progress?.setColor(value.palette.primary)
        unavailable?.let { value.bindParagraphStyle(it, ReverbTextRole.BODY_MEDIUM, value.palette.muted) }
        val current = state
        val callbacks = actions
        if (current != null && callbacks != null) bind(current, renderedBuffer, flip, callbacks)
        requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Child labels refresh during the same dispatch; the next render must publish them.
        contentDirty = true
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        setMeasuredDimension(width, height)
        geometry = reverbHomeGeometry(width, height, environment.textDensity.density, environment.appearance.captureControls)
        controls.measure(exact(geometry.clusterWidth), exact(geometry.clusterHeight))
        swipeSurface.measure(exact(geometry.bodyWidth), exact(geometry.blobAreaHeight))
        blob.measure(exact(geometry.blobSize), exact(geometry.blobSize))
        unavailable?.measure(
            MeasureSpec.makeMeasureSpec(geometry.bodyWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec((geometry.blobAreaHeight - dp(12)).coerceAtLeast(0), MeasureSpec.AT_MOST),
        )
        progress?.let { progress ->
            val size = dp(24)
            // The bottom padding is outside the size modifier and consumes height first.
            progress.measure(exact(size.coerceAtMost(geometry.bodyWidth)),
                exact(size.coerceAtMost((geometry.blobAreaHeight - dp(12)).coerceAtLeast(0))))
        }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        swipeSurface.layout(geometry.side, 0, geometry.side + geometry.bodyWidth, geometry.blobAreaHeight)
        val blobLeft = ((geometry.bodyWidth - blob.measuredWidth) / 2f).roundToInt()
        val blobTop = ((geometry.blobAreaHeight - blob.measuredHeight) / 2f).roundToInt()
        blob.layout(blobLeft, blobTop, blobLeft + blob.measuredWidth, blobTop + blob.measuredHeight)
        progress?.let { progress ->
            val left = ((geometry.bodyWidth - progress.measuredWidth) / 2f).roundToInt()
            val bottom = geometry.blobAreaHeight - dp(12).coerceAtMost(geometry.blobAreaHeight)
            progress.layout(left, bottom - progress.measuredHeight, left + progress.measuredWidth, bottom)
        }
        unavailable?.let { message ->
            val left = ((geometry.bodyWidth - message.measuredWidth) / 2f).roundToInt()
            val bottom = geometry.blobAreaHeight - dp(12).coerceAtMost(geometry.blobAreaHeight)
            message.layout(left, bottom - message.measuredHeight, left + message.measuredWidth, bottom)
        }
        val clusterLeft = geometry.side + ((geometry.bodyWidth - controls.measuredWidth) / 2f).roundToInt()
        controls.layout(clusterLeft, geometry.blobAreaHeight,
            clusterLeft + controls.measuredWidth, geometry.blobAreaHeight + controls.measuredHeight)
        bodyBounds.set(geometry.side.toFloat(), 0f,
            (geometry.side + geometry.bodyWidth).toFloat(), height.toFloat())
        onBoundsChanged(this, bodyBounds, blob)
    }
    override fun close() = blob.close()
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}
