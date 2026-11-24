package smallthingz.ui.components

import android.graphics.Rect
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbApplicationWidgets
import smallthingz.ui.reverb.ReverbDesign
import kotlin.math.roundToInt

/** Local measurement/binding only. No Activity/window, IME, input injection or accessibility service. */
@RunWith(AndroidJUnit4::class)
class ReverbApplicationWidgetsIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun joinedFieldsKeepEditorIdentityEditableSelectionAndPasswordPolicy() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val widgets = ReverbApplicationWidgets(context, ReverbDesign.theme(context, false))
        val editor = widgets.input().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            setText("local fixture value")
            setSelection(2, 7)
        }
        val value = editor.text
        val transformation = editor.transformationMethod
        val inputType = editor.inputType
        var edits = 0
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { edits++ }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val group = widgets.joinedInputs("Password" to editor, "Endpoint" to widgets.input()) as ViewGroup
        assertSame(editor, group.descendants().filterIsInstance<NativeTextField>().first())
        assertSame(value, editor.text)
        assertSame(transformation, editor.transformationMethod)
        assertEquals(inputType, editor.inputType)
        assertEquals(2, editor.selectionStart)
        assertEquals(7, editor.selectionEnd)
        assertEquals(0, edits)
    }

    @Test fun pairedActionsStackNarrowAndKeepLogicalOrderInRtlWithoutCallbacks() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val theme = ReverbDesign.theme(context, false)
        val widgets = ReverbApplicationWidgets(context, theme)
        var callbacks = 0
        val first = widgets.button("First action") { callbacks++ }
        val second = widgets.button("Second action") { callbacks++ }
        try {
            val pair = widgets.actionPair(first, second) as ViewGroup
            fun measure(width: Int) {
                pair.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                pair.layout(0, 0, pair.measuredWidth, pair.measuredHeight)
            }
            fun bounds(child: View) = Rect(0, 0, child.width, child.height).also {
                pair.offsetDescendantRectToMyCoords(child, it)
            }
            val wide = 2 * maxOf(first.preferredWidth(false), second.preferredWidth(false)) + 2
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                pair.layoutDirection = direction
                measure((120 * theme.density.density).roundToInt())
                assertTrue(bounds(second).top >= bounds(first).bottom)
                measure(wide)
                if (direction == View.LAYOUT_DIRECTION_RTL) assertTrue(bounds(second).right <= bounds(first).left)
                else assertTrue(bounds(first).right <= bounds(second).left)
                second.visibility = View.GONE
                measure(wide)
                assertEquals(pair.width, first.width)
                second.visibility = View.VISIBLE
            }
            widgets.bindButton(first, enabled = false)
            first.performClick()
            assertEquals(0, callbacks)
        } finally { first.close(); second.close() }
    }

    private fun ViewGroup.descendants(): Sequence<View> = sequence {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            yield(child)
            if (child is ViewGroup) yieldAll(child.descendants())
        }
    }
}
