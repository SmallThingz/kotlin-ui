package smallthingz.ui.components

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.doOnLayout
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

/** A contained permission recipe; app identity, request text and authorization remain caller-owned. */
data class NativePermissionDialogRecipe(
    val maxWidthDp: Int = 360,
    val windowInsetDp: Int = 24,
    val contentInsetDp: Int = 24,
    val cornerRadiusDp: Float = 28f,
    val iconSizeDp: Int = 40,
    val identityGapDp: Int = 12,
    val requestGapDp: Int = 20,
    val scopeGapDp: Int = 16,
    val actionGapDp: Int = 4,
    val actionRadiusDp: Float = 12f,
    val actionSeamRadiusDp: Float = 4f,
    val actionMinimumHeightDp: Int = 56,
    val noiseSeed: Int? = NoiseDrawable.SEED_BACKGROUND,
    val windowTheme: Int = android.R.style.Theme_Material_Dialog_NoActionBar,
    val dimAmount: Float? = null,
    val windowAnimations: Int? = null,
) {
    fun placeAtBottom(window: Window) = placeAtEdge(window, NativeDialogEdge.BOTTOM)

    internal fun placeAtEdge(window: Window, edge: NativeDialogEdge) {
        val gravity = if (edge == NativeDialogEdge.TOP) Gravity.TOP else Gravity.BOTTOM
        window.setGravity(gravity or Gravity.CENTER_HORIZONTAL)
        window.attributes = window.attributes.apply {
            y = (windowInsetDp * window.context.resources.displayMetrics.density).roundToInt()
        }
    }

    init {
        require(dimAmount == null || dimAmount in 0f..1f)
        require(maxWidthDp > 0 && windowInsetDp >= 0 && contentInsetDp >= 0)
        require(iconSizeDp > 0 && identityGapDp >= 0 && requestGapDp >= 0 && scopeGapDp >= 0 && actionGapDp >= 0)
        require(cornerRadiusDp.isFinite() && cornerRadiusDp >= 0 && actionRadiusDp.isFinite() && actionRadiusDp >= 0)
        require(actionSeamRadiusDp.isFinite() && actionSeamRadiusDp >= 0 && actionMinimumHeightDp >= 48)
    }
}

/**
 * Single-presentation permission window. Programmatic dismissal is silent; accepted cancellation is
 * delivered synchronously once, so a queued callback cannot consume a later request. Main thread only.
 */
