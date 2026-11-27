package smallthingz.ui.injector

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeGrainPreset
import smallthingz.ui.reverb.ReverbDesign

/** Source-only regression coverage for extracted UI recipes; no window or operation is opened. */
@RunWith(AndroidJUnit4::class)
class InjectDialogRecipesIntegrationTest {
    @Test fun confirmationColorsFollowSemanticRolesAndKeepHostOwnedButtons() = main { context ->
        for (dark in listOf(false, true)) for (destructive in listOf(false, true)) {
            val theme = InjectDesign.theme(context, dark)
            val appearance = InjectAppearance(context, theme)
            var callbacks = 0
            val actions = appearance.confirmationActions("Cancel", "Continue", { callbacks++ },
                destructive = destructive, onConfirm = { callbacks++ })
            try {
                assertSame(theme.palette, appearance.scheme)
                assertSame(theme.controlTheme, appearance.controlTheme)
                assertEquals(Color.TRANSPARENT, actions.cancelButton.sampledContainerColor)
                assertEquals(if (destructive) theme.palette.errorContainer else theme.palette.primary,
                    actions.confirmButton.sampledContainerColor)
                assertTrue(actions.isActionFooter)
                assertSame(actions.actionSurface.pointerContent, actions.cancelButton.parent)
                assertSame(actions.actionSurface.pointerContent, actions.confirmButton.parent)
                actions.confirmButton.bind("Continue", false, theme.palette.buttonPalette)
                assertTrue(actions.cancelButton.isEnabled)
                assertEquals(0, callbacks)
            } finally {
                actions.confirmButton.close()
                actions.cancelButton.close()
            }
            assertEquals(0, callbacks)
        }
    }

    @Test fun topAndBottomDialogSurfacesPreserveTheSameWindowPaletteAndGrain() = main { context ->
        for (dark in listOf(false, true)) for (grain in listOf(0f, 1f)) {
            val base = InjectDesign.theme(context, dark)
            val palette = base.palette.copy(surfacePreset = NativeGrainPreset(grain, .5f))
            val theme = InjectDesign.windowTheme(base.density, base.fontResolver, palette)
            val body = View(context).apply { layoutParams = FrameLayout.LayoutParams(1, 40) }
            val panel = InjectPanelSurface(context, theme, body)
            val sheet = InjectDesign.actionSheetStyle(theme)
            assertEquals(palette.background, sheet.palette.surfaceContainerLow)
            assertEquals(grain, sheet.recipe.grainIntensity, 0f)
            assertEquals(ReverbDesign.windowBackdrop(palette).paintedTextSurface, panel.paintedTextSurface)
            panel.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
            assertEquals(400, panel.measuredWidth)
        }
    }

    private fun main(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { block(instrumentation.targetContext) }
    }
}
