package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
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
    private val outline: smallthingz.ui.components.NativeOutlinedFieldOutline = recipe.createOutline(field, scope, environment.textDensity.density, environment.palette.outline)
    private val support = environment.paragraph(context, "", ReverbTextRole.BODY_SMALL, environment.palette.error)
    private val cancel = ReverbLibraryTextAction(context, environment, inputRoot)
    private val confirm = ReverbLibraryTextAction(context, environment, inputRoot)
    private val actions = LinearLayout(context).apply {
        orientation = HORIZONTAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(cancel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(confirm, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
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
        editor.onNativeFocusChanged = { retargetOutline() }
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
        editor.isEnabled = !busy
        support.setText(error.orEmpty())
        support.visibility = if (error == null) GONE else VISIBLE
        value.bindParagraphStyle(support, ReverbTextRole.BODY_SMALL, value.palette.error)
        cancel.bind(value, cancelLabel, enabled = !busy, onClick = onCancel)
        confirm.bind(value, confirmLabel, enabled = !busy && name.isNotBlank(), onClick = onSubmit)
        retargetOutline()
        requestLayout()
    }
    fun startEditing() {
        if (closed || !isAttachedToWindow || busy || !editor.requestFocus()) return
        editor.setSelection(editor.text.length)
        editor.context.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun close() {
        if (closed) return
        closed = true
        val focused = editor.isFocused
        var failure = smallthingz.ui.util.closePreservingPrimaryFailure(null, editor::releaseCallbacks)
        if (focused) {
            failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure) {
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
            }
            failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure, editor::clearFocus)
        }
        smallthingz.ui.util.closePreservingPrimaryFailure(failure, outline::close)?.let { throw it }
    }
}
