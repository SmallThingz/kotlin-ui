package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.noiseSurfaceRange
import smallthingz.ui.reverb.ReverbBrowserChrome
import smallthingz.ui.reverb.ReverbBrowserChromeAction
import smallthingz.ui.reverb.ReverbBrowserChromeIcon
import smallthingz.ui.reverb.ReverbBrowserPanelSettings
import smallthingz.ui.reverb.ReverbBrowserPanelTabs
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.reverb.ReverbTitledTopBarLayout
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

/** Palette ownership checks; no animation clock, texture readiness or window is required. */
@RunWith(AndroidJUnit4::class)
class NativeToolbarPaletteIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val fill = 0xff596b7d.toInt()

    @Test fun constructionAndPresetOnlyRebindKeepOneDrawableAndNotifyInk() = main {
        val owner = View(instrumentation.targetContext)
        val style = NativeToolbarStyle.Default
        var notifications = 0
        val binding = NativeToolbarBackground(owner, style, palette(0f)) { notifications++ }
        val drawable = owner.background as NoiseDrawable
        assertEquals(noiseSurfaceRange(fill, 0), drawable.paintedTextSurface)
        assertEquals("Initial paint already has the requested grain", 0, notifications)
        assertFlat(drawable)
        for ((index, intensity) in listOf(.25f, 1f, 0f).withIndex()) {
            binding.bind(style, palette(intensity))
            assertSame(drawable, owner.background)
            assertEquals(noiseSurfaceRange(fill, (intensity * 255).roundToInt()), drawable.paintedTextSurface)
            assertEquals(index + 1, notifications)
            binding.bind(style, palette(intensity))
            assertEquals("Identical palette is a no-op", index + 1, notifications)
        }
    }

    @Test fun paletteConstructionLegacyCallsAndNullSeedPreserveTheirContracts() = main {
        val owner = View(instrumentation.targetContext)
        val style = NativeToolbarStyle.Default
        for (intensity in listOf(0f, .25f, 1f, null)) {
            NativeToolbarBackground(owner, style, palette(intensity))
            assertEquals(noiseSurfaceRange(fill, ((intensity ?: 1f) * 255).roundToInt()),
                (owner.background as NoiseDrawable).paintedTextSurface)
        }
        // Keep both the old trailing-lambda constructor and old color-only bind callable.
        val legacy = NativeToolbarBackground(owner, style, fill) {}
        val drawable = owner.background as NoiseDrawable
        assertEquals(noiseSurfaceRange(fill, 255), drawable.paintedTextSurface)
        legacy.bind(style, fill, .25f)
        assertEquals(noiseSurfaceRange(fill, 64), drawable.paintedTextSurface)
        legacy.bind(style, fill)
        assertEquals(noiseSurfaceRange(fill, 255), drawable.paintedTextSurface)
        legacy.bind(style.copy(grainSeed = null), palette(1f))
        assertSame(drawable, owner.background)
        assertEquals(noiseSurfaceRange(fill, 0), drawable.paintedTextSurface)
    }

    @Test fun browserToolbarUsesSuppliedPaletteAndToolbarRecipeAtFirstPaint() = main {
        val context = instrumentation.targetContext
        val resolver = createFontFamilyResolver(context)
        for ((intensity, seed) in listOf(0f to NoiseDrawable.SEED_TOP_BAR,
            .25f to NoiseDrawable.SEED_TOP_BAR, 1f to null)) {
            val appearance = ReverbDesign.appearance.copy(
                toolbars = ReverbDesign.appearance.toolbars.copy(grainSeed = seed))
            val theme = ReverbTheme(Density(1f), resolver, palette(intensity), appearance)
            fun action(id: String) = ReverbBrowserChromeAction(id, id, ReverbBrowserChromeIcon.Shared(ReverbIcon.Save))
            val chrome = ReverbBrowserChrome(context, theme, "Address", action("site"), action("tabs"), action("menu"))
            try {
                val toolbar = (chrome.chromeToolbar as ViewGroup).getChildAt(0)
                val drawable = toolbar.background as NoiseDrawable
                assertEquals(noiseSurfaceRange(fill, if (seed == null) 0 else (intensity * 255).roundToInt()),
                    drawable.paintedTextSurface)
                if (intensity == 0f || seed == null) assertFlat(drawable)
            } finally { chrome.close() }
        }
    }

    @Test fun browserUsesWindowBackdropRecipeWithoutAStatusSlot() = main {
        val context = instrumentation.targetContext
        val colors = palette(.25f).copy(surfaceContainerHigh = 0x80596b7d.toInt())
        val base = ReverbDesign.appearance
        val appearance = base.copy(backdrops = base.backdrops.copy(pageGrainSeed = null))
        val theme = ReverbTheme(Density(1f), createFontFamilyResolver(context), colors, appearance)
        fun action(id: String) = ReverbBrowserChromeAction(id, id, ReverbBrowserChromeIcon.Shared(ReverbIcon.Save))
        val chrome = ReverbBrowserChrome(context, theme, "Address", action("site"), action("tabs"), action("menu"))
        try {
            assertEquals(noiseSurfaceRange(fill, 0), (chrome.background as NoiseDrawable).paintedTextSurface)

            assertEquals(3, chrome.childCount)
            assertSame(chrome.pageHost, chrome.getChildAt(2))
        } finally { chrome.close() }
    }

    @Test fun titledToolbarRetainsSelectedGeometryAndPaletteOnRebindAndLegacyDefaults() = main {
        val context = instrumentation.targetContext
        val style = ReverbDesign.appearance.toolbars.copy(compactHeightDp = 82f)
        val navigation = View(context)
        val title = View(context)
        val action = View(context)
        var notifications = 0
        val toolbar = ReverbTitledTopBarLayout(context, 1f, palette(.25f), navigation, title, action,
            style = style) { notifications++ }
        val drawable = toolbar.background as NoiseDrawable
        assertToolbarHeight(toolbar, 82)
        assertEquals(noiseSurfaceRange(fill, 64), drawable.paintedTextSurface)
        assertEquals(0, notifications)

        toolbar.bindTitledToolbar(1f, palette(0f))
        assertSame(drawable, toolbar.background)
        assertSame(navigation, toolbar.contentClipView.navigationView)
        assertSame(title, toolbar.contentClipView.titleView)
        assertSame(action, toolbar.contentClipView.actionView)
        assertToolbarHeight(toolbar, 82)
        assertEquals(noiseSurfaceRange(fill, 0), drawable.paintedTextSurface)
        assertEquals(1, notifications)
        assertFlat(drawable)

        // Both original constructor forms retain the shared default recipe.
        val legacy = ReverbTitledTopBarLayout(context, 1f, palette(null), View(context), View(context), View(context))
        val legacyCallback = ReverbTitledTopBarLayout(context, 1f, palette(null), View(context), View(context), View(context)) {}
        for (bar in listOf(legacy, legacyCallback)) {
            assertToolbarHeight(bar, ReverbDesign.appearance.toolbars.compactHeightPx(1f))
            assertEquals(noiseSurfaceRange(fill, 255), (bar.background as NoiseDrawable).paintedTextSurface)
        }
    }

    @Test fun browserPanelTitlesForwardSelectedHeightGrainAndNullSeed() = main {
        val context = instrumentation.targetContext
        val base = ReverbDesign.appearance
        for ((intensity, seed) in listOf(.25f to NoiseDrawable.SEED_TOP_BAR, 1f to null)) {
            val appearance = base.copy(toolbars = base.toolbars.copy(compactHeightDp = 82f, grainSeed = seed))
            val theme = ReverbTheme(Density(1f), createFontFamilyResolver(context), palette(intensity), appearance)
            val settings = ReverbBrowserPanelSettings(context, theme, "Settings", "Back", "Undo", "Apply")
            try {
                val tabs = ReverbBrowserPanelTabs<String>(context, theme, "Tabs", "Back", "New")
                try {
                    for (panel in listOf(settings, tabs)) {
                        val toolbar = panel.getChildAt(0) as ReverbTitledTopBarLayout
                        assertToolbarHeight(toolbar, 82)
                        val drawable = toolbar.background as NoiseDrawable
                        assertEquals(noiseSurfaceRange(fill, if (seed == null) 0 else 64), drawable.paintedTextSurface)
                        if (seed == null) assertFlat(drawable)
                    }
                } finally { tabs.close() }
            } finally { settings.close() }
        }
    }

    private fun assertToolbarHeight(toolbar: ReverbTitledTopBarLayout, expected: Int) {
        toolbar.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(expected, toolbar.measuredHeight)
    }

    private fun palette(intensity: Float?) = NativeColorScheme.fallback(false).copy(surface = fill,
        surfacePreset = intensity?.let { NativeGrainPreset(it, 1f - it) })

    private fun assertFlat(drawable: NoiseDrawable) {
        val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                assertEquals(fill, bitmap.getPixel(x, y))
        } finally { bitmap.recycle() }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }
}
