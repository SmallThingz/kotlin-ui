package smallthingz.ui.components

import android.app.Dialog
import android.app.Activity
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.graphics.NoiseDrawable
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

enum class NativeDialogPlacement { CENTER, TOP, BOTTOM }
enum class NativeDialogCorners { ALL, TOP, BOTTOM }
enum class NativeDialogWidthPolicy { FRACTION, PLATFORM_WRAP_CONTENT }
enum class NativeDialogDimPolicy { EXPLICIT, THEME }
enum class NativeDialogSoftInputAdjust { RESIZE, UNSPECIFIED }

/** Explicit application design tokens; these do not change any child control's state or style. */
data class NativeDialogStyle(
    val widthFraction: Float = .92f,
    val maxWidthDp: Float? = 560f,
    val maxHeightFraction: Float = .88f,
    val fillHeight: Boolean = false,
    val cornerRadiusDp: Float = 24f,
    val contentPaddingDp: Float = 20f,
    val titleSizeSp: Float = 20f,
    val titleBottomPaddingDp: Float = 16f,
    val actionTopPaddingDp: Float = 12f,
    val dimAmount: Float = .32f,
    val scrollBody: Boolean = false,
    val surfaceColor: Int? = null,
    val outlineColor: Int? = null,
    val outlineWidthDp: Float = 1f,
    val noise: Boolean = false,
    val showHeading: Boolean = true,
    val placement: NativeDialogPlacement = NativeDialogPlacement.CENTER,
    val corners: NativeDialogCorners = NativeDialogCorners.ALL,
    val horizontalMarginDp: Float = 0f,
    val bodyMaxHeightDp: Float? = null,
    /** Construction-time policy. Fractional sizing preserves existing callers. */
    val widthPolicy: NativeDialogWidthPolicy = NativeDialogWidthPolicy.FRACTION,
    /** Content minimum for PLATFORM_WRAP_CONTENT, bounded by platform constraints. */
    val minWidthDp: Float = 0f,
    /** THEME leaves the construction theme's dim flag and amount untouched. */
    val dimPolicy: NativeDialogDimPolicy = NativeDialogDimPolicy.EXPLICIT,
    val softInputAdjust: NativeDialogSoftInputAdjust = NativeDialogSoftInputAdjust.RESIZE,
    /** Optional app-supplied positive pixel cap, retained until the style changes.
     * Current available height and maxHeightFraction may further reduce the measured panel. */
    val requestedHeightPx: Int? = null,
    /** Insets on the retained actions host only; null inherits contentPaddingDp. */
    val actionHorizontalPaddingDp: Float? = null,
    val actionBottomPaddingDp: Float? = null,
) {
    init {
        require(widthFraction.isFinite() && widthFraction > 0f && widthFraction <= 1f)
        require(maxHeightFraction.isFinite() && maxHeightFraction > 0f && maxHeightFraction <= 1f)
        require(requestedHeightPx == null || requestedHeightPx > 0) {
            "requestedHeightPx must be positive when supplied"
        }
        require(maxWidthDp == null || maxWidthDp.isFinite() && maxWidthDp > 0f)
        require(cornerRadiusDp.isFinite() && cornerRadiusDp >= 0f)
        require(contentPaddingDp.isFinite() && contentPaddingDp >= 0f)
        require(titleSizeSp.isFinite() && titleSizeSp > 0f)
        require(titleBottomPaddingDp.isFinite() && titleBottomPaddingDp >= 0f)
        require(actionTopPaddingDp.isFinite() && actionTopPaddingDp >= 0f)
        require(actionHorizontalPaddingDp == null || actionHorizontalPaddingDp.isFinite() && actionHorizontalPaddingDp >= 0f)
        require(actionBottomPaddingDp == null || actionBottomPaddingDp.isFinite() && actionBottomPaddingDp >= 0f)
        require(dimAmount.isFinite() && dimAmount in 0f..1f)
        require(outlineWidthDp.isFinite() && outlineWidthDp >= 0f)
        require(horizontalMarginDp.isFinite() && horizontalMarginDp >= 0f)
        require(bodyMaxHeightDp == null || bodyMaxHeightDp.isFinite() && bodyMaxHeightDp >= 0f)
        require(minWidthDp.isFinite() && minWidthDp >= 0f)
        require(maxWidthDp == null || minWidthDp <= maxWidthDp)
        require(widthPolicy != NativeDialogWidthPolicy.PLATFORM_WRAP_CONTENT || horizontalMarginDp == 0f) {
            "Platform wrap content uses platform constraints, not fractional margins"
        }
    }
}

