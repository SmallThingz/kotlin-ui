package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeButtonWindowRecipeIntegrationTest {
    @Test fun windowAppearanceReachesMountedRangeAndTrimLabels() {
        val default = NativeUiAppearance.Default
        val alternate = default.copy(typography = NativeTypography(mapOf(
            NativeTypographyRole.LABEL_LARGE to default.typography[NativeTypographyRole.LABEL_LARGE]
                .copy(fontSize = 23.sp, lineHeight = 29.sp),
        )))
        var defaultExportWidth = 0
        var defaultTrimWidth = 0
        for (appearance in listOf(default, alternate)) {
            withWindow { activity ->
                val density = Density(activity)
                val resolver = createFontFamilyResolver(activity)
                val colors = NativeColorScheme.fallback(darkTheme = appearance === alternate)
                val exportRecipe = appearance.buttons.rangeExport
                val trimRecipes = appearance.buttons.trimActions
                var exports = 0
                var saves = 0
                val export = NativeMaterialButtonView(activity, exportRecipe.button, density, resolver) { exports++ }
                val save = NativeMaterialButtonView(activity, trimRecipes.save.button, density, resolver) { saves++ }
                val cancel = NativeMaterialButtonView(activity, trimRecipes.cancel, density, resolver) {}
                val exportGlyph = ColorDrawable(Color.WHITE).also(export::setLeadingIcon)
                val saveGlyph = ColorDrawable(Color.WHITE).also(save::setLeadingIcon)
                val cancelGlyph = ColorDrawable(Color.WHITE).also(cancel::setLeadingIcon)
                val exportGroup = NativeGroupLayout(activity, checkNotNull(exportRecipe.button.surface.radiusDp)).apply {
                    addRow(export, NativeGroupLayout.LayoutParams(-1, -1))
                }
                val trimGroup = NativeGroupLayout(activity, trimRecipes.cornerRadiusDp, LinearLayout.HORIZONTAL).apply {
                    addRow(cancel, NativeGroupLayout.LayoutParams(-2, -2))
                    addRow(save, NativeGroupLayout.LayoutParams(-2, -2).apply { dividerStart = true })
                }
                fun dp(value: Int) = (value * density.density).roundToInt()
                val exportHeight = dp(checkNotNull(exportRecipe.surroundingHeightDp))
                val host = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(exportGroup, LinearLayout.LayoutParams(-1, exportHeight))
                    addView(trimGroup, LinearLayout.LayoutParams(-2, -2))
                }
                exportRecipe.bindSurroundingSurface(exportGroup, colors.buttonPalette, enabled = true)
                trimRecipes.bindSurface(trimGroup, colors)
                export.bind("Export", true, colors.buttonPalette)
                cancel.bind("Cancel", false, colors.buttonPalette)
                save.bind("Saving", false, colors.buttonPalette, busy = true)
                activity.setContentView(host)
                return@withWindow {
                    assertTrue(export.isAttachedToWindow && save.isAttachedToWindow)
                    assertEquals(dp(50), export.height)
                    assertEquals(dp(15) + dp(20) + dp(7), export.label.left)
                    assertEquals(dp(20), exportGlyph.bounds.width())
                    assertEquals(dp(18), saveGlyph.bounds.width())
                    assertEquals(colors.primary, exportGroup.paintedFillColor)
                    assertEquals(colors.surfaceContainerHigh, trimGroup.paintedFillColor)
                    assertEquals(colors.onPrimary, glyphColor(exportGlyph))
                    assertEquals(colors.primary, glyphColor(saveGlyph))
                    assertEquals(colors.onSurface, glyphColor(cancelGlyph))
                    assertTrue(export.performClick())
                    assertFalse(save.performClick())
                    assertFalse(cancel.performClick())
                    assertEquals(1, exports)
                    assertEquals(0, saves)
                    if (appearance === default) {
                        defaultExportWidth = export.label.measuredWidth
                        defaultTrimWidth = save.label.measuredWidth
                    } else {
                        assertTrue(export.label.measuredWidth > defaultExportWidth)
                        assertTrue(save.label.measuredWidth > defaultTrimWidth)
                    }
                    export.close(); cancel.close(); save.close()
                }
            }
        }
    }

    @Test fun rowSampledCloseImageDimsAndRecoversOnIndependentButtonEnableChanges() = withWindow { activity ->
        val colors = NativeColorScheme.fallback(darkTheme = false)
        val density = Density(activity)
        val resolver = createFontFamilyResolver(activity)
        val row = NativeSettingsRow(activity, colors.settingsControls)
        val glyph = ImageView(activity).apply { setImageDrawable(ColorDrawable(Color.WHITE)) }
        var closes = 0
        val button = NativeMaterialButtonView(activity, NativeUiAppearance.Default.buttons.raisedIcon, density, resolver) { closes++ }
        row.bind("Tab")
        row.isEnabled = false
        button.contentDescription = "Close tab"
        button.setLeadingView(glyph)
        button.bind("", false, colors.buttonPalette)
        row.setAccessory(button) { ink -> button.setLeadingImageTint(ink) }
        activity.setContentView(row)
        return@withWindow {
            assertTrue(button.isAttachedToWindow)
            assertEquals(97, Color.alpha(checkNotNull(glyph.imageTintList).defaultColor))
            assertFalse(button.performClick())
            button.bind("", true, colors.buttonPalette)
            assertEquals(255, Color.alpha(checkNotNull(glyph.imageTintList).defaultColor))
            assertTrue(button.performClick())
            assertEquals(1, closes)
            button.bind("", false, colors.buttonPalette)
            assertEquals(97, Color.alpha(checkNotNull(glyph.imageTintList).defaultColor))
            row.setAccessory(null)
            button.close()
        }
    }

    private fun glyphColor(glyph: ColorDrawable): Int {
        val bounds = glyph.bounds
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        try {
            glyph.draw(Canvas(bitmap).apply { translate(-bounds.left.toFloat(), -bounds.top.toFloat()) })
            return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun withWindow(mount: (Activity) -> () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var assertions: () -> Unit
            instrumentation.runOnMainSync { assertions = mount(activity) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync(assertions)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
