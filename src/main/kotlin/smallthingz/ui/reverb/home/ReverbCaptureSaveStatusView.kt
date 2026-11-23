package smallthingz.ui.reverb.home

import smallthingz.ui.animation.view.ViewFrameLoop
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbSheetButtonHost
import smallthingz.ui.reverb.ReverbStandardIconButtonView
import smallthingz.ui.reverb.library.LibraryRecordingRow
import smallthingz.ui.reverb.library.LibraryRecordingRowActions
import smallthingz.ui.reverb.library.ReverbLibraryRecordingRow
import smallthingz.ui.util.closePreservingPrimaryFailure
import smallthingz.ui.text.view.MergedRowAccessibility
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativeMergedRowAccessibilityState
import smallthingz.ui.text.view.NativeMergedRowRole
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.input.phase.NativeClickPhaseNode
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.R
import kotlinx.coroutines.CoroutineScope

/** Already-formatted status supplied after the app accepts or completes a save. */
sealed interface ReverbCaptureSaveStatus {
    data class Saving(val cancellable: Boolean) : ReverbCaptureSaveStatus
    data class Saved(val name: String, val duration: String, val size: String, val timestamp: String) : ReverbCaptureSaveStatus
}

/** CaptureSaveStatusCard retains the last non-null status throughout its slide/fade exit. */
class ReverbCaptureSaveStatusView(
    context: Context,
    private var environment: ReverbEnvironment,
    private val host: ReverbSheetButtonHost,
    private val scope: CoroutineScope,
    private val onCancel: () -> Unit,
) : ViewGroup(context), AutoCloseable {
    private var initialized = false
    private var requestedStatus: ReverbCaptureSaveStatus? = null
    private var branch: CaptureStatusBranch? = null
    private var motion = CaptureSaveStatusMotion(false)
    private var closed = false
    private val frames = ViewFrameLoop(this, scope, { !closed && motion.running }) { time, scale ->
        motion.frame(time, scale)
        publishFrame()
        if (motion.exitCompleted) disposeExitedContent()
    }
    val hitLayout = host.hit(this, emptyList(), children = { branch?.parentHitChildren ?: emptyList() })

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        visibility = GONE
    }

    fun bind(status: ReverbCaptureSaveStatus?, next: ReverbEnvironment) {
        if (closed) return
        environment = next
        if (!initialized) {
            initialized = true
            requestedStatus = status
            motion = CaptureSaveStatusMotion(status != null)
            status?.let(::render)
            return
        }
        branch?.bindEnvironment(next)
        if (requestedStatus == status) return
        requestedStatus = status
        motion.setVisible(status != null)
        status?.let(::render)
        publishFrame()
        frames.request()
    }

    private fun render(value: ReverbCaptureSaveStatus) {
        val saving = value is ReverbCaptureSaveStatus.Saving
        val previous = branch
        if (previous == null || previous.saving != saving) {
            if (previous != null) {
                removeView(previous)
                previous.close()
            }
            branch = CaptureStatusBranch(context, environment, host, scope, saving, onCancel).also(::addView)
        }
        branch!!.bind(value, environment)
        visibility = VISIBLE
        motion.setVisible(requestedStatus != null)
        requestLayout()
        frames.request()
    }

    private fun publishFrame() {
        branch?.let { it.translationY = motion.offsetY.toFloat(); it.alpha = motion.alpha }
    }

    private fun disposeExitedContent() {
        if (requestedStatus != null || !motion.exitCompleted) return
        val previous = branch
        branch = null
        if (previous != null) { removeView(previous); previous.close() }
        motion = CaptureSaveStatusMotion(false)
        visibility = GONE
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        val content = branch
        if (content == null) { setMeasuredDimension(resolveSize(0, w), resolveSize(0, h)); return }
        content.measure(w, h)
        setMeasuredDimension(resolveSize(content.measuredWidth, w), resolveSize(content.measuredHeight, h))
        motion.measured(content.measuredHeight)
        publishFrame()
        frames.request()
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        branch?.let { child ->
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - child.measuredWidth else 0
            child.layout(x, 0, x + child.measuredWidth, child.measuredHeight)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); frames.request() }
    override fun onDetachedFromWindow() { frames.cancel(); super.onDetachedFromWindow() }
    override fun close() {
        if (closed) return
        closed = true
        frames.cancel()
        val previous = branch; branch = null
        previous?.let(::removeView)
        previous?.close()
    }
}

