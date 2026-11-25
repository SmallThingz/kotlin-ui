package smallthingz.ui.reverb

import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeActionSheetContentContext
import smallthingz.ui.components.NativeDeterminateLinearProgress
import smallthingz.ui.components.NativeIndeterminateLinearProgress
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.max
import kotlin.math.roundToInt

class ReverbTextSheet(
    anchor: View,
    environment: ReverbTheme,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    buttons: (NativeActionSheetContentContext) -> List<ReverbSheetButton>,
) : AutoCloseable {
    private val dialog = createReverbActionSheet(anchor, environment, title, onDismiss)
    private val body = ReverbSheetTextBody(dialog.contentContext, environment, message)
    private val actions = ReverbSheetActions(anchor.context, dialog.contentContext, environment, buttons(dialog.contentContext))
    private var closed = false
    init {
        dialog.setContent(body, actions, listOf(actions.hitLayout), scrollable = true)
    }
    fun show() = dialog.show()
    fun bind(title: String, message: String, next: ReverbTheme, onDismiss: () -> Unit) {
        if (closed) return
        dialog.bindReverbTheme(next)
        dialog.bind(title, onDismiss)
        body.bind(message, next)
        actions.bindEnvironment(next)
    }
    override fun close() {
        if (closed) return
        closed = true
        releaseSheetParts(listOf<() -> Unit>(dialog::dismiss, actions::close, body::close))
    }
}

