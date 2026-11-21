package smallthingz.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCompactFieldIntegrationTest {
    /** Detached endpoint paint and RTL compound geometry at a fixed 1dp per pixel. */
    @Test fun compactFieldPaintsRaisedFillAndUsesRelativeCompoundGap() = main {
        val context = oneDpPerPixel(instrumentation.context)
        val palette = NativeColorScheme.fallback(darkTheme = false)
        val field = NativeUiAppearance.Default.compactField.createField(context, palette)
        val leading = ColorDrawable(Color.MAGENTA).apply { setBounds(0, 0, 18, 18) }
        field.setSingleLine(true)
        field.layoutDirection = View.LAYOUT_DIRECTION_RTL
        field.hint = "Address"
        field.setCompoundDrawablesRelative(leading, null, null, null)
        field.applyPlaceholderTextStyle(NativeTextStyle(14f))
        field.measure(exact(160), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        field.layout(0, 0, field.measuredWidth, field.measuredHeight)
        assertEquals(48, field.height)
        assertEquals(12, field.paddingStart)
        assertEquals(8, field.paddingTop)
        assertEquals(8, field.compoundDrawablePadding)
        assertSame(leading, field.compoundDrawablesRelative[0])
        assertSame(leading, field.compoundDrawables[2])
        assertEquals(38, field.compoundPaddingRight)
        assertEquals(NativePlaceholderPresentation.NATIVE, field.placeholderPresentation)
        assertEquals("Address", field.hint.toString())

        val background = checkNotNull(field.background)
        assertEquals(palette.surfaceContainerHigh, backgroundCenter(field))
        field.isEnabled = false
        assertSame(background, field.background)
        assertEquals(palette.surface, backgroundCenter(field))
    }

    /** Attached editor/connection retention on theme rebind, with no transition-frame sampling. */
    @Test fun boundCompactSkinRetainsFocusedNativeEditorAndReleasesItsSpacing() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val light = NativeColorScheme.fallback(darkTheme = false)
        val dark = NativeColorScheme.fallback(darkTheme = true)
        val recipe = NativeUiAppearance.Default.compactField
        val plain = NativeTextFieldStyle(outlined = false)
        val uriType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        lateinit var field: NativeTextField
        try {
            main {
                field = NativeTextField(oneDpPerPixel(activity), light.pageControls, plain).apply {
                    inputType = uriType
                    imeOptions = EditorInfo.IME_ACTION_GO
                    hint = "Address"
                    setText("example.test")
                    compoundDrawablePadding = 3
                }
                activity.setContentView(field)
            }
            instrumentation.waitForIdleSync()
            main {
                assertTrue(field.isAttachedToWindow && field.requestFocus())
                val text = field.text
                val connection = checkNotNull(field.onCreateInputConnection(EditorInfo()))
                try {
                    assertTrue(connection.setSelection(4, 4))
                    assertTrue(connection.setComposingRegion(1, 4))
                    recipe.bind(field, light)
                    val background = checkNotNull(field.background)
                    assertEquals(8, field.compoundDrawablePadding)
                    recipe.bind(field, dark)
                    recipe.bind(field, dark)
                    assertSame(background, field.background)
                    assertSame(text, field.text)
                    assertTrue(field.hasFocus())
                    assertEquals(uriType, field.inputType)
                    assertEquals(EditorInfo.IME_ACTION_GO, field.imeOptions)
                    assertEquals(4, field.selectionStart)
                    assertEquals(1, BaseInputConnection.getComposingSpanStart(text))
                    assertEquals(4, BaseInputConnection.getComposingSpanEnd(text))
                    val node = checkNotNull(field.createAccessibilityNodeInfo())
                    try {
                        assertTrue(node.isEditable)
                        assertEquals("Address", node.hintText?.toString())
                    } finally {
                        node.recycle()
                    }
                    assertTrue(connection.finishComposingText())
                    field.applyStyle(plain)
                    assertEquals(3, field.compoundDrawablePadding)
                    assertSame(text, field.text)
                    assertTrue(field.hasFocus())
                } finally {
                    connection.closeConnection()
                }
            }
        } finally {
            main { activity.finish() }
        }
    }

    @Test fun labeledFieldHonorsItalicInExpandedAndFloatingParagraphs() = main {
        val context = oneDpPerPixel(instrumentation.context)
        val palette = NativeColorScheme.fallback(darkTheme = false)
        fun renderLabel(italic: Boolean, floating: Boolean): Bitmap {
            val labelText = NativeTextStyle(24f, 30f, fontFamily = "serif", italic = italic)
            val style = NativeLabeledTextFieldStyle(errorColor = palette.error,
                outlineWidthDp = 0f, focusedOutlineWidthDp = 0f,
                expandedLabelStyle = labelText, floatingLabelStyle = labelText)
            val field = NativeLabeledTextField(context, palette.settingsControls, "Italic sample", style)
            if (floating) field.editor.setText("Value")
            field.measure(exact(320), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            field.layout(0, 0, field.measuredWidth, field.measuredHeight)
            return Bitmap.createBitmap(field.width, field.height, Bitmap.Config.ARGB_8888).also {
                field.draw(Canvas(it))
            }
        }

        for (floating in listOf(false, true)) {
            val upright = renderLabel(italic = false, floating = floating)
            val italic = renderLabel(italic = true, floating = floating)
            try {
                assertFalse("Expected glyph differences for floating=$floating", upright.sameAs(italic))
            } finally {
                upright.recycle()
                italic.recycle()
            }
        }
    }

    private fun backgroundCenter(field: NativeTextField): Int {
        val bitmap = Bitmap.createBitmap(field.width, field.height, Bitmap.Config.ARGB_8888)
        try {
            val background = checkNotNull(field.background)
            background.setBounds(0, 0, bitmap.width, bitmap.height)
            background.draw(Canvas(bitmap))
            return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun oneDpPerPixel(context: Context): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { densityDpi = 160; fontScale = 1f },
    )
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
}
