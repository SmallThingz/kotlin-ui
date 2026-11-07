package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.input.NativeAndroidInteropRequests
import kotlin.math.roundToInt

/** Optional retained surface renderer; the control owns content, geometry and input. */
interface NativeSegmentedPresentation {
    val surface: ViewGroup
    fun setContent(content: View) { surface.addView(content) }
    fun bindSurface(palette: ControlPalette)
}

/** A single-row capsule with owner-bound selection and a stable Android pointer interop host. */
class NativeSegmentedControl(
    context: Context,
    palette: ControlPalette,
    presentation: NativeSegmentedPresentation? = null,
    private var density: Float = context.resources.displayMetrics.density,
    private var inputAllowed: (() -> Boolean)? = { true },
) : ViewGroup(context), NativeInputGate, AutoCloseable {
    private var closed = false
    private val presentation = presentation ?: DefaultSegmentedPresentation(context, palette)
    private val row = SegmentHost(context)
    private val choices = NativeSegmentedChoice(context, palette,
        NativeChoiceStyle(radiusDp = 22f, minHeightDp = 34f, horizontalPaddingDp = 0f,
            verticalPaddingDp = 0f, outlined = false, selectedOutlined = false, iconSizeDp = 16f, iconGapDp = 6f,
            normalTextRole = NativeTextRole.CONTENT, selectedTextRole = NativeTextRole.ON_ACCENT,
            normalIconRole = NativeTextRole.CONTENT, selectedIconRole = NativeTextRole.ON_ACCENT,
            textStyle = NativeTextStyle(sizeSp = 14f, lineHeightSp = 20f, weight = 400),
            animateSelection = true), gapDp = 0f, activateOnFocusNavigation = false, singleRow = true)
    var onSelectionRequest: ((String) -> Boolean)? = null

    override val acceptsNativeInput: Boolean
        get() = !closed && isEnabled && isAttachedToWindow && isShown && hasWindowFocus() && inputAllowed?.invoke() == true

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(choices)
        this.presentation.setContent(row)
        addView(this.presentation.surface)
        choices.onSelectionRequest = { id -> acceptsNativeInput && onSelectionRequest?.invoke(id) == true }
    }

    fun bind(options: List<NativeChoice>, selectedId: String, palette: ControlPalette,
             density: Float = resources.displayMetrics.density) {
        if (closed) return
        require(density.isFinite() && density > 0f)
        this.density = density
        presentation.bindSurface(palette)
        choices.applyPalette(palette)
        choices.bind(options, selectedId)
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        presentation.surface.measure(w, h)
        setMeasuredDimension(presentation.surface.measuredWidth, presentation.surface.measuredHeight)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        presentation.surface.layout(0, 0, width, height)
    }
    override fun close() {
        if (closed) return
        closed = true
        inputAllowed = null
        onSelectionRequest = null
        choices.close()
    }

    private inner class SegmentHost(context: Context) : ViewGroup(context), NativeAndroidInteropHost {
        override val nativeInteropRequests = NativeAndroidInteropRequests()
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            nativeInteropRequests.request(disallowIntercept)
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            choices.measure(exact((width - 2 * dp(4)).coerceAtLeast(0)), exact(dp(34)))
            setMeasuredDimension(resolveSize(width, w), resolveSize(dp(42), h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            choices.layout(dp(4), dp(4), dp(4) + choices.measuredWidth, dp(4) + choices.measuredHeight)
        }
    }
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}

internal class DefaultSegmentedPresentation(context: Context, palette: ControlPalette) : NativeSegmentedPresentation {
    override val surface = NativeSurface(context, NativeSurfaceStyle(palette.surface, cornerRadiusDp = 30f,
        outlineColor = palette.outline))
    override fun bindSurface(palette: ControlPalette) {
        surface.applyStyle(NativeSurfaceStyle(palette.surface, cornerRadiusDp = 30f, outlineColor = palette.outline))
    }
}
