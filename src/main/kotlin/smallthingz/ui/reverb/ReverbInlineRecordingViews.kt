package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeButtonInputPolicy
import smallthingz.ui.components.NativeMergedActionLayout
import smallthingz.ui.components.forMergedActions
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.input.NativeAndroidInteropRequests
import kotlin.math.roundToInt

/** Metadata already resolved by the app owner. No nested viewport, duplicate heading or close row. */
class ReverbInlineRecordingInfo(context: Context, private var environment: ReverbEnvironment) : LinearLayout(context) {
    private var appliedEnvironment: ReverbEnvironment? = null
    private val labels = List(3) { environment.paragraph(context, "", ReverbTextRole.LABEL_MEDIUM, environment.palette.muted) }
    private val values = List(3) { environment.paragraph(context, "", ReverbTextRole.BODY_MEDIUM, environment.palette.ink) }
    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        labels.indices.forEach { addView(labels[it]); addView(values[it]) }
        bindEnvironment(environment)
    }
    fun bind(rows: List<Pair<String, String>>) {
        require(rows.size == 3)
        rows.forEachIndexed { index, (label, value) -> labels[index].setText(label); values[index].setText(value) }
        requestLayout()
    }
    fun bindEnvironment(value: ReverbEnvironment) {
        if (appliedEnvironment === value) return
        val geometryChanged = appliedEnvironment?.textDensity?.density != value.textDensity.density
        appliedEnvironment = value
        environment = value
        val edge = (16 * value.textDensity.density).roundToInt()
        if (geometryChanged) setPadding(edge, 0, edge, edge)
        labels.indices.forEach { index ->
            value.bindParagraphStyle(labels[index], ReverbTextRole.LABEL_MEDIUM, value.palette.muted)
            value.bindParagraphStyle(values[index], ReverbTextRole.BODY_MEDIUM, value.palette.ink)
            if (geometryChanged) {
                labels[index].layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = edge / 2
                }
                values[index].layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
        }
        requestLayout()
    }
}

