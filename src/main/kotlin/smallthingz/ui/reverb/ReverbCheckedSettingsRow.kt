package smallthingz.ui.reverb

import android.content.Context
import android.widget.FrameLayout
import smallthingz.ui.components.NativeInputGate

/** Shared title/detail choice. Binding is silent; activation requests owner-controlled checked state. */
class ReverbCheckedSettingsRow(
    context: Context,
    theme: ReverbTheme,
    onCheckedChangeRequest: (Boolean) -> Unit,
) : FrameLayout(context), NativeInputGate, AutoCloseable {
    private var checked = false
    private var closed = false
    private var boundEnabled = false
    private var rowReady = false
    private var onRequest: ((Boolean) -> Unit)? = onCheckedChangeRequest
    internal val row = ReverbAppChoiceRow(context, theme, trigger = false,
        checkbox = true, showIdentityIcon = false) { onRequest?.invoke(!checked) }
    override val acceptsNativeInput get() = !closed && isEnabled && boundEnabled

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rowReady = true
    }

    fun bind(title: String, detail: String?, checked: Boolean, enabled: Boolean, theme: ReverbTheme) {
        if (closed) return
        this.checked = checked
        boundEnabled = enabled
        isEnabled = enabled
        row.bind(ReverbAppChoice("checked-row", title, enabled = enabled), checked, theme, detail.orEmpty())
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!rowReady) return
        row.isEnabled = acceptsNativeInput
        row.isFocusable = acceptsNativeInput
        // Disabling focus clears View's touch-mode flag; restore the row's original policy too.
        row.isFocusableInTouchMode = acceptsNativeInput
        row.isClickable = acceptsNativeInput
    }

    override fun close() {
        if (closed) return
        closed = true
        onRequest = null
        row.close()
    }
}
