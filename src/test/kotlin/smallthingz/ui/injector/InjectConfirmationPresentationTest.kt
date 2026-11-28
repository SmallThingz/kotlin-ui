package smallthingz.ui.injector

import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.widget.ImageView
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
    @Test fun permissionStatusTextDefersIconsAndVisibleRebindUsesTheCurrentGrant() {
        val context = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) {
            val row = object : InjectPermissionRow(context, InjectDesign.theme(context, dark), android.R.drawable.sym_def_app_icon) {
                val statusIcon get() = trailing as ImageView
            }
            fun iconInitialized(name: String) = (InjectPermissionRow::class.java
                .getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(row) as Lazy<*>).isInitialized()
            var calls = 0
            fun bind(showStatus: Boolean, granted: Boolean) {
                row.bindStatus("Root", if (showStatus) "Waiting…" else if (granted) "Allowed" else "Not allowed",
                    showStatus, granted, enabled = true, requestEnabled = !showStatus,
                    requestLabel = "Allow", onRequest = { calls++ })
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row.statusIcon.importantForAccessibility)
                assertEquals(!showStatus && !granted, row.canRequestNow)
            }

            bind(showStatus = true, granted = false)
            assertEquals(View.GONE, row.statusIcon.visibility)
            assertNull("Initial textual status must not construct the hidden icon", row.statusIcon.drawable)
            bind(showStatus = true, granted = true)
            assertNull("A hidden grant change must not construct the other icon either", row.statusIcon.drawable)
            assertFalse(iconInitialized("ungrantedIcon"))
            assertFalse(iconInitialized("grantedIcon"))

            bind(showStatus = false, granted = false)
            val ungranted = row.statusIcon.drawable as MaterialRoundedIconDrawable
            assertEquals(View.VISIBLE, row.statusIcon.visibility)
            assertEquals(MaterialRoundedIcon.RadioButtonUnchecked, ungranted.icon)
            assertTrue(iconInitialized("ungrantedIcon"))
            assertFalse(iconInitialized("grantedIcon"))

            bind(showStatus = true, granted = true)
            assertEquals(View.GONE, row.statusIcon.visibility)
            assertSame("Hidden state does not replace the retained drawable", ungranted, row.statusIcon.drawable)
            assertFalse(iconInitialized("grantedIcon"))
            bind(showStatus = false, granted = true)
            val granted = row.statusIcon.drawable as MaterialRoundedIconDrawable
            assertEquals(View.VISIBLE, row.statusIcon.visibility)
            assertEquals(MaterialRoundedIcon.CheckCircle, granted.icon)
            assertTrue(iconInitialized("grantedIcon"))

            bind(showStatus = true, granted = false)
            assertSame(granted, row.statusIcon.drawable)
            bind(showStatus = false, granted = false)
            assertEquals(View.VISIBLE, row.statusIcon.visibility)
            assertSame("Returning to an ungranted icon reuses its original drawable", ungranted, row.statusIcon.drawable)
            bind(showStatus = false, granted = true)
            assertSame("Returning to a granted icon reuses its original drawable", granted, row.statusIcon.drawable)
            assertEquals(0, calls)
        }
    }

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
