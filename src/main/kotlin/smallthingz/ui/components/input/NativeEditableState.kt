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
package smallthingz.ui.components.input

import android.graphics.Paint
import android.os.Build
import android.os.LocaleList
import android.text.Layout
import android.text.method.TextKeyListener
import android.view.Gravity
import android.view.View
import android.text.Editable
import android.text.Selection
import android.text.TextUtils
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import smallthingz.ui.text.TextRange
import smallthingz.ui.text.input.EditCommand
import smallthingz.ui.text.input.EditProcessor
import smallthingz.ui.text.input.TextFieldValue

/** Owner-bound raw String editing on the caller's actual Editable; no rendering or focus ownership. */
class NativeEditableState(private val editor: EditText, private val afterTextApplied: () -> Unit) {
    private var applyingOwner = false
    private var previousOwnerText: String? = null
    private var lastCallbackText: String? = null
    private var readCurrentRaw: (() -> String)? = null
    private var onRawChanged: ((String) -> Unit)? = null
    private var nativeEditDepth = 0
    private var nativeEditPending = false
    private val editProcessor = EditProcessor()
    // Public composing-span manipulation only; this is never a registered IME session.
    private val composingSpans = object : BaseInputConnection(editor, true) {
        override fun getEditable(): Editable = checkNotNull(editor.editableText)
    }

    /**
     * One-time plain editor setup before caller-owned IME hints, typography and listeners.
     * Keeps the native Editor and unrestricted raw input; does not request focus or set text.
     */
    fun initializeUnrestrictedSingleLineEditor() {
        with(editor) {
            // The cursor-only style does not inherit EditText's touch-mode focus default.
            isFocusableInTouchMode = true
            background = null
            foreground = null
            // AndroidComposeView opts out of the platform overlay; cursor/selection own field focus.
            defaultFocusHighlightEnabled = false
            setPadding(0, 0, 0, 0)
            setCompoundDrawablePadding(0)
            minimumWidth = 0
            minimumHeight = 0
            setMinWidth(0)
            setMinHeight(0)
            setAutoSizeTextTypeWithDefaults(EditText.AUTO_SIZE_TEXT_TYPE_NONE)
            includeFontPadding = false
            setFallbackLineSpacing(true)
            gravity = Gravity.TOP or Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_GRAVITY
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            paintFlags = Paint.ANTI_ALIAS_FLAG
            paint.hinting = Paint.HINTING_ON
            paint.isSubpixelText = false
            paint.isLinearText = false
            paint.textSkewX = 0f
            paint.isFakeBoldText = false
            fontFeatureSettings = ""
            textScaleX = 1f
            textLocales = LocaleList.getDefault()
            breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            justificationMode = android.graphics.text.LineBreaker.JUSTIFICATION_MODE_NONE
            if (Build.VERSION.SDK_INT >= 33) {
                lineBreakStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_STYLE_NONE
                lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE
            }
            if (Build.VERSION.SDK_INT >= 35) {
                setUseBoundsForWidth(false)
                setShiftDrawingOffsetForStartOverhang(false)
            }
            // IME hints must not filter raw Unicode, punctuation or pasted newlines.
            keyListener = TextKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE)
            filters = emptyArray()
            setSingleLine(true)
            transformationMethod = null // Source VisualTransformation.None retains raw newlines.
            setHorizontallyScrolling(true)
            setSelectAllOnFocus(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
    }

    /** Install callbacks before the caller updates typography/IME hints and reconciles owner text. */
    fun bindCallbacks(readCurrentRaw: () -> String, onRawChanged: (String) -> Unit) {
        this.readCurrentRaw = readCurrentRaw
        this.onRawChanged = onRawChanged
    }
    fun releaseCallbacks() { readCurrentRaw = null; onRawChanged = null }

    /** Forward the caller's existing TextWatcher after its initialization gate. */
    fun afterTextChanged() {
        if (applyingOwner) return
        nativeEditPending = true
        if (nativeEditDepth == 0) publishNativeRaw()
    }

    fun acceptOwnerText(raw: String) {
        if (raw != previousOwnerText) {
            previousOwnerText = raw
            lastCallbackText = raw // remember(value), not every rejected onValueChange callback.
        }
        if (TextUtils.equals(editor.text, raw)) return
        val start = editor.selectionStart.coerceAtLeast(0).coerceAtMost(raw.length)
        val end = editor.selectionEnd.coerceAtLeast(0).coerceAtMost(raw.length)
        applyingOwner = true
        try {
            val value = checkNotNull(editor.editableText)
            BaseInputConnection.removeComposingSpans(value)
            value.replace(0, editor.length(), raw)
            Selection.setSelection(value, start, end)
            afterTextApplied()
        } finally { applyingOwner = false }
    }

    inline fun <T> nativeEdit(block: () -> T): T {
        beginNativeEdit()
        try { return block() }
        finally { endNativeEdit() }
    }

    @PublishedApi internal fun beginNativeEdit() { nativeEditDepth++ }
    @PublishedApi internal fun endNativeEdit() {
        nativeEditDepth--
        if (nativeEditDepth == 0 && nativeEditPending) publishNativeRaw()
    }

    fun publishNativeRaw() {
        nativeEditPending = false
        val proposed = editor.text.toString()
        val changed = proposed != lastCallbackText
        lastCallbackText = proposed
        if (changed) onRawChanged?.invoke(proposed)
        // Rejected drafts restore only after the complete command/key/paste updated selection.
        readCurrentRaw?.invoke()?.let(::acceptOwnerText)
        afterTextApplied()
    }

    fun currentFieldValue(): TextFieldValue {
        val value = checkNotNull(editor.editableText)
        val start = BaseInputConnection.getComposingSpanStart(value)
        val end = BaseInputConnection.getComposingSpanEnd(value)
        return TextFieldValue(value.toString(),
            TextRange(editor.selectionStart.coerceAtLeast(0), editor.selectionEnd.coerceAtLeast(0)),
            if (start >= 0 && end >= 0 && start != end) TextRange(start, end) else null)
    }

    fun applyFieldValue(value: TextFieldValue) {
        applyingOwner = true
        try {
            val target = checkNotNull(editor.editableText)
            if (!TextUtils.equals(target, value.text)) target.replace(0, target.length, value.text)
            BaseInputConnection.removeComposingSpans(target)
            value.composition?.let { composingSpans.setComposingRegion(it.min, it.max) }
            Selection.setSelection(target, value.selection.start, value.selection.end)
            afterTextApplied()
        } finally { applyingOwner = false }
    }

    internal val textLength: Int get() = checkNotNull(editor.editableText).length

    internal fun process(commands: List<EditCommand>): TextFieldValue {
        editProcessor.reset(currentFieldValue(), null)
        return editProcessor.apply(commands)
    }
}
