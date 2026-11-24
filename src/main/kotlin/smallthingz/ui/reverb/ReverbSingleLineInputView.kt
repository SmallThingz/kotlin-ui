/*
 * Editing/IME contracts adapted from AndroidX foundation/ui-text 1.11.2.
 * Copyright 2021 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package smallthingz.ui.reverb

import smallthingz.ui.common.NativeEditableInputView
import smallthingz.ui.common.nativeSingleLineEnterCommand
import smallthingz.ui.components.input.NativeEditableState
import smallthingz.ui.components.input.NativeQueuedInputConnection


import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.inputmethod.InputContentInfo
import android.text.InputType
import smallthingz.ui.text.NativeEditableLineHeightSpan
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import smallthingz.ui.graphics.Color
import smallthingz.ui.text.Paragraph
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.TextRange
import smallthingz.ui.text.input.TextFieldValue
import smallthingz.ui.text.resolveDefaults
import smallthingz.ui.text.resolveEditorMetrics
import smallthingz.ui.unit.LayoutDirection
import smallthingz.ui.text.style.LineHeightStyle
import androidx.core.view.inputmethod.EditorInfoCompat
import smallthingz.ui.animation.view.ViewColorTransition

/**
 * Native editable/IME owner extracted mechanically from SettingsRetentionInputView. No composition
 * or hidden rendering tree. The caller owns source style, IME hints and Done action; raw edits stay
 * unrestricted. This candidate keeps native Editor drawing/handles and therefore is NOT a qualified
 * replacement until cursor/selection/key/IME and actual paragraph raster match the released field.
 * API28 cursor styling uses its public constructor resource path; handle setters remain API29+.
 */