class NativePermissionDialog(
    activity: Activity,
    private val colors: NativeColorScheme,
    icon: Drawable?,
    appName: CharSequence,
    summary: CharSequence,
    private val denyLabel: CharSequence,
    private val allowLabel: CharSequence,
    scopeBody: View? = null,
    private val onDeny: () -> Unit,
    private val onAllow: () -> Unit,
    private val onCancel: () -> Unit,
    private val appearance: NativeUiAppearance = NativeUiAppearance.Default,
    private val recipe: NativePermissionDialogRecipe = appearance.modals.permissionDialog,
    showDragHandle: Boolean = false,
) {
    private val hostWindow = activity.window
    private val host = hostWindow.decorView
    private val density = Density(activity)
    private val fonts = createFontFamilyResolver(activity)
    private val handleRuntime = if (showDragHandle) NativeModalWindowRuntime(activity) else null
    private var busy = false
    private var allowEnabled = true
    private var ended = false
    private var shown = false
    private var inputFocused = false
    private var escapeDown = false
    private var blockedGesture = false
    private val error = text(activity, "", NativeTypographyRole.BODY_MEDIUM, colors.error)
    private val progress = text(activity, "", NativeTypographyRole.BODY_SMALL, colors.muted)
    private val allow: NativeMaterialButtonView = action(activity, appearance.buttons.filled.forDialogAction()) {
        if (!busy && allowEnabled) { setBusy(true); onAllow() }
    }.apply {
        setLeadingIcon(smallthingz.ui.icons.MaterialRoundedIconDrawable(
            smallthingz.ui.icons.MaterialRoundedIcon.Check, density.density, colors.ink))
    }
    private val deny: NativeMaterialButtonView = action(activity, appearance.buttons.text.forDialogAction()) {
        if (!busy) { setBusy(true); onDeny() }
    }.apply {
        setLeadingIcon(smallthingz.ui.icons.MaterialRoundedIconDrawable(
            smallthingz.ui.icons.MaterialRoundedIcon.Close, density.density, colors.ink))
    }
    private val dialog: Dialog = object : Dialog(activity, recipe.windowTheme) {
        override fun cancel() {
            if (busy || !ownsInputFocus()) return
            dismiss()
            onCancel()
        }

        override fun dismiss() {
            if (ended) return
            ended = true
            inputFocused = false
            escapeDown = false
            handleRuntime.use { allow.use { deny.use { super.dismiss() } } }
        }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            inputFocused = false
            // A View can keep local focus while another window owns input. Neither its pending
            // activation nor Escape/Back tracking may survive that window ownership transition.
            cancelPendingInput()
            super.onWindowFocusChanged(hasFocus)
            inputFocused = hasFocus && !ended
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (!ownsInputFocus()) {
                cancelPendingInput()
                return true
            }
            val escape = event.keyCode == KeyEvent.KEYCODE_ESCAPE
            if (escape && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) escapeDown = false
            return try {
                super.dispatchKeyEvent(event)
            } finally {
                // A focused child may consume the UP after our fallback accepted its DOWN.
                if (escape && event.action == KeyEvent.ACTION_UP) escapeDown = false
            }
        }

        // Dialog dispatches to the focused child before these fallback handlers.
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode != KeyEvent.KEYCODE_ESCAPE) return super.onKeyDown(keyCode, event)
            if (event.repeatCount == 0) {
                escapeDown = !busy && ownsInputFocus() && event.hasNoModifiers()
                if (escapeDown) event.startTracking()
            }
            return true
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode != KeyEvent.KEYCODE_ESCAPE) return super.onKeyUp(keyCode, event)
            val accepted = escapeDown && event.isTracking && !event.isCanceled && event.hasNoModifiers() && !busy && ownsInputFocus()
            escapeDown = false
            if (accepted) cancel()
            return true
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (!ownsInputFocus()) {
                cancelPendingInput()
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) blockedGesture = false
            val obscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
                (Build.VERSION.SDK_INT >= 29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0)
            if (obscured && !blockedGesture) {
                blockedGesture = true
                // A child filtering obscured touches still needs to receive its terminal cancel.
                val cancel = MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_CANCEL,
                    event.pointerCount,
                    Array(event.pointerCount) { i -> MotionEvent.PointerProperties().also { event.getPointerProperties(i, it) } },
                    Array(event.pointerCount) { i -> MotionEvent.PointerCoords().also { event.getPointerCoords(i, it) } },
                    event.metaState, event.buttonState, event.xPrecision, event.yPrecision, event.deviceId,
                    event.edgeFlags, event.source, 0)
                try {
                    super.dispatchTouchEvent(cancel)
                } finally { cancel.recycle() }
                allow.cancelInput()
                deny.cancelInput()
            }
            return if (blockedGesture) true else super.dispatchTouchEvent(event)
        }
    }
    private val window = requireNotNull(dialog.window)
    private val resize = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) sizeWindow()
    }

    private fun ownsInputFocus(): Boolean = !ended && shown && inputFocused && dialog.isShowing &&
        window.decorView.let { it.isAttachedToWindow && it.isShown && it.hasWindowFocus() }

    private fun cancelPendingInput() {
        escapeDown = false
        // Only a new focused DOWN can admit another pointer gesture.
        blockedGesture = true
        allow.cancelInput()
        deny.cancelInput()
        window.peekDecorView()?.let { decor ->
            decor.cancelPendingInputEvents()
            decor.keyDispatcherState?.reset()
        }
    }

    init {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val identity = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (icon != null) addView(ImageView(activity).apply {
                    setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(recipe.iconSizeDp), dp(recipe.iconSizeDp)))
                addView(text(activity, appName, NativeTypographyRole.TITLE_MEDIUM, colors.ink).apply {
                    isAccessibilityHeading = true
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (icon != null) marginStart = dp(recipe.identityGapDp)
                })
            }
            addView(identity, row())
            addView(text(activity, summary, NativeTypographyRole.TITLE_LARGE, colors.ink, centered = true),
                row(recipe.requestGapDp))
            if (scopeBody != null) addView(scopeBody, row(recipe.scopeGapDp))
            error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            error.visibility = View.GONE
            addView(error, row(recipe.scopeGapDp))
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(body)
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            progress.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            progress.visibility = View.GONE
            addView(progress, row())
            addView(NativeMergedActionLayout(activity, listOf(allow, deny), colors).asActionFooter(),
                row(recipe.scopeGapDp))
        }
        val footer = ScrollView(activity).apply { addView(actions) }
        val handle = handleRuntime?.let {
            createNativeDialogDragHandle(activity, colors, appearance, it, dialog::cancel)
        }
        // Measure the actions first; only the content relinquishes height when the window shrinks.
        val panel = object : ViewGroup(activity) {
            private var topInset = 0
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                val inset = minOf(dp(recipe.contentInsetDp), width / 8)
                val innerWidth = (width - inset * 2).coerceAtLeast(1)
                val visible = Rect().also(host::getWindowVisibleDisplayFrame)
                val limit = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED)
                    visible.height().coerceAtLeast(1) else MeasureSpec.getSize(h)
                topInset = minOf(inset, limit / 8)
                handle?.measure(
                    MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(limit / 2, MeasureSpec.AT_MOST),
                )
                val handleHeight = handle?.measuredHeight ?: 0
                val innerHeight = limit - topInset - handleHeight
                val minimumBody = minOf(dp(recipe.contentInsetDp), innerHeight / 3)
                val footerLimit = innerHeight - minimumBody
                footer.measure(exact(innerWidth), MeasureSpec.makeMeasureSpec(footerLimit, MeasureSpec.AT_MOST))
                val remaining = (innerHeight - footer.measuredHeight).coerceAtLeast(0)
                scroll.measure(exact(innerWidth), MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.AT_MOST))
                setMeasuredDimension(width, minOf(limit, scroll.measuredHeight + footer.measuredHeight + topInset + handleHeight))
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val inset = minOf(dp(recipe.contentInsetDp), width / 8)
                val bodyTop = topInset + (handle?.measuredHeight ?: 0)
                handle?.let {
                    val left = (width - it.measuredWidth) / 2
                    it.layout(left, topInset, left + it.measuredWidth, bodyTop)
                }
                scroll.layout(inset, bodyTop, width - inset, bodyTop + scroll.measuredHeight)
                footer.layout(inset, scroll.bottom, width - inset, scroll.bottom + footer.measuredHeight)
            }
        }.apply {
            handle?.let { addView(it) }
            addView(scroll)
            addView(footer)
            if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        }
        val surface = NativeSurface(activity, NativeSurfaceStyle(colors.surfaceContainerHigh,
            cornerRadiusDp = recipe.cornerRadiusDp, noise = recipe.noiseSeed != null,
            noiseSeed = recipe.noiseSeed ?: NoiseDrawable.SEED_BACKGROUND)).apply {
            setNoiseIntensity(colors.surfacePreset?.foregroundGrain ?: 1f)
            addView(panel, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        surface.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                host.addOnLayoutChangeListener(resize)
                handleRuntime?.setStarted(true)
            }

            override fun onViewDetachedFromWindow(view: View) {
                host.removeOnLayoutChangeListener(resize)
                handleRuntime?.setStarted(false)
            }
        })
        dialog.setTitle(summary)
        dialog.setContentView(surface)
        // A stray outside tap should not discard a permission request during multi-window transitions.
        dialog.setCanceledOnTouchOutside(false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setGravity(Gravity.CENTER)
        recipe.windowAnimations?.let(window::setWindowAnimations)
        recipe.dimAmount?.let { amount ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(amount)
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        bindActions()
    }

    private fun text(activity: Activity, value: CharSequence, role: NativeTypographyRole, color: Int,
        centered: Boolean = false): NativeText {
        val style = appearance.modals.typography.nativeTextStyle(role)
        return NativeText(activity, colors.pageControls.copy(content = color), style).apply {
            text = value
            gravity = if (centered) Gravity.CENTER else Gravity.START
        }
    }

    private fun action(activity: Activity, source: NativeButtonRecipe, click: () -> Unit): NativeMaterialButtonView =
        object : NativeMaterialButtonView(activity, source.copy(surface = source.surface.copy(
            assignedSize = false, minimumHeightDp = recipe.actionMinimumHeightDp, radiusDp = 0f)),
            density, fonts, onClick = { if (ownsInputFocus()) click() }) {
            override fun performClick(): Boolean = ownsInputFocus() && super.performClick()

            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_CLICK && !ownsInputFocus()) return false
                return super.performAccessibilityAction(action, arguments)
            }
        }

    private fun row(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * density.density).roundToInt()
    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)

    private fun sizeWindow() {
        val frame = Rect().also(host::getWindowVisibleDisplayFrame)
        val available = minOf(host.width.takeIf { it > 0 } ?: frame.width(), frame.width().takeIf { it > 0 } ?: Int.MAX_VALUE)
        window.setLayout((available - dp(recipe.windowInsetDp * 2)).coerceIn(1, dp(recipe.maxWidthDp)), WRAP_CONTENT)
    }

    fun showAtBottom() {
        if (!shown) recipe.placeAtBottom(window)
        show()
    }

    /** Edge placement is opt-in so callers keep their existing consent flow by default. */
    fun showAtEdge(edge: NativeDialogEdge) {
        if (!shown) {
            recipe.placeAtEdge(window, edge)
            if (recipe.windowAnimations == null) {
                window.setWindowAnimations(if (edge == NativeDialogEdge.TOP)
                    smallthingz.ui.R.style.NativePermissionEnterFromTop else smallthingz.ui.R.style.NativePermissionEnterFromBottom)
            }
        }
        show()
    }

    fun show() {
        check(!ended) { "Create a new permission dialog for each presentation" }
        if (shown) return
        shown = true
        try {
            val secure = WindowManager.LayoutParams.FLAG_SECURE
            window.setFlags(hostWindow.attributes.flags and secure, secure)
            dialog.show()
            sizeWindow()
        } catch (failure: Throwable) {
            try { dismiss() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun dismiss() = dialog.dismiss()

    /** Busy disables both actions and Back; the supplied scope view stays caller-owned. */
    fun setBusy(value: Boolean) {
        busy = value
        escapeDown = false
        dialog.setCancelable(!value)
        dialog.setCanceledOnTouchOutside(false)
        bindActions()
    }

    private fun bindActions() {
        // Permission actions use the primary pair; keep the normal dialog surface unchanged.
        val palette = colors.buttonPalette.copy(tonalSurface = colors.primary, onTonal = colors.onPrimary)
        allow.bind(allowLabel.toString(), allowEnabled && !busy, palette)
        deny.bind(denyLabel.toString(), !busy, palette)
    }

    /** Retains Allow availability across busy transitions. */
    fun setAllowEnabled(enabled: Boolean) {
        allowEnabled = enabled
        bindActions()
    }

    /** Optional localized progress supplied by the consumer, without replacing either action label. */
    fun setProgress(message: CharSequence?) {
        progress.setText(message?.toString().orEmpty())
        progress.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun setError(message: CharSequence?) {
        error.setText(message?.toString().orEmpty())
        error.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (error.visibility == View.VISIBLE) error.doOnLayout {
            if (error.visibility == View.VISIBLE) error.requestRectangleOnScreen(Rect(0, 0, error.width, error.height))
        }
    }
}
