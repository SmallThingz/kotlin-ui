package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.geometry.roundedGroupGeometry
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

private val actionProgressScope by lazy { CoroutineScope(Dispatchers.Main.immediate) }

/** Creates a main-thread indicator whose frame work follows its attachment and visibility. */
fun nativeActionSpinner(context: Context, color: Int) = NativeCircularProgress(
    context, color, AndroidUiFrameClock(Choreographer.getInstance()),
    actionProgressScope, { readCachedAnimatorDurationScale(context) },
)

/**
 * Standalone switch and thumb progress with one measured input and accessibility owner.
 * Activation and switch rendering remain owned by [NativeToggleRow]. Callers supply the name,
 * checked/enabled state and progress wording, and may override [accessibilityClickLabel].
 * Progress does not change whether the action is enabled or what its callback does.
 */
open class NativeProgressSwitch(
    context: Context,
    private val scheme: NativeColorScheme,
    name: CharSequence,
    onChanged: (Boolean) -> Unit,
    private val minimumTargetDp: Float = NativeToolbarActionStyle.Default.minimumTargetDp,
    private val mark: NativeMaterialSwitchView = NativeMaterialSwitchView(context, scheme),
) : NativeToggleRow(context, View(context), null, mark, onChanged = onChanged) {
    private var closed = false
    private val indicationClip = Path()
    private val spinner by lazy {
        nativeActionSpinner(context, scheme.ink).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    init {
        contentDescription = name
    }

    /** A null description removes progress and restores the shared On/Off semantics. */
    fun bindProgress(checked: Boolean, enabled: Boolean, progress: CharSequence?) {
        if (closed) return
        bindToggle(checked, enabled, scheme)
        mark.setThumbProgress(if (progress == null) null else spinner)
        ViewCompat.setStateDescription(this, progress)
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure = closePreservingPrimaryFailure(null) { super.close() }
        // Detaching stops the indicator's frames and observer without creating an unused spinner.
        failure = closePreservingPrimaryFailure(failure) { mark.setThumbProgress(null) }
        failure?.let { throw it }
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        super.createAccessibilityNodeInfo()?.also { node ->
            // The shared switch delegate otherwise supplies its default On/Off description.
            ViewCompat.getStateDescription(this)?.let { AccessibilityNodeInfoCompat.wrap(node).stateDescription = it }
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        mark.measure(unspecified, unspecified)
        val minimumTarget = (minimumTargetDp * resources.displayMetrics.density).roundToInt()
        val width = resolveSize(maxOf(suggestedMinimumWidth, minimumTarget, mark.measuredWidth), widthSpec)
        val height = resolveSize(maxOf(suggestedMinimumHeight, minimumTarget, mark.measuredHeight), heightSpec)
        mark.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, height)
        measureRowRole(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = (width - mark.measuredWidth) / 2
        val y = (height - mark.measuredHeight) / 2
        mark.layout(x, y, x + mark.measuredWidth, y + mark.measuredHeight)
        layoutRowRole()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        val radius = roundedGroupGeometry(w.toFloat(), h.toFloat(), density,
            minOf(w, h) / (2f * density)).radius
        indicationClip.rewind()
        indicationClip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
    }

    override fun onDrawForeground(canvas: Canvas) {
        // Only the standalone indication is pill-shaped. Keep the mark/progress and the full
        // rectangular input/accessibility target, including its corners, owned by the same row.
        val checkpoint = canvas.save()
        try {
            canvas.clipPath(indicationClip)
            super.onDrawForeground(canvas)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    // A disabled accessory still owns its area instead of falling through to its parent.
    override fun onTouchEvent(event: MotionEvent) = super.onTouchEvent(event) || !isEnabled
}
