package smallthingz.ui.injector

import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.icons.MaterialRoundedIconDrawable

@RunWith(RobolectricTestRunner::class)
@Config(application = InjectCompositeHostApplication::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class InjectConfirmationPresentationTest {
    @Test fun cancelIsTextOnlyWhileConfirmKeepsItsRequestedGlyphAndSemanticColors() {
        val context = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) for (destructive in listOf(false, true)) {
            val theme = InjectDesign.theme(context, dark)
            val appearance = InjectAppearance(context, theme)
            val confirmIcon = if (destructive) MaterialRoundedIcon.Delete else MaterialRoundedIcon.Check
            var callbacks = 0
            val actions = appearance.confirmationActions("Cancel", if (destructive) "Delete" else "Save",
                { callbacks++ }, confirmIcon = confirmIcon, destructive = destructive,
                onConfirm = { callbacks++ })
            try {
                assertNull(actionGlyph(actions.cancelButton))
                assertEquals(confirmIcon, actionGlyph(actions.confirmButton)?.icon)
                assertEquals("Cancel", actions.cancelButton.label.getText())
                assertEquals(Color.TRANSPARENT, actions.cancelButton.sampledContainerColor)
                assertEquals(if (destructive) theme.palette.errorContainer else theme.palette.primary,
                    actions.confirmButton.sampledContainerColor)
                assertTrue(actions.isActionFooter)
                assertSame(actions.actionSurface.pointerContent, actions.cancelButton.parent)
                assertSame(actions.actionSurface.pointerContent, actions.confirmButton.parent)
                assertTrue(actions.cancelButton.isEnabled)
                assertTrue(actions.confirmButton.isEnabled)
            } finally {
                actions.confirmButton.close()
                actions.cancelButton.close()
            }
            assertEquals(0, callbacks)
        }
    }

    @Test fun equalShortLabelsKeepSharedWidthsAndNarrowStackingWithoutAnInvisibleIconSlot() {
        val application = RuntimeEnvironment.getApplication()
        for (fontScale in listOf(1f, 2f)) {
            val context = application.createConfigurationContext(
                Configuration(application.resources.configuration).apply { this.fontScale = fontScale })
            val theme = InjectDesign.theme(context, dark = false)
            val appearance = InjectAppearance(context, theme)
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val actions = appearance.confirmationActions("OK", "OK", {}, onConfirm = {})
                val cancel = actions.cancelButton
                val confirm = actions.confirmButton
                try {
                    actions.layoutDirection = direction
                    measure(actions, 360)
                    assertNull(actionGlyph(cancel))
                    assertNull(actionGlyph(confirm))
                    assertEquals(cancel.actionMinimumWidthPx, confirm.actionMinimumWidthPx)
                    assertEquals(cancel.width, confirm.width)
                    assertEquals(cancel.top, confirm.top)
                    assertEquals(cancel.width + confirm.width, actions.actionSurface.width)

                    measure(actions, cancel.width + 8)
                    assertEquals(cancel.width, confirm.width)
                    assertEquals(cancel.left, confirm.left)
                    assertTrue(confirm.top >= cancel.bottom)

                    // The shared natural-width rule still follows text; no fixed equal-width override.
                    confirm.bind("Save a copy", true, theme.palette.buttonPalette)
                    measure(actions, 360)
                    assertTrue(confirm.width > cancel.width)
                    assertEquals(cancel.top, confirm.top)
                } finally {
                    confirm.close()
                    cancel.close()
                }
            }
        }
    }

    // Same retained-glyph inspection used by NativeMergedActionLayoutIntegrationTest.
    private fun actionGlyph(button: NativeMaterialButtonView): MaterialRoundedIconDrawable? =
        NativeMaterialButtonView::class.java.getDeclaredField("icon").apply { isAccessible = true }
            .get(button) as MaterialRoundedIconDrawable?

    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
