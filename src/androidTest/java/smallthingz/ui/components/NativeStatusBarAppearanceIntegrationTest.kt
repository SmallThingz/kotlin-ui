package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.noiseSurfaceRange
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.reverb.bindReverbTheme
import smallthingz.ui.reverb.bindSupportingParagraph
import smallthingz.ui.reverb.createReverbActionSheet
import smallthingz.ui.reverb.createReverbSupportingParagraph
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp
import kotlin.math.roundToInt

/** Shared recipe, supplied appearance, clipping, and the sheet's supporting-text binder. */
@RunWith(AndroidJUnit4::class)
class NativeStatusBarAppearanceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val fill = 0xff293b4d.toInt()

    @Test fun statusRecipeUsesBackgroundGrainWithLegacyAndNullSeedFallbacks() = main {
        for (intensity in listOf(0f, .25f, 1f, null)) for (seed in listOf(NoiseDrawable.SEED_TOP_BAR, null)) {
            val palette = NativeColorScheme.fallback(true).copy(surface = fill,
                surfacePreset = intensity?.let { NativeGrainPreset(it, 1f - it) })
            val protection = NativeStatusBarStyle(grainSeed = seed).create(palette)
            val drawable = protection.drawable as NoiseDrawable
            assertFalse(protection.darkIcons)
            assertEquals(noiseSurfaceRange(fill, if (seed == null) 0 else ((intensity ?: 1f) * 255).roundToInt()),
                drawable.paintedTextSurface)
            if (intensity == 0f || seed == null) assertFlatStrip(protection)
        }
    }

    @Test fun sheetUsesSuppliedResolverAndStatusRecipeWithoutChangingContrastPolicy() = main {
        val context = instrumentation.targetContext
        val resolver = createFontFamilyResolver(context)
        for (darkIcons in listOf(false, true)) for (windowGrain in listOf(0f, .25f, null)) {
            val requestedModes = mutableListOf<Boolean>()
            val statusPalette = NativeColorScheme.fallback(!darkIcons).copy(surface = fill,
                surfacePreset = NativeGrainPreset(.75f, 1f))
            val recipe = NativeStatusBarStyle(darkIcons = darkIcons)
            val appearance = ReverbDesign.appearance.copy(
                colorScheme = NativeColorSchemeResolver { dark, _, _ -> requestedModes.add(dark); statusPalette },
                modals = ReverbDesign.appearance.modals.copy(statusBar = recipe))
            // The light content palette must not replace deliberate contrasting status protection.
            val contentPalette = NativeColorScheme.fallback(false).copy(
                surfacePreset = windowGrain?.let { NativeGrainPreset(it, 1f) })
            val theme = ReverbTheme(Density(1f), resolver, contentPalette, appearance)
            val sheet = createReverbActionSheet(View(context), theme, "Title") {}
            try {
                val protection = (sheet.contentView.parent as View).foreground as NativeStatusBarProtection
                assertEquals(listOf(!darkIcons), requestedModes)
                assertEquals(darkIcons, protection.darkIcons)
                assertEquals(noiseSurfaceRange(fill, ((windowGrain ?: .75f) * 255).roundToInt()),
                    (protection.drawable as NoiseDrawable).paintedTextSurface)
                if (windowGrain == 0f) assertFlatStrip(protection)
            } finally { sheet.dismiss() }
        }
    }

    @Test fun retainedSheetRebindsStatusWithItsDispatchedInsets() = main {
        val context = instrumentation.targetContext
        val fontResolver = createFontFamilyResolver(context)
        val content = NativeColorScheme.fallback(false)
        val requestedModes = mutableListOf<Boolean>()
        val newFill = 0xff78654b.toInt()
        fun theme(windowGrain: Float?, surface: Int, seed: Int?, darkIcons: Boolean): ReverbTheme {
            val status = content.copy(surface = surface, surfacePreset = NativeGrainPreset(.25f, 1f))
            val appearance = ReverbDesign.appearance.copy(
                colorScheme = NativeColorSchemeResolver { dark, _, _ -> requestedModes.add(dark); status },
                modals = ReverbDesign.appearance.modals.copy(statusBar = NativeStatusBarStyle(darkIcons, seed)))
            return ReverbTheme(Density(1f), fontResolver,
                content.copy(surfacePreset = windowGrain?.let { NativeGrainPreset(it, it) }), appearance)
        }

        var dismissals = 0
        val sheet = createReverbActionSheet(View(context),
            theme(1f, fill, NoiseDrawable.SEED_TOP_BAR, false), "Title") { dismissals++ }
        try {
            val window = requireNotNull(sheet.window)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val surface = sheet.contentView
            val root = surface.parent as View
            val body = View(sheet.context)
            var actionsInvoked = 0
            val actions = View(body.context).apply { setOnClickListener { actionsInvoked++ } }
            sheet.setContent(body, actions, emptyList())
            val contentParent = body.parent
            fun protection() = root.foreground as NativeStatusBarProtection
            val initial = protection()
            dispatchStatusInsets(root, 4)
            assertEquals(noiseSurfaceRange(fill, 255), (initial.drawable as NoiseDrawable).paintedTextSurface)
            assertFalse(controller.isAppearanceLightStatusBars)

            sheet.bindReverbTheme(theme(0f, fill, NoiseDrawable.SEED_TOP_BAR, false))
            assertEquals(0f, sheet.style.recipe.grainIntensity, 0f)
            val flat = protection()
            assertNotSame(initial, flat)
            assertNull(initial.callback)
            assertSame(root, flat.callback)
            assertStripPixels(flat, fill, 4)

            sheet.bindReverbTheme(theme(null, fill, NoiseDrawable.SEED_TOP_BAR, false))
            assertEquals(noiseSurfaceRange(fill, 64), (protection().drawable as NoiseDrawable).paintedTextSurface)
            val unchangedStyle = sheet.style
            sheet.bindReverbTheme(theme(null, newFill, NoiseDrawable.SEED_TOP_BAR, false))
            val fractional = protection()
            assertEquals(noiseSurfaceRange(newFill, 64), (fractional.drawable as NoiseDrawable).paintedTextSurface)
            sheet.bindReverbTheme(theme(null, newFill, NoiseDrawable.SEED_TOP_BAR, true))
            val darkIcons = protection()
            assertSame(unchangedStyle, sheet.style)
            assertNotSame(fractional, darkIcons)
            assertTrue(controller.isAppearanceLightStatusBars)
            sheet.bindReverbTheme(theme(null, newFill, NoiseDrawable.SEED_TOP_BAR, true))
            assertSame(darkIcons, protection())

            dispatchStatusInsets(root, 6)
            sheet.bindReverbTheme(theme(null, newFill, null, true))
            assertStripPixels(protection(), newFill, 6)
            sheet.bindReverbTheme(theme(null, newFill, null, false))
            assertFalse(controller.isAppearanceLightStatusBars)
            assertStripPixels(protection(), newFill, 6)
            assertEquals(listOf(true, true, true, true, false, false, false, true), requestedModes)
            assertSame(window, sheet.window)
            assertSame(surface, sheet.contentView)
            assertSame(root, surface.parent)
            assertSame(contentParent, body.parent)
            assertSame(contentParent, actions.parent)
            assertTrue(sheet.acceptsContentActions)
            assertTrue(actions.performClick())
            assertEquals(1, actionsInvoked)
            assertEquals(0, dismissals)
        } finally { sheet.dismiss() }
    }

    @Test fun commonReplacementHandlesNullIdentityAndDisposedOwner() = main {
        val context = instrumentation.targetContext
        val theme = ReverbDesign.windowTheme(Density(1f), createFontFamilyResolver(context),
            NativeColorScheme.fallback(false).copy(onSurface = Color.BLACK))
        val sheet = createReverbActionSheet(View(context), theme, "Title") {}
        val root = sheet.contentView.parent as View
        val original = root.foreground as NativeStatusBarProtection
        val replacement = NativeStatusBarProtection(ColorDrawable(fill), darkIcons = false)
        val window = requireNotNull(sheet.window)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        try {
            sheet.updateStatusBarProtection(original)
            assertSame(original, root.foreground)
            sheet.updateStatusBarProtection(null)
            assertNull(root.foreground)
            assertNull(original.callback)
            assertTrue(controller.isAppearanceLightStatusBars)
            dispatchStatusInsets(root, 5)
            sheet.updateStatusBarProtection(replacement)
            assertStripPixels(replacement, fill, 5)
            assertFalse(controller.isAppearanceLightStatusBars)
        } finally { sheet.dismiss() }

        sheet.updateStatusBarProtection(null)
        sheet.updateStatusBarAppearance { error("Disposed sheet resolved status appearance") }
        sheet.bindReverbTheme(theme)
        assertSame(replacement, root.foreground)
        assertFalse(controller.isAppearanceLightStatusBars)
    }

    @Test fun windowThemePresetAndSuppliedNullSeedRemainAuthoritative() = main {
        val context = instrumentation.targetContext
        val palette = NativeColorScheme.fallback(false).copy(surface = Color.WHITE,
            surfacePreset = NativeGrainPreset(0f, 1f))
        val theme = ReverbDesign.windowTheme(Density(1f), createFontFamilyResolver(context), palette)
        val protection = ReverbDesign.statusBarProtection(context, theme)
        val expectedDark = ReverbDesign.appearance.resolveColorScheme(context, darkTheme = true)
        assertFalse(protection.darkIcons)
        assertEquals(noiseSurfaceRange(expectedDark.surface, 0),
            (protection.drawable as NoiseDrawable).paintedTextSurface)

        val custom = ReverbDesign.appearance.copy(
            colorScheme = NativeColorSchemeResolver { _, _, _ -> palette.copy(surface = fill) },
            modals = ReverbDesign.appearance.modals.copy(statusBar = NativeStatusBarStyle(grainSeed = null)))
        val noisyTheme = ReverbTheme(theme.density, theme.fontResolver,
            palette.copy(surfacePreset = NativeGrainPreset(1f, 0f)), custom)
        val flatProtection = ReverbDesign.statusBarProtection(context, noisyTheme)
        assertEquals(noiseSurfaceRange(fill, 0), (flatProtection.drawable as NoiseDrawable).paintedTextSurface)
        assertFlatStrip(flatProtection)
    }

    @Test fun contextOnlyStatusHelperRetainsDefaultAppearance() = main {
        val context = instrumentation.targetContext
        val expected = ReverbDesign.appearance.modals.statusBar.let { style ->
            style.create(ReverbDesign.appearance.resolveColorScheme(context, !style.darkIcons))
        }
        val actual = ReverbDesign.statusBarProtection(context)
        assertEquals(expected.darkIcons, actual.darkIcons)
        assertEquals((expected.drawable as NoiseDrawable).paintedTextSurface,
            (actual.drawable as NoiseDrawable).paintedTextSurface)
    }

    @Test fun supportingParagraphUsesSuppliedTypographyOnConstructionAndRebind() = main {
        val context = instrumentation.targetContext
        val resolver = createFontFamilyResolver(context)
        val role = NativeTypographyRole.BODY_SMALL
        val palette = NativeColorScheme.fallback(false)
        fun theme(size: Int) = ReverbTheme(Density(1f), resolver, palette,
            ReverbDesign.appearance.copy(typography = NativeTypography(mapOf(role to
                ReverbDesign.appearance.typography[role].copy(fontSize = size.sp, lineHeight = (size + 12).sp)))))
        val initial = theme(29)
        val next = theme(43)
        val text = "Supporting text\nSecond line"
        val paragraph = createReverbSupportingParagraph(context, text, role, initial)
        fun reference(theme: ReverbTheme) = ParagraphView(context, theme.appearance.typography[role],
            theme.density, theme.fontResolver, palette.onSurfaceVariant).also { it.setText(text) }
        assertEquals(height(reference(initial)), height(paragraph))
        val before = paragraph.measuredHeight
        next.bindSupportingParagraph(paragraph, role)
        assertEquals(text, paragraph.getText())
        assertEquals(height(reference(next)), height(paragraph))
        assertNotEquals(before, paragraph.measuredHeight)
    }

    private fun height(view: View): Int {
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return view.measuredHeight
    }

    private fun dispatchStatusInsets(root: View, top: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
    }

    private fun assertFlatStrip(protection: NativeStatusBarProtection) {
        protection.updateInsets(WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 4, 0, 0)).build())
        assertStripPixels(protection, fill, 4)
    }

    private fun assertStripPixels(protection: NativeStatusBarProtection, color: Int, top: Int) {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            protection.setBounds(0, 0, 16, 12)
            protection.draw(Canvas(bitmap))
            for (y in 0 until 12) for (x in 0 until 16)
                assertEquals(if (y < top) color else Color.WHITE, bitmap.getPixel(x, y))
        } finally { bitmap.recycle() }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }
}
