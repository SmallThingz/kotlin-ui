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
package smallthingz.ui.common

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.components.NativeCaretEditText
import smallthingz.ui.components.input.NativeEditableState

/** Shared native Editor mechanics; each leaf retains its initialization and presentation state. */
abstract class NativeEditableInputView(
    context: Context,
    cursorThicknessPx: Float,
    cursorColor: Int,
) : NativeCaretEditText(context, cursorThicknessPx, cursorColor) {
    protected abstract val initialized: Boolean
    protected abstract val editing: NativeEditableState

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (nativeSurfaceInputAllowed(this)) super.createAccessibilityNodeInfo() else null

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!nativeSurfaceInputAllowed(this) && action != AccessibilityNodeInfo.ACTION_CLEAR_FOCUS &&
            action != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) return false
        return super.performAccessibilityAction(action, arguments)
    }

    /** Called by the leaf after its typography, at the original selection/watcher publication point. */
    protected fun observeNativeEdits() {
        Selection.setSelection(checkNotNull(editableText), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                if (initialized) editing.afterTextChanged()
            }
        })
    }

    override fun scrollTo(x: Int, y: Int) {
        // Both released single-line fields place unrestricted raw text at (horizontalOffset, 0).
        // Native cursor-follow must not introduce a vertical scrolling axis for pasted newlines.
        super.scrollTo(x, 0)
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        // RecordingInputConnection/BasicTextField(String) preserves raw characters, not rich spans.
        editing.nativeEdit { super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        editing.nativeEdit { super.onKeyDown(keyCode, event) }
}