/**
 * A retained modal component. The caller supplies its existing content/actions and owns their jobs.
 * Palette changes never recreate those Views. Standard cancel/dismiss listeners remain available.
 */
open class NativeDialog(
    context: Context,
    palette: ControlPalette,
    title: CharSequence,
    style: NativeDialogStyle = NativeDialogStyle(),
    /** Zero resolves the caller's android:dialogTheme. */
    themeResId: Int = android.R.style.Theme_DeviceDefault_Dialog_NoActionBar,
) : Dialog(context, themeResId) {
    private var currentPalette = palette
    private var currentStyle = style
    private val heading = NativeText(context, palette, NativeTextStyle(style.titleSizeSp, weight = 500))
    private val headerHost = FrameLayout(context).apply {
        addView(heading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val bodyHost = FrameLayout(context)
    private val actionsHost = FrameLayout(context).apply { visibility = View.GONE }
    private val bodyScroller = if (style.scrollBody) ScrollView(context).apply {
        isFillViewport = false
        isNestedScrollingEnabled = true
        addView(bodyHost, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    } else null
    private val panel = ModalPanel(context, headerHost, bodyScroller ?: bodyHost, actionsHost)
    private var presentationHost: ViewGroup = panel
    private var fullViewportPresentation = false
    private var generation = 0L
    private var presentationActive = false
    private var focusReturn: FocusReturn? = null
    private var requestedInitialFocus: WeakReference<View>? = null
    private var detachedGeneration: Long? = null
    private var registeredCallbacks = false
    private var cancelable = true
    private var cancelOnOutside = true
    private var appliedWidth = -1
    private var appliedHeight = -1
    private val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(configuration: Configuration) {
            applyComponentStyle()
            updateWindowGeometry()
        }
        override fun onLowMemory() = Unit
    }

    /** Stable host identity, useful for ownership checks and an app's existing semantic IDs. */
    val contentRoot: ViewGroup get() = panel
    val header: View? get() = headerHost.getChildAt(0)
    val body: View? get() = bodyHost.getChildAt(0)
    val actions: View? get() = actionsHost.getChildAt(0)
    /** The single retained scroll owner, when [NativeDialogStyle.scrollBody] was requested. */
    val bodyScrollView: ScrollView? get() = bodyScroller
    protected val modalStyle: NativeDialogStyle get() = currentStyle
    protected val modalPalette: ControlPalette get() = currentPalette
    protected val allowsUserCancellation: Boolean get() = cancelable
    protected val allowsOutsideCancellation: Boolean get() = cancelable && cancelOnOutside
    protected val hasActivePresentation: Boolean get() = presentationActive

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (style.widthPolicy == NativeDialogWidthPolicy.PLATFORM_WRAP_CONTENT) {
            presentationHost = ModalWrapContentHost(context, panel,
                placement = { currentStyle.placement },
                canCancel = { presentationActive && allowsOutsideCancellation },
                requestCancel = { cancel() },
            )
        }
        super.setContentView(presentationHost)
        super.setTitle(title)
        heading.text = title
        heading.isAccessibilityHeading = true
        panel.accessibilityPaneTitle = title
        if (Build.VERSION.SDK_INT >= 29) panel.isForceDarkAllowed = false
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { detachedGeneration = null }
            override fun onViewDetachedFromWindow(view: View) { detachedGeneration = generation }
        })
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isShowing) updateWindowGeometry()
        }
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (style.dimPolicy == NativeDialogDimPolicy.EXPLICIT) {
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        applyComponentStyle()
        setCanceledOnTouchOutside(true)
    }

    fun setHeading(title: CharSequence) {
        requireMainThread()
        if (heading.text != title) heading.text = title
        super.setTitle(title)
        panel.accessibilityPaneTitle = title
    }

    /** Called only by a subclass constructor, before any application body or actions exist. */
    protected fun installPresentationHost(factory: (ViewGroup) -> ViewGroup) {
        requireMainThread()
        check(!presentationActive && !panel.isAttachedToWindow && body == null && actions == null)
        check(presentationHost === panel)
        (panel.parent as? ViewGroup)?.removeView(panel)
        presentationHost = factory(panel)
        fullViewportPresentation = true
        super.setContentView(presentationHost)
        window?.setWindowAnimations(0)
        appliedWidth = -1
        appliedHeight = -1
    }

    protected fun installLeadingControl(view: View) {
        requireMainThread()
        check(!presentationActive && !panel.isAttachedToWindow)
        panel.installLeadingControl(view)
    }

    protected open fun onPresentationFailure() = Unit

    /** Replaces the default title with a retained header, for example a title and close action. */
    fun setHeader(view: View?, params: FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )) {
        requireMainThread()
        if (header === view) return
        require(view?.parent == null) { "Modal header already belongs to another parent" }
        headerHost.removeAllViews()
        if (view != null) headerHost.addView(view, params)
        headerHost.visibility = if (view == null || !currentStyle.showHeading) View.GONE else View.VISIBLE
        applyComponentPadding()
    }

    /** The body may own a RecyclerView or its own scroll container; scrolling is never imposed. */
    fun setBody(view: View, params: FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )) {
        requireMainThread()
        if (body === view) return
        require(view.parent == null) { "Modal body already belongs to another parent" }
        bodyHost.removeAllViews()
        bodyHost.addView(view, params)
    }

    fun setActions(view: View?, params: FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )) {
        requireMainThread()
        if (actions === view) return
        require(view?.parent == null) { "Modal actions already belong to another parent" }
        actionsHost.removeAllViews()
        if (view != null) actionsHost.addView(view, params)
        actionsHost.visibility = if (view == null) View.GONE else View.VISIBLE
        applyComponentPadding()
    }

    open fun applyPalette(palette: ControlPalette) {
        requireMainThread()
        if (currentPalette == palette) return
        currentPalette = palette
        applyComponentPaint()
    }

    open fun applyStyle(style: NativeDialogStyle) {
        requireMainThread()
        require(style.scrollBody == currentStyle.scrollBody) {
            "Choose the scroll owner at construction; changing it would reparent live input" }
        require(style.widthPolicy == currentStyle.widthPolicy) { "Choose the width policy at construction" }
        require(style.dimPolicy == currentStyle.dimPolicy) { "Choose the dim policy at construction" }
        if (currentStyle == style) return
        currentStyle = style
        applyComponentStyle()
        if (isShowing) updateWindowGeometry()
    }

    /** The opener is explicit because touch activation need not have moved keyboard focus to it. */
    fun showFrom(opener: View?, initialFocus: View? = null) {
        requireMainThread()
        check(!presentationActive) { "An active modal already owns its opener" }
        require(initialFocus == null || isInsidePanel(initialFocus)) {
            "Initial focus must belong to the modal content" }
        generation++
        focusReturn = opener?.takeIf { it.isAttachedToWindow }?.let { target ->
            target.windowToken?.let { token -> FocusReturn(
                WeakReference(target), WeakReference(target.rootView),
                target.rootView.findFocus()?.let(::WeakReference), token, generation,
            ) }
        }
        requestedInitialFocus = initialFocus?.let(::WeakReference)
        showWindow()
    }

    override fun show() {
        requireMainThread()
        if (!presentationActive) {
            generation++
            focusReturn = null
            requestedInitialFocus = null
        }
        showWindow()
    }

    private fun showWindow() {
        try {
            super.show()
        } catch (failure: Throwable) {
            // Dialog calls onStart before adding its window. Failed attachment has no onStop.
            presentationActive = false
            generation++
            focusReturn = null
            requestedInitialFocus = null
            if (registeredCallbacks) {
                registeredCallbacks = false
                try { context.unregisterComponentCallbacks(callbacks) }
                catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
            }
            try { onPresentationFailure() }
            catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
            throw failure
        }
    }

    override fun onStart() {
        super.onStart()
        presentationActive = true
        if (!registeredCallbacks) {
            context.registerComponentCallbacks(callbacks)
            registeredCallbacks = true
        }
        updateWindowGeometry()
        prepareInitialFocus()
    }

    private fun prepareInitialFocus() {
        val target = requestedInitialFocus?.get()
        requestedInitialFocus = null
        if (target == null || !target.isEnabled) return
        var ancestor: View? = target
        while (ancestor != null) {
            if (ancestor.visibility != View.VISIBLE) return
            if (ancestor === panel) {
                // Request before attachment: the explicit target wins initial traversal, while
                // a later app OnShowListener can still deliberately move focus elsewhere.
                target.requestFocus()
                return
            }
            ancestor = ancestor.parent as? View
        }
    }

    override fun onStop() {
        presentationActive = false
        if (registeredCallbacks) {
            context.unregisterComponentCallbacks(callbacks)
            registeredCallbacks = false
        }
        super.onStop()
        val restore = focusReturn
        focusReturn = null
        requestedInitialFocus = null
        // A backgrounded Activity is not a dismissal. Require this modal's actual detach.
        if (restore != null && detachedGeneration == restore.generation) {
            restore.opener.get()?.post {
                val opener = restore.opener.get() ?: return@post
                val root = restore.root.get() ?: return@post
                if (generation != restore.generation || isShowing || !opener.isAttachedToWindow ||
                    !opener.isShown || !opener.isEnabled || opener.rootView !== root ||
                    opener.windowToken != restore.windowToken || !root.hasWindowFocus()) return@post
                val focused = root.findFocus()
                if (focused == null || focused === opener || focused === restore.previousFocus?.get()) {
                    opener.requestFocus()
                }
            }
        }
    }

    override fun setCancelable(flag: Boolean) {
        cancelable = flag
        super.setCancelable(flag)
    }

    override fun setCanceledOnTouchOutside(cancel: Boolean) {
        cancelOnOutside = cancel
        if (cancel) cancelable = true
        super.setCanceledOnTouchOutside(cancel)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (cancelable) event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (cancelable && event.isTracking && !event.isCanceled) cancel()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isInsidePanel(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === panel) return true
            current = current.parent as? View
        }
        return false
    }

    private fun applyComponentStyle() {
        val style = currentStyle
        val typography = NativeTextStyle(style.titleSizeSp, weight = 500)
        heading.applyStyle(typography)
        heading.componentTypography(typography)
        applyComponentPadding()
        panel.fillHeight = style.fillHeight
        panel.bodyMaximumHeightPx = style.bodyMaxHeightDp?.let(::dp) ?: Int.MAX_VALUE
        panel.wrapContentWidth = style.widthPolicy == NativeDialogWidthPolicy.PLATFORM_WRAP_CONTENT
        panel.minimumWidthPx = dp(style.minWidthDp)
        panel.maximumWidthPx = style.maxWidthDp?.let(::dp) ?: Int.MAX_VALUE
        applySoftInputPolicy()
        headerHost.visibility = if (style.showHeading && header != null) View.VISIBLE else View.GONE
        applyComponentPaint()
        panel.requestLayout()
    }

    private fun applyComponentPadding() {
        val padding = dp(currentStyle.contentPaddingDp)
        headerHost.setPaddingRelative(padding, padding, padding, dp(currentStyle.titleBottomPaddingDp))
        val bodyTop = if (header == null || !currentStyle.showHeading) padding else 0
        bodyHost.setPaddingRelative(padding, bodyTop, padding, if (actions == null) padding else 0)
        val actionHorizontal = currentStyle.actionHorizontalPaddingDp?.let { dp(it) } ?: padding
        val actionBottom = currentStyle.actionBottomPaddingDp?.let { dp(it) } ?: padding
        actionsHost.setPaddingRelative(actionHorizontal, dp(currentStyle.actionTopPaddingDp), actionHorizontal, actionBottom)
    }

    private fun applyComponentPaint() {
        heading.applyPalette(currentPalette)
        panel.setPaint(currentPalette, currentStyle)
        if (currentStyle.dimPolicy == NativeDialogDimPolicy.EXPLICIT) {
            window?.let { if (it.attributes.dimAmount != currentStyle.dimAmount) it.setDimAmount(currentStyle.dimAmount) }
        }
    }

    private fun applySoftInputPolicy() {
        val window = window ?: return
        val adjustment = when (currentStyle.softInputAdjust) {
            NativeDialogSoftInputAdjust.RESIZE -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            NativeDialogSoftInputAdjust.UNSPECIFIED -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED
        }
        val mode = (window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or adjustment
        if (mode != window.attributes.softInputMode) window.setSoftInputMode(mode)
    }

    private fun updateWindowGeometry() {
        val window = window ?: return
        val manager = context.getSystemService(WindowManager::class.java)
        val available = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            Pair((metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1),
                (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1))
        } else {
            val metrics = context.resources.displayMetrics
            val ownerDecor = findOwnerActivity()?.window?.decorView
            Pair((ownerDecor?.width?.takeIf { it > 0 } ?: metrics.widthPixels).coerceAtLeast(1),
                (ownerDecor?.height?.takeIf { it > 0 } ?: metrics.heightPixels).coerceAtLeast(1))
        }
        val horizontalMargin = currentStyle.horizontalMarginDp * context.resources.displayMetrics.density
        val requestedWidth = ((available.first - 2f * horizontalMargin).coerceAtLeast(1f) *
            currentStyle.widthFraction).roundToInt().coerceAtLeast(1)
        val width = currentStyle.maxWidthDp?.let { min(requestedWidth, dp(it).coerceAtLeast(1)) } ?: requestedWidth
        val maximumHeight = (available.second * currentStyle.maxHeightFraction).roundToInt().coerceAtLeast(1)
        val height = currentStyle.requestedHeightPx?.let { min(maximumHeight, it) } ?: maximumHeight
        if (appliedWidth != width || appliedHeight != height) {
            appliedWidth = width
            appliedHeight = height
            panel.preferredWidthPx = width
            panel.maximumHeightPx = height
            panel.requestLayout()
            val windowWidth = when {
                fullViewportPresentation -> ViewGroup.LayoutParams.MATCH_PARENT
                currentStyle.widthPolicy == NativeDialogWidthPolicy.PLATFORM_WRAP_CONTENT -> ViewGroup.LayoutParams.WRAP_CONTENT
                else -> width
            }
            window.setLayout(windowWidth,
                if (fullViewportPresentation) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val gravity = when (currentStyle.placement) {
            NativeDialogPlacement.CENTER -> Gravity.CENTER
            NativeDialogPlacement.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            NativeDialogPlacement.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        if (window.attributes.gravity != gravity) window.setGravity(gravity)
    }

    private fun findOwnerActivity(): Activity? {
        ownerActivity?.let { return it }
        var candidate: Context = context
        while (candidate is ContextWrapper) {
            if (candidate is Activity) return candidate
            val base = candidate.baseContext
            if (base === candidate) break
            candidate = base
        }
        return candidate as? Activity
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()
    private fun requireMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }

    private data class FocusReturn(
        val opener: WeakReference<View>,
        val root: WeakReference<View>,
        val previousFocus: WeakReference<View>?,
        val windowToken: IBinder,
        val generation: Long,
    )
}

/** Header and actions are measured before the body so scrolling/input cannot push actions away. */
private class ModalPanel(
    context: Context,
    private val heading: View,
    private val body: View,
    private val actions: View,
) : ViewGroup(context) {
    var maximumHeightPx = Int.MAX_VALUE
    var preferredWidthPx = 0
    var wrapContentWidth = false
    var minimumWidthPx = 0
    var maximumWidthPx = Int.MAX_VALUE
    var fillHeight = false
    var bodyMaximumHeightPx = Int.MAX_VALUE
    private val clip = Path()
    private val bounds = RectF()
    private val corners = FloatArray(8)
    private val parts = mutableListOf(heading, body, actions)
    private var leading: View? = null
    private val solidFill = ColorDrawable()
    private var noisyFill: NoiseDrawable? = null
    private var border: NativeSurfaceBorderDrawable? = null
    private var requestedRadius = 0f
    private var cornerRole = NativeDialogCorners.ALL
    private var outlineColor: Int? = null
    private var outlineWidth = 0f
    private var colorMotion:smallthingz.ui.animation.view.ViewColorTransition?=null

    private fun publishColors() {
        val colors=colorMotion ?: return
        solidFill.color=colors.color(0)
        noisyFill?.setBackgroundColor(colors.color(0))
        border?.setColor(colors.color(1))
        invalidate()
    }

    init {
        addView(heading)
        addView(body)
        addView(actions)
        isFocusableInTouchMode = false
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    fun installLeadingControl(view: View) {
        check(leading == null && view.parent == null)
        leading = view
        parts.add(0, view)
        addView(view, 0)
    }

    fun setPaint(palette: ControlPalette, style: NativeDialogStyle) {
        val density = resources.displayMetrics.density
        requestedRadius = style.cornerRadiusDp * density
        cornerRole = style.corners
        outlineColor = style.outlineColor
        outlineWidth = style.outlineWidthDp * density
        val fill = style.surfaceColor ?: palette.surface
        val colors=colorMotion ?: smallthingz.ui.animation.view.ViewColorTransition(this,
            intArrayOf(fill,outlineColor ?: Color.TRANSPARENT),::publishColors).also {colorMotion=it}
        colors.retarget(fill,outlineColor ?: Color.TRANSPARENT)
        val fillDrawable = if (style.noise) {
            (noisyFill ?: NoiseDrawable(backgroundColor = colors.color(0)).also { noisyFill = it })
        } else solidFill
        if (background !== fillDrawable) background = fillDrawable
        val outline = outlineColor?.let {
            border ?: NativeSurfaceBorderDrawable().also { border = it }
        }
        if (foreground !== outline) foreground = outline
        updateClip()
        publishColors()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximumHeightPx
            else min(maximumHeightPx, MeasureSpec.getSize(heightMeasureSpec))
        var childState = 0
        val width = if (!wrapContentWidth) resolveSize(preferredWidthPx, widthMeasureSpec) else {
            val incomingMode = MeasureSpec.getMode(widthMeasureSpec)
            val incoming = if (incomingMode == MeasureSpec.UNSPECIFIED) maximumWidthPx
                else min(maximumWidthPx, MeasureSpec.getSize(widthMeasureSpec))
            val bound = incoming.coerceIn(0, 0x3fffffff)
            val probeWidth = MeasureSpec.makeMeasureSpec(bound, MeasureSpec.AT_MOST)
            val probeHeight = MeasureSpec.makeMeasureSpec(availableHeight.coerceIn(0, 0x3fffffff), MeasureSpec.AT_MOST)
            var desired = minimumWidthPx
            for (part in parts) {
                if (part.visibility == View.GONE) continue
                part.measure(probeWidth, probeHeight)
                desired = maxOf(desired, part.measuredWidth)
                childState = combineMeasuredStates(childState, part.measuredState)
            }
            if (desired > bound) childState = childState or MEASURED_STATE_TOO_SMALL
            resolveSize(desired.coerceAtMost(bound), widthMeasureSpec)
        }
        // Wrapping can change at the selected width; recompute all retained parts and their budgets.
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val actionsHeight = measurePart(actions, widthSpec, availableHeight)
        val leadingHeight = leading?.let { measurePart(it, widthSpec,
            (availableHeight - actionsHeight).coerceAtLeast(0)) } ?: 0
        val headingHeight = measurePart(heading, widthSpec,
            (availableHeight - actionsHeight - leadingHeight).coerceAtLeast(0))
        val bodyHeight = min(bodyMaximumHeightPx,
            (availableHeight - headingHeight - actionsHeight - leadingHeight).coerceAtLeast(0))
        val fill = fillHeight || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        body.measure(widthSpec, MeasureSpec.makeMeasureSpec(bodyHeight, if (fill) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
        val desiredHeight = leadingHeight + headingHeight + body.measuredHeight + actionsHeight
        for (part in parts) if (part.visibility != View.GONE) {
            childState = combineMeasuredStates(childState, part.measuredState)
        }
        setMeasuredDimension(resolveSizeAndState(width, widthMeasureSpec, childState),
            resolveSizeAndState(min(availableHeight, desiredHeight), heightMeasureSpec,
                childState shl MEASURED_HEIGHT_STATE_SHIFT))
    }

    private fun measurePart(view: View, widthSpec: Int, available: Int): Int {
        if (view.visibility == View.GONE) return 0
        view.measure(widthSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
        return view.measuredHeight
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var y = 0
        for (view in parts) {
            if (view.visibility == View.GONE) continue
            view.layout(0, y, width, y + view.measuredHeight)
            y += view.measuredHeight
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateClip()
    }

    private fun updateClip() {
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        // Match the shared surface's vertical-pair normalization. Native Path then performs
        // final horizontal fitting; the border receives the exact same outer corner geometry.
        val limit = min(width, height).coerceAtLeast(0).toFloat()
        val radius = min(requestedRadius, if (cornerRole == NativeDialogCorners.ALL) limit / 2f else limit)
        corners.fill(radius)
        when (cornerRole) {
            NativeDialogCorners.ALL -> Unit
            NativeDialogCorners.TOP -> for (index in 4..7) corners[index] = 0f
            NativeDialogCorners.BOTTOM -> for (index in 0..3) corners[index] = 0f
        }
        clip.reset()
        clip.addRoundRect(bounds, corners, Path.Direction.CW)
        border?.applyGeometry(colorMotion?.color(1) ?: outlineColor, outlineWidth, corners)
    }

    override fun draw(canvas: Canvas) {
        val checkpoint = canvas.save()
        try {
            canvas.clipPath(clip)
            super.draw(canvas)
        } finally { canvas.restoreToCount(checkpoint) }
    }
}

/** An exact platform root does not stretch the bounded visual panel. */
private class ModalWrapContentHost(
    context: Context,
    private val panel: ViewGroup,
    private val placement: () -> NativeDialogPlacement,
    private val canCancel: () -> Boolean,
    private val requestCancel: () -> Unit,
) : ViewGroup(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var outside = false
    private var outsideTap = false
    private var downX = 0f
    private var downY = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun childSpec(spec: Int): Int = if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) spec
            else MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(spec), MeasureSpec.AT_MOST)
        panel.measure(childSpec(widthMeasureSpec), childSpec(heightMeasureSpec))
        setMeasuredDimension(resolveSizeAndState(panel.measuredWidth, widthMeasureSpec, panel.measuredState),
            resolveSizeAndState(panel.measuredHeight, heightMeasureSpec,
                panel.measuredState shl MEASURED_HEIGHT_STATE_SHIFT))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = (width - panel.measuredWidth) / 2
        val y = when (placement()) {
            NativeDialogPlacement.TOP -> 0
            NativeDialogPlacement.CENTER -> (height - panel.measuredHeight) / 2
            NativeDialogPlacement.BOTTOM -> height - panel.measuredHeight
        }
        panel.layout(x, y, x + panel.measuredWidth, y + panel.measuredHeight)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        fun inside(): Boolean = event.x >= panel.left && event.x < panel.right &&
            event.y >= panel.top && event.y < panel.bottom
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            outside = !inside(); outsideTap = outside
            downX = event.x; downY = event.y
        }
        if (!outside) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (inside() || kotlin.math.abs(event.x - downX) > slop ||
                kotlin.math.abs(event.y - downY) > slop) outsideTap = false
            MotionEvent.ACTION_POINTER_DOWN -> outsideTap = false
            MotionEvent.ACTION_UP -> {
                val close = outsideTap && !inside() && kotlin.math.abs(event.x - downX) <= slop &&
                    kotlin.math.abs(event.y - downY) <= slop && canCancel()
                outside = false; outsideTap = false
                if (close) requestCancel()
            }
            MotionEvent.ACTION_CANCEL -> { outside = false; outsideTap = false }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        outside = false; outsideTap = false
        super.onDetachedFromWindow()
    }
}
