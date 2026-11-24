package smallthingz.ui.common

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.annotation.MainThread
import smallthingz.ui.components.NativeButtonTouchTarget
import smallthingz.ui.components.NativeLabeledTextField
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.acceptsNativeInput
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbStandardIconButtonView
import smallthingz.ui.util.closePreservingPrimaryFailure

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
    /** Text admission only; trailing actions have their own enabled state. */
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
    private var editCallback: ((ID, String) -> Unit)? = onEdit
    private var actionCallback: ((CredentialActionTarget<ID>) -> Unit)? = onAction
    private var actionStates = emptyMap<CredentialActionTarget<ID>, CredentialActionState<ID>>()
    private var iconEnvironment = ReverbEnvironment(theme.density, theme.fontResolver, theme.colors)

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
                    // EditText retains BufferType.EDITABLE even without a key listener. Android
                    // advertises and executes ACTION_SET_TEXT from that buffer type alone; keep
                    // authoritative binding available without exposing an accessibility edit path.
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.isEditable = false
                            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
                        }

                        override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean =
                            action != AccessibilityNodeInfo.ACTION_SET_TEXT &&
                                super.performAccessibilityAction(host, action, arguments)
                    }
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
                        if (spec.editable && canDispatchEdit(editor) && editor.acceptsNativeInput() &&
                            canDispatchEdit(editor) && editor.text === s) {
                            editCallback?.invoke(spec.id, s?.toString().orEmpty())
                        }
                    }
                })
            }
        }
    }
    private val joined = recipe.createGroup(context, theme.colors, *editors.values.toTypedArray())
    private val buttons = actions.sortedWith(compareBy({ it.field.ordinal }, { it.action.ordinal })).associateWith { target ->
        val button: View = when (target.action) {
            CredentialFieldAction.REVEAL -> NativeMaterialButtonView(
                context, theme.appearance.buttons.neutralText, theme.density, theme.fontResolver,
            ) { dispatchAction(target) }
            CredentialFieldAction.COPY, CredentialFieldAction.REROLL -> ReverbStandardIconButtonView(
                context, iconEnvironment, { joined.view },
            ).apply {
                visual.filterTouchesWhenObscured = true
                visual.mergedRowAccessibility.enableStandaloneAccessibility({ !closed }) {
                    visual.glyph.contentDescription
                }
            }
        }
        button.apply {
            visibility = View.GONE
            filterTouchesWhenObscured = true
        }
    }
    private val actionHosts = buttons.mapValues { (_, button) ->
        if (button is NativeMaterialButtonView) NativeButtonTouchTarget(button).apply {
            visibility = button.visibility
            filterTouchesWhenObscured = true
        } else button
    }
    private val trailingRows = buttons.entries.groupBy { it.key.field }.mapValues { (_, entries) ->
        object : LinearLayout(context) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus) for ((_, button) in entries) {
                    when (button) {
                        is NativeMaterialButtonView -> button.cancelInput()
                        is ReverbStandardIconButtonView -> button.cancelInput()
                    }
                }
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            for ((target, button) in entries) {
                // Constrain a long Show/Hide label before shrinking the standard icon footprints.
                // Its canonical target owns the 48dp strip around the intrinsic 40dp button,
                // before the field's fallback touch delegate can route that strip to the editor.
                addView(actionHosts.getValue(target),
                    LinearLayout.LayoutParams(-2, -2, if (button is NativeMaterialButtonView) 1f else 0f))
            }
        }
    }
    val view: View = joined.view

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

        actionStates = byAction
        for ((action, button) in buttons) bindAction(action, button, byAction[action])
        for ((id, row) in trailingRows) {
            editors.getValue(id).setTrailingContent(row.takeIf { byAction.keys.any { it.field == id } })
        }
    }

    fun bindTheme(theme: ControlTheme) {
        if (closed || this.theme === theme) return
        this.theme = theme
        iconEnvironment = ReverbEnvironment(theme.density, theme.fontResolver, theme.colors)
        joined.bindPalette(theme.colors)
        for ((action, button) in buttons) {
            if (button is NativeMaterialButtonView) button.bindTypography(theme.density, theme.fontResolver)
            bindAction(action, button, actionStates[action])
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
        editCallback = null
        actionCallback = null
        actionStates = emptyMap()
        // Host focus/text callbacks may throw or reenter close. Retirement is terminal,
        // but every remaining field/action must still release its retained state.
        var failure = closePreservingPrimaryFailure(null, ::dismissKeyboard)
        for (spec in fields) {
            val field = editors.getValue(spec.id)
            failure = closePreservingPrimaryFailure(failure) { field.setEditorEnabled(false) }
            failure = closePreservingPrimaryFailure(failure) {
                bindField(field, spec, CredentialFieldState(spec.id, "", enabled = false))
            }
        }
        for ((action, button) in buttons) {
            failure = closePreservingPrimaryFailure(failure) { bindAction(action, button, null) }
            failure = closePreservingPrimaryFailure(failure) {
                if (button is NativeMaterialButtonView) button.close()
                if (button is ReverbStandardIconButtonView) button.cancelInput()
            }
        }
        failure = closePreservingPrimaryFailure(failure) { view.visibility = View.GONE }
        failure?.let { throw it }
    }

    private fun dispatchAction(target: CredentialActionTarget<ID>) {
        val button = buttons.getValue(target)
        // Admission can synchronously hide, rebind or close the owner. Query it once, then recheck local state.
        if (canDispatchAction(target, button) && button.acceptsNativeInput() && canDispatchAction(target, button)) {
            actionCallback?.invoke(target)
        }
    }

    private fun canDispatchAction(target: CredentialActionTarget<ID>, button: View) =
        !closed && actionStates[target]?.enabled == true && button.isAttachedToWindow &&
            button.isShown && button.hasWindowFocus()

    private fun canDispatchEdit(editor: View) =
        !closed && !binding && editor.isEnabled && editor.isAttachedToWindow && editor.isShown

    private fun bindAction(target: CredentialActionTarget<ID>, button: View, state: CredentialActionState<ID>?) {
        val enabled = !closed && state?.enabled == true
        button.visibility = if (state == null) View.GONE else View.VISIBLE
        actionHosts.getValue(target).visibility = button.visibility
        when (button) {
            is NativeMaterialButtonView -> button.bind(state?.label.orEmpty(), enabled, theme.colors.buttonPalette)
            is ReverbStandardIconButtonView -> {
                if (!enabled) button.cancelInput()
                button.bind(
                    iconEnvironment,
                    if (target.action == CredentialFieldAction.COPY) ReverbIcon.Copy else ReverbIcon.Reset,
                    state?.label.orEmpty(), enabled, theme.colors.muted, theme.colors.ink,
                ) { dispatchAction(target) }
            }
        }
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

        // Disabling text must not recursively disable independently admitted trailing actions.
        field.setEditorEnabled(state.enabled)
        field.setPlaceholder(state.placeholder)
        field.setSupportingText(state.supportingText)
        field.setError(state.error)
    }
}
