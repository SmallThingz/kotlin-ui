package smallthingz.ui.email

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeGrainPreset
import smallthingz.ui.components.withLaidOutPaintActivity
import smallthingz.ui.controls.textSurfaceRange

@RunWith(AndroidJUnit4::class)
class EmailComposerFieldsIntegrationTest {
    @Test fun themeRebindingUpdatesGroupPaintAndRetainsSubjectDraftFocusSelectionAndRecipientAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var composer: EmailComposerFields
        lateinit var theme: ControlTheme
        lateinit var recipient: View
        var actions = 0
        val flat = NativeColorScheme.fallback(false).copy(surfaceContainerHigh = Color.BLACK)
        withLaidOutPaintActivity(mount = { activity ->
            theme = ControlTheme.resolve(activity, false).withColorScheme(flat)
            composer = EmailComposerFields(activity, theme, "Subject")
            composer.subjectEditor.showSoftInputOnFocus = false
            composer.subjectEditor.setText("Retained subject draft")
            recipient = View(activity).apply { setOnClickListener { actions++ } }
            composer.addRecipientRow(recipient, LinearLayout.LayoutParams(-1, 48))
            assertEquals(textSurfaceRange(Color.BLACK), composer.view.paintedTextSurface)
            listOf(composer.view, composer.subjectEditor, recipient)
        }) {
            instrumentation.runOnMainSync {
                val group = composer.view
                val editor = composer.subjectEditor
                val editorParent = editor.parent
                val recipientParent = recipient.parent
                assertTrue(editor.requestFocus())
                editor.setSelection(2, 8)
                val draft = editor.text
                var edits = 0
                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { edits++ }
                    override fun afterTextChanged(s: Editable?) = Unit
                }
                editor.addTextChangedListener(watcher)
                try {
                    val half = flat.copy(surfacePreset = NativeGrainPreset(0f, .5f))
                    val states = listOf(
                        half to textSurfaceRange(Color.BLACK, 0xff090909.toInt()),
                        half.copy(surfaceContainerHigh = Color.WHITE) to textSurfaceRange(0xfff6f6f6.toInt(), Color.WHITE),
                        flat to textSurfaceRange(Color.BLACK),
                    )
                    for ((colors, expectedRange) in states) {
                        composer.bindTheme(theme.withColorScheme(colors))
                        assertEquals(colors.surfaceContainerHigh, group.paintedFillColor)
                        assertEquals(expectedRange, group.paintedTextSurface)
                        assertSame(group, composer.view)
                        assertSame(editor, composer.subjectEditor)
                        assertSame(editorParent, editor.parent)
                        assertSame(recipientParent, recipient.parent)
                        assertSame(draft, editor.text)
                        assertEquals("Retained subject draft", editor.text.toString())
                        assertEquals(2, editor.selectionStart)
                        assertEquals(8, editor.selectionEnd)
                        assertTrue(editor.hasFocus())
                    }
                    assertEquals(0, edits)
                    assertTrue(recipient.performClick())
                    assertEquals(1, actions)
                } finally {
                    editor.removeTextChangedListener(watcher)
                }
            }
        }
    }
}
