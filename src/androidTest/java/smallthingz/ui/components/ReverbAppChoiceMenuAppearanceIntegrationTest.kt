package smallthingz.ui.components

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.reverb.ReverbAppChoice
import smallthingz.ui.reverb.ReverbAppChoiceRow
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.text.view.ParagraphView

/** Detached views snap semantic colors; attached input/focus coverage stays in ReverbAppChoiceIntegrationTest. */
@RunWith(AndroidJUnit4::class)
class ReverbAppChoiceMenuAppearanceIntegrationTest {
    @Test fun pickerSelectionUsesMenuRolesWithoutRetintingAppIdentityOrEnablingDisabledChoices() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) {
            val theme = ReverbDesign.theme(context, dark)
            val icon = ColorDrawable(Color.MAGENTA)
            val choice = ReverbAppChoice("browser", "Browser", "org.example.browser", icon)
            val row = ReverbAppChoiceRow(context, theme, trigger = false) { error("Binding must not select") }
            try {
                for (selected in listOf(false, true, false, true)) {
                    row.bind(choice, selected, theme)
                    assertEquals(if (selected) theme.palette.ink else theme.palette.muted, ink(row.label))
                    assertEquals(if (selected) View.VISIBLE else View.GONE, row.trailing.visibility)
                    if (selected) assertEquals(theme.palette.primary, tint(row.trailing))
                    assertSame(icon, row.icon.drawable)
                    assertNull(row.icon.imageTintList)
                    assertEquals(theme.palette.muted, ink(row.packageLabel))
                    assertEquals(selected, row.isSelected)
                    assertTrue(row.isEnabled)
                }
                row.bind(choice.copy(enabled = false, disabledReason = "Access required"), true, theme)
                assertEquals(theme.palette.muted, ink(row.label))
                assertEquals(theme.palette.muted, tint(row.trailing))
                assertEquals(theme.palette.muted, ink(row.reason))
                assertEquals("Access required", row.reason.getText())
                assertEquals(View.VISIBLE, row.reason.visibility)
                assertTrue(row.isSelected)
                assertFalse(row.isEnabled)
                row.bind(choice, true, theme)
                assertEquals(theme.palette.ink, ink(row.label))
                assertEquals(theme.palette.primary, tint(row.trailing))
                assertEquals(View.GONE, row.reason.visibility)
            } finally { row.close() }
        }
    }

    @Test fun triggerAndCheckboxKeepTheirExistingRoles() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) {
            val theme = ReverbDesign.theme(context, dark)
            for (trigger in listOf(false, true)) {
                val row = ReverbAppChoiceRow(context, theme, trigger = trigger, checkbox = !trigger) {
                    error("Binding must not select")
                }
                try {
                    for (enabled in listOf(true, false)) for (selected in listOf(false, true)) {
                        row.bind(ReverbAppChoice("browser", "Browser", enabled = enabled), selected, theme)
                        assertEquals(if (enabled) theme.palette.ink else theme.palette.muted, ink(row.label))
                        assertEquals(if (!trigger && selected) theme.palette.primary else theme.palette.muted,
                            tint(row.trailing))
                        assertEquals(View.VISIBLE, row.trailing.visibility)
                        assertEquals(enabled, row.isEnabled)
                    }
                } finally { row.close() }
            }
        }
    }

    private fun ink(label: ParagraphView): Int {
        val packed = ParagraphView::class.java.getDeclaredField("textColor").apply { isAccessible = true }.get(label) as Long
        return smallthingz.ui.graphics.Color(packed.toULong()).toArgb()
    }

    private fun tint(glyph: ReverbGlyphView): Int = ReverbGlyphView::class.java
        .getDeclaredField("displayedColor").apply { isAccessible = true }.getInt(glyph)

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
