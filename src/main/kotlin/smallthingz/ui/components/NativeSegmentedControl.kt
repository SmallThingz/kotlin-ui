package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.input.NativeAndroidInteropRequests
import kotlin.math.roundToInt

private const val MIN_SEGMENT_HORIZONTAL_PADDING_DP = 12f

/** Optional retained surface renderer; the control owns content, geometry and input. */
interface NativeSegmentedPresentation {
    val surface: ViewGroup
    fun setContent(content: View) { surface.addView(content) }
    fun bindSurface(palette: ControlPalette)
    /** Actual opaque/composited underlay, including animation. Unknown or translucent paint keeps legacy ink. */
    val paintedSurfaceColor: Int? get() = null
}

/** Dimensions and paint roles for a retained single-row segmented control. */
data class NativeSegmentedStyle(
    val surfaceRadiusDp: Float = 30f,
    val insetDp: Float = 4f,
    val choiceStyle: NativeChoiceStyle = NativeChoiceStyle(
        radiusDp = 22f, minHeightDp = 34f, horizontalPaddingDp = MIN_SEGMENT_HORIZONTAL_PADDING_DP, verticalPaddingDp = 0f,
        outlined = false, selectedOutlined = false, iconSizeDp = 16f, iconGapDp = 6f,
        normalTextRole = NativeTextRole.CONTENT, selectedTextRole = NativeTextRole.ON_ACCENT,
        normalIconRole = NativeTextRole.CONTENT, selectedIconRole = NativeTextRole.ON_ACCENT,
        textStyle = NativeTextStyle(sizeSp = 14f, lineHeightSp = 20f, weight = 400),
    ),
) {
    init {
        require(surfaceRadiusDp.isFinite() && surfaceRadiusDp >= 0f)
        require(insetDp.isFinite() && insetDp >= 0f)
        require((2 * insetDp + choiceStyle.minHeightDp).isFinite())
    }

    companion object {
        val Default = NativeSegmentedStyle()
    }
}

/**
 * A compact capsule that reflows its retained choices when text needs more space.
 * Each choice reserves at least 12dp before and after its text/icon content, including custom styles.
 */
