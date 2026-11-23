package smallthingz.ui.reverb.home

import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.animation.view.ViewFrameLoop
import smallthingz.ui.common.NativeHorizontalDrag
import smallthingz.ui.interaction.view.NativeClickEnvironment
import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.phase.PhasePointerRoot
import smallthingz.ui.input.phase.nativeMinimumTouchDistance
import kotlinx.coroutines.CoroutineScope

/**
 * Executable normal-Home presentation lifetime. Owns one existing body View, source buffer flip
 * state, its frame callback and stationary horizontal detector. No Composition, screen renderer
 * registry, service reference, recorder command or durable setting is owned here.
 *
 * The capture-state owner supplies current state/actions. Selecting a tab/swiping calls selectBuffer
 * only after the released navigation commit; it never activates or changes the capture destination.
 */
class ReverbHomeBodyOwner<S : Any, M : Any>(
    context: Context,
    initialEnvironment: ReverbEnvironment,
    initialState: ReverbHomeBodyState<S, M>,
    initialSelection: S,
    initialActions: ReverbHomeActions<S>,
    animationScope: CoroutineScope,
    private val nativeInputRoot: () -> View?,
    oneShot: S,
    looping: S,
    readoutCache: ReverbHomeReadoutCache<M>,
    resolveCaptureState: (S, Boolean, Boolean, Boolean, S?) -> ReverbHomeCaptureState,
    onBoundsChanged: (body: View, bodyLocalBounds: RectF, blob: View) -> Unit,
) : AutoCloseable {
    private var environment = initialEnvironment
    private var state = initialState
    private var actions = initialActions
    private val motion = ReverbHomeBufferMotion(initialSelection, oneShot, looping)
    private val flipFrame = ReverbHomeFlipFrame()
    private var pointerBuffer = initialSelection
    private var pointerSaving = initialState.saving
    private var closed = false
    private val detector: NativeHorizontalDrag = NativeHorizontalDrag(
        touchSlop = { ViewConfiguration.get(context).scaledTouchSlop.toFloat() },
        onStart = { motion.dragStarted(); render() },
        onDelta = { amount ->
            motion.dragged(amount, view.swipeSurface.width.toFloat())
            render()
        },
        onEnd = { motion.dragEnded(frames.durationScale); render(); requestFrame() },
        onCancel = { motion.dragCancelled(frames.durationScale); render(); requestFrame() },
    )
    private val blobInput: NativeClickEnvironment = object : NativeClickEnvironment {
        override val nativeInputRoot get() = this@ReverbHomeBodyOwner.nativeInputRoot()
        override val minimumTouchWidthPx get() = 48f * environment.textDensity.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
        override fun admitDown(event: MotionEvent): Boolean {
            val blob = view.blobInputView
            val x = event.x; val y = event.y
            if (x >= 0f && y >= 0f && x < blob.width && y < blob.height) return true
            return event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
                nativeMinimumTouchDistance(Offset(x,y), Size(blob.width.toFloat(),blob.height.toFloat()),
                    Size(minimumTouchWidthPx,minimumTouchHeightPx)).isFinite()
        }
    }
    private val presentationActions: ReverbHomeActions<S> = ReverbHomeActions(
        selectBuffer = { next ->
            motion.requestSelection(next, frames.durationScale)
            render(); requestFrame()
        },
        activateBuffer = { actions.activateBuffer(it) },
        openBufferSettings = { actions.openBufferSettings(it) },
        exportFull = { actions.exportFull(it) },
        exportCustom = { actions.exportCustom(it) },
        clearBuffer = { actions.clearBuffer(it) },
        openLibrary = { actions.openLibrary() },
    )
    val view: ReverbHomeBodyView<S, M> = ReverbHomeBodyView(context, initialEnvironment, nativeInputRoot, blobInput, animationScope,
        oneShot, looping, readoutCache, resolveCaptureState,
        swipeSurfaceFactory = { blob -> nativeHomeSwipeSurface(
            context, blob, blob.nativeClickEndpoint, detector,
            minimumTarget = { 48f * environment.textDensity.density },
        ) }, onBoundsChanged = onBoundsChanged)
    private val frames: ViewFrameLoop = ViewFrameLoop(view, animationScope, { !closed && motion.running }) { time, scale ->
        val commit = motion.frame(time, scale)
        render()
        if (commit != null) actions.selectBuffer(commit)
    }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = requestFrame()
        override fun onViewDetachedFromWindow(v: View) {
            removeFrame()
            restartHomeDragInput(motion, detector, frames.durationScale)
        }
    }

    init {
        view.addOnAttachStateChangeListener(attachment)
        motion.bind(initialSelection, initialState.saving, frames.durationScale)
        render()
    }

    fun bind(nextState: ReverbHomeBodyState<S, M>, selection: S, nextActions: ReverbHomeActions<S>) {
        check(!closed)
        state = nextState
        actions = nextActions
        motion.bind(selection, nextState.saving, frames.durationScale)
        render()
        requestFrame()
    }

    fun setEnvironment(value: ReverbEnvironment) {
        check(!closed)
        environment = value
        view.setEnvironment(value)
        render()
    }

    private fun render() {
        if (closed) return
        val nextBuffer = motion.displayedBuffer
        val nextSaving = state.saving
        if (nextBuffer != pointerBuffer || nextSaving != pointerSaving) {
            pointerBuffer = nextBuffer
            pointerSaving = nextSaving
            restartHomeDragInput(motion, detector, frames.durationScale)
        }
        motion.writeFlipFrame(flipFrame)
        view.bind(state, motion.renderedBuffer, flipFrame, presentationActions)
    }

    private fun requestFrame() { if (!closed) frames.request() }
    private fun removeFrame() = frames.cancel()

    override fun close() {
        if (closed) return
        closed = true
        removeFrame()
        view.removeOnAttachStateChangeListener(attachment)
        (view.swipeSurface as PhasePointerRoot).cancelPointerInput()
        restartHomeDragInput(motion, detector, frames.durationScale)
        view.close()
    }
}
