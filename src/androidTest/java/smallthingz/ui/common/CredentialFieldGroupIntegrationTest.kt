package smallthingz.ui.common

import android.content.Intent
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeLabeledTextField
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.reverb.ReverbDesign

@RunWith(AndroidJUnit4::class)
class CredentialFieldGroupIntegrationTest {
    private enum class Field { CONNECTOR, CLIENT }

    @Test fun equivalentBindsRetainFocusAndDoNotEchoEdits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val edits = mutableListOf<Pair<Field, String>>()
        val actions = mutableListOf<CredentialActionTarget<Field>>()
        val copy = CredentialActionTarget(Field.CLIENT, CredentialFieldAction.COPY)
        lateinit var group: CredentialFieldGroup<Field>
        val connector = CredentialFieldState(Field.CONNECTOR, "sample-connector")
        val hiddenClient = CredentialFieldState(Field.CLIENT, "••••", placeholder = "Sample token")
        try {
            instrumentation.runOnMainSync {
                group = CredentialFieldGroup(
                    activity, ReverbDesign.theme(activity, dark = false).controls,
                    listOf(
                        CredentialFieldSpec(Field.CONNECTOR, "Connector", maxInputLength = 17),
                        CredentialFieldSpec(Field.CLIENT, "Client", editable = false),
                    ),
                    actions = setOf(copy),
                    onAction = { actions += it },
                ) { field, value -> edits += field to value }
                group.bind(listOf(connector, hiddenClient), listOf(CredentialActionState(copy, "Copy")))
                activity.setContentView(group.view)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val descendants = descendants(group.view).toList()
                val fields = descendants.filterIsInstance<NativeLabeledTextField>()
                val copyButton = descendants.filterIsInstance<NativeMaterialButtonView>().single()
                assertTrue(copyButton.performClick())
                assertEquals(listOf(copy), actions)
                val editor = fields.first().editor
                val client = fields.last().editor
                assertTrue(editor.requestFocus())
                editor.setSelection(1, 4)
                val editable = editor.text
                group.bind(listOf(hiddenClient, connector))
                group.bindTheme(ReverbDesign.theme(activity, dark = true).controls)
                assertSame(editable, editor.text)
                assertTrue(editor.hasFocus())
                assertEquals(1, editor.selectionStart)
                assertEquals(4, editor.selectionEnd)
                assertTrue(editor.transformationMethod is PasswordTransformationMethod)
                assertNull(client.keyListener)
                assertFalse(client.isCursorVisible)
                val clientNode = checkNotNull(client.createAccessibilityNodeInfo())
                try {
                    assertFalse(clientNode.isEditable)
                } finally {
                    clientNode.recycle()
                }
                assertFalse(copyButton.performClick())
                assertTrue(edits.isEmpty())

                group.bind(listOf(CredentialFieldState(Field.CONNECTOR, connector.value, revealed = true), hiddenClient))
                assertNull(editor.transformationMethod)
                assertTrue(editor.hasFocus())
                assertEquals(1, editor.selectionStart)
                assertEquals(4, editor.selectionEnd)
                assertTrue(edits.isEmpty())
                checkNotNull(editor.text).replace(1, 4, "TEST")
                assertEquals(listOf(Field.CONNECTOR to "sTESTle-connector"), edits)

                editor.setSelection(1, 4)
                val legacy = CredentialFieldState(Field.CONNECTOR, "sample-connector-legacy", revealed = true)
                group.bind(listOf(legacy, hiddenClient))
                assertTrue(legacy.value.contentEquals(editor.text))
                assertTrue(editor.hasFocus())
                assertEquals(1, editor.selectionStart)
                assertEquals(4, editor.selectionEnd)
                assertEquals(1, edits.size)
                val current = checkNotNull(editor.text)
                current.delete(connector.value.length, current.length)
                current.append("123")
                assertEquals("sample-connector1", current.toString())
                assertEquals(Field.CONNECTOR to "sample-connector1", edits.last())

                group.bind(listOf(CredentialFieldState(Field.CONNECTOR, "", enabled = false, error = "Sample error"), hiddenClient))
                assertFalse(editor.isEnabled)
                val editorNode = checkNotNull(editor.createAccessibilityNodeInfo())
                try {
                    assertNotNull(editorNode.error)
                } finally {
                    editorNode.recycle()
                }
                assertEquals(3, edits.size)
                group.close()
                group.close()
                group.bind(listOf(connector, hiddenClient))
                assertTrue(editor.text.isNullOrEmpty())
                assertTrue(client.text.isNullOrEmpty())
                assertEquals(View.GONE, group.view.visibility)
                assertFalse(copyButton.performClick())
                assertEquals(listOf(copy), actions)
                assertEquals(3, edits.size)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun rejectsDuplicateFieldsAndAmbiguousUpdates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val theme = ReverbDesign.theme(context, dark = false).controls
            val field = CredentialFieldSpec(Field.CONNECTOR, "Connector")
            assertTrue(runCatching {
                CredentialFieldGroup(context, theme, listOf(field, field)) { _, _ -> }
            }.exceptionOrNull() is IllegalArgumentException)
            val group = CredentialFieldGroup(context, theme, listOf(field)) { _, _ -> }
            val state = CredentialFieldState(Field.CONNECTOR, "fixture")
            try {
                assertTrue(runCatching { group.bind(listOf(state, state)) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { group.bind(listOf(CredentialFieldState(Field.CLIENT, "fixture"))) }
                    .exceptionOrNull() is IllegalArgumentException)
            } finally {
                group.close()
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
