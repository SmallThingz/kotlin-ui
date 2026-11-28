package smallthingz.ui.reverb.settings

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.components.NativeExpansionView
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeTopBarInsets
import smallthingz.ui.components.NativeViewAnimatorScale
import smallthingz.ui.components.atMost
import smallthingz.ui.components.exact
import smallthingz.ui.geometry.Rect
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbTextSurfaceScope
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.view.NativeTextSemanticsClip

/** Places the Settings toolbar and scroll surface within the remaining window insets. */
abstract class ReverbSettingsScreenLayout(
    context: Context,
    private var environment: ReverbEnvironment,
    private val frames: MonotonicFrameClock,
    private val animationScope: CoroutineScope,
) : ViewGroup(context), ReverbTextSurfaceScope {
    private lateinit var toolbar: View
    private var list: ReverbSettingsSectionListView? = null
    private var listFocusability = FOCUS_BEFORE_DESCENDANTS
    private var progress: NativeCircularProgress? = null
    private var insets = NativeTopBarInsets(0, 0, 0)
    private var bottomInset = 0
    private var inputsAllowed = false
    private var hydrationPending = false
    private var loadingBackAllowed = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren = false
        clipToPadding = false
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    protected fun installTopBar(view: View) {
        toolbar = view
        addView(view)
        applyExposure()
    }

    protected fun setSettingsList(next: ReverbSettingsSectionListView?) {
        if (list === next) return
        list?.let {
            removeView(it)
            it.descendantFocusability = listFocusability
        }
        list = next
        if (next != null) {
            listFocusability = next.descendantFocusability
            applyListExposure(next)
            addView(next, 0)
        }
    }

    protected fun bindSettingsSurface(
        next: ReverbEnvironment,
        remainingInsets: NativeTopBarInsets,
        remainingBottom: Int,
        inputAllowed: Boolean,
        pending: Boolean,
        loadingBackAllowed: Boolean = false,
    ) {
        val layoutChanged = environment.textDensity != next.textDensity || insets != remainingInsets ||
            bottomInset != remainingBottom
        val paintChanged = environment.palette.primary != next.palette.primary
        val exposureChanged = inputsAllowed != inputAllowed || hydrationPending != pending ||
            this.loadingBackAllowed != loadingBackAllowed
        environment = next
        insets = remainingInsets
        bottomInset = remainingBottom
        inputsAllowed = inputAllowed
        hydrationPending = pending
        this.loadingBackAllowed = loadingBackAllowed
        if (exposureChanged) applyExposure()
        if (pending) {
            val existing = progress
            if (existing == null) {
                val scale = NativeViewAnimatorScale(context)
                progress = NativeCircularProgress(context, next.palette.primary, frames, animationScope,
                    scale::read, strokeWidthDp = 4f).also {
                    it.isScreenReaderFocusable = true
                    it.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
                    addView(it)
                }
            } else if (paintChanged) existing.setColor(next.palette.primary)
            progress?.bindSurfacePreset(next.palette.surfacePreset)
        } else if (progress != null) {
            progress?.let(::removeView)
            progress = null
        }
        if (layoutChanged) requestLayout()
    }

    private fun applyExposure() {
        importantForAccessibility = if (inputsAllowed || hydrationPending) IMPORTANT_FOR_ACCESSIBILITY_NO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val descendants = if (inputsAllowed) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        toolbar.importantForAccessibility = if (loadingBackAllowed) IMPORTANT_FOR_ACCESSIBILITY_NO else descendants
        list?.let(::applyListExposure)
    }

    private fun applyListExposure(view: ReverbSettingsSectionListView) {
        view.descendantFocusability = if (inputsAllowed) listFocusability else FOCUS_BLOCK_DESCENDANTS
        // Main can request a leaf directly, so block the subtree before clearing its focus.
        if (!inputsAllowed) view.findFocus()?.clearFocus()
        view.importantForAccessibility = if (inputsAllowed) IMPORTANT_FOR_ACCESSIBILITY_NO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.alpha = if (hydrationPending) .38f else 1f
    }

    protected fun settingsSourceClip(view: View): NativeTextSemanticsClip {
        val group = view.parent as? NativeGroupLayout
        return if (view === list?.viewport || view is NativeExpansionView || group?.pointerContent === view)
            NativeTextSemanticsClip.Rectangle(Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else NativeTextSemanticsClip.Unclipped
    }

    protected abstract fun onSettingsLayoutChanged()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        toolbar.measure(exact(width), atMost(height))
        list?.measure(exact(width - insets.left - insets.right), exact(height - toolbar.measuredHeight - bottomInset))
        val progressSize = (40f * environment.textDensity.density).toInt()
        progress?.measure(exact(progressSize), exact(progressSize))
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = toolbar.measuredHeight
        toolbar.layout(0, 0, width, top)
        list?.let { it.layout(insets.left, top, insets.left + it.measuredWidth, top + it.measuredHeight) }
        progress?.let {
            val x = insets.left + (width - insets.left - insets.right - it.measuredWidth) / 2
            val y = top + (height - top - bottomInset - it.measuredHeight) / 2
            it.layout(x, y, x + it.measuredWidth, y + it.measuredHeight)
        }
        onSettingsLayoutChanged()
    }

    protected fun releaseSettingsSurface() {
        setSettingsList(null)
        progress?.let(::removeView)
        progress = null
    }
}
