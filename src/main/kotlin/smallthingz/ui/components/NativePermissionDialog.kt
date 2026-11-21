package smallthingz.ui.components

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.doOnLayout
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.ControlStyle

/** Main-thread permission prompt. The caller owns decisions, the scope model and the Activity lifetime. */
class NativePermissionDialog(
    activity: Activity,
    palette: ControlPalette,
    icon: Drawable?,
    appName: CharSequence,
    summary: CharSequence,
    denyLabel: CharSequence,
    allowLabel: CharSequence,
    scopeBody: View? = null,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
    onCancel: () -> Unit,
) {
    private val dialog = Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar)
    private val window = requireNotNull(dialog.window)
    private val host = activity.window.decorView
    private val style = ControlStyle(activity, palette)
    private val error = NativeText(activity, palette)
    private val deny = Button(activity, null, 0)
    private val allow = GuardedPermissionButton(activity)
    private var busy = false
    private var allowEnabled = true
    private val resize = View.OnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) sizeWindow()
    }

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        if (icon != null) {
            content.addView(ImageView(activity).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, style.dp(32), style.dp(32))
        }
        content.addView(NativeText(activity, palette, NativeTextStyle(20f, weight = 500)).apply {
            text = appName
            gravity = Gravity.CENTER
            isAccessibilityHeading = true
        }, row(if (icon == null) 0 else 12))
        content.addView(NativeText(activity, palette, role = NativeTextRole.MUTED).apply {
            text = summary
            gravity = Gravity.CENTER
        }, row(6))
        if (scopeBody != null) content.addView(scopeBody, row(16))
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        error.visibility = View.GONE
        content.addView(error, row(8))

        val scroll = ScrollView(activity).apply { addView(content) }
        val actions = LinearLayout(activity)
        fun action(button: Button, label: CharSequence, positive: Boolean, click: () -> Unit) {
            style.configureButton(button)
            style.applyButton(button, filled = positive, outlined = !positive)
            button.text = label
            button.setSingleLine(false)
            button.gravity = Gravity.CENTER
            button.setOnClickListener { if (button.isEnabled && dialog.isShowing) click() }
            actions.addView(button, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (positive) marginStart = style.dp(8)
            })
        }
        action(deny, denyLabel, false, onDeny)
        action(allow, allowLabel, true, onAllow)
        val panel = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = style.dp(560)
                val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit
                    else minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            val inset = style.dp(20)
            setPadding(inset, inset, inset, inset)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
            addView(actions, row(16))
        }
        dialog.setTitle(appName)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { onCancel() }
        // The listener follows the content attachment even on platform-driven dismissal.
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = host.addOnLayoutChangeListener(resize)
            override fun onViewDetachedFromWindow(v: View) = host.removeOnLayoutChangeListener(resize)
        })
        dialog.setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_ESCAPE || !event.hasNoModifiers()) false else {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !busy) dialog.cancel()
                true
            }
        }
        window.setBackgroundDrawable(style.shape(palette.surface, radius = 24))
        window.setGravity(Gravity.CENTER)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    private fun row(topMarginDp: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = style.dp(topMarginDp)
    }

    private fun sizeWindow() {
        val available = host.width.takeIf { it > 0 } ?: style.dp(host.resources.configuration.screenWidthDp)
        window.setLayout((available - style.dp(32)).coerceIn(1, style.dp(400)), WRAP_CONTENT)
    }

    fun show() {
        sizeWindow()
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()

    fun setBusy(busy: Boolean) {
        this.busy = busy
        deny.isEnabled = !busy
        allow.isEnabled = allowEnabled && !busy
        dialog.setCancelable(!busy)
        dialog.setCanceledOnTouchOutside(!busy)
    }

    /** Retains the caller's Allow availability across busy transitions. */
    fun setAllowEnabled(enabled: Boolean) {
        allowEnabled = enabled
        allow.isEnabled = enabled && !busy
    }

    fun setError(message: CharSequence?) {
        error.text = message
        error.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (error.visibility == View.VISIBLE) error.doOnLayout {
            if (error.visibility == View.VISIBLE) {
                error.requestRectangleOnScreen(Rect(0, 0, error.width, error.height))
            }
        }
    }
}

/** Reject the entire gesture even if an overlay disappears before the final finger lifts. */
private class GuardedPermissionButton(context: Context) : Button(context, null, 0) {
    private var blocked = false

    init { filterTouchesWhenObscured = true }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) blocked = false
        if (blocked) return true
        val partial = Build.VERSION.SDK_INT >= 29 &&
            event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0
        if (!partial && event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED == 0) {
            return super.dispatchTouchEvent(event)
        }
        blocked = true
        val cancel = MotionEvent.obtain(event)
        try {
            cancel.action = MotionEvent.ACTION_CANCEL
            // Clear Button's pending press directly; its dispatch filter would drop this flagged event.
            super.onTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        return true
    }
}