/** Complete replacement header: no hidden summary dimensions participate in its measurement. */
class ReverbInlineRecordingRename(
    context: Context,
    private var environment: ReverbEnvironment,
    scope: CoroutineScope,
    inputRoot: View,
    label: String,
    private val cancelLabel: String,
    private val confirmLabel: String,
    private val readName: () -> String,
    private val onNameChanged: (String) -> Unit,
    private val onCancel: () -> Unit,
    private val onSubmit: () -> Unit,
) : LinearLayout(context), AutoCloseable {
    private val recipe = environment.appearance.outlinedField
    private var error: String? = null
    private var busy = false
    private var closed = false
    private var inputActive = true
    private var editingRequested = false
    private var showImePosted = false
    private val showIme = Runnable {
        showImePosted = false
        if (editingRequested && canStartEditing() && editor.isFocused) {
            // Consume before crossing into IMM: a synchronous retirement cannot leave a retry.
            editingRequested = false
            context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    val editor = ReverbSingleLineInputView(context, environment, configuration())
    private val field: FrameLayout = object : FrameLayout(context), NativeAndroidInteropHost {
        override val nativeInteropRequests = NativeAndroidInteropRequests()
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            nativeInteropRequests.request(disallowIntercept)
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            outline.draw(canvas, width, height)
        }
    }
    /** Include the painted outline when a parent reveals this editor above the keyboard. */
    val outlinedField: View get() = this.field
    private val outline: smallthingz.ui.components.NativeOutlinedFieldOutline = recipe.createOutline(field, scope, environment.textDensity.density, environment.palette.outline)
    private val support = environment.paragraph(context, "", ReverbTextRole.BODY_SMALL, environment.palette.error)
    private val actionRecipe = environment.appearance.buttons.text.forMergedActions()
    private val actionInput = NativeButtonInputPolicy(inputRoot = { inputRoot })
    private val cancel = NativeMaterialButtonView(context, actionRecipe, environment.textDensity,
        environment.sharedFontResolver, actionInput, onCancel)
    private val confirm = NativeMaterialButtonView(context, actionRecipe, environment.textDensity,
        environment.sharedFontResolver, actionInput, onSubmit)
    private val actions = NativeMergedActionLayout(context, listOf(cancel, confirm), environment.palette).asActionFooter(density = environment.textDensity.density)
    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        field.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        editor.hint = label
        editor.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        editor.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isContentInvalid = error != null
                info.error = error
            }
        }
        field.addView(editor, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(field, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(support, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        support.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        editor.onNativeFocusChanged = { focused ->
            if (!focused) cancelEditingRequest()
            retargetOutline()
        }
        bind(environment, readName(), null, false)
    }
    private fun configuration() = NativeSingleLineInputConfiguration(recipe.textStyle,
        environment.palette.onSurface, if (error == null) environment.palette.primary else environment.palette.error,
        recipe.selectionColor(environment.palette.primary), environment.palette.primary, nativeRenameImeContract())
    private fun retargetOutline() = outline.bind(environment.textDensity.density, environment.palette.outline,
        environment.palette.primary, environment.palette.error, editor.isFocused, error != null)

    fun bind(value: ReverbEnvironment, name: String, error: String?, busy: Boolean) {
        if (closed) return
        environment = value; this.error = error; this.busy = busy
        val edge = (recipe.paddingDp * value.textDensity.density).roundToInt()
        setPadding(edge, edge, edge, 0)
        field.setPadding(edge, edge, edge, edge)
        field.minimumHeight = (recipe.minimumFieldHeightDp * value.textDensity.density).roundToInt()
        editor.bind(name, configuration(), value, readName, onNameChanged, { if (!this.busy) onSubmit() })
        if (busy) cancelEditingRequest()
        editor.setInputActive(inputActive && !busy)
        editor.isEnabled = inputActive && !busy
        support.setText(error.orEmpty())
        support.visibility = if (error == null) GONE else VISIBLE
        value.bindParagraphStyle(support, ReverbTextRole.BODY_SMALL, value.palette.error)
        actions.bindPalette(value.palette)
        actions.asActionFooter(density = value.textDensity.density)
        cancel.bindTypography(value.textDensity, value.sharedFontResolver)
        confirm.bindTypography(value.textDensity, value.sharedFontResolver)
        cancel.bind(cancelLabel, !busy, value.palette.buttonPalette, onClick = onCancel)
        confirm.bind(confirmLabel, !busy && name.isNotBlank(), value.palette.buttonPalette, onClick = onSubmit)
        retargetOutline()
        requestLayout()
    }
    /** One explicit request survives an exiting menu's window focus, never a retired editor. */
    fun startEditing() {
        if (closed || !inputActive || busy) return
        editingRequested = true
        scheduleEditing()
    }

    fun setInputActive(active: Boolean) {
        if (closed || inputActive == active) return
        inputActive = active
        if (!active) cancelEditingRequest()
        editor.setInputActive(active && !busy)
        editor.isEnabled = active && !busy
        // Block replacement focus before clearFocus can synchronously search this subtree.
        editor.isFocusable = active
        editor.isFocusableInTouchMode = active
        if (!active) editor.clearFocus()
    }

    private fun canStartEditing() = !closed && inputActive && !busy && isAttachedToWindow &&
        isShown && width > 0 && height > 0 && editor.isLaidOut && hasWindowFocus() && editor.isEnabled

    private fun scheduleEditing() {
        if (!editingRequested || showImePosted || !canStartEditing()) return
        if (!editor.requestFocus() || !editingRequested || !canStartEditing()) return
        editor.setSelection(editor.text.length)
        // ViewRoot must establish the focused editor's connection before the one show request.
        showImePosted = true
        editor.post(showIme)
    }

    private fun cancelEditingRequest() {
        editingRequested = false
        showImePosted = false
        editor.removeCallbacks(showIme)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        scheduleEditing()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) scheduleEditing()
        else {
            // Keep an unfulfilled explicit request while a closing action menu owns the window.
            showImePosted = false
            editor.removeCallbacks(showIme)
        }
    }

    override fun onDetachedFromWindow() {
        cancelEditingRequest()
        super.onDetachedFromWindow()
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelEditingRequest()
        val focused = editor.isFocused
        var failure = smallthingz.ui.util.closePreservingPrimaryFailure(null, editor::releaseCallbacks)
        if (focused) {
            failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure) {
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
            }
            failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure, editor::clearFocus)
        }
        failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure, cancel::close)
        failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure, confirm::close)
        smallthingz.ui.util.closePreservingPrimaryFailure(failure, outline::close)?.let { throw it }
    }
}
