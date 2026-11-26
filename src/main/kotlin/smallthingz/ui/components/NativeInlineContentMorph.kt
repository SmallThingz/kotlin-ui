package smallthingz.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.animation.SpringValue
import smallthingz.ui.animation.view.ViewSpringDriver
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import kotlin.math.roundToInt

/** One retained row: its source action becomes the editor without a second card or layout jump. */
class NativeInlineContentMorph<Editor : View>(
    context: Context,
    private val action: View,
    private val retire: (Editor) -> Unit,
) : ViewGroup(context), AutoCloseable {
    private var expanded: Editor? = null
    private var opening = false
    private var sourceAvailable = true
    private var closed = false
    private var measuredContentHeight = -1
    private val progress = SpringValue.float(0f)
    private val durationScale = NativeViewAnimatorScale(context)
    private var driver: ViewSpringDriver<Float, smallthingz.ui.animation.core.AnimationVector1D>? = null
    val hasEditor get() = expanded != null
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = true
        addView(action)
    }
    fun editor(next: Editor?, sourceAvailable: Boolean) {
        if (closed) return
        this.sourceAvailable = sourceAvailable
        if (next != null && expanded !== next) {
            releaseEditor()
            expanded = next
            addView(next)
        }
        opening = next != null
        expanded?.importantForAccessibility = if (opening) IMPORTANT_FOR_ACCESSIBILITY_NO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        action.importantForAccessibility = if (opening) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val target = if (opening) 1f else 0f
        if (durationScale.target(target) == 0f || !isAttachedToWindow) {
            progress.snapTo(target)
            publish()
        } else {
            val motion = driver ?: ViewSpringDriver(this, progress, { publish() },
                durationScale::read).also { driver = it }
            motion.retarget(target)
            publish()
        }
    }
    private fun publish() {
        val fraction = progress.value.coerceIn(0f, 1f)
        action.alpha = 1f - fraction
        action.visibility = if (!sourceAvailable || opening && !progress.isRunning && fraction == 1f)
            INVISIBLE else VISIBLE
        expanded?.alpha = fraction
        if (!opening && !progress.isRunning) releaseEditor()
        // Alpha changes invalidate their own Views. Remeasure only when the rounded natural
        // height differs from the last measurement; child/parent layout requests remain intact.
        if (contentHeight() != measuredContentHeight) requestLayout()
    }
    private fun contentHeight(): Int {
        val collapsedHeight = if (sourceAvailable) action.measuredHeight else 0
        return collapsedHeight +
            (((expanded?.measuredHeight ?: collapsedHeight) - collapsedHeight) *
                progress.value.coerceIn(0f, 1f)).roundToInt()
    }
    private fun releaseEditor() {
        val old = expanded ?: return
        expanded = null
        try { retire(old) } finally { removeView(old) }
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        action.measure(exact(width), unspecified())
        expanded?.measure(exact(width), unspecified())
        measuredContentHeight = contentHeight()
        setMeasuredDimension(width, resolveSize(measuredContentHeight, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        action.layout(0, 0, width, action.measuredHeight)
        expanded?.let { it.layout(0, 0, width, it.measuredHeight) }
    }
    override fun onDetachedFromWindow() {
        driver?.close(); driver = null
        progress.snapTo(if (opening) 1f else 0f)
        publish()
        super.onDetachedFromWindow()
    }
    override fun close() {
        if (closed) return
        closed = true
        driver?.close(); driver = null
        releaseEditor()
    }
}

/** A denied optional setting is retried once the animation has a new target. */
class NativeViewAnimatorScale(private val context: Context) {
    private var target: Float? = null
    private var denied = false

    fun target(next: Float): Float {
        if (target != next) { target = next; denied = false }
        return read()
    }

    fun read(): Float {
        if (!ValueAnimator.areAnimatorsEnabled()) return 0f
        if (denied) return 1f
        val scale = try { readCachedAnimatorDurationScale(context) }
        catch (_: SecurityException) { denied = true; 1f }
        return if (scale.isFinite() && scale >= 0f) scale else 1f
    }
}
