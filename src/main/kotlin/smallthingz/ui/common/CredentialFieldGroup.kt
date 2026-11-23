package smallthingz.ui.common

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.annotation.MainThread
import smallthingz.ui.components.NativeLabeledTextField
import smallthingz.ui.components.NativeMaterialButtonView

enum class CredentialKeyboard { TEXT, URI, PASSWORD }

enum class CredentialFieldAction { REVEAL, COPY, REROLL }

class CredentialFieldSpec<ID : Enum<ID>>(
    val id: ID,
    val label: String,
    val keyboard: CredentialKeyboard = CredentialKeyboard.PASSWORD,
    val editable: Boolean = true,
    /** Editor limit, including any extra character used by callers to detect length overflow. */
    val maxInputLength: Int? = null,
) {
    init {
        require(maxInputLength == null || maxInputLength > 0)
    }
}

/** Intentionally uses identity-based toString to keep input values out of diagnostics. */
class CredentialFieldState<ID : Enum<ID>>(
    val id: ID,
    val value: String,
    val enabled: Boolean = true,
    val revealed: Boolean = false,
    val error: String? = null,
    val supportingText: String? = null,
    val placeholder: String? = null,
)

data class CredentialActionTarget<ID : Enum<ID>>(val field: ID, val action: CredentialFieldAction)

class CredentialActionState<ID : Enum<ID>>(
    val target: CredentialActionTarget<ID>,
    val label: String,
    val enabled: Boolean = true,
)

/** Fixed-membership credential editors with shared joined-field paint and semantic actions. */
@MainThread
class CredentialFieldGroup<ID : Enum<ID>>(
    context: Context,
    theme: ControlTheme,
    fields: List<CredentialFieldSpec<ID>>,
    actions: Set<CredentialActionTarget<ID>> = emptySet(),
    onAction: (CredentialActionTarget<ID>) -> Unit = {},
    onEdit: (ID, String) -> Unit,
) : AutoCloseable {
    private val fields = fields.toList()
    private val recipe = theme.appearance.joinedFields
    private var theme = theme
    private var binding = false
    private var closed = false

    init {
        val ids = fields.map { it.id }.toSet()
        require(ids.isNotEmpty() && ids.size == fields.size)
        require(actions.all { it.field in ids })
    }

    private val editors = this.fields.associate { spec ->
        spec.id to recipe.createField(context, theme.colors, spec.label).apply {
            editor.apply {
                id = View.generateViewId()
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or when (spec.keyboard) {
                    CredentialKeyboard.TEXT -> InputType.TYPE_TEXT_VARIATION_NORMAL
                    CredentialKeyboard.URI -> InputType.TYPE_TEXT_VARIATION_URI
                    CredentialKeyboard.PASSWORD -> InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                if (!spec.editable) {
                    keyListener = null
                    isCursorVisible = false
                    isFocusable = false
                    isClickable = false
                    isLongClickable = false
                }
                isSaveEnabled = false
                setFreezesText(false)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
                }
                filterTouchesWhenObscured = true
                spec.maxInputLength?.let { maxLength ->
                    val userLimit = InputFilter.LengthFilter(maxLength)
                    filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                        if (binding) null else userLimit.filter(source, start, end, dest, dstart, dend)
                    })
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (!closed && !binding && spec.editable && isEnabled) onEdit(spec.id, s?.toString().orEmpty())
                    }
                })
            }
        }
    }
    private val joined = recipe.createGroup(context, theme.colors, *editors.values.toTypedArray())
    private val buttons = actions.sortedWith(compareBy({ it.field.ordinal }, { it.action.ordinal })).associateWith { target ->
        NativeMaterialButtonView(context, theme.appearance.buttons.neutralText, theme.density, theme.fontResolver) {
            if (!closed) onAction(target)
        }.apply {
            visibility = View.GONE
            filterTouchesWhenObscured = true
        }
    }
    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(joined.view, LinearLayout.LayoutParams(-1, -2))
        for (button in buttons.values) addView(button, LinearLayout.LayoutParams(-1, -2))
    }

    init {
        val ordered = this.fields.filter { it.editable }.map { editors.getValue(it.id) }
        for ((index, field) in ordered.withIndex()) {
            val next = ordered.getOrNull(index + 1)?.editor
            field.editor.nextFocusForwardId = next?.id ?: View.NO_ID
            val imeAction = if (next == null) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
            field.editor.imeOptions = imeAction or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
    }

    /** Each configured field must occur once. Omitted action states hide the corresponding action. */
    fun bind(states: List<CredentialFieldState<ID>>, actions: List<CredentialActionState<ID>> = emptyList()) {
        if (closed) return
        val byId = states.associateBy { it.id }
        require(states.size == editors.size && byId.keys == editors.keys)
        val byAction = actions.associateBy { it.target }
        require(byAction.size == actions.size && buttons.keys.containsAll(byAction.keys))

        binding = true
        try {
            for (spec in fields) bindField(editors.getValue(spec.id), spec, byId.getValue(spec.id))
        } finally {
            binding = false
        }

        for ((action, button) in buttons) {
            val state = byAction[action]
            button.visibility = if (state == null) View.GONE else View.VISIBLE
            if (state != null) button.bind(state.label, state.enabled, theme.colors.buttonPalette)
        }
    }

    fun bindTheme(theme: ControlTheme) {
        if (closed || this.theme === theme) return
        this.theme = theme
        joined.bindPalette(theme.colors)
        for (button in buttons.values) {
            button.bindTypography(theme.density, theme.fontResolver)
            button.bind(button.label.getText(), button.isEnabled, theme.colors.buttonPalette)
        }
    }

    fun dismissKeyboard() {
        val focused = view.findFocus() ?: return
        view.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    override fun close() {
        if (closed) return
        closed = true
        dismissKeyboard()
        for (spec in fields) {
            bindField(editors.getValue(spec.id), spec, CredentialFieldState(spec.id, "", enabled = false))
        }
        for (button in buttons.values) button.close()
        view.visibility = View.GONE
    }

    private fun bindField(
        field: NativeLabeledTextField,
        spec: CredentialFieldSpec<ID>,
        state: CredentialFieldState<ID>,
    ) {
        val editor = field.editor
        val masked = spec.keyboard == CredentialKeyboard.PASSWORD && !state.revealed
        val transformation = if (masked) PasswordTransformationMethod.getInstance() else null
        val valueChanged = !state.value.contentEquals(editor.text)
        val maskChanged = editor.transformationMethod !== transformation
        if (valueChanged || maskChanged) {
            val start = editor.selectionStart
            val end = editor.selectionEnd
            if (maskChanged) editor.transformationMethod = transformation
            if (valueChanged) editor.setText(state.value)
            if (start >= 0 && end >= 0) {
                editor.setSelection(start.coerceAtMost(editor.length()), end.coerceAtMost(editor.length()))
            }
        }

        field.isEnabled = state.enabled
        field.setPlaceholder(state.placeholder)
        field.setSupportingText(state.supportingText)
        field.setError(state.error)
    }
}
