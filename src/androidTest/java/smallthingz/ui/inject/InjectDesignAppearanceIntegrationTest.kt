package smallthingz.ui.inject

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeGrainPreset
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.reverb.reverbActionSheetStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(AndroidJUnit4::class)
class InjectDesignAppearanceIntegrationTest {
    @Test fun explicitThemeChoiceWinsOverEitherSystemConfiguration() = onMain { target ->
        for (systemDark in listOf(false, true)) {
            val configuration = Configuration(target.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = target.createConfigurationContext(configuration)
            for (dark in listOf(false, true)) {
                val expected = ReverbDesign.theme(context, dark)
                val actual: InjectTheme = InjectDesign.theme(context, dark)
                assertEquals("systemDark=$systemDark, explicitDark=$dark", expected.palette, actual.palette)
                assertEquals(ReverbTheme::class.java, actual.javaClass)
                assertSame(ReverbDesign.appearance, actual.controls.appearance)
                assertSame(actual.palette, actual.controls.colors)
                assertEquals(reverbActionSheetStyle(actual), InjectDesign.actionSheetStyle(actual))
            }
        }
    }

    @Test fun windowThemePreservesTheSuppliedPaletteDensityAndFontResolver() = onMain { context ->
        val density = Density(2f, 1.3f)
        val resolver = createFontFamilyResolver(context)
        val palette = ReverbDesign.theme(context, true).palette.copy(
            background = Color.BLACK,
            surface = Color.WHITE,
            primary = 0xff3578a1.toInt(),
            surfacePreset = NativeGrainPreset(backgroundGrain = 0f, foregroundGrain = .5f),
        )
        val actual: InjectTheme = InjectDesign.windowTheme(density, resolver, palette)
        assertSame(density, actual.density)
        assertSame(resolver, actual.fontResolver)
        assertSame(palette, actual.palette)
        assertSame(palette, actual.controls.colors)
        assertSame(ReverbDesign.appearance, actual.controls.appearance)
        assertEquals(reverbActionSheetStyle(actual), InjectDesign.actionSheetStyle(actual))
        // A supplied window's explicit zero-background-grain policy must survive the alias.
        assertEquals(textSurfaceRange(Color.BLACK), InjectDesign.windowBackdrop(palette).paintedTextSurface)
    }

    @Test fun canonicalBlackBackdropStillCarriesFullBackgroundGrain() = onMain { context ->
        for (dark in listOf(false, true)) {
            val palette = InjectDesign.theme(context, dark).palette
            val preset = requireNotNull(palette.surfacePreset)
            assertEquals(1f, preset.backgroundGrain, 0f)
            assertEquals(.5f, preset.foregroundGrain, 0f)
            val actual = InjectDesign.windowBackdrop(palette)
            assertEquals(ReverbDesign.windowBackdrop(palette).paintedTextSurface, actual.paintedTextSurface)
            assertNotEquals(textSurfaceRange(palette.background), actual.paintedTextSurface)
            if (dark) {
                assertEquals(Color.BLACK, palette.background)
                assertEquals(textSurfaceRange(Color.BLACK, 0xff111111.toInt()), actual.paintedTextSurface)
            }
        }
    }

    @Test fun foregroundGrainCanBeDisabledAndRestoredWithoutChangingTheBackdrop() = onMain { context ->
        for (dark in listOf(false, true)) {
            val palette = InjectDesign.theme(context, dark).palette
            val preset = requireNotNull(palette.surfacePreset)
            val group = NativeGroupLayout(context)
            group.setPaletteImmediately(Color.BLACK, palette.border)
            group.setSurfacePreset(preset)
            val textured = textSurfaceRange(Color.BLACK, 0xff090909.toInt())
            assertEquals("default foreground, dark=$dark", textured, group.paintedTextSurface)

            val noForegroundGrain = NativeGrainPreset(preset.backgroundGrain, foregroundGrain = 0f)
            group.setSurfacePreset(noForegroundGrain)
            assertEquals(textSurfaceRange(Color.BLACK), group.paintedTextSurface)
            assertEquals(
                InjectDesign.windowBackdrop(palette).paintedTextSurface,
                InjectDesign.windowBackdrop(palette.copy(surfacePreset = noForegroundGrain)).paintedTextSurface,
            )

            group.setSurfacePreset(preset)
            assertEquals("restored foreground, dark=$dark", textured, group.paintedTextSurface)
        }
    }

    private fun onMain(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { block(instrumentation.targetContext) }
    }
}
