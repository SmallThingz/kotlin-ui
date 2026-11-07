package smallthingz.ui.components.input

import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.text.TextRange
import smallthingz.ui.text.input.TextFieldValue

/** Actual Android Editable and spans; direct connection calls do not claim real-keyboard parity. */
@RunWith(AndroidJUnit4::class)
class NativeEditableStateIntegrationTest {
    private fun main(test: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(test)

    private class Editor {
        val view = EditText(InstrumentationRegistry.getInstrumentation().targetContext)
        var owner = "12"
        val callbacks = mutableListOf<String>()
        var onChange: (String) -> Unit = { owner = it }
        var applied: () -> Unit = {}
        val state = NativeEditableState(view) { applied() }
        var closeFailure: Throwable? = null
        var batchFailureAt: String? = null
        var onBatchBegin: () -> Unit = {}
        var onBatchEnd: () -> Unit = {}
        val batchFailure = IllegalStateException("target batch")
        val cursorRequests = mutableListOf<Pair<Int, Int?>>()
        val target = object : BaseInputConnection(view, true) {
            override fun getEditable(): Editable = checkNotNull(view.editableText)
            override fun beginBatchEdit(): Boolean {
                if (batchFailureAt == "begin") throw batchFailure
                onBatchBegin()
                return true
            }
            override fun endBatchEdit(): Boolean {
                if (batchFailureAt == "end") throw batchFailure
                onBatchEnd()
                return true
            }
            override fun closeConnection() {
                removeComposingSpans(view.editableText)
                closeFailure?.let { throw it }
            }
            override fun requestCursorUpdates(mode: Int): Boolean {
                cursorRequests += mode to null
                return true
            }
            override fun requestCursorUpdates(mode: Int, filter: Int): Boolean {
                cursorRequests += mode to filter
                return true
            }
        }
        val actions = mutableListOf<Int>()
        val connection = NativeQueuedInputConnection(target, state, autoCorrect = true, onEditorAction = actions::add)
        init {
            view.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = state.afterTextChanged()
            })
            state.bindCallbacks({ owner }) { callbacks += it; onChange(it) }
            state.acceptOwnerText(owner)
        }
    }

    @Test fun rawDraftsAndOwnerRejectionRetainCallbackAndCompositionContract() = main {
        val h = Editor()
        assertTrue(h.connection.setSelection(2, 2))
        assertTrue(h.callbacks.isEmpty())
        assertTrue(h.connection.commitText("α\n-.😀", 1))
        assertEquals("12α\n-.😀", h.owner)
        assertEquals(listOf(h.owner), h.callbacks)
        h.onChange = {}
        val accepted = h.owner
        assertTrue(h.connection.commitText("rejected", 1))
        assertEquals(accepted, h.view.text.toString())
        val callbackCount = h.callbacks.size
        assertTrue(h.connection.commitText("rejected", 1))
        assertEquals(callbackCount, h.callbacks.size) // Same rejected String is not a new owner value.
        assertTrue(h.connection.setComposingRegion(0, 1))
        h.owner = "8"
        h.state.acceptOwnerText(h.owner)
        assertEquals(TextRange(1), h.state.currentFieldValue().selection)
        assertNull(h.state.currentFieldValue().composition)
        h.connection.closeConnection()
    }

    @Test fun nestedBatchPublishesOneFinalValueAndCloseDiscardsPendingCommands() = main {
        val h = Editor()
        assertTrue(h.connection.beginBatchEdit())
        assertTrue(h.connection.beginBatchEdit())
        h.connection.setSelection(2, 2)
        h.connection.commitText("x", 1)
        h.connection.commitText("y", 1)
        assertTrue(h.connection.endBatchEdit())
        assertEquals("12", h.view.text.toString())
        assertTrue(h.callbacks.isEmpty())
        assertFalse(h.connection.endBatchEdit())
        assertEquals("12xy", h.owner)
        assertEquals(listOf("12xy"), h.callbacks)
        h.connection.beginBatchEdit()
        h.connection.commitText("discard", 1)
        h.connection.closeConnection()
        assertEquals("12xy", h.view.text.toString())
        assertFalse(h.connection.commitText("closed", 1))
        assertFalse(h.connection.beginBatchEdit())
        assertFalse(h.connection.requestCursorUpdates(1))
        assertTrue(h.cursorRequests.isEmpty())
    }

    @Test fun nativeTransactionsAndReentrantOwnerBindingPublishOnlyCompletedText() = main {
        val h = Editor()
        h.onChange = { h.owner = "accepted"; h.state.acceptOwnerText(h.owner) }
        h.state.nativeEdit {
            h.view.append("a")
            h.state.nativeEdit { h.view.append("b") }
            assertTrue(h.callbacks.isEmpty())
        }
        assertEquals(listOf("12ab"), h.callbacks)
        assertEquals("accepted", h.view.text.toString())
        h.state.releaseCallbacks()
        h.state.nativeEdit { h.view.append("c") }
        assertEquals(listOf("12ab"), h.callbacks)
        assertEquals("acceptedc", h.view.text.toString())
        h.connection.closeConnection()
    }

    @Test fun codePointDeletionAndReversedSelectionUseActualEditableState() = main {
        val h = Editor()
        h.owner = "a😀b"
        h.state.acceptOwnerText(h.owner)
        h.connection.setSelection(3, 3)
        h.connection.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals("ab", h.owner)
        h.connection.setSelection(2, 0)
        assertEquals("ab", h.connection.getSelectedText(0).toString())
        h.connection.commitText("z", 1)
        assertEquals("z", h.owner)
        assertEquals(TextRange(1), h.state.currentFieldValue().selection)
        h.connection.closeConnection()
    }

    @Test fun closePreservesCompositionAndSuppressionOrder() = main {
        val h = Editor()
        val value = TextFieldValue("draft", TextRange(2), TextRange(1, 4))
        h.state.applyFieldValue(value)
        val targetFailure = IllegalStateException("target close")
        val restoreFailure = IllegalStateException("post-apply hook")
        h.closeFailure = targetFailure
        h.applied = { throw restoreFailure }
        val failure = runCatching { h.connection.closeConnection() }.exceptionOrNull()
        assertSame(targetFailure, failure)
        assertEquals(listOf(restoreFailure), targetFailure.suppressed.toList())
        assertEquals(value, h.state.currentFieldValue())
        h.connection.closeConnection() // Idempotent, even after the first close throws.
    }

    @Test fun newerTextOverloadsJoinTheSameBatchAndDefaultCursorRouteStaysDistinct() = main {
        if (Build.VERSION.SDK_INT < 33) return@main
        val h = Editor()
        h.connection.beginBatchEdit()
        h.connection.setSelection(2, 2)
        h.connection.commitText("x", 1, null)
        h.connection.setComposingText("y", 1, null)
        assertEquals("12", h.view.text.toString())
        assertTrue(h.callbacks.isEmpty())
        h.connection.endBatchEdit()
        assertEquals("12xy", h.owner)
        assertEquals(TextRange(3, 4), h.state.currentFieldValue().composition)
        h.connection.requestCursorUpdates(1, 4)
        assertEquals(listOf(1 to 4), h.cursorRequests)
        if (Build.VERSION.SDK_INT >= 34) {
            h.connection.replaceText(0, 2, "A", 1, null)
            assertEquals("Axy", h.owner)
            assertNull(h.state.currentFieldValue().composition)
        }
        h.connection.closeConnection()
    }
    @Test fun sameConnectionCallbackCommitDoesNotReplayTheOuterCommand() = main {
        val h = Editor()
        h.owner = ""
        h.state.acceptOwnerText(h.owner)
        h.onChange = {
            h.owner = it
            if (it == "a") assertTrue(h.connection.commitText("b", 1))
        }
        assertTrue(h.connection.commitText("a", 1))
        assertEquals("ab", h.view.text.toString())
        assertEquals("ab", h.owner)
        assertEquals(listOf("a", "ab"), h.callbacks)
        h.connection.closeConnection()
    }

    @Test fun callbackBatchKeepsItsCommandsWhetherItEndsInsideOrAfterPublication() = main {
        for (endInside in listOf(false, true)) {
            val h = Editor()
            h.owner = ""
            h.state.acceptOwnerText(h.owner)
            h.onChange = {
                h.owner = it
                if (it == "a") {
                    assertTrue(h.connection.beginBatchEdit())
                    assertTrue(h.connection.commitText("b", 1))
                    if (endInside) assertFalse(h.connection.endBatchEdit())
                }
            }
            h.connection.commitText("a", 1)
            if (!endInside) {
                assertEquals("a", h.owner)
                assertEquals(listOf("a"), h.callbacks)
                assertFalse(h.connection.endBatchEdit())
            }
            assertEquals("ab", h.view.text.toString())
            assertEquals(listOf("a", "ab"), h.callbacks)
            h.connection.closeConnection()
        }
    }

    @Test fun callbackFailureRetainsUncompletedPrefixAndPendingNestedBatch() = main {
        val h = Editor()
        h.owner = ""
        h.state.acceptOwnerText(h.owner)
        val failure = IllegalStateException("owner callback")
        h.onChange = {
            h.owner = it
            h.connection.beginBatchEdit()
            h.connection.commitText("b", 1)
            throw failure
        }
        assertSame(failure, runCatching { h.connection.commitText("a", 1) }.exceptionOrNull())
        assertEquals("a", h.view.text.toString())
        h.onChange = { h.owner = it }
        assertFalse(h.connection.endBatchEdit())
        // Existing failure semantics are not a rollback: the uncompleted a prefix is retained.
        assertEquals("aab", h.view.text.toString())
        assertEquals(listOf("a", "aab"), h.callbacks)
        h.connection.closeConnection()
    }

    @Test fun closingFromCallbackRevokesThePendingTailAndTheOuterPrefix() = main {
        val h = Editor()
        h.onChange = {
            h.owner = it
            h.connection.beginBatchEdit()
            h.connection.commitText("discard", 1)
            h.connection.closeConnection()
        }
        h.connection.setSelection(2, 2)
        h.connection.commitText("a", 1)
        assertEquals("12a", h.view.text.toString())
        assertEquals(listOf("12a"), h.callbacks)
        assertFalse(h.connection.commitText("closed", 1))
    }

    @Test fun targetBatchFailureKeepsThePriorNontransactionalRetryContract() = main {
        for ((phase, afterFailure, afterRetry) in listOf(
            Triple("begin", "12", "12ab"), Triple("end", "12a", "12aab"),
        )) {
            val h = Editor()
            h.connection.setSelection(2, 2)
            h.batchFailureAt = phase
            assertSame(h.batchFailure, runCatching { h.connection.commitText("a", 1) }.exceptionOrNull())
            assertEquals(afterFailure, h.view.text.toString())
            h.batchFailureAt = null
            assertTrue(h.connection.commitText("b", 1))
            assertEquals(afterRetry, h.view.text.toString())
            h.connection.closeConnection()
        }
    }

    @Test fun targetBatchCallbacksAdmitCommandsWithoutReenteringTheCurrentSnapshot() = main {
        for (phase in listOf("begin", "end")) {
            val h = Editor()
            h.owner = ""
            h.state.acceptOwnerText(h.owner)
            var admitted = false
            val enqueue = {
                if (!admitted) {
                    admitted = true
                    assertTrue(h.connection.commitText("b", 1))
                }
            }
            if (phase == "begin") h.onBatchBegin = enqueue else h.onBatchEnd = enqueue
            assertTrue(h.connection.commitText("a", 1))
            assertEquals("ab", h.view.text.toString())
            assertEquals(listOf("a", "ab"), h.callbacks)
            h.connection.closeConnection()
        }
    }

    @Test fun closeFromTargetBeginSkipsTheSnapshotButStillEndsTheTargetBatch() = main {
        val h = Editor()
        h.connection.setSelection(2, 2)
        var ended = false
        h.onBatchBegin = { h.connection.closeConnection() }
        h.onBatchEnd = { ended = true }
        assertTrue(h.connection.commitText("a", 1))
        assertTrue(ended)
        assertEquals("12", h.view.text.toString())
        assertEquals("12", h.owner)
        assertTrue(h.callbacks.isEmpty())
        assertFalse(h.connection.commitText("closed", 1))
    }

    @Test fun closeAfterApplySkipsPublicationWithoutRollingBackTheEditable() = main {
        val h = Editor()
        h.connection.setSelection(2, 2)
        var ended = false
        h.applied = { h.connection.closeConnection() }
        h.onBatchEnd = { ended = true }
        assertTrue(h.connection.commitText("a", 1))
        assertTrue(ended)
        assertEquals("12a", h.view.text.toString())
        assertEquals("12", h.owner)
        assertTrue(h.callbacks.isEmpty())
        assertFalse(h.connection.commitText("closed", 1))
    }

    @Test fun unmatchedBatchEndDoesNotStrandNormalOrNestedCommands() = main {
        val h = Editor()
        h.connection.setSelection(2, 2)
        assertFalse(h.connection.endBatchEdit())
        assertFalse(h.connection.endBatchEdit())
        assertTrue(h.connection.commitText("a", 1))
        assertEquals("12a", h.view.text.toString())
        assertEquals(listOf("12a"), h.callbacks)
        assertTrue(h.connection.beginBatchEdit())
        assertTrue(h.connection.beginBatchEdit())
        assertTrue(h.connection.commitText("b", 1))
        assertTrue(h.connection.endBatchEdit())
        assertTrue(h.connection.commitText("c", 1))
        assertEquals("12a", h.view.text.toString())
        assertFalse(h.connection.endBatchEdit())
        assertEquals("12abc", h.owner)
        assertEquals(listOf("12a", "12abc"), h.callbacks)
        assertFalse(h.connection.endBatchEdit())
        assertTrue(h.connection.commitText("d", 1))
        assertEquals("12abcd", h.view.text.toString())
        h.connection.closeConnection()
    }

    @Test fun unmatchedEndDuringPublicationDoesNotStrandTheReentrantTail() = main {
        val h = Editor()
        h.owner = ""
        h.state.acceptOwnerText(h.owner)
        h.onChange = {
            h.owner = it
            if (it == "a") {
                assertFalse(h.connection.endBatchEdit())
                assertTrue(h.connection.commitText("b", 1))
            }
        }
        assertTrue(h.connection.commitText("a", 1))
        assertEquals("ab", h.view.text.toString())
        assertEquals(listOf("a", "ab"), h.callbacks)
        h.connection.closeConnection()
    }

    @Test fun lateBatchEndsAfterCloseAreFalseAndInert() = main {
        val h = Editor()
        h.connection.beginBatchEdit()
        h.connection.commitText("discard", 1)
        h.connection.closeConnection()
        h.onBatchBegin = { error("Closed connection touched target begin") }
        h.onBatchEnd = { error("Closed connection touched target end") }
        h.applied = { error("Closed connection changed Editable state") }
        assertFalse(h.connection.endBatchEdit())
        assertFalse(h.connection.endBatchEdit())
        assertFalse(h.connection.beginBatchEdit())
        assertFalse(h.connection.commitText("closed", 1))
        assertEquals("12", h.view.text.toString())
        assertEquals("12", h.owner)
        assertTrue(h.callbacks.isEmpty())
    }

}
