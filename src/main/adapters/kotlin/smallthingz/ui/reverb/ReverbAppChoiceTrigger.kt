package smallthingz.ui.reverb

import android.content.Context
import android.widget.FrameLayout
import smallthingz.ui.components.NativeInputGate

/** Semantic installed-app trigger. Binding never selects or commits an application choice. */
open class ReverbAppChoiceTrigger(
    context: Context,
    private var theme: ReverbTheme,
    onOpen: () -> Unit,
) : FrameLayout(context), NativeInputGate, AutoCloseable {
    private var open: (() -> Unit)? = onOpen
    private var closed = false
    private var choice: ReverbAppChoice? = null
    internal val row = ReverbAppChoiceRow(context, theme, trigger = true) { open?.invoke() }
    internal var picker: ReverbAppChoicePicker? = null
        private set
    override val acceptsNativeInput get() = !closed && isEnabled && choice?.enabled == true

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(row, LayoutParams(-1, -2))
    }

    fun bind(choice: ReverbAppChoice, theme: ReverbTheme) {
        if (closed) return
        if (!choice.enabled || this.choice?.id != choice.id) dismissChoices()
        this.choice = choice
        this.theme = theme
        isEnabled = choice.enabled
        row.bind(choice, selected = false, theme)
        picker?.bindTheme(theme)
    }

    fun showChoices(title: String, choices: List<ReverbAppChoice>, selectedId: String?,
        canSelect: () -> Boolean, onSelect: (String) -> Unit) {
        dismissChoices()
        if (!acceptsNativeInput) return
        val next = ReverbAppChoicePicker(this, theme, title, choices, selectedId, canSelect, onSelect)
        next.onRetired = { if (picker === next) picker = null }
        picker = next
        next.show()
    }

    fun dismissChoices() {
        val previous = picker
        picker = null
        previous?.close()
    }

    override fun onDetachedFromWindow() {
        dismissChoices()
        super.onDetachedFromWindow()
    }

    override fun close() {
        if (closed) return
        closed = true
        open = null
        dismissChoices()
        row.close()
    }
}
