package smallthingz.ui.reverb

import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMaterialButtonView

/** Actual constructed button paint; no window is shown and no response is authorized here. */
@RunWith(AndroidJUnit4::class)
class ReverbConfirmationDestructiveActionIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun destructiveOptInUsesSemanticPaintInLightAndDarkWithoutRecoloringCancel() = main {
        val context = instrumentation.targetContext
        for (dark in listOf(false, true)) {
            val theme = ReverbDesign.theme(context, dark)
            for (destructive in listOf(false, true)) {
                // Deliberately neutral copy: semantics must come from the option, not translated text.
                val sheet = ReverbConfirmationSheet(View(context), theme, "Review", "Synthetic operation",
                    "Cancel", "Continue", destructive = destructive, onCancel = {}, onConfirm = {})
                try {
                    assertPaint(sheet, theme, destructive)
                } finally { sheet.close() }
            }
        }
    }

    @Test fun oldPositionalAndTrailingLambdaCallsKeepNormalPaintEvenWithDestructiveCopy() = main {
        val context = instrumentation.targetContext
        for (dark in listOf(false, true)) {
            val theme = ReverbDesign.theme(context, dark)
            // Both forms existed before the optional semantic parameter was added.
            val positional = ReverbConfirmationSheet(View(context), theme, "Clear", "Clear history?",
                "Cancel", "Delete", {}, {})
            try {
                assertPaint(positional, theme, false)
            } finally { positional.close() }
            val trailing = ReverbConfirmationSheet(View(context), theme, "Clear", "Clear history?",
                "Cancel", "Delete", onCancel = {}) {}
            try {
                assertPaint(trailing, theme, false)
            } finally { trailing.close() }
        }
    }

    @Test fun semanticOptionDoesNotAuthorizeUnshownResponsesAndOwnerCloseRemainsSilent() = main {
        val context = instrumentation.targetContext
        for (destructive in listOf(false, true)) {
            val responses = mutableListOf<Boolean>()
            val sheet = ReverbConfirmationSheet(View(context), ReverbDesign.theme(context, false),
                "Review", "Synthetic operation", "Cancel", "Continue", destructive = destructive,
                onCancel = { responses += false }, onConfirm = { responses += true })
            try {
                assertFalse(sheet.canRespond)
                assertFalse(sheet.respond(true))
                assertFalse(sheet.respond(false))
                assertTrue(responses.isEmpty())
                sheet.close()
                sheet.close()
                assertFalse(sheet.canRespond)
                assertFalse(sheet.respond(true))
                assertFalse(sheet.respond(false))
                assertTrue(responses.isEmpty())
            } finally { sheet.close() }
        }
    }

    private fun assertPaint(sheet: ReverbConfirmationSheet, theme: ReverbTheme, destructive: Boolean) {
        val confirm = button(sheet, "confirm")
        val cancel = button(sheet, "cancel")
        assertEquals(if (destructive) theme.palette.errorContainer else theme.palette.primary,
            confirm.sampledContainerColor)
        assertEquals(if (destructive) theme.palette.onErrorContainer else theme.palette.onPrimary, ink(confirm))
        assertEquals(if (destructive) ReverbIcon.Delete else ReverbIcon.Check, icon(confirm))
        assertEquals(Color.TRANSPARENT, cancel.sampledContainerColor)
        assertEquals(theme.palette.primary, ink(cancel))
        assertEquals(ReverbIcon.Close, icon(cancel))
    }

    private fun button(sheet: ReverbConfirmationSheet, name: String): NativeMaterialButtonView {
        val wrapper = ReverbConfirmationSheet::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(sheet) as ReverbSheetButton
        return wrapper.accessibilitySemanticView as NativeMaterialButtonView
    }

    private fun ink(button: NativeMaterialButtonView): Int = NativeMaterialButtonView::class.java
        .getDeclaredField("displayedInk").apply { isAccessible = true }.getInt(button)

    private fun icon(button: NativeMaterialButtonView): ReverbIcon =
        (NativeMaterialButtonView::class.java.getDeclaredField("icon")
            .apply { isAccessible = true }.get(button) as ReverbIconDrawable).icon

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
