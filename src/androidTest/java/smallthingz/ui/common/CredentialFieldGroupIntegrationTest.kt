package smallthingz.ui.common

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Parcelable
import android.text.Editable
import android.text.method.PasswordTransformationMethod
import android.util.SparseArray
import android.view.KeyEvent
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
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbStandardIconButtonView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

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
                    assertTrue(clientNode.isPassword)
                } finally {
                    clientNode.recycle()
                }
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
                    when (control) {
                        is NativeMaterialButtonView -> assertEquals("Afficher", control.label.getText())
                        is ReverbStandardIconButtonView -> {
                            assertTrue(control.filterTouchesWhenObscured)
                            assertTrue(control.visual.filterTouchesWhenObscured)
                            assertNull(control.background)
                            assertNull(control.visual.background)
                            assertGlyph(control, if (target.action == CredentialFieldAction.COPY) ReverbIcon.Copy else ReverbIcon.Reset)
                        }
                    }
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
                assertEquals("Hide", (controls.first() as NativeMaterialButtonView).label.getText())
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

    private fun clickTarget(view: View): View =
        if (view is ReverbStandardIconButtonView) view.visual else view

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