/** One conditional Saving/Saved subtree, preserving each RecordingSummaryCard's own motion lifetime. */
private class CaptureStatusBranch(
    context: Context,
    private var environment: ReverbEnvironment,
    private val host: ReverbSheetButtonHost,
    scope: CoroutineScope,
    val saving: Boolean,
    private val onCancel: () -> Unit,
) : ViewGroup(context), NativeMergedRowAccessibilityHost, AutoCloseable {
    private val row = ReverbLibraryRecordingRow(context, environment, scope, host.inputRoot)
    private val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock])
    private val progress: NativeCircularProgress? = if (saving) object : NativeCircularProgress(
        context, environment.palette.primary, frames, scope,
        { scope.coroutineContext[smallthingz.ui.MotionDurationScale]?.scaleFactor ?: 1f },
        strokeWidthDp = 2.5f,
    ) {
        override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
            if (host.sourceNode(this).present) super.createAccessibilityNodeInfo() else null
    } else null
    private var cancel: ReverbStandardIconButtonView? = null
    private var cancelNode: NativeClickPhaseNode? = null
    private var cancelHitChildren: List<NativeViewPointerHitLayout> = emptyList()
    private var cancelLease: AutoCloseable? = null
    private var closed = false
    private val noActions = LibraryRecordingRowActions(null, null, null)
    private val textLeases = row.paragraphLeaves.map { label ->
        label.isScreenReaderFocusable = saving // Saved has the source outer merging live region.
        label.bindPlainTextAccessibility(host.textWindow, host.textGeometry, { host.sourceNode(label) }) {
            host.sourceNode(label).present
        }
    }
    private val clipLease = host.registerTextClip(row)
    private val surfaceClipLease = host.registerTextClip(row.summarySurface.pointerContent)
    override val mergesAccessibilityDescendants get() = !saving
    override val hasAccessibilitySemanticNode get() = !saving
    override val mergedRowAccessibility = MergedRowAccessibility(this, NativeMergedRowRole.BUTTON,
        { NativeMergedRowAccessibilityState(true, null, null,
            row.paragraphLeaves.filter { it.visibility != GONE }, clickable = false, rolePresent = false) },
        { false })
    private val mergedLease = if (host.sourceProvider != null) {
        mergedRowAccessibility.bindSourceGeometry(host.textGeometry, host.sourceProvider) { !host.sourceNode(this).hidden }
    } else mergedRowAccessibility.bindGeometry(host.textGeometry,
        { host.sourceNode(this) }, { host.sourceNode(it).present }, { !host.sourceNode(this).hidden })
    private val surfaceShield = object : NativeViewPhasePointerNode {
        override val view get() = row.summarySurface
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
        override fun cancel() = Unit
    }
    val hitLayout = run {
        val surfaceHit = host.hit(row.summarySurface, listOf(surfaceShield),
            children = { cancelHitChildren },
            clip = { row.summarySurface.containsVisualPoint(it.x, it.y) })
        val surfaceHitChildren = listOf(surfaceHit)
        val rowHit = host.hit(row, emptyList(), children = { surfaceHitChildren },
            clip = { it.x >= 0f && it.x < row.width && it.y >= 0f && it.y < row.height })
        val rowHitChildren = listOf(rowHit)
        host.hit(this, emptyList(), children = { rowHitChildren })
    }
    val parentHitChildren = listOf(hitLayout)

    init {
        clipChildren = false; clipToPadding = false
        importantForAccessibility = if (saving) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = if (saving) ACCESSIBILITY_LIVE_REGION_NONE else ACCESSIBILITY_LIVE_REGION_POLITE
        if (!saving) {
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    mergedRowAccessibility.onInitializeAccessibilityNodeInfo(host, info)
                    info.isScreenReaderFocusable = !this@CaptureStatusBranch.host.sourceNode(this@CaptureStatusBranch).hidden
                    info.liveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
                    mergedRowAccessibility.performAccessibilityAction(host, action, args)
                override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
                    mergedRowAccessibility.onInitializeAccessibilityEvent(host, event)
                }
            }
        }
        progress?.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun performAccessibilityAction(view: View, action: Int, args: Bundle?): Boolean = when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> super.performAccessibilityAction(view, action, args)
                else -> false
            }
            override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(view, info)
                info.isEnabled = true; info.isFocusable = false; info.isFocused = false
                info.isClickable = false; info.isLongClickable = false
                info.isScreenReaderFocusable = true
                info.text = null; info.contentDescription = null
                info.setBoundsInParent(android.graphics.Rect())
                val actions = info.actionList
                for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
                info.addAction(if (view.isAccessibilityFocused)
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
                host.sourceNode(view).populate(info)
            }
            override fun onInitializeAccessibilityEvent(view: View, event: AccessibilityEvent) {
                super.onInitializeAccessibilityEvent(view, event)
                event.className = "android.view.View"; event.text.clear(); event.contentDescription = null
            }
        }
        row.setSummarySlots(progress, null)
        addView(row)
    }

    fun bind(value: ReverbCaptureSaveStatus, next: ReverbEnvironment) {
        bindEnvironment(next)
        val presentation = when (value) {
            is ReverbCaptureSaveStatus.Saving -> {
                bindCancel(value.cancellable)
                LibraryRecordingRow("capture-saving", context.getString(R.string.saving),
                    context.getString(R.string.reverb_brand_name), null, false, false, true, null)
            }
            is ReverbCaptureSaveStatus.Saved -> LibraryRecordingRow("capture-saved", value.name,
                "${value.duration} • ${value.size}", value.timestamp, false, false, true, null)
        }
        row.bind(presentation, noActions)
    }
    fun bindEnvironment(next: ReverbEnvironment) {
        if (environment === next) return
        environment = next
        row.setEnvironment(next)
        progress?.setColor(next.palette.primary)
        cancel?.let(::bindCancelButton)
    }
    private fun bindCancel(present: Boolean) {
        if (!present) {
            val old = cancel
            cancel = null
            cancelHitChildren = emptyList()
            val oldNode = cancelNode; cancelNode = null
            val oldLease = cancelLease; cancelLease = null
            row.setSummarySlots(progress, null)
            closeStatusBindings({ oldNode?.cancel() }, { old?.cancelInput() }, { oldLease?.close() })
        } else {
            if (cancel == null) {
                val button = ReverbStandardIconButtonView(context, environment, { host.inputRoot })
                cancel = button
                val node = NativeClickPhaseNode(button.visual, button.visual.nativeClickEndpoint, host.interestedParent)
                cancelNode = node
                val visualHit = host.hit(button.visual, listOf(node), clip = { position ->
                    val radius = minOf(button.visual.width, button.visual.height) * .5f
                    val dx = position.x - button.visual.width * .5f
                    val dy = position.y - button.visual.height * .5f
                    dx * dx + dy * dy <= radius * radius
                })
                val visualHitChildren = listOf(visualHit)
                cancelHitChildren = listOf(host.hit(button, emptyList(), children = { visualHitChildren }))
                cancelLease = button.bindAccessibility(host.textWindow, host.textGeometry, host.sourceProvider ?: { view ->
                    host.sourceNode(view, if (view === button.visual) button.minimumTouchTargetPx else 0f)
                })
                row.setSummarySlots(progress, button)
            }
            bindCancelButton(checkNotNull(cancel))
        }
    }
    private fun bindCancelButton(button: ReverbStandardIconButtonView) =
        button.bind(environment, ReverbIcon.Close, context.getString(R.string.cancel), true,
            environment.palette.onSurfaceVariant, environment.palette.onSurface, onCancel)

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (!saving && mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
    override fun addChildrenForAccessibility(out: ArrayList<View>) {
        if (!saving) mergedRowAccessibility.addChildrenForAccessibility(out) else super.addChildrenForAccessibility(out)
    }
    override fun onMeasure(w: Int, h: Int) {
        row.measure(w, h)
        setMeasuredDimension(resolveSize(row.measuredWidth, w), resolveSize(row.measuredHeight, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - row.measuredWidth else 0
        row.layout(x, 0, x + row.measuredWidth, row.measuredHeight)
    }
    override fun close() {
        if (closed) return
        closed = true
        val node = cancelNode; cancelNode = null
        cancelHitChildren = emptyList()
        val button = cancel; cancel = null
        val lease = cancelLease; cancelLease = null
        var failure = closePreservingPrimaryFailure(null) { node?.cancel() }
        failure = closePreservingPrimaryFailure(failure) { button?.cancelInput() }
        failure = closePreservingPrimaryFailure(failure) { lease?.close() }
        failure = closePreservingPrimaryFailure(failure, row::unbind)
        failure = closePreservingPrimaryFailure(failure, mergedLease::close)
        failure = closePreservingPrimaryFailure(failure, surfaceClipLease::close)
        failure = closePreservingPrimaryFailure(failure, clipLease::close)
        for (index in textLeases.indices.reversed()) {
            failure = closePreservingPrimaryFailure(failure, textLeases[index]::close)
        }
        failure?.let { throw it }
    }
}

private fun closeStatusBindings(vararg close: () -> Unit) {
    var failure: Throwable? = null
    for (action in close) failure = closePreservingPrimaryFailure(failure, action)
    failure?.let { throw it }
}
