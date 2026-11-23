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
package smallthingz.ui.reverb.settings

import smallthingz.ui.components.input.NativeEditableState
import smallthingz.ui.components.input.NativeQueuedInputConnection

import smallthingz.ui.animation.view.ViewColorTransition

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import smallthingz.ui.text.RetentionLineHeightSpan
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.resolveDefaults
import smallthingz.ui.text.resolveEditorMetrics
import smallthingz.ui.unit.LayoutDirection
import androidx.core.view.inputmethod.EditorInfoCompat
import smallthingz.ui.common.NativeEditableInputView
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbTextRole
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.reverb.ReverbTextSurfaceTarget
import smallthingz.ui.text.TextRange
import smallthingz.ui.text.input.TextFieldValue

enum class ReverbRetentionMode { TIME, SIZE }

/** One native editor per buffer. Each retention mode owns its draft's selection and composition. */
open class ReverbRetentionInputView(
    context: Context,
    initialEnvironment: ReverbEnvironment,
) : NativeEditableInputView(context,
    initialEnvironment.appearance.editorVisuals.cursorWidthDp * initialEnvironment.textDensity.density,
    initialEnvironment.palette.primary), ReverbTextSurfaceTarget {
    override fun invalidateTextSurfaceInk() = invalidate()
    private var environment = initialEnvironment
    private val colorMotion:ViewColorTransition by lazy {ViewColorTransition(this,
        intArrayOf(initialEnvironment.palette.ink,initialEnvironment.palette.primary),::applyColors)}
    protected final override var initialized = false
        private set
    private var mode = ReverbRetentionMode.TIME
    private val modeFields = arrayOfNulls<TextFieldValue>(ReverbRetentionMode.entries.size)
    private var inputConnection: NativeQueuedInputConnection? = null
    private var focusKey: Boolean? = null
    private var focusPending = false
    private var appliedTypeface: Typeface? = null
    private var appliedEnvironment: ReverbEnvironment? = null
    private var lineHeightSpan: RetentionLineHeightSpan? = null
    protected override val editing = NativeEditableState(this, ::installLineHeightSpan)

    private val focusRequest = Runnable {
        if (!focusPending) return@Runnable
        focusPending = false
        if (isAttachedToWindow) requestFocus()
    }

    init {
        editing.initializeUnrestrictedSingleLineEditor()
        applyImeHints()
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                // Pinned default Done hides the keyboard; it does not clear field focus.
                context.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(windowToken, 0)
                true
            } else false
        }
        initialized = true
        applyTypography()
        observeNativeEdits()
    }

    fun bind(
        rawString: String,
        mode: ReverbRetentionMode,
        bufferLabel: String,
        environment: ReverbEnvironment,
        requestFocus: Boolean,
        readCurrentRaw: () -> String,
        onRawChanged: (String) -> Unit,
    ) {
        val modeChanged = this.mode != mode
        if (modeChanged) {
            modeFields[this.mode.ordinal] = editing.currentFieldValue()
            retireInputConnection()
            this.mode = mode
            applyImeHints()
        }
        editing.bindCallbacks(readCurrentRaw, onRawChanged)
        this.environment = environment
        contentDescription = bufferLabel
        applyTypography()
        editing.acceptOwnerText(rawString)
        if (modeChanged) {
            val restored = modeFields[mode.ordinal]?.takeIf { it.text == rawString }
            modeFields[mode.ordinal] = null
            editing.applyFieldValue(restored ?: TextFieldValue(rawString, TextRange.Zero))
            if (isFocused) context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }
        // Corresponds to LaunchedEffect(requestFocus), not an on-layout/on-resume focus loop.
        if (focusKey != requestFocus) {
            focusKey = requestFocus
            removeCallbacks(focusRequest)
            focusPending = requestFocus
            if (focusPending && isAttachedToWindow) post(focusRequest)
        }
    }

    /** Explicit owner disposal. A temporary detach alone must not discard current callbacks. */
    fun releaseCallbacks() {
        removeCallbacks(focusRequest)
        focusPending = false
        try { retireInputConnection() } finally {
            editing.releaseCallbacks()
            modeFields.fill(null)
            colorMotion.close()
        }
    }

    override fun beforeEditorTextDraw() = publishTextInk()

    private fun applyImeHints() {
        val hints = retentionImeContract(mode)
        // Keep the unrestricted key listener installed above; never use setInputType here.
        setRawInputType(hints.inputType)
        imeOptions = hints.imeOptions
        transformationMethod = null
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        retireInputConnection()
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        val hints = retentionImeContract(mode)
        outAttrs.inputType = hints.inputType
        outAttrs.imeOptions = hints.imeOptions // Do not inherit native NEXT/PREVIOUS flags.
        outAttrs.initialSelStart = selectionStart
        outAttrs.initialSelEnd = selectionEnd
        EditorInfoCompat.setInitialSurroundingText(outAttrs, text.toString())
        // KeyboardOptions defaults autoCorrect to true for both modes. Decimal only omits the
        // TYPE_TEXT_FLAG_AUTO_CORRECT bit; source commitCorrection still returns this option.
        return NativeQueuedInputConnection(connection, editing, autoCorrect = true) { action ->
            // Native IME_NULL must not synthesize another Enter; other source defaults do nothing.
            when (action) {
                EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS ->
                    onEditorAction(action)
            }
        }.also { inputConnection = it }
    }

    private fun retireInputConnection() {
        val previous = inputConnection
        inputConnection = null
        previous?.closeConnection()
    }

    private fun currentStyle(): TextStyle = environment.appearance.editorVisuals.largeValueStyle(
        environment.appearance.typography[ReverbTextRole.BODY_LARGE], environment.palette.ink)

    private fun direction(): LayoutDirection =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun publishTextInk() {
        val ink = smallthingz.ui.controls.textSurfaceForeground(ReverbTextSurface.sample(this),colorMotion.color(0))
        if (currentTextColor != ink) setTextColor(ink)
    }
    private fun applyColors() {
        publishTextInk()
        val primary=colorMotion.color(1)
        val recipe = environment.appearance.editorVisuals
        bindCaret(recipe.cursorWidthDp * environment.textDensity.density, primary, recipe.selectionColor(primary))
    }

    private fun applyTypography() {
        val style = currentStyle()
        val resolved = resolveDefaults(style, direction())
        val face = editorTypeface(resolved, environment.sharedFontResolver)
        if (appliedEnvironment === environment && appliedTypeface === face) return
        appliedEnvironment = environment
        appliedTypeface = face
        val metrics = environment.appearance.editorVisuals.largeValueText.resolveEditorMetrics(environment.textDensity)
        applyEditorTypography(face, metrics)
        lineHeightSpan = RetentionLineHeightSpan(metrics.lineHeightPx)
        installCaret()
        colorMotion.retarget(environment.palette.ink,environment.palette.primary)
        applyColors()
        applyEditorMinimum(resolved, environment.textDensity, environment.sharedFontResolver)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (focusPending) post(focusRequest)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(focusRequest)
        focusPending = false
        super.onDetachedFromWindow()
    }
}

data class RetentionImeContract(val inputType: Int, val imeOptions: Int)

fun retentionImeContract(mode: ReverbRetentionMode): RetentionImeContract {
    val ascii = mode == ReverbRetentionMode.TIME
    return RetentionImeContract(
        if (ascii) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            if (ascii) EditorInfo.IME_FLAG_FORCE_ASCII else 0,
    )
}