class ReverbSingleLineInputView(
    context: Context,
    initialEnvironment: ReverbEnvironment,
    initialConfiguration: NativeSingleLineInputConfiguration,
) : NativeEditableInputView(context,
    initialEnvironment.appearance.editorVisuals.cursorWidthDp * initialEnvironment.textDensity.density,
    initialConfiguration.cursorColor) {
    private var environment = initialEnvironment
    protected override var initialized = false
        private set
    private var configuration = initialConfiguration
    private val colorMotion:ViewColorTransition by lazy {ViewColorTransition(this,
        intArrayOf(initialConfiguration.textColor,initialConfiguration.selectionBackgroundColor,
            initialConfiguration.cursorColor,initialConfiguration.selectionHandleColor),::applyColors)}
    private var onDone: (() -> Unit)? = null
    private var inputSession: NativeQueuedInputConnection? = null
    private var inputActive = true
    private var callbacksReleased = false
    var onNativeFocusChanged: ((Boolean) -> Unit)? = null
    private var appliedTypeface: Typeface? = null
    private var appliedEnvironment: ReverbEnvironment? = null
    private var appliedConfiguration: NativeSingleLineInputConfiguration? = null
    private var minimumParagraph: Paragraph? = null
    private var lineHeightSpan: NativeEditableLineHeightSpan? = null
    protected override val editing = NativeEditableState(this, ::installLineHeightSpan)


    init {
        editing.initializeUnrestrictedSingleLineEditor()
        applyImeHints()
        setOnEditorActionListener { _, action, event ->
            // Android16 calls this for hardware DOWN and UP. Hardware Enter is handled once by
            // our key route below; IME actions have their own source action dispatch.
            if (event == null && action == EditorInfo.IME_ACTION_DONE) {
                onDone?.invoke()
                true
            } else false
        }
        initialized = true
        applyTypography()
        observeNativeEdits()
    }

    fun bind(
        rawString: String,
        configuration: NativeSingleLineInputConfiguration,
        environment: ReverbEnvironment,
        readCurrentRaw: () -> String,
        onRawChanged: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        if (callbacksReleased) return
        editing.bindCallbacks(readCurrentRaw, onRawChanged)
        this.onDone = onDone
        this.environment = environment
        val imeChanged = this.configuration.ime != configuration.ime
        this.configuration = configuration
        applyTypography()
        applyImeHints()
        editing.acceptOwnerText(rawString)
        if (imeChanged && isFocused) {
            context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }
        // No auto-focus: released RenameRecordingDialog has no FocusRequester/LaunchedEffect.
    }

    /** A temporary detach retains owner callbacks; the actual sheet lifetime calls this. */
    fun releaseCallbacks() {
        callbacksReleased = true
        editing.releaseCallbacks()
        onDone = null
        onNativeFocusChanged = null
        retireInputSession()
    }

    /** Temporary owner deactivation rejects replacement connections as well as the old one. */
    fun setInputActive(active: Boolean) {
        if (inputActive == active) return
        inputActive = active
        if (!active) retireInputSession()
    }

    override fun onCheckIsTextEditor(): Boolean = !callbacksReleased && inputActive &&
        isEnabled && smallthingz.ui.common.nativeSurfaceInputAllowed(this) && super.onCheckIsTextEditor()

    /** Revoke first: an old connection cannot regain authority when this retained View reattaches. */
    fun retireInputSession() {
        val previous = inputSession
        inputSession = null
        previous?.closeConnection()
    }

    override fun onDetachedFromWindow() {
        val failure = smallthingz.ui.util.closePreservingPrimaryFailure(null, ::retireInputSession)
        smallthingz.ui.util.closePreservingPrimaryFailure(failure) { super.onDetachedFromWindow() }
            ?.let { throw it }
    }

    private fun applyImeHints() {
        val hints = configuration.ime
        // Keep the unrestricted key listener installed above; never use setInputType here.
        setRawInputType(hints.inputType)
        imeOptions = hints.imeOptions
        transformationMethod = null
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        retireInputSession()
        // ViewRoot may establish the new connection before delivering window-focus gain.
        // Old wrappers retire on focus loss; do not reject that legitimate replacement handshake.
        if (callbacksReleased || !inputActive || !isEnabled || !isAttachedToWindow || !isFocused ||
            !isShown || !smallthingz.ui.common.nativeSurfaceInputAllowed(this)) return null
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        val hints = configuration.ime
        outAttrs.inputType = hints.inputType
        outAttrs.imeOptions = hints.imeOptions // Do not inherit native NEXT/PREVIOUS flags.
        outAttrs.initialSelStart = selectionStart
        outAttrs.initialSelEnd = selectionEnd
        EditorInfoCompat.setInitialSurroundingText(outAttrs, text.toString())
        return object : NativeQueuedInputConnection(connection, editing, hints.autoCorrect, ::sourceEditorAction) {
            override fun reportFullscreenMode(enabled: Boolean): Boolean = false
            override fun getHandler(): Handler? = null
            override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
            override fun getCursorCapsMode(reqModes: Int): Int = editing.currentFieldValue().let {
                TextUtils.getCapsMode(it.text, it.selection.min, reqModes)
            }
            @RequiresApi(33)
            override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
                requestCursorUpdates(cursorUpdateMode or cursorUpdateFilter)
        }.also { inputSession = it }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!nativeSingleLineEnterCommand(keyCode, event.isAltPressed, event.isMetaPressed)) return false
            onDone?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) false
        else super.onKeyUp(keyCode, event)

    // Matches TextView's default IME traversal; focusSearch's annotation omits these logical directions.
    @android.annotation.SuppressLint("WrongConstant")
    private fun sourceEditorAction(action: Int) {
        when (action) {
            EditorInfo.IME_ACTION_DONE -> onDone?.invoke()
            EditorInfo.IME_ACTION_NEXT -> focusSearch(FOCUS_FORWARD)?.requestFocus(FOCUS_FORWARD)
            EditorInfo.IME_ACTION_PREVIOUS -> focusSearch(FOCUS_BACKWARD)?.requestFocus(FOCUS_BACKWARD)
            // Custom Done is the only released KeyboardActions callback. Other default actions
            // except focus traversal are no-ops, not native TextView's keyboard-hide fallback.
        }
    }

    /** Outer decorated field owns editable semantics; native Editor is only its edit/input leaf. */
    fun setTextFromSemantics(value: String): Boolean {
        editing.applyFieldValue(TextFieldValue(value, TextRange(value.length)))
        editing.publishNativeRaw()
        return true
    }

    fun setSelectionFromSemantics(start: Int, end: Int): Boolean {
        val current = editing.currentFieldValue()
        if (start == current.selection.start && end == current.selection.end) return false
        if (minOf(start, end) < 0 || maxOf(start, end) > current.text.length) return false
        editing.applyFieldValue(TextFieldValue(current.annotatedString, TextRange(start, end)))
        return true
    }

    private fun currentStyle(): TextStyle = configuration.style.copy(color = Color(configuration.textColor))

    private fun direction(): LayoutDirection =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun applyColors() {
        setTextColor(colorMotion.color(0))
        bindCaret(environment.appearance.editorVisuals.cursorWidthDp * environment.textDensity.density,
            colorMotion.color(2), colorMotion.color(1), colorMotion.color(3))
    }

    private fun applyTypography() {
        val style = currentStyle()
        val resolved = resolveDefaults(style, direction())
        val face = editorTypeface(resolved, environment.sharedFontResolver)
        if (appliedEnvironment === environment && appliedTypeface === face && appliedConfiguration == configuration) return
        appliedEnvironment = environment
        appliedConfiguration = configuration
        appliedTypeface = face
        val metrics = resolved.resolveEditorMetrics(environment.textDensity)
        applyEditorTypography(face, metrics)
        lineHeightSpan = NativeEditableLineHeightSpan(metrics.lineHeightPx,
            resolved.lineHeightStyle ?: LineHeightStyle.Default)
        installCaret(tintHandles = true)
        colorMotion.retarget(configuration.textColor,configuration.selectionBackgroundColor,
            configuration.cursorColor,configuration.selectionHandleColor)
        applyColors()
        minimumParagraph = applyEditorMinimum(resolved, environment.textDensity, environment.sharedFontResolver)
        installLineHeightSpan()
        requestLayout()
        invalidate()
    }

    private fun installLineHeightSpan() {
        val value = editableText ?: return
        lineHeightSpan?.install(value)
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (initialized) {
            appliedEnvironment = null
            applyTypography()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) retireInputSession()
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        if (!gainFocus) retireInputSession()
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        onNativeFocusChanged?.invoke(gainFocus)
    }
}

data class NativeSingleLineImeContract(val inputType:Int,val imeOptions:Int,val autoCorrect:Boolean)

data class NativeSingleLineInputConfiguration(
    val style:TextStyle,
    val textColor:Int,
    val cursorColor:Int,
    val selectionBackgroundColor:Int,
    val selectionHandleColor:Int,
    val ime:NativeSingleLineImeContract,
)

fun nativeRenameImeContract() = NativeSingleLineImeContract(
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
    EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_FORCE_ASCII,
    autoCorrect=true,
)