private class ReverbSheetTextBody(
    private val host: NativeActionSheetContentContext,
    initialEnvironment: ReverbTheme,
    message: String,
) : ViewGroup(host.inputRoot.context), AutoCloseable {
    private val text = createReverbSupportingParagraph(context, message, NativeTypographyRole.BODY_MEDIUM, initialEnvironment)
    private val lease = text.bindPlainTextAccessibility(host.accessibility.cursor, host.accessibility.geometry,
        { host.accessibility.sourceNode(text) }) { host.accessibility.sourceNode(text).present }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(text) }
    fun bind(message: String, next: ReverbTheme) {
        next.bindSupportingParagraph(text, NativeTypographyRole.BODY_MEDIUM)
        text.setText(message)
    }
    override fun onMeasure(w: Int, h: Int) {
        text.measure(captureAtMost(MeasureSpec.getSize(w)), h)
        setMeasuredDimension(resolveSize(text.measuredWidth, w), resolveSize(text.measuredHeight, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - text.measuredWidth else 0
        text.layout(x, 0, x + text.measuredWidth, text.measuredHeight)
    }
    override fun close() = lease.close()
}

class ReverbClearProgressSheet(
    anchor: View,
    environment: ReverbTheme,
    initial: ReverbClearProgressPresentation,
    private val onCancel: () -> Unit,
) : AutoCloseable {
    private val dialog = createReverbActionSheet(anchor, environment, initial.title) {}
    private val body = CaptureClearProgressBody(dialog.contentContext, environment, initial)
    private val cancel = ReverbSheetButton(anchor.context, dialog.contentContext, environment, false,
        initial.cancelLabel, onCancel, leadingIcon = ReverbIcon.Close)
    private val actions = ReverbSheetActions(anchor.context, dialog.contentContext, environment, listOf(cancel))
    private var closed = false
    init {
        dialog.setDismissalAdmission { false }
        dialog.setContent(body, actions, listOf(actions.hitLayout), scrollable = true)
        bind(initial, environment)
    }
    fun show() = dialog.show()
    fun bind(status: ReverbClearProgressPresentation, next: ReverbTheme) {
        if (closed) return
        dialog.bindReverbTheme(next)
        dialog.bind(status.title) {}
        body.bind(status, next)
        actions.bindEnvironment(next)
        cancel.bind(status.cancelLabel, status.canCancel, onCancel)
    }
    override fun close() {
        if (closed) return
        closed = true
        releaseSheetParts(listOf<() -> Unit>(dialog::dismiss, actions::close, body::close))
    }
}

data class ReverbClearProgressPresentation(
    val title: String,
    val detail: String,
    val note: String,
    val cancelLabel: String,
    val canCancel: Boolean,
    val fraction: Float?,
    val operationId: Long,
    val isTerminal: Boolean,
)

/** Source Column: progress,12dp,bodyMedium detail,8dp,bodySmall cancellation note. */
private class CaptureClearProgressBody(
    private val host: NativeActionSheetContentContext,
    initialEnvironment: ReverbTheme,
    initial: ReverbClearProgressPresentation,
) : ViewGroup(host.inputRoot.context), AutoCloseable {
    private var environment = initialEnvironment
    private var progress: View? = null
    private var progressLease: AutoCloseable? = null
    private val detail = createReverbSupportingParagraph(context, "", NativeTypographyRole.BODY_MEDIUM, environment)
    private val note = createReverbSupportingParagraph(context, initial.note, NativeTypographyRole.BODY_SMALL, environment)
    private val textLeases = listOf(detail, note).map { label ->
        label.bindPlainTextAccessibility(host.accessibility.cursor, host.accessibility.geometry,
            { host.accessibility.sourceNode(label) }) { host.accessibility.sourceNode(label).present }
    }
    private var firstGap = 0
    private var secondGap = 0
    private var closed = false
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        addView(detail); addView(note)
        bind(initial, environment)
    }
    fun bind(status: ReverbClearProgressPresentation, next: ReverbTheme) {
        if (closed) return
        environment = next
        val fraction = status.fraction
        val wantDeterminate = fraction != null
        val current = progress
        if (current == null || (current is NativeDeterminateLinearProgress) != wantDeterminate) {
            progressLease?.close(); progressLease = null
            current?.let(::removeView)
            val replacement = if (wantDeterminate) NativeDeterminateLinearProgress(context, next.palette.primary,
                next.palette.secondaryContainer, next.density.density)
                else NativeIndeterminateLinearProgress(context, next.palette.primary, next.palette.secondaryContainer,
                    next.density.density, host.runtime.frames, host.runtime.scope,
                    { readCachedAnimatorDurationScale(context) })
            progress = replacement
            addView(replacement, 0)
            progressLease = when (replacement) {
                is NativeDeterminateLinearProgress -> replacement.bindAccessibility(host.accessibility.geometry) {
                    host.accessibility.sourceNode(replacement).present
                }
                is NativeIndeterminateLinearProgress -> replacement.bindAccessibility(host.accessibility.geometry) {
                    host.accessibility.sourceNode(replacement).present
                }
                else -> error("Unexpected progress branch")
            }
        }
        when (val value = progress) {
            is NativeDeterminateLinearProgress -> {
                value.bindSurfacePreset(next.palette.surfacePreset)
                value.bind(checkNotNull(fraction), next.palette.primary,
                    next.palette.secondaryContainer, next.density.density, status.operationId, status.isTerminal)
            }
            is NativeIndeterminateLinearProgress -> {
                value.bindSurfacePreset(next.palette.surfacePreset)
                value.bindColors(next.palette.primary, next.palette.secondaryContainer, next.density.density)
            }
        }
        next.bindSupportingParagraph(detail, NativeTypographyRole.BODY_MEDIUM)
        next.bindSupportingParagraph(note, NativeTypographyRole.BODY_SMALL)
        detail.setText(status.detail)
        note.setText(status.note)
        // Text, branch membership and density request layout only when their geometry changes.
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val bounded = MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED
        val maximum = if (bounded) MeasureSpec.getSize(h) else Int.MAX_VALUE
        val indicator = checkNotNull(progress)
        indicator.measure(captureExact(width), if (bounded) captureAtMost(maximum) else h)
        var used = indicator.measuredHeight
        firstGap = minOf(dp(12), max(0, maximum - used)); used += firstGap
        detail.measure(captureAtMost(width), if (bounded) captureAtMost(max(0, maximum - used)) else h)
        used += detail.measuredHeight
        secondGap = minOf(dp(8), max(0, maximum - used)); used += secondGap
        note.measure(captureAtMost(width), if (bounded) captureAtMost(max(0, maximum - used)) else h)
        used += note.measuredHeight
        setMeasuredDimension(resolveSize(width, w), resolveSize(used, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val indicator = checkNotNull(progress)
        indicator.layout(0, 0, indicator.measuredWidth, indicator.measuredHeight)
        fun place(view: View, top: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - view.measuredWidth else 0
            view.layout(x, top, x + view.measuredWidth, top + view.measuredHeight)
        }
        val detailTop = indicator.measuredHeight + firstGap
        place(detail, detailTop)
        place(note, detailTop + detail.measuredHeight + secondGap)
    }
    override fun close() {
        if (closed) return
        closed = true
        val lease = progressLease; progressLease = null
        progress?.let(::removeView); progress = null
        releaseSheetParts(listOf<() -> Unit>({ lease?.close() }) + textLeases.asReversed().map { value -> { value.close() } })
    }
    private fun dp(value: Int) = (value * environment.density.density).roundToInt()
}

internal fun releaseSheetParts(blocks: List<() -> Unit>) {
    var failure: Throwable? = null
    for (block in blocks) try { block() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    failure?.let { throw it }
}
private fun captureExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
private fun captureAtMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
