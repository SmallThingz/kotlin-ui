package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.components.NativeMergedActionLayout
import smallthingz.ui.components.atMost
import smallthingz.ui.components.bindStatus
import smallthingz.ui.components.exact
import smallthingz.ui.components.tileStatus
import smallthingz.ui.components.unspecified
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.input.NativeAndroidInteropRequests
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbIcon
import kotlin.math.roundToInt

/** Shared segmented header, duration body, and a fixed footer inside the inline tile editor. */
open class ReverbQuickTileEditor<T : Any>(
    context: Context,
    private var environment: ReverbEnvironment,
    options: List<SegmentOption<T>>,
    initial: T,
    inputRoot: () -> View?,
    onSelect: (T) -> Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) : ViewGroup(context), NativeAndroidInteropHost, AutoCloseable {
    override val nativeInteropRequests = NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private var controlsEnabled = false
    private val selector = ReverbSegmentedSelector(context, environment.theme, options, initial,
        inputAllowed = { controlsEnabled && inputRoot() != null }, onSelectionRequest = onSelect)
    private val loading = tileStatus(context, "", environment)
    private val warning = tileStatus(context, "", environment)
    private val message = tileStatus(context, "", environment)
    private val cancel = ReverbSettingsActionView(context, environment, inputRoot, leadingIcon = ReverbIcon.Close, action = onCancel)
    private val save = ReverbSettingsActionView(context, environment, inputRoot, leadingIcon = ReverbIcon.Save, action = onSave)
    private var wheelView: View? = null
    private val footer = NativeMergedActionLayout(context, listOf(cancel, save), environment.palette).asActionFooter(density = environment.textDensity.density)
    private val recipe get() = environment.appearance.quickTiles

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(selector.view)
        addView(loading)
        addView(warning)
        addView(message)
        addView(footer)
        loading.visibility = GONE
        warning.visibility = GONE
        message.visibility = GONE
    }

    protected fun replaceWheelView(view: View?) {
        if (wheelView === view) return
        wheelView?.let(::removeView)
        wheelView = view
        if (view != null) addView(view, 2)
    }

    protected fun bindInlineLayout(
        next: ReverbEnvironment,
        options: List<SegmentOption<T>>,
        selected: T,
        controlsEnabled: Boolean,
        loadingText: String?,
        warningText: String?,
        messageText: String?,
        cancelText: String,
        cancelEnabled: Boolean,
        saveText: String,
        saveEnabled: Boolean,
    ) {
        val densityChanged = environment.textDensity != next.textDensity
        environment = next
        this.controlsEnabled = controlsEnabled
        selector.bind(options, selected, next.theme)
        bindStatus(loading, loadingText, next)
        bindStatus(warning, warningText, next)
        bindStatus(message, messageText, next)
        footer.bindPalette(next.palette)
        footer.asActionFooter(density = next.textDensity.density)
        cancel.bind(cancelText, cancelEnabled, next)
        save.bind(saveText, saveEnabled, next)
        if (densityChanged) requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val inset = dp(recipe.editorInsetDp)
        val gap = dp(recipe.editorGapDp)
        val inner = (width - 2 * inset).coerceAtLeast(0)
        selector.view.measure(exact(inner), unspecified())
        footer.measure(exact(inner), unspecified())
        var height = inset + selector.view.measuredHeight + gap + footer.measuredHeight
        for (index in 1 until childCount - 1) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            if (child === wheelView) {
                val wheelHeight = (environment.appearance.durationWheel.heightDp * environment.textDensity.density).roundToInt()
                child.measure(exact(inner), exact(wheelHeight))
            } else child.measure(atMost(inner), unspecified())
            height += gap + child.measuredHeight
        }
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inset = dp(recipe.editorInsetDp)
        val gap = dp(recipe.editorGapDp)
        var y = inset
        place(selector.view, inset, y)
        y += selector.view.measuredHeight
        for (index in 1 until childCount - 1) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            y += gap
            place(child, inset, y)
            y += child.measuredHeight
        }
        place(footer, inset, height - footer.measuredHeight)
    }

    private fun place(child: View, start: Int, top: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
        child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
    }

    override fun close() {
        selector.close()
        cancel.close()
        save.close()
    }

    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}
