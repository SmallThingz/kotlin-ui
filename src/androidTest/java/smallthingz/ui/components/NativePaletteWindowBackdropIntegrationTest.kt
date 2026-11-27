package smallthingz.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable

@RunWith(AndroidJUnit4::class)
class NativePaletteWindowBackdropIntegrationTest {
    @Test fun resolvedWindowUsesBackgroundRoleAndBackgroundGrain() = onMain {
        val palette = NativeColorScheme.fallback(false).copy(
            background = Color.BLACK, surface = Color.WHITE,
            surfacePreset = NativeGrainPreset(backgroundGrain = .5f, foregroundGrain = 0f),
        )
        val style = NativeBackdropStyle(pageGrainSeed = null)
        assertEquals(textSurfaceRange(Color.BLACK, 0xff090909.toInt()),
            style.windowBackground(palette).paintedTextSurface)
        assertEquals(textSurfaceRange(Color.BLACK), style.pageBackground(palette).paintedTextSurface)
        assertEquals(textSurfaceRange(Color.BLACK),
            style.copy(windowGrainSeed = null).windowBackground(palette).paintedTextSurface)
    }

    @Test fun bindingRetainsDrawableBoundsAndCallbackWhileUpdatingSeedFillAndIntensity() = onMain { context ->
        val palette = NativeColorScheme.fallback(false).copy(
            background = Color.BLACK,
            surfacePreset = NativeGrainPreset(backgroundGrain = 0f, foregroundGrain = 1f),
        )
        val style = NativeBackdropStyle.Default
        val drawable = style.windowBackground(palette)
        val owner = View(context).apply { background = drawable }
        drawable.setBounds(3, 4, 103, 204)
        val callback = drawable.callback
        assertEquals(textSurfaceRange(Color.BLACK), drawable.paintedTextSurface)

        val half = palette.copy(surfacePreset = NativeGrainPreset(.5f, 0f))
        style.bindWindowBackground(drawable, half)
        assertEquals(textSurfaceRange(Color.BLACK, 0xff090909.toInt()), drawable.paintedTextSurface)
        style.copy(windowGrainSeed = null).bindWindowBackground(drawable, half)
        assertEquals(textSurfaceRange(Color.BLACK), drawable.paintedTextSurface)
        // A raw palette restores the existing full-grain fallback and the configured seed.
        style.bindWindowBackground(drawable, palette.copy(background = Color.WHITE, surfacePreset = null))
        assertEquals(textSurfaceRange(0xffeeeeee.toInt(), Color.WHITE), drawable.paintedTextSurface)
        assertSame(drawable, owner.background)
        assertSame(callback, drawable.callback)
        assertEquals(Rect(3, 4, 103, 204), drawable.bounds)
    }

    @Test fun contextOverloadKeepsFrameworkThemeWhilePaletteOverloadUsesAppChoice() = onMain { context ->
        val themed = ContextThemeWrapper(context, android.R.style.Theme_Material_Light_NoActionBar)
        val value = TypedValue()
        check(themed.theme.resolveAttribute(android.R.attr.colorBackground, value, true))
        val frameworkColor = if (value.resourceId != 0) themed.getColor(value.resourceId) else value.data
        val appColor = frameworkColor xor 0x00ffffff
        val palette = NativeColorScheme.fallback(true).copy(background = appColor)
        val style = NativeBackdropStyle(windowGrainSeed = null)
        val framework = requireNotNull(style.windowBackground(themed))
        val app = style.windowBackground(palette)
        assertEquals(textSurfaceRange(frameworkColor), framework.paintedTextSurface)
        assertEquals(textSurfaceRange(appColor), app.paintedTextSurface)
        assertNotEquals(framework.paintedTextSurface, app.paintedTextSurface)
    }

    private fun onMain(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { block(instrumentation.targetContext) }
    }
}
