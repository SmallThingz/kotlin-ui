package smallthingz.ui.common

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.SparseArray
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeInputGate
import smallthingz.ui.components.NativeLabeledTextField
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbStandardIconButtonView
import smallthingz.ui.text.view.ParagraphView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CredentialFieldGroupIntegrationTest {
    private enum class Field { CONNECTOR, CLIENT }

    @Test fun readOnlyAccessibilityRejectsSetTextWhileEditableSiblingStillAcceptsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        var group: CredentialFieldGroup<Field>? = null
        val edits = mutableListOf<Pair<Field, String>>()
        try {
            instrumentation.runOnMainSync {
                group = CredentialFieldGroup(activity, ReverbDesign.theme(activity, false).controls,
                    listOf(CredentialFieldSpec(Field.CONNECTOR, "Connector"),
                        CredentialFieldSpec(Field.CLIENT, "Client", editable = false))) { id, value -> edits += id to value }
                val installed = requireNotNull(group)
                installed.bind(listOf(CredentialFieldState(Field.CONNECTOR, "editable fixture"),
                    CredentialFieldState(Field.CLIENT, "read-only fixture")))
                descendants(installed.view).filterIsInstance<NativeLabeledTextField>().forEach {
                    it.editor.showSoftInputOnFocus = false
                }
                activity.setContentView(installed.view)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val installed = requireNotNull(group)
                val editors = descendants(installed.view).filterIsInstance<NativeLabeledTextField>().map { it.editor }.toList()
                val editable = editors.first()
                val readOnly = editors.last()
                assertTrue(editable.requestFocus())
                editable.setSelection(1, 4)
                val retained = readOnly.text
                val replacement = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "changed fixture")
                }
                repeat(2) {
                    assertFalse(readOnly.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, replacement))
                    assertSame(retained, readOnly.text)
                    assertEquals("read-only fixture", readOnly.text.toString())
                    assertFalse(readOnly.hasFocus())
                    assertTrue(editable.hasFocus())
                    assertEquals(1, editable.selectionStart)
                    assertEquals(4, editable.selectionEnd)
                    assertTrue(edits.isEmpty())
                    installed.bindTheme(ReverbDesign.theme(activity, true).controls)
                }
                val editableNode = requireNotNull(editable.createAccessibilityNodeInfo())
                try {
                    assertTrue(editableNode.isEditable)
                    assertTrue(editableNode.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT))
                } finally { editableNode.recycle() }
                assertTrue(editable.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, replacement))
                assertEquals("changed fixture", editable.text.toString())
                assertEquals(listOf(Field.CONNECTOR to "changed fixture"), edits)
                installed.close()
                assertFalse(readOnly.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, replacement))
                assertTrue(readOnly.text.isNullOrEmpty())
                assertEquals(1, edits.size)
            }
        } finally {
            instrumentation.runOnMainSync { try { group?.close() } finally { activity.finish() } }
        }
    }

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
        var created = false
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
                created = true
                group.bind(listOf(connector, hiddenClient), listOf(CredentialActionState(copy, "Copy")))
                activity.setContentView(group.view)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val descendants = descendants(group.view).toList()
                val fields = descendants.filterIsInstance<NativeLabeledTextField>()
                val copyButton = descendants.filterIsInstance<ReverbStandardIconButtonView>().single()
                assertTrue(copyButton.visual.performClick())
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
                assertFalse(editor.isSaveEnabled)
                val savedState = SparseArray<Parcelable>()
                group.view.saveHierarchyState(savedState)
                assertNull(savedState[editor.id])
                assertNull(savedState[client.id])
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, editor.importantForAutofill)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO, editor.importantForContentCapture)
                }
                assertTrue(editor.filterTouchesWhenObscured)
                assertTrue(editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
                assertTrue(editor.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
                assertNull(client.keyListener)
                assertFalse(client.isCursorVisible)
                val clientNode = checkNotNull(client.createAccessibilityNodeInfo())
                try {
                    assertFalse(clientNode.isEditable)
                    assertEquals(android.widget.TextView::class.java.name, clientNode.className)
                    assertTrue(clientNode.isPassword)
                    assertFalse(clientNode.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT))
                } finally {
                    clientNode.recycle()
                }
                val clientText = client.text
                assertFalse(client.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "unexpected edit")
                    }))
                assertSame(clientText, client.text)
                assertEquals(hiddenClient.value, client.text.toString())
                assertFalse(client.hasFocus())
                assertTrue(editor.hasFocus())
                copyButton.visual.performClick()
                assertEquals(listOf(copy), actions)
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
                copyButton.visual.performClick()
                assertEquals(listOf(copy), actions)
                assertEquals(3, edits.size)
            }
        } finally {
            instrumentation.runOnMainSync {
                try { if (created) group.close() } finally { activity.finish() }
            }
        }
    }

    @Test fun trailingActionsKeepTheirOwnAdmissionAndRouteOnlyByFieldId() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val targets = Field.values().flatMap { field ->
            CredentialFieldAction.values().map { CredentialActionTarget(field, it) }
        }
        val labels = mapOf(
            CredentialFieldAction.REVEAL to "Afficher",
            CredentialFieldAction.COPY to "Copier",
            CredentialFieldAction.REROLL to "Régénérer",
        )
        val states = listOf(
            CredentialFieldState(Field.CONNECTOR, "synthetic-connector", enabled = false),
            CredentialFieldState(Field.CLIENT, "synthetic-client", enabled = false),
        )
        val admitted = targets.map { CredentialActionState(it, labels.getValue(it.action)) }
        val calls = mutableListOf<CredentialActionTarget<Field>>()
        val edits = mutableListOf<Pair<Field, String>>()
        lateinit var group: CredentialFieldGroup<Field>
        var created = false
        try {
            instrumentation.runOnMainSync {
                group = CredentialFieldGroup(
                    activity, ReverbDesign.theme(activity, dark = false).controls,
                    listOf(
                        CredentialFieldSpec(Field.CONNECTOR, "Connector"),
                        CredentialFieldSpec(Field.CLIENT, "Client", editable = false),
                    ),
                    actions = targets.toSet(), onAction = { calls += it },
                ) { field, value -> edits += field to value }
                created = true
                group.bind(states, admitted)
                activity.setContentView(group.view)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(group.view is NativeGroupLayout)
                assertEquals(1, descendants(group.view).filterIsInstance<NativeGroupLayout>().count())
                val fields = descendants(group.view).filterIsInstance<NativeLabeledTextField>().toList()
                val controls = targets.associateWith { target ->
                    val field = fields[target.field.ordinal]
                    assertFalse(field.editor.isEnabled)
                    val row = field.trailingHost.getChildAt(0) as LinearLayout
                    assertEquals(LinearLayout.HORIZONTAL, row.orientation)
                    assertEquals(3, row.childCount)
                    row.getChildAt(target.action.ordinal)
                }
                assertNull(fields.last().editor.keyListener)
                assertFalse(fields.last().editor.isFocusable)
                for (target in targets) {
                    val control = controls.getValue(target)
                    val click = clickTarget(control)
                    assertTrue(control.isShown)
                    assertTrue(click.isEnabled)
                    assertTrue(click.isFocusable)
                    val node = checkNotNull(click.createAccessibilityNodeInfo())
                    try {
                        assertEquals(labels.getValue(target.action), node.contentDescription?.toString())
                        assertTrue(node.isEnabled)
                        assertTrue(node.isClickable)
                    } finally { node.recycle() }
                    assertTrue(control is ReverbStandardIconButtonView)
                    val icon = control as ReverbStandardIconButtonView
                    assertTrue(icon.filterTouchesWhenObscured)
                    assertTrue(icon.visual.filterTouchesWhenObscured)
                    assertNull(icon.background)
                    assertNull(icon.visual.background)
                    assertGlyph(icon, when (target.action) {
                        CredentialFieldAction.REVEAL -> ReverbIcon.Eye
                        CredentialFieldAction.COPY -> ReverbIcon.Copy
                        CredentialFieldAction.REROLL -> ReverbIcon.Reset
                    })
                    click.performClick()
                }
                assertEquals(targets, calls)
                assertTrue(edits.isEmpty())

                val disabled = setOf(
                    CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.REVEAL),
                    CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.COPY),
                    CredentialActionTarget(Field.CLIENT, CredentialFieldAction.REROLL),
                )
                group.bind(states.reversed(), admitted.map {
                    CredentialActionState(it.target, it.label, enabled = it.target !in disabled)
                })
                group.bindTheme(ReverbDesign.theme(activity, dark = true).controls)
                calls.clear()
                for (target in targets) {
                    val control = controls.getValue(target)
                    val click = clickTarget(control)
                    assertTrue(control.isShown)
                    assertEquals(target !in disabled, click.isEnabled)
                    assertEquals(target !in disabled, click.isFocusable)
                    click.performClick()
                    if (target in disabled) {
                        assertFalse(click.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    }
                }
                assertEquals(targets.filter { it !in disabled }, calls)

                group.bind(states)
                calls.clear()
                for (field in fields) {
                    assertEquals(View.GONE, field.trailingHost.visibility)
                    assertEquals(0, field.trailingHost.childCount)
                }
                for (control in controls.values) {
                    assertEquals(View.GONE, control.visibility)
                    clickTarget(control).performClick()
                    assertFalse(clickTarget(control).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                }
                assertTrue(calls.isEmpty())

                val clientCopy = CredentialActionTarget(Field.CLIENT, CredentialFieldAction.COPY)
                group.bind(states, listOf(CredentialActionState(clientCopy, "Copiar")))
                assertEquals(View.GONE, fields.first().trailingHost.visibility)
                val retainedCopy = controls.getValue(clientCopy)
                assertSame(retainedCopy, descendants(fields.last().trailingHost)
                    .filterIsInstance<ReverbStandardIconButtonView>().first { it.visibility == View.VISIBLE })
                assertTrue(clickTarget(retainedCopy).isEnabled)
                clickTarget(retainedCopy).performClick()
                assertEquals(listOf(clientCopy), calls)

                group.close()
                group.close()
                group.bind(states, admitted)
                group.bindTheme(ReverbDesign.theme(activity, dark = false).controls)
                for (control in controls.values) {
                    clickTarget(control).performClick()
                    assertFalse(clickTarget(control).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                }
                assertEquals(listOf(clientCopy), calls)
                assertTrue(fields.all { it.editor.text.isNullOrEmpty() })
                assertTrue(edits.isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync {
                try { if (created) group.close() } finally { activity.finish() }
            }
        }
    }

    @Test fun attachedTrailingActionsReflowWithoutGapsOrLosingEditorFocusInRtl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val targets = CredentialFieldAction.values().map { CredentialActionTarget(Field.CONNECTOR, it) }
        val admitted = targets.map { CredentialActionState(it, when (it.action) {
            CredentialFieldAction.REVEAL -> "Show"
            CredentialFieldAction.COPY -> "Copy"
            CredentialFieldAction.REROLL -> "Regenerate"
        }) }
        val state = CredentialFieldState(Field.CONNECTOR, "synthetic-credential")
        val edits = mutableListOf<Pair<Field, String>>()
        lateinit var group: CredentialFieldGroup<Field>
        var created = false
        val density = activity.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        try {
            instrumentation.runOnMainSync {
                group = CredentialFieldGroup(
                    activity, ReverbDesign.theme(activity, dark = false).controls,
                    listOf(CredentialFieldSpec(Field.CONNECTOR, "Connector")), actions = targets.toSet(),
                ) { field, value -> edits += field to value }
                created = true
                group.bind(listOf(state), admitted)
                activity.setContentView(FrameLayout(activity).apply {
                    isFocusableInTouchMode = true
                    addView(group.view, FrameLayout.LayoutParams(px(320), ViewGroup.LayoutParams.WRAP_CONTENT))
                })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val field = descendants(group.view).filterIsInstance<NativeLabeledTextField>().single()
                val row = field.trailingHost.getChildAt(0) as LinearLayout
                val controls = (0 until row.childCount).map { row.getChildAt(it) }
                val editor = field.editor
                assertTrue(editor.requestFocus())
                editor.setSelection(2, 7)
                val retainedText = editor.text
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    for (widthDp in listOf(320, 224, 320)) {
                        group.view.layoutDirection = direction
                        group.bind(listOf(state), admitted)
                        // Exercise actual attached Views with exact host constraints, without an IME resize race.
                        group.view.measure(View.MeasureSpec.makeMeasureSpec(px(widthDp), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        group.view.layout(0, 0, group.view.measuredWidth, group.view.measuredHeight)
                        assertTrue(group.view.isAttachedToWindow)
                        assertSame(row, field.trailingHost.getChildAt(0))
                        assertSame(retainedText, editor.text)
                        assertTrue(editor.hasFocus())
                        assertEquals(2, editor.selectionStart)
                        assertEquals(7, editor.selectionEnd)
                        assertEquals(direction, row.layoutDirection)
                        if (widthDp == 224) assertTrue(field.trailingHost.top >= editor.bottom)
                        else {
                            assertTrue(field.trailingHost.top < editor.bottom)
                            if (direction == View.LAYOUT_DIRECTION_RTL) assertEquals(field.trailingHost.right, editor.left)
                            else assertEquals(editor.right, field.trailingHost.left)
                        }
                        assertEquals(controls.sumOf { it.width }, row.width)
                        val inVisualOrder = controls.sortedBy { it.left }
                        assertEquals(0, inVisualOrder.first().left)
                        for ((first, second) in inVisualOrder.zipWithNext()) assertEquals(first.right, second.left)
                        assertEquals(row.width, inVisualOrder.last().right)
                        assertEquals(if (direction == View.LAYOUT_DIRECTION_RTL) controls.reversed() else controls, inVisualOrder)
                        for (icon in controls.filterIsInstance<ReverbStandardIconButtonView>()) {
                            assertEquals(px(48), icon.width)
                            assertEquals(px(48), icon.height)
                            assertEquals(px(40), icon.visual.width)
                            assertEquals(px(24), icon.visual.glyph.width)
                            assertTrue(icon.left >= 0 && icon.right <= row.width)
                        }
                        val reveal = controls.first() as ReverbStandardIconButtonView
                        assertEquals(px(48), reveal.height)
                        assertEquals(px(40), reveal.visual.height)
                        assertTrue(reveal.width >= px(48))
                    }
                }
                group.bind(listOf(state), admitted.map {
                    CredentialActionState(it.target, if (it.target.action == CredentialFieldAction.REVEAL)
                        "Afficher les caractères du mot de passe" else it.label)
                })
                group.view.measure(View.MeasureSpec.makeMeasureSpec(px(224), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                group.view.layout(0, 0, group.view.measuredWidth, group.view.measuredHeight)
                assertTrue(row.width <= field.width)
                assertTrue(field.trailingHost.top >= editor.bottom)
                for (icon in controls.filterIsInstance<ReverbStandardIconButtonView>()) assertEquals(px(48), icon.width)
                group.bind(listOf(CredentialFieldState(Field.CONNECTOR, state.value, revealed = true)),
                    admitted.map { CredentialActionState(it.target, if (it.target.action == CredentialFieldAction.REVEAL) "Hide" else it.label) })
                assertNull(editor.transformationMethod)
                assertEquals("Hide", (controls.first() as ReverbStandardIconButtonView).visual.glyph.contentDescription)
                assertGlyph(controls.first() as ReverbStandardIconButtonView, ReverbIcon.EyeOff)
                assertTrue(editor.hasFocus())
                assertEquals(2, editor.selectionStart)
                assertEquals(7, editor.selectionEnd)
                assertTrue(edits.isEmpty())
                group.dismissKeyboard()
                assertFalse(editor.hasFocus())
            }
        } finally {
            instrumentation.runOnMainSync {
                try { if (created) group.close() } finally { activity.finish() }
            }
        }
    }

    @Test fun rootDispatchRoutesRevealMarginsWithoutEditingAcrossResizeRtlAndHiddenActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val reveal = CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.REVEAL)
        val copy = CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.COPY)
        val reroll = CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.REROLL)
        val admitted = listOf(CredentialActionState(reveal, "Show"), CredentialActionState(copy, "Copy"),
            CredentialActionState(reroll, "Regenerate"))
        val state = CredentialFieldState(Field.CONNECTOR, "synthetic-credential")
        val calls = mutableListOf<CredentialActionTarget<Field>>()
        val editorEvents = mutableListOf<Int>()
        val edits = mutableListOf<Pair<Field, String>>()
        var group: CredentialFieldGroup<Field>? = null
        lateinit var host: AdmissionHost
        lateinit var field: NativeLabeledTextField
        lateinit var row: LinearLayout
        lateinit var button: View
        lateinit var retainedText: Editable
        var wide = 0
        var narrow = 0
        val density = activity.resources.displayMetrics.density
        fun px(value: Int) = (value * density).roundToInt()
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        try {
            main {
                val installed = CredentialFieldGroup(activity, ReverbDesign.theme(activity, false).controls,
                    listOf(CredentialFieldSpec(Field.CONNECTOR, "Connector")), setOf(reveal, copy, reroll),
                    onAction = { calls += it }) { id, value -> edits += id to value }
                group = installed
                installed.bind(listOf(state), admitted)
                field = descendants(installed.view).filterIsInstance<NativeLabeledTextField>().single()
                row = field.trailingHost.getChildAt(0) as LinearLayout
                button = (row.getChildAt(0) as ReverbStandardIconButtonView).visual
                field.editor.showSoftInputOnFocus = false
                // Record routing without opening an IME or letting a misrouted tap edit the fixture.
                field.editor.setOnTouchListener { _, event -> editorEvents += event.actionMasked; true }
                host = AdmissionHost(activity).apply {
                    isFocusableInTouchMode = true
                    addView(installed.view, FrameLayout.LayoutParams(-1, -2))
                }
                activity.setContentView(host)
            }
            awaitDraw(host)
            main {
                // Stay within the actual owned window; do not inject coordinates outside its root.
                wide = minOf(host.width, px(320))
                assertTrue("Fixture needs room for three 48dp actions and field insets", wide >= px(224))
                narrow = minOf(px(224), wide - px(16))
                assertTrue(field.editor.requestFocus())
                field.editor.setSelection(2, 7)
                retainedText = field.editor.text
            }
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                for (width in listOf(wide, narrow, wide)) {
                    main {
                        host.layoutDirection = direction
                        requireNotNull(group).view.layoutParams = FrameLayout.LayoutParams(width, -2)
                        requireNotNull(group).bind(listOf(state), admitted)
                    }
                    awaitDraw(host)
                    main {
                        assertTrue(button.isShown && button.hasWindowFocus())
                        assertEquals(px(40), button.height)
                        assertEquals(px(48), row.height)
                        val buttonBounds = boundsIn(host, button)
                        val rowBounds = boundsIn(host, row)
                        val x = buttonBounds.exactCenterX()
                        val stripYs = listOf(rowBounds.top + .5f, rowBounds.bottom - .5f)
                        assertTrue(stripYs.first() < buttonBounds.top)
                        assertTrue(stripYs.last() >= buttonBounds.bottom)
                        for (y in stripYs) {
                            assertTrue(x >= 0 && x < host.width && y >= 0 && y < host.height)
                            calls.clear(); editorEvents.clear()
                            assertTrue(rootTap(host, x, y))
                            // With the unwrapped 40dp button these root-dispatched taps reach the
                            // field's editor delegate instead, despite direct performClick passing.
                            assertEquals("Reveal strip must not reach the editor", emptyList<Int>(), editorEvents)
                            assertEquals(listOf(reveal), calls)
                        }
                        assertSame(retainedText, field.editor.text)
                        assertTrue(field.editor.hasFocus())
                        assertEquals(2, field.editor.selectionStart)
                        assertEquals(7, field.editor.selectionEnd)
                        val target = button.parent as ReverbStandardIconButtonView
                        assertEquals(px(48), target.height)
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, target.importantForAccessibility)
                        assertRevealAccessibilityChild(target, button)
                        assertEquals("Show", button.createAccessibilityNodeInfo()?.contentDescription?.toString())
                        val icons = descendants(row).filterIsInstance<ReverbStandardIconButtonView>().drop(1).toList()
                        for ((icon, expected) in icons.zip(listOf(copy, reroll))) {
                            calls.clear(); editorEvents.clear()
                            val bounds = boundsIn(host, icon.visual)
                            assertTrue(rootTap(host, bounds.exactCenterX(), bounds.exactCenterY()))
                            assertEquals(listOf(expected), calls)
                            assertTrue(editorEvents.isEmpty())
                        }
                    }
                }
            }
            main {
                requireNotNull(group).bind(listOf(state), admitted.map {
                    CredentialActionState(it.target, it.label, enabled = it.target != reveal)
                })
            }
            awaitDraw(host)
            main {
                val bounds = boundsIn(host, button.parent as ReverbStandardIconButtonView)
                calls.clear(); editorEvents.clear()
                assertTrue(rootTap(host, bounds.exactCenterX(), bounds.top + .5f))
                assertTrue("Disabled Reveal consumes its target without invoking an action", calls.isEmpty())
                assertTrue("Disabled Reveal must not fall through to the editor", editorEvents.isEmpty())
                requireNotNull(group).bind(listOf(state), admitted)
            }
            awaitDraw(host)
            lateinit var target: ReverbStandardIconButtonView
            var downTime = 0L
            var pendingX = 0f
            var pendingY = 0f
            main {
                target = button.parent as ReverbStandardIconButtonView
                val bounds = boundsIn(host, target)
                pendingX = bounds.exactCenterX(); pendingY = bounds.top + .5f
                downTime = SystemClock.uptimeMillis()
                calls.clear(); editorEvents.clear()
                assertTrue(dispatchFinger(host, MotionEvent.ACTION_DOWN, pendingX, pendingY, downTime))
                requireNotNull(group).bind(listOf(state), listOf(CredentialActionState(copy, "Copy")))
            }
            awaitDraw(host)
            main {
                dispatchFinger(host, MotionEvent.ACTION_UP, pendingX, pendingY, downTime)
                assertTrue("Hiding Reveal retires the pending gesture", calls.isEmpty())
                assertEquals(View.GONE, target.visibility)
                assertFalse(button.isShown)
                assertEquals(px(48), row.width)
                assertSame(row, field.trailingHost.getChildAt(0))
                assertEquals(1, (0 until row.childCount).count { row.getChildAt(it).visibility == View.VISIBLE })
                requireNotNull(group).bind(listOf(state))
            }
            awaitDraw(host)
            main {
                assertEquals(View.GONE, field.trailingHost.visibility)
                assertEquals(0, field.trailingHost.childCount)
                assertFalse(target.isShown)
                calls.clear(); editorEvents.clear()
                val bounds = boundsIn(host, field.editor)
                assertTrue(rootTap(host, bounds.exactCenterX(), bounds.exactCenterY()))
                assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), editorEvents)
                assertTrue("Hidden actions leave the editor usable", calls.isEmpty())
                requireNotNull(group).bind(listOf(CredentialFieldState(Field.CONNECTOR, state.value,
                    enabled = false, revealed = true)), listOf(CredentialActionState(reveal, "Hide")))
            }
            awaitDraw(host)
            main {
                assertSame(row, field.trailingHost.getChildAt(0))
                assertSame(target, button.parent)
                assertTrue(target.isShown)
                assertFalse(field.editor.isEnabled)
                assertNull(field.editor.transformationMethod)
                assertEquals("Hide", target.visual.glyph.contentDescription)
                assertGlyph(target, ReverbIcon.EyeOff)
                calls.clear(); editorEvents.clear()
                val bounds = boundsIn(host, target)
                assertTrue(rootTap(host, bounds.exactCenterX(), bounds.top + .5f))
                assertEquals(listOf(reveal), calls)
                assertTrue(editorEvents.isEmpty())
                assertTrue(edits.isEmpty())
            }
        } finally {
            main { try { group?.close() } finally { activity.finish() } }
        }
    }

    @Test fun actionsRespectAncestorAndWindowAdmissionAndRetireKeysAcrossFocusLoss() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val targets = CredentialFieldAction.values().map { CredentialActionTarget(Field.CLIENT, it) }
        val calls = mutableListOf<CredentialActionTarget<Field>>()
        lateinit var group: CredentialFieldGroup<Field>
        lateinit var host: AdmissionHost
        lateinit var controls: List<View>
        var dialog: Dialog? = null
        var created = false
        try {
            instrumentation.runOnMainSync {
                group = CredentialFieldGroup(
                    activity, ReverbDesign.theme(activity, dark = false).controls,
                    listOf(CredentialFieldSpec(Field.CLIENT, "Client", editable = false)),
                    actions = targets.toSet(), onAction = { calls += it },
                ) { _, _ -> error("Read-only field must not emit edits") }
                created = true
                group.bind(listOf(CredentialFieldState(Field.CLIENT, "synthetic-client", enabled = false)),
                    targets.map { CredentialActionState(it, it.action.name) })
                host = AdmissionHost(activity).apply {
                    isFocusableInTouchMode = true
                    addView(group.view, FrameLayout.LayoutParams(-1, -2))
                }
                activity.setContentView(host)
                dialog = Dialog(activity).apply {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                    setContentView(View(activity), ViewGroup.LayoutParams(100, 100))
                }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(group.view.hasWindowFocus())
                val field = descendants(group.view).filterIsInstance<NativeLabeledTextField>().single()
                assertFalse(field.editor.isEnabled)
                val row = field.trailingHost.getChildAt(0) as ViewGroup
                controls = (0 until row.childCount).map { clickTarget(row.getChildAt(it)) }
                host.admitted = false
                val checks = host.admissionChecks
                for (control in controls) {
                    control.performClick()
                    control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                }
                assertTrue(calls.isEmpty())
                assertEquals(controls.size * 2, host.admissionChecks - checks)

                host.admitted = true
                for (control in controls) {
                    control.performClick()
                    assertTrue(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                }
                assertEquals(targets.flatMap { listOf(it, it) }, calls)
                calls.clear()

                // Admission may retire the owner synchronously while still returning true.
                host.onAdmission = { group.view.visibility = View.GONE }
                controls.first().performClick()
                assertTrue(calls.isEmpty())
                host.onAdmission = null
                group.view.visibility = View.VISIBLE
            }
            for (index in targets.indices) {
                instrumentation.runOnMainSync {
                    val action = controls[index]
                    action.isFocusableInTouchMode = true
                    assertTrue(action.requestFocus())
                    assertTrue(action.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                }
                changeWindowFocus(group.view, focused = false) { checkNotNull(dialog).show() }
                instrumentation.runOnMainSync {
                    assertTrue(group.view.isAttachedToWindow && group.view.isShown)
                    assertTrue(controls[index].isFocused)
                    val checks = host.admissionChecks
                    for (control in controls) {
                        control.performClick()
                        control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                    }
                    assertEquals(targets.take(index), calls)
                    assertEquals(checks, host.admissionChecks)
                }
                changeWindowFocus(group.view, focused = true) { checkNotNull(dialog).dismiss() }
                instrumentation.runOnMainSync {
                    val action = controls[index]
                    assertTrue(action.isFocused)
                    assertFalse(action.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
                    assertEquals(targets.take(index), calls)
                    assertTrue(action.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                    assertTrue(action.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
                    assertEquals(targets.take(index + 1), calls)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                try {
                    dialog?.dismiss()
                    if (created) group.close()
                } finally { activity.finish() }
            }
        }
    }

    @Test fun retainedEditsRequireAttachedVisibleAdmissionAndResumeWithoutBindingEcho() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val edits = mutableListOf<Pair<Field, String>>()
        val client = CredentialFieldState(Field.CLIENT, "synthetic-client")
        lateinit var group: CredentialFieldGroup<Field>
        lateinit var host: AdmissionHost
        lateinit var retained: Editable
        var created = false
        try {
            instrumentation.runOnMainSync {
                group = CredentialFieldGroup(
                    activity, ReverbDesign.theme(activity, dark = false).controls,
                    listOf(
                        CredentialFieldSpec(Field.CONNECTOR, "Connector", maxInputLength = 32),
                        CredentialFieldSpec(Field.CLIENT, "Client", editable = false),
                    ),
                ) { field, value -> edits += field to value }
                created = true
                group.bind(listOf(CredentialFieldState(Field.CONNECTOR, "seed"), client))
                val editor = descendants(group.view).filterIsInstance<NativeLabeledTextField>().first().editor
                retained = checkNotNull(editor.text)
                retained.append("0")
                assertTrue(edits.isEmpty())
                host = AdmissionHost(activity).apply { addView(group.view, FrameLayout.LayoutParams(-1, -2)) }
                activity.setContentView(host)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val fields = descendants(group.view).filterIsInstance<NativeLabeledTextField>().toList()
                val editor = fields.first().editor
                assertSame(retained, editor.text)
                host.admitted = false
                val checks = host.admissionChecks
                retained.append("1")
                assertTrue(edits.isEmpty())
                assertEquals(checks + 1, host.admissionChecks)

                host.admitted = true
                group.view.visibility = View.GONE
                retained.append("2")
                assertTrue(edits.isEmpty())
                assertEquals(checks + 1, host.admissionChecks)
                group.view.visibility = View.VISIBLE

                host.removeView(group.view)
                assertFalse(editor.isAttachedToWindow)
                retained.append("3")
                assertTrue(edits.isEmpty())
                assertEquals(checks + 1, host.admissionChecks)
                host.addView(group.view, FrameLayout.LayoutParams(-1, -2))
                assertTrue(editor.isAttachedToWindow && editor.isShown)
                assertSame(retained, editor.text)
                retained.append("4")
                assertEquals(listOf(Field.CONNECTOR to "seed01234"), edits)

                checkNotNull(fields.last().editor.text).append("-changed")
                assertEquals(1, edits.size)
                val legacy = "synthetic-legacy-" + "x".repeat(40)
                group.bind(listOf(CredentialFieldState(Field.CONNECTOR, legacy), client))
                assertEquals(legacy, editor.text.toString())
                group.bind(listOf(CredentialFieldState(Field.CONNECTOR, "fresh"), client))
                assertEquals(1, edits.size)
                val fresh = checkNotNull(editor.text)
                fresh.append("!")
                assertEquals(Field.CONNECTOR to "fresh!", edits.last())
                assertEquals(2, edits.size)

                host.onAdmission = { group.view.visibility = View.GONE }
                fresh.append("?")
                assertEquals(2, edits.size)
                host.onAdmission = null
                group.view.visibility = View.VISIBLE
                fresh.append("r")
                assertEquals(Field.CONNECTOR to "fresh!?r", edits.last())
                assertEquals(3, edits.size)
                group.close()
                checkNotNull(editor.text).append("retired")
                assertEquals(3, edits.size)
            }
        } finally {
            instrumentation.runOnMainSync {
                try { if (created) group.close() } finally { activity.finish() }
            }
        }
    }

    @Test fun throwingRetirementWatchersStillClearOtherFieldsAndRetireActionsOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val reveal = CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.REVEAL)
            val copy = CredentialActionTarget(Field.CLIENT, CredentialFieldAction.COPY)
            var callbacks = 0
            val group = CredentialFieldGroup(context, ReverbDesign.theme(context, false).controls,
                listOf(CredentialFieldSpec(Field.CONNECTOR, "Connector"), CredentialFieldSpec(Field.CLIENT, "Client")),
                actions = setOf(reveal, copy), onAction = { callbacks++ }, onEdit = { _, _ -> callbacks++ })
            group.bind(listOf(CredentialFieldState(Field.CONNECTOR, "first fixture"),
                CredentialFieldState(Field.CLIENT, "second fixture")),
                listOf(CredentialActionState(reveal, "Show"), CredentialActionState(copy, "Copy")))
            val fields = descendants(group.view).filterIsInstance<NativeLabeledTextField>().toList()
            val failures = listOf(IllegalStateException("first watcher"), IllegalStateException("second watcher"))
            val clears = IntArray(2)
            val watchers = fields.mapIndexed { index, field ->
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (s.isNullOrEmpty()) {
                            clears[index]++
                            group.close() // Reentrancy cannot restart retirement or skip the sibling.
                            throw failures[index]
                        }
                    }
                }.also { field.editor.addTextChangedListener(it) }
            }
            try {
                val failure = runCatching { group.close() }.exceptionOrNull()
                assertSame(failures[0], failure)
                assertEquals(listOf(failures[1]), failure?.suppressed?.toList())
                assertTrue(fields.all { it.editor.text.isNullOrEmpty() && !it.editor.isEnabled })
                assertEquals(View.GONE, group.view.visibility)
                assertTrue(descendants(group.view).filterIsInstance<ReverbStandardIconButtonView>()
                    .all { it.visibility == View.GONE })
                group.bind(listOf(CredentialFieldState(Field.CONNECTOR, "retired bind"),
                    CredentialFieldState(Field.CLIENT, "retired bind")))
                group.close()
                assertTrue(fields.all { it.editor.text.isNullOrEmpty() })
                assertArrayEquals(intArrayOf(1, 1), clears)
                assertEquals(0, callbacks)
            } finally {
                fields.forEachIndexed { index, field -> field.editor.removeTextChangedListener(watchers[index]) }
                group.close()
            }
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
            val copy = CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.COPY)
            val unknownField = CredentialActionTarget(Field.CLIENT, CredentialFieldAction.COPY)
            assertTrue(runCatching {
                CredentialFieldGroup(context, theme, listOf(field), setOf(unknownField)) { _, _ -> }
            }.exceptionOrNull() is IllegalArgumentException)
            val group = CredentialFieldGroup(context, theme, listOf(field), setOf(copy)) { _, _ -> }
            val state = CredentialFieldState(Field.CONNECTOR, "fixture")
            try {
                assertTrue(runCatching { group.bind(listOf(state, state)) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { group.bind(listOf(CredentialFieldState(Field.CLIENT, "fixture"))) }
                    .exceptionOrNull() is IllegalArgumentException)
                val action = CredentialActionState(copy, "Copy")
                assertTrue(runCatching { group.bind(listOf(state), listOf(action, action)) }
                    .exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { group.bind(listOf(state), listOf(CredentialActionState(unknownField, "Copy"))) }
                    .exceptionOrNull() is IllegalArgumentException)
                val unconfigured = CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.REROLL)
                assertTrue(runCatching { group.bind(listOf(state), listOf(CredentialActionState(unconfigured, "Regenerate"))) }
                    .exceptionOrNull() is IllegalArgumentException)
            } finally {
                group.close()
            }
        }
    }

    @Test fun revealGlyphUsesFieldStateNotLocalizedActionLabelAndHasNoVisibleText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val reveal = CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.REVEAL)
        val calls = mutableListOf<CredentialActionTarget<Field>>()
        val edits = mutableListOf<String>()
        lateinit var group: CredentialFieldGroup<Field>
        var created = false
        try {
            instrumentation.runOnMainSync {
                group = CredentialFieldGroup(activity, ReverbDesign.theme(activity, false).controls,
                    listOf(CredentialFieldSpec(Field.CONNECTOR, "Credential")), setOf(reveal),
                    onAction = { calls += it }) { _, value -> edits += value }
                created = true
                group.bind(listOf(CredentialFieldState(Field.CONNECTOR, "synthetic-value")),
                    listOf(CredentialActionState(reveal, "Masquer")))
                descendants(group.view).filterIsInstance<NativeLabeledTextField>().single()
                    .editor.showSoftInputOnFocus = false
                activity.setContentView(group.view)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val editor = descendants(group.view).filterIsInstance<NativeLabeledTextField>().single().editor
                val action = descendants(group.view).filterIsInstance<ReverbStandardIconButtonView>().single()
                assertTrue(editor.requestFocus())
                editor.setSelection(2, 6)
                val text = editor.text
                assertFalse(descendants(action).any { it is ParagraphView })
                for (revealed in listOf(false, true, false)) {
                    // Deliberately identical localized label: it must not determine the glyph.
                    group.bind(listOf(CredentialFieldState(Field.CONNECTOR, "synthetic-value", revealed = revealed)),
                        listOf(CredentialActionState(reveal, "Masquer")))
                    group.bindTheme(ReverbDesign.theme(activity, revealed).controls)
                    assertGlyph(action, if (revealed) ReverbIcon.EyeOff else ReverbIcon.Eye)
                    assertEquals("Masquer", action.visual.glyph.contentDescription)
                    val node = checkNotNull(action.visual.createAccessibilityNodeInfo())
                    try { assertEquals("Masquer", node.contentDescription?.toString()) } finally { node.recycle() }
                    assertEquals(!revealed, editor.transformationMethod is PasswordTransformationMethod)
                    assertSame(text, editor.text)
                    assertTrue(editor.hasFocus())
                    assertEquals(2, editor.selectionStart)
                    assertEquals(6, editor.selectionEnd)
                    action.visual.performClick()
                }
                assertEquals(listOf(reveal, reveal, reveal), calls)
                assertTrue(edits.isEmpty())
                group.close()
                action.visual.performClick()
                assertEquals(3, calls.size)
                assertTrue(editor.text.isNullOrEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { try { if (created) group.close() } finally { activity.finish() } }
        }
    }

    private enum class GroupField { FIRST, SECOND, GENERATED, READ_ONLY }

    @Test fun labeledCategoryBreaksKeepIncidentGeometryAndReadOnlyCopySemantics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val copy = CredentialActionTarget(GroupField.READ_ONLY, CredentialFieldAction.COPY)
        val calls = mutableListOf<CredentialActionTarget<GroupField>>()
        val edits = mutableListOf<String>()
        lateinit var group: CredentialFieldGroup<GroupField>
        var created = false
        try {
            instrumentation.runOnMainSync {
                group = CredentialFieldGroup(activity, ReverbDesign.theme(activity, false).controls, listOf(
                    CredentialFieldSpec(GroupField.FIRST, "First", groupLabel = "Manual inputs"),
                    CredentialFieldSpec(GroupField.SECOND, "Second"),
                    CredentialFieldSpec(GroupField.GENERATED, "Generated", editable = false,
                        startsGroup = true, groupLabel = "Generate"),
                    CredentialFieldSpec(GroupField.READ_ONLY, "Address", keyboard = CredentialKeyboard.URI,
                        editable = false, startsGroup = true, groupLabel = "Read-only")),
                    setOf(copy), onAction = { calls += it }) { _, value -> edits += value }
                created = true
                group.bind(GroupField.values().map {
                    CredentialFieldState(it, "synthetic-" + it.name,
                        supportingText = if (it == GroupField.READ_ONLY) "Lecture seule" else null)
                }, listOf(CredentialActionState(copy, "Copier")))
                descendants(group.view).filterIsInstance<NativeLabeledTextField>().forEach {
                    it.editor.showSoftInputOnFocus = false
                }
                activity.setContentView(group.view)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val host = group.view as LinearLayout
                val groups = (0 until host.childCount).map { host.getChildAt(it) as NativeGroupLayout }
                val fields = descendants(host).filterIsInstance<NativeLabeledTextField>().toList()
                val first = fields.first().editor
                assertTrue(first.requestFocus())
                first.setSelection(1, 4)
                val text = first.text
                val labels = listOf("Manual inputs", "Generate", "Read-only")
                for (dark in listOf(false, true)) {
                    group.bindTheme(ReverbDesign.theme(activity, dark).controls)
                    val density = activity.resources.displayMetrics.density
                    host.measure(View.MeasureSpec.makeMeasureSpec((320f * density).roundToInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    host.layout(0, 0, host.measuredWidth, host.measuredHeight)
                    assertEquals(3, groups.size)
                    assertEquals(0, groups.first().top)
                    assertEquals(host.height, groups.last().bottom)
                    for ((left, right) in groups.zipWithNext()) assertEquals((2f * density).roundToInt(), right.top - left.bottom)
                    for ((index, surface) in groups.withIndex()) {
                        assertEquals(index != 0, surface.containsVisualPoint(.5f, .5f))
                        assertEquals(index != groups.lastIndex, surface.containsVisualPoint(.5f, surface.height - .5f))
                        val heading = surface.pointerContent.getChildAt(0) as ParagraphView
                        assertEquals(labels[index], heading.getText())
                        assertEquals((12f * density).roundToInt(), heading.paddingStart)
                        assertEquals((8f * density).roundToInt(), heading.paddingTop)
                        assertEquals((5f * density).roundToInt(), heading.paddingBottom)
                        val node = checkNotNull(heading.createAccessibilityNodeInfo())
                        try { if (Build.VERSION.SDK_INT >= 28) assertTrue(node.isHeading) } finally { node.recycle() }
                    }
                    assertSame(text, first.text)
                    assertTrue(first.hasFocus())
                    assertEquals(1, first.selectionStart)
                    assertEquals(4, first.selectionEnd)
                    assertEquals(fields[1].editor.id, first.nextFocusForwardId)
                    assertEquals(View.NO_ID, fields[1].editor.nextFocusForwardId)
                    val readOnly = fields.last().editor
                    val node = checkNotNull(readOnly.createAccessibilityNodeInfo())
                    try {
                        assertEquals(android.widget.TextView::class.java.name, node.className)
                        assertFalse(node.isEditable)
                        assertFalse(node.isFocusable)
                        assertFalse(node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT))
                    } finally { node.recycle() }
                    assertNull(readOnly.keyListener)
                    assertFalse(readOnly.requestFocus())
                    assertFalse(readOnly.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "rejected")
                    }))
                    assertTrue(descendants(fields.last()).filterIsInstance<ParagraphView>().any { it.getText() == "Lecture seule" })
                    val action = descendants(fields.last()).filterIsInstance<ReverbStandardIconButtonView>().single()
                    action.visual.performClick()
                }
                assertEquals(listOf(copy, copy), calls)
                assertTrue(edits.isEmpty())
                group.close()
                assertTrue(fields.all { it.editor.text.isNullOrEmpty() })
            }
        } finally {
            instrumentation.runOnMainSync { try { if (created) group.close() } finally { activity.finish() } }
        }
    }

    private fun clickTarget(view: View): View = when (view) {
        is ReverbStandardIconButtonView -> view.visual
        else -> view
    }

    private fun assertRevealAccessibilityChild(target: ReverbStandardIconButtonView, button: View) {
        assertTrue(button.filterTouchesWhenObscured)
        if (Build.VERSION.SDK_INT >= 34) {
            // Android infers sensitive accessibility data from obscured-touch protection. A direct
            // in-process query is not an accessibility-tool request, so the platform filters this
            // child before the standalone delegate is reached. Verify that protection first.
            assertTrue(button.isAccessibilityDataSensitive)
            assertEquals(emptyList<View>(), ArrayList<View>().also { target.addChildrenForAccessibility(it) })
            // Query only this synthetic action's structure, never the credential editor or window.
            // No global service/fetch flags, obscured-touch flags, geometry or input policy change.
            button.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_NO)
        }
        try {
            val actions = ArrayList<View>().also { target.addChildrenForAccessibility(it) }
            assertEquals(listOf(button), actions)
        } finally {
            if (Build.VERSION.SDK_INT >= 34) {
                button.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_AUTO)
                assertTrue(button.isAccessibilityDataSensitive)
            }
        }
        assertTrue(button.filterTouchesWhenObscured)
    }

    private fun boundsIn(root: ViewGroup, child: View) = Rect(0, 0, child.width, child.height).also {
        root.offsetDescendantRectToMyCoords(child, it)
    }

    private fun rootTap(root: View, x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = dispatchFinger(root, MotionEvent.ACTION_DOWN, x, y, downTime)
        val up = dispatchFinger(root, MotionEvent.ACTION_UP, x, y, downTime)
        return down && up
    }

    private fun dispatchFinger(root: View, action: Int, x: Float, y: Float, downTime: Long): Boolean {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun awaitDraw(view: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val drawn = CountDownLatch(1)
        var posted = false
        val listener = ViewTreeObserver.OnDrawListener {
            if (!posted) { posted = true; view.post { drawn.countDown() } }
        }
        instrumentation.runOnMainSync {
            view.viewTreeObserver.addOnDrawListener(listener)
            view.postInvalidateOnAnimation()
        }
        try {
            assertTrue("Credential hierarchy must draw", drawn.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { view.viewTreeObserver.removeOnDrawListener(listener) }
        }
    }

    private class AdmissionHost(context: Context) : FrameLayout(context), NativeInputGate {
        var admitted = true
        var admissionChecks = 0
        var onAdmission: (() -> Unit)? = null
        override val acceptsNativeInput: Boolean
            get() {
                admissionChecks++
                onAdmission?.invoke()
                return admitted
            }
    }

    /** Wait for a real window focus transition; dispatchWindowFocusChanged alone doesn't change hasWindowFocus. */
    private fun changeWindowFocus(view: View, focused: Boolean, change: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val changed = CountDownLatch(1)
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { if (it == focused) changed.countDown() }
        instrumentation.runOnMainSync {
            view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            change()
            if (view.hasWindowFocus() == focused) changed.countDown()
        }
        try {
            assertTrue("Window focus did not become $focused", changed.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals(focused, view.hasWindowFocus()) }
        } finally {
            instrumentation.runOnMainSync { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }
    }

    /** Compare glyph coverage, independent of the current palette transition. */
    private fun assertGlyph(button: ReverbStandardIconButtonView, icon: ReverbIcon) {
        val actual = button.visual.glyph
        assertTrue(actual.width > 0 && actual.height > 0)
        val expected = ReverbGlyphView(button.context, icon, Color.BLACK)
        expected.measure(View.MeasureSpec.makeMeasureSpec(actual.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(actual.height, View.MeasureSpec.EXACTLY))
        expected.layout(0, 0, actual.width, actual.height)
        fun coverage(view: View): IntArray {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val pixels = IntArray(view.width * view.height)
                bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
                return IntArray(pixels.size) { if (Color.alpha(pixels[it]) == 0) 0 else 1 }
            } finally { bitmap.recycle() }
        }
        assertArrayEquals(coverage(expected), coverage(actual))
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
