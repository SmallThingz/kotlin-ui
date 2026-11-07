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

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
import smallthingz.ui.text.input.CommitTextCommand
import smallthingz.ui.text.input.DeleteSurroundingTextCommand
import smallthingz.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import smallthingz.ui.text.input.EditCommand
import smallthingz.ui.text.input.FinishComposingTextCommand
import smallthingz.ui.text.input.SetComposingRegionCommand
import smallthingz.ui.text.input.SetComposingTextCommand
import smallthingz.ui.text.input.SetSelectionCommand
import smallthingz.ui.text.input.getSelectedText
import smallthingz.ui.text.input.getTextAfterSelection
import smallthingz.ui.text.input.getTextBeforeSelection

/**
 * Queues source EditCommands and preserves the caller's distinct editor-action/IME policy.
 * Commands admitted by a callback drain after the current publication returns, unless batched.
 * A failing publication retains its unfinished prefix; it does not roll back the Editable.
 */
open class NativeQueuedInputConnection(
    private val target: InputConnection,
    private val state: NativeEditableState,
    private val autoCorrect: Boolean,
    onEditorAction: (Int) -> Unit,
) : InputConnectionWrapper(target, false) {
    private var active = true
    private var depth = 0
    private var draining = false
    private val commands = ArrayList<EditCommand>()
    private val actions = NativeEditableConnectionActions(
        active = { active },
        currentTextLength = { state.textLength },
        queueSelectAll = { command(SetSelectionCommand(0, it)) },
        sendKey = { action, code -> sendKeyEvent(KeyEvent(action, code)) },
        onEditorAction = onEditorAction,
    )

    override fun beginBatchEdit(): Boolean {
        if (!active) return false
        depth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (!active || depth == 0) return false
        depth--
        if (depth == 0 && !draining) {
            draining = true
            try {
                while (active && depth == 0 && commands.isNotEmpty()) {
                    val prefixSize = commands.size
                    val value = state.process(commands.toList())
                    target.beginBatchEdit()
                    try {
                        if (active) state.applyFieldValue(value)
                        if (active) state.publishNativeRaw()
                    } finally { target.endBatchEdit() }
                    // Owner/target callbacks can enqueue another batch or close this connection.
                    // Only a successfully completed prefix is retired; exceptions retain it.
                    if (active) commands.subList(0, prefixSize).clear()
                }
            } finally { draining = false }
        }
        return depth > 0
    }

    private fun command(value: EditCommand): Boolean {
        if (!active) return false
        depth++
        try { commands.add(value) }
        finally { endBatchEdit() }
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int) =
        command(CommitTextCommand(text.toString(), newCursorPosition))
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
        command(SetComposingTextCommand(text.toString(), newCursorPosition))
    override fun setComposingRegion(start: Int, end: Int) = command(SetComposingRegionCommand(start, end))
    override fun setSelection(start: Int, end: Int) = command(SetSelectionCommand(start, end))
    override fun finishComposingText() = command(FinishComposingTextCommand())
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
        command(DeleteSurroundingTextCommand(beforeLength, afterLength))
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
        command(DeleteSurroundingTextInCodePointsCommand(beforeLength, afterLength))

    // InputConnectionWrapper otherwise forwards these overloads directly to Native Editor,
    // bypassing our command queue. Source RecordingIC inherits the interface's forwarding.
    @RequiresApi(33)
    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
        commitText(text, newCursorPosition)
    @RequiresApi(33)
    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
        setComposingText(text, newCursorPosition)
    @RequiresApi(33)
    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?) =
        setComposingRegion(start, end)
    @RequiresApi(34)
    override fun replaceText(start: Int, end: Int, text: CharSequence,
                             newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        // Exact Android InputConnection default; these are API argument checks, not draft filters.
        require(start >= 0 && end >= 0)
        beginBatchEdit()
        finishComposingText()
        setSelection(start, end)
        commitText(text, newCursorPosition, textAttribute)
        endBatchEdit()
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
        state.currentFieldValue().getTextBeforeSelection(n).toString()
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
        state.currentFieldValue().getTextAfterSelection(n).toString()
    override fun getSelectedText(flags: Int): CharSequence? = state.currentFieldValue().let {
        if (it.selection.collapsed) null else it.getSelectedText().toString()
    }
    override fun commitCompletion(text: CompletionInfo?): Boolean = false
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = active && autoCorrect
    override fun performContextMenuAction(id: Int): Boolean = actions.performContextMenuAction(id)
    override fun performEditorAction(actionCode: Int): Boolean = actions.performEditorAction(actionCode)
    override fun sendKeyEvent(event: KeyEvent): Boolean =
        actions.sendKeyEvent { target.sendKeyEvent(event) }
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean =
        actions.performPrivateCommand()
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
        active && target.requestCursorUpdates(cursorUpdateMode)
    @RequiresApi(33)
    override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
        active && target.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter)

    override fun closeConnection() {
        if (!active) return
        active = false
        commands.clear() // Source discards an unfinished batch without applying it.
        depth = 0
        val preserved = state.currentFieldValue()
        var primary: Throwable? = null
        try { target.closeConnection() }
        catch (error: Throwable) { primary = error; throw error }
        finally {
            // Native Editor may clear composing spans on close; source RecordingIC does not.
            try { state.applyFieldValue(preserved) }
            catch (error: Throwable) {
                if (primary == null) throw error else primary.addSuppressed(error)
            }
        }
    }
}
