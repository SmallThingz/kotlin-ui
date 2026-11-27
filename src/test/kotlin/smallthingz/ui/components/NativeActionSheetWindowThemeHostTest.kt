package smallthingz.ui.components

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.view.Window
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeActionSheetWindowThemeHostTest {
    @Test @Config(qualifiers = "night")
    fun lightAnchorKeepsLightDialogUnderSystemNight() = assertWindowTheme(light = true)

    @Test @Config(qualifiers = "notnight")
    fun darkAnchorKeepsDarkDialogUnderSystemDay() = assertWindowTheme(light = false)

    private fun assertWindowTheme(light: Boolean) {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = controller.get()
            val anchorTheme = if (light) android.R.style.Theme_DeviceDefault_Light
                else android.R.style.Theme_DeviceDefault
            val anchorContext = ContextThemeWrapper(activity, anchorTheme)
            val anchor = View(anchorContext)
            val paintPalette = NativeColorScheme.fallback(false).copy(surfacePreset = NativeGrainPreset(0f, 0f))
            val style = NativeModalRecipes().actionSheetStyle(
                Density(1f), createFontFamilyResolver(anchorContext), paintPalette)
            val sheet = NativeActionSheet.create(anchor, style, "Rename", onDismiss = {})
            try {
                val window = requireNotNull(sheet.window)
                val context = window.context
                val oppositeNight = if (light) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                assertEquals(oppositeNight, context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)

                // Use the real platform dialog parent as an independent color oracle.
                val dialogTheme = if (light) android.R.style.Theme_DeviceDefault_Light_Dialog
                    else android.R.style.Theme_DeviceDefault_Dialog
                val expectedInk = primaryTextColor(ContextThemeWrapper(anchorContext, dialogTheme))
                assertEquals(light, ColorUtils.calculateLuminance(expectedInk) < .5)
                assertEquals(expectedInk, primaryTextColor(context))
                assertTrue(window.hasFeature(Window.FEATURE_NO_TITLE))

                if (Build.VERSION.SDK_INT >= 29) {
                    val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
                    try {
                        assertEquals(light, attrs.getBoolean(0, !light))
                    } finally {
                        attrs.recycle()
                    }
                }
            } finally {
                sheet.dismiss()
            }
        } finally {
            controller.destroy()
        }
    }

    private fun primaryTextColor(context: Context): Int {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try {
            requireNotNull(attrs.getColorStateList(0)).defaultColor
        } finally {
            attrs.recycle()
        }
    }
}
