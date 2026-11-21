package smallthingz.ui.components

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.doOnLayout
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.font.FontStyle
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
) {
    init {
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
    private val recipe: NativePermissionDialogRecipe = NativePermissionDialogRecipe(),
) {
    private val host = activity.window.decorView
    private val density = Density(activity)
    private val fonts = createFontFamilyResolver(activity)
    private var busy = false
    private var allowEnabled = true
    private var ended = false
    private var shown = false
    private var escapeDown = false
    private var blockedGesture = false
    private val error = text(activity, "", NativeTypographyRole.BODY_MEDIUM, colors.error)
    private val progress = text(activity, "", NativeTypographyRole.BODY_SMALL, colors.muted)
    private val allow: NativeMaterialButtonView = action(activity, appearance.buttons.tonal) {
        if (!busy && allowEnabled) { setBusy(true); onAllow() }
    }
    private val deny: NativeMaterialButtonView = action(activity, appearance.buttons.tonal) {
        if (!busy) { setBusy(true); onDeny() }
    }
    private val dialog: Dialog = object : Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar) {
        override fun cancel() {
            if (ended || busy) return
            dismiss()
            onCancel()
        }

        override fun dismiss() {
            if (ended) return
            ended = true
            escapeDown = false
            host.removeOnLayoutChangeListener(resize)
            super.dismiss()
            allow.close()
            deny.close()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            // Let an editor consume its own keys before considering dialog dismissal.
            if (super.dispatchKeyEvent(event)) {
                if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) escapeDown = false
                return true
            }
            if (event.keyCode != KeyEvent.KEYCODE_ESCAPE) return false
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) escapeDown = !busy && !ended && event.hasNoModifiers()
            } else if (event.action == KeyEvent.ACTION_UP) {
                val accepted = escapeDown && !busy && !ended && !event.isCanceled && event.hasNoModifiers()
                escapeDown = false
                if (accepted) cancel()
            }
            return true
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) blockedGesture = false
            val obscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
                (Build.VERSION.SDK_INT >= 29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0)
            if (obscured && !blockedGesture) {
                blockedGesture = true
                val cancel = MotionEvent.obtain(event)
                try {
                    cancel.action = MotionEvent.ACTION_CANCEL
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
            addView(actionHost(activity, allow, first = true), row(recipe.scopeGapDp))
            addView(actionHost(activity, deny, first = false), row(recipe.actionGapDp))
        }
        val footer = ScrollView(activity).apply { addView(actions) }
        // Measure the actions first; only the content relinquishes height when the window shrinks.
        val panel = object : ViewGroup(activity) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                val inset = minOf(dp(recipe.contentInsetDp), width / 8)
                val innerWidth = (width - inset * 2).coerceAtLeast(1)
                val visible = Rect().also(host::getWindowVisibleDisplayFrame)
                val limit = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED)
                    visible.height().coerceAtLeast(1) else MeasureSpec.getSize(h)
                actions.measure(exact(innerWidth), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val innerHeight = (limit - inset * 2).coerceAtLeast(0)
                val footerLimit = if (actions.measuredHeight > innerHeight * 2 / 3)
                    innerHeight * 2 / 3 else innerHeight
                footer.measure(exact(innerWidth), MeasureSpec.makeMeasureSpec(footerLimit, MeasureSpec.AT_MOST))
                val remaining = (innerHeight - footer.measuredHeight).coerceAtLeast(0)
                scroll.measure(exact(innerWidth), MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.AT_MOST))
                setMeasuredDimension(width, minOf(limit, scroll.measuredHeight + footer.measuredHeight + inset * 2))
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val inset = minOf(dp(recipe.contentInsetDp), width / 8)
                scroll.layout(inset, inset, width - inset, inset + scroll.measuredHeight)
                footer.layout(inset, scroll.bottom, width - inset, scroll.bottom + footer.measuredHeight)
            }
        }.apply {
            addView(scroll)
            addView(footer)
            if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        }
        val surface = NativeSurface(activity, NativeSurfaceStyle(colors.surfaceContainerHigh,
            cornerRadiusDp = recipe.cornerRadiusDp, noise = true)).apply {
            addView(panel, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        dialog.setTitle(summary)
        dialog.setContentView(surface)
        // A stray outside tap should not discard a permission request during multi-window transitions.
        dialog.setCanceledOnTouchOutside(false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setGravity(Gravity.CENTER)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        window.addFlags(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        bindActions()
    }

    private fun text(activity: Activity, value: CharSequence, role: NativeTypographyRole, color: Int,
        centered: Boolean = false): NativeText {
        val type = appearance.modals.typography[role]
        val size = if (type.fontSize.isSp) type.fontSize.value else role.style.fontSizeSp
        val style = NativeTextStyle(sizeSp = size,
            lineHeightSp = when {
                type.lineHeight.isSp -> type.lineHeight.value
                type.lineHeight.isEm -> type.lineHeight.value * size
                else -> role.style.lineHeightSp
            },
            weight = type.fontWeight?.weight ?: role.style.fontWeight,
            letterSpacing = if (type.letterSpacing.isEm) type.letterSpacing.value else 0f,
            letterSpacingSp = if (type.letterSpacing.isSp) type.letterSpacing.value else null,
            includeFontPadding = type.platformStyle?.includeFontPadding ?: false,
            fontFamily = type.fontFamily?.familyName ?: "sans-serif",
            lineHeightStyle = type.lineHeightStyle,
            italic = type.fontStyle == FontStyle.Italic)
        return NativeText(activity, colors.pageControls.copy(content = color), style).apply {
            text = value
            gravity = if (centered) Gravity.CENTER else Gravity.START
        }
    }

    private fun action(activity: Activity, source: NativeButtonRecipe, click: () -> Unit): NativeMaterialButtonView =
        NativeMaterialButtonView(activity, source.copy(surface = source.surface.copy(
            assignedSize = true, minimumHeightDp = recipe.actionMinimumHeightDp, radiusDp = 0f)),
            density, fonts, onClick = { if (!ended && dialog.isShowing) click() })

    private fun actionHost(activity: Activity, button: NativeMaterialButtonView, first: Boolean): View =
        object : NativeSurface(activity, NativeSurfaceStyle(Color.TRANSPARENT,
            cornerRadiusDp = recipe.actionRadiusDp, cornerRadii = NativeCornerRadii(
                topStartDp = if (first) recipe.actionRadiusDp else recipe.actionSeamRadiusDp,
                topEndDp = if (first) recipe.actionRadiusDp else recipe.actionSeamRadiusDp,
                bottomStartDp = if (first) recipe.actionSeamRadiusDp else recipe.actionRadiusDp,
                bottomEndDp = if (first) recipe.actionSeamRadiusDp else recipe.actionRadiusDp))) {
            init { addView(button) }
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                val height = maxOf(dp(button.minimumTouchTargetDp), button.intrinsicHeight(width))
                button.measure(exact(width), exact(height))
                setMeasuredDimension(width, height)
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

    fun show() {
        check(!ended) { "Create a new permission dialog for each presentation" }
        if (shown) return
        shown = true
        dialog.show()
        sizeWindow()
        host.addOnLayoutChangeListener(resize)
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
        val palette = colors.buttonPalette.copy(tonalSurface = colors.primaryContainer, onTonal = colors.onPrimaryContainer)
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