class NativeSegmentedControl private constructor(
    context: Context,
    palette: ControlPalette,
    presentation: NativeSegmentedPresentation?,
    private var density: Float,
    private var inputAllowed: (() -> Boolean)?,
    private val style: NativeSegmentedStyle,
) : ViewGroup(context), NativeInputGate, AutoCloseable {
    constructor(
        context: Context,
        palette: ControlPalette,
        presentation: NativeSegmentedPresentation? = null,
        density: Float = context.resources.displayMetrics.density,
        inputAllowed: (() -> Boolean)? = { true },
    ) : this(context, palette, presentation, density, inputAllowed, NativeSegmentedStyle.Default)

    constructor(
        context: Context,
        palette: ControlPalette,
        style: NativeSegmentedStyle,
        density: Float = context.resources.displayMetrics.density,
        inputAllowed: (() -> Boolean)? = { true },
        presentation: NativeSegmentedPresentation? = null,
    ) : this(context, palette, presentation, density, inputAllowed, style)

    private var closed = false
    private var boundOptions: List<NativeChoice>? = null
    private var boundPalette: ControlPalette? = null
    private val presentation = presentation ?: DefaultSegmentedPresentation(context, palette, style.surfaceRadiusDp)
    private val row = SegmentHost(context)
    private val choices = NativeSegmentedChoice(context, palette, style.choiceStyle.copy(
        horizontalPaddingDp = style.choiceStyle.horizontalPaddingDp.coerceAtLeast(MIN_SEGMENT_HORIZONTAL_PADDING_DP),
        startPaddingDp = style.choiceStyle.startPaddingDp?.coerceAtLeast(MIN_SEGMENT_HORIZONTAL_PADDING_DP),
        endPaddingDp = style.choiceStyle.endPaddingDp?.coerceAtLeast(MIN_SEGMENT_HORIZONTAL_PADDING_DP),
    ),
        gapDp = 0f, activateOnFocusNavigation = false, singleRow = true,
        movingSelectionBackground = true)
    var onSelectionRequest: ((String) -> Boolean)? = null
    internal var preferredWidthPixels = 0
        private set

    override val acceptsNativeInput: Boolean
        get() = !closed && isEnabled && isAttachedToWindow && isShown && hasWindowFocus() && inputAllowed?.invoke() == true

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(choices)
        choices.useUniformSelectionForeground { this.presentation.paintedSurfaceColor }
        this.presentation.setContent(row)
        addView(this.presentation.surface)
        choices.onSelectionRequest = { id -> acceptsNativeInput && onSelectionRequest?.invoke(id) == true }
    }

    internal fun setOptionsEnabled(enabled: Boolean) {
        if (!closed) choices.isEnabled = enabled
    }

    fun bind(options: List<NativeChoice>, selectedId: String, palette: ControlPalette,
             density: Float = resources.displayMetrics.density) {
        if (closed) return
        require(density.isFinite() && density > 0f)
        if (this.density != density) {
            this.density = density
            requestLayout()
        }
        if (boundPalette != palette) {
            boundPalette = palette
            presentation.bindSurface(palette)
            choices.applyPalette(palette)
        }
        if (boundOptions != options) {
            boundOptions = options.toList()
            choices.bind(options, selectedId)
        } else {
            choices.bindSelection(selectedId)
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        choices.probeCapsuleCells()
        preferredWidthPixels = (choices.preferredCellWidth.toLong() * choices.childCount + 2L * dp(style.insetDp))
            .coerceAtMost(View.MEASURED_SIZE_MASK.toLong()).toInt()
        // The probe measured retained chips unconstrained. Every wrapper on their measure path
        // must participate in restoring final cell sizes, even if the resolved surface spec is unchanged.
        var child: View? = choices
        while (child != null && child !== this) {
            child.forceLayout()
            child = child.parent as? View
        }
        presentation.surface.measure(exact(resolveSize(preferredWidthPixels, w)), h)
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
        private var horizontalInset = 0
        override val nativeInteropRequests = NativeAndroidInteropRequests()
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            nativeInteropRequests.request(disallowIntercept)
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            horizontalInset = minOf(dp(style.insetDp), ((width - 1).coerceAtLeast(0) / 2))
            val minChoiceHeight = dp(style.choiceStyle.minHeightDp)
            val innerWidth = (width - 2 * horizontalInset).coerceAtLeast(0)
            val preferredCell = choices.preferredCellWidth.coerceAtLeast(1)
            val maxColumns = (innerWidth / preferredCell).coerceAtLeast(1)
            val wrap = choices.preferredCellWidth.toLong() * choices.childCount > innerWidth
            choices.capsuleColumns = if (wrap) maxColumns else null
            choices.measure(exact(innerWidth), if (wrap) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                else exact(maxOf(minChoiceHeight, choices.preferredCellContentHeight)))
            val originalHeight = dp(2 * style.insetDp + style.choiceStyle.minHeightDp)
            val desiredHeight = originalHeight + (choices.measuredHeight - minChoiceHeight).coerceAtLeast(0)
            setMeasuredDimension(resolveSize(width, w), resolveSize(desiredHeight, h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val top = dp(style.insetDp)
            choices.layout(horizontalInset, top, horizontalInset + choices.measuredWidth, top + choices.measuredHeight)
        }
    }
    private fun dp(value: Float) = (value * density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}

internal class DefaultSegmentedPresentation(
    context: Context,
    palette: ControlPalette,
    private val radiusDp: Float,
) : NativeSegmentedPresentation {
    override val surface = NativeSurface(context, NativeSurfaceStyle(palette.surface, cornerRadiusDp = radiusDp,
        outlineColor = palette.outline))
    override fun bindSurface(palette: ControlPalette) {
        surface.applyStyle(NativeSurfaceStyle(palette.surface, cornerRadiusDp = radiusDp, outlineColor = palette.outline))
    }
    override val paintedSurfaceColor: Int get() = surface.paintedFillColor
}
