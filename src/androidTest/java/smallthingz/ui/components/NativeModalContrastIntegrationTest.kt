package smallthingz.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EdgeEffect
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.animation.ColorTransition
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.ReverbAboutContentView
import smallthingz.ui.reverb.ReverbAboutDetails
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.reverb.menuColors
import smallthingz.ui.reverb.reverbActionSheetStyle
import smallthingz.ui.scroll.NativeAxisOverscroll
import smallthingz.ui.scroll.NativeOverscrollSource
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.scroll.NativeVerticalViewport
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp

/** Synchronous real-view paint checks: no window, animation waits, input or device-state changes. */
@RunWith(AndroidJUnit4::class)
class NativeModalContrastIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun longPressMenuFirstPaintAndLightDarkLightRebind() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val runtime = NativeModalWindowRuntime(context)
        val icons = object : NativeMenuIcons<Int> {
            override val check = 0
            override fun create(context: android.content.Context, icon: Int, color: Int) = View(context)
            override fun bind(view: View, icon: Int, color: Int) = Unit
        }
        val initial = palette(true).menuColors()
        val menu = NativeMenuContent(context, Density(1f), TextStyle(fontSize = 24.sp),
            createFontFamilyResolver(context), initial, NativeAnchoredMenuRecipe(), icons, NativeVerticalScrollState(),
            runtime.frames, runtime.scope, { false })
        try {
            val viewport = menu.viewport
            val exactWidth = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
            val exactHeight = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
            viewport.measure(exactWidth, exactHeight)
            viewport.layout(0, 0, 200, 100)
            val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
            try { viewport.draw(Canvas(bitmap)) }
            finally { bitmap.recycle() }

            // Inspect the actual edge created by the viewport's normal overscroll owner.
            val ownerField = NativeVerticalViewport::class.java.getDeclaredField("overscroll").apply { isAccessible = true }
            val owner = ownerField.get(viewport) as NativeAxisOverscroll
            val nearField = NativeAxisOverscroll::class.java.getDeclaredField("near").apply { isAccessible = true }
            owner.scroll(10f, NativeOverscrollSource.USER_INPUT) { 0f }
            val edge = nearField.get(owner) as EdgeEffect
            fun assertRetainedGlow(color: Int) {
                assertSame(viewport, descendants(menu).filterIsInstance<NativeVerticalViewport>().single())
                assertSame(owner, ownerField.get(viewport))
                assertSame(edge, nearField.get(owner))
                assertEquals(color, edge.color)
            }
            assertRetainedGlow(initial.muted)
            assertEquals(grainRange(initial.raised, .5f), menu.paintedTextSurface)
            for (dark in listOf(true, false, true, false)) {
                val palette = palette(dark)
                val intensity = palette.surfacePreset?.foregroundGrain ?: 0f
                val colors = palette.menuColors()
                if (dark) assertTrue(colors.raised > palette.raised)
                else assertEquals(palette.raised, colors.raised)
                // A stale/equal ink target must still be corrected against the current paint.
                menu.bindActions(listOf(NativeMenuAction("Copy", 0, palette.raised, palette.ink) {}), colors)
                assertRetainedGlow(colors.muted)
                val label = descendants(menu).filterIsInstance<ParagraphView>().single()
                assertEquals(grainRange(colors.raised, intensity), menu.paintedTextSurface)
                assertEquals(menu.paintedTextSurface, label.paintedTextSurface!!.asLong)
                assertInk(label, textSurfaceForeground(menu.paintedTextSurface, palette.raised))
                menu.bindGrain(intensity)
                assertFalse("An unchanged grain bind must not dirty settled text", label.isDirty)
            }
            val choiceColors = palette(true).menuColors()
            menu.bind(NativeMenuChoices("One", listOf("One", "Two")) { it }, choiceColors) {}
            assertRetainedGlow(choiceColors.muted)
        } finally { menu.close(); runtime.close() }
    }

    @Test fun githubCardFirstPaintAndLightDarkLightRebind() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val resolver = createFontFamilyResolver(context)
        val runtime = NativeModalWindowRuntime(context)
        val details = ReverbAboutDetails("Reverb", "1.0", "GitHub", "Repository", "Unavailable")
        fun theme(dark: Boolean) = ReverbDesign.windowTheme(Density(1f), resolver, palette(dark))
        val about = ReverbAboutContentView(context, theme(true), details, {}, { false }, runtime, 0)
        try {
            for ((index, dark) in listOf(true, false, true, false).withIndex()) {
                val next = theme(dark)
                if (index != 0) about.bind(next, details)
                val panel = descendants(about).filterIsInstance<NativeFloatingPanelSurface>().single()
                val floating = NativeFloatingPanelRecipe()
                val panelFill = floating.surfaceColor(next.palette)
                assertEquals(grainRange(panelFill,
                    next.palette.surfacePreset?.foregroundGrain ?: 1f), panel.paintedTextSurface)
                val repository = descendants(about).filterIsInstance<NativeGroupLayout>().first()
                assertEquals(floating.link.fill(next.palette), ReverbDesign.modalLinkSurface(next.palette))
                assertEquals(grainRange(floating.link.fill(next.palette),
                    next.palette.surfacePreset?.foregroundGrain ?: 0f), repository.paintedTextSurface)
                val title = descendants(repository).filterIsInstance<ParagraphView>().first()
                assertEquals(repository.paintedTextSurface, title.paintedTextSurface!!.asLong)
                assertInk(title, textSurfaceForeground(repository.paintedTextSurface, next.palette.onSurface))
                // Deliberately stale target reproduces the bypass that made modal text unreadable.
                title.animateTextColor(repository.paintedFillColor)
                assertInk(title, textSurfaceForeground(repository.paintedTextSurface, repository.paintedFillColor))
            }
        } finally { about.dispose(); runtime.close() }
    }

    @Test fun githubCardHonorsActiveLinkRecipeOnFirstPaintAndRebind() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val resolver = createFontFamilyResolver(context)
        val runtime = NativeModalWindowRuntime(context)
        val details = ReverbAboutDetails("Reverb", "1.0", "GitHub", "Repository", "Unavailable")
        val base = palette(false)
        val colors = base.copy(surfaceContainerHighest = base.surface)
        val appearance = ReverbDesign.appearance
        val floating = appearance.modals.floating
        fun theme(elevationDp: Float): ReverbTheme {
            val panel = floating.copy(link = floating.link.copy(elevationDp = elevationDp))
            return ReverbTheme(Density(1f), resolver, colors,
                appearance.copy(modals = appearance.modals.copy(floating = panel)))
        }
        val about = ReverbAboutContentView(context, theme(0f), details, {}, { false }, runtime, 0)
        try {
            val repository = descendants(about).filterIsInstance<NativeGroupLayout>().first()
            val defaultFill = ReverbDesign.modalLinkSurface(colors)
            assertNotEquals(defaultFill, colors.surface)
            assertEquals(colors.surface, repository.paintedFillColor)

            val rebound = theme(16f)
            val expected = rebound.appearance.modals.floating.link.fill(colors)
            assertNotEquals(defaultFill, expected)
            about.bind(rebound, details)
            assertEquals(expected, repository.paintedFillColor)
        } finally { about.dispose(); runtime.close() }
    }

    @Test fun sheetTitleFirstPaintRebindAndControlPaintOwnership() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val resolver = createFontFamilyResolver(context)
        fun style(dark: Boolean) = reverbActionSheetStyle(
            ReverbDesign.windowTheme(Density(1f), resolver, palette(dark)))
        var current = style(true)
        val content = NativeSheetContent(context, current, "Confirm", { null }, null, {})
        val surface = NativeSheetSurface.PaintedSurface(context, current, NativeDialogEdge.BOTTOM)
        surface.clip.addView(content)
        val body = FrameLayout(context)
        val custom = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val customLabel = ParagraphView(context, TextStyle(fontSize = 24.sp), Density(1f), resolver, Color.WHITE)
        custom.addView(customLabel)
        body.addView(custom)
        val control = NativeSurface(context, NativeSurfaceStyle(Color.WHITE))
        val controlLabel = ParagraphView(context, TextStyle(), Density(1f), resolver, Color.BLACK)
        control.addView(controlLabel)
        body.addView(control)
        val button = NativeMaterialButtonView(context, NativeUiAppearance.Default.buttons.filled,
            Density(1f), resolver, onClick = {})
        body.addView(button)
        content.setContent(body, FrameLayout(context))
        for (dark in listOf(true, false, true, false)) {
            current = style(dark)
            surface.updateStyle(current)
            content.updateStyle(current)
            assertEquals(if (dark) .5f else 1f, current.recipe.grainIntensity, 0f)
            assertEquals(NoiseDrawable.SEED_BACKGROUND, current.recipe.grainSeed)
            assertEquals(2f, current.recipe.handleHeightDp, 0f)
            assertEquals(16f, current.recipe.handleVerticalPaddingDp, 0f)
            val source = palette(dark)
            val common = NativeUiAppearance.Default.modals.actionSheetStyle(Density(1f), resolver, source)
            assertEquals(common, current)
            val low = source.surfaceContainerLow
            val expectedFill = Color.argb(Color.alpha(low), (Color.red(low) * .92f).toInt(),
                (Color.green(low) * .92f).toInt(), (Color.blue(low) * .92f).toInt())
            assertEquals(expectedFill, current.palette.surfaceContainerLow)
            assertEquals(grainRange(expectedFill, current.recipe.grainIntensity),
                surface.paintedTextSurface)
            val title = content.titleView
            assertEquals(surface.paintedTextSurface, title.paintedTextSurface!!.asLong)
            assertInk(title, textSurfaceForeground(surface.paintedTextSurface, current.palette.title))
            assertNull(customLabel.paintedTextSurface)
            assertNull(controlLabel.paintedTextSurface)
            assertNull("Directly painted button ink stays button-owned", button.label.paintedTextSurface)
        }
        button.close()
    }

    @Test fun sheetTooltipInkTracksCurrentFillAtIntermediateThemeFrame() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val resolver = createFontFamilyResolver(context)
        fun style(dark: Boolean): NativeActionSheetStyle {
            val base = reverbActionSheetStyle(ReverbDesign.windowTheme(Density(1f), resolver, palette(dark)))
            return base.copy(tooltipStyle = base.tooltipStyle.copy(fontSize = 24.sp))
        }
        val light = style(false)
        val dark = style(true)
        val type = Class.forName("smallthingz.ui.components.NativePlainTooltipWindow\$TooltipBubble")
        val bubble = type.getDeclaredConstructor(Context::class.java, NativeActionSheetStyle::class.java,
            String::class.java).apply { isAccessible = true }.newInstance(context, light, "Drag handle") as ViewGroup
        val label = descendants(bubble).filterIsInstance<ParagraphView>().single()
        val motion = type.getDeclaredField("fillMotion").apply { isAccessible = true }
            .get(bubble) as ViewColorTransition
        try {
            type.getDeclaredMethod("updateStyle", NativeActionSheetStyle::class.java)
                .apply { isAccessible = true }.invoke(bubble, dark)
            val samples = ColorTransition(intArrayOf(light.palette.inverseSurface, light.palette.inverseOnSurface))
            samples.retarget(intArrayOf(dark.palette.inverseSurface, dark.palette.inverseOnSurface), 0L)
            samples.frame(ColorTransition.DURATION_NANOS * 3 / 10)
            val fill = samples.color(0)
            val ink = samples.color(1)
            assertTrue(samples.isInterpolating)
            assertNotEquals(dark.palette.inverseSurface, fill)
            assertTrue(ColorUtils.calculateContrast(ink, fill) < 4.5)

            // Publish deterministic samples through the real detached paint owners.
            label.animateTextColor(ink)
            motion.snap(fill)
            val underlay = textSurfaceRange(fill)
            val corrected = textSurfaceForeground(underlay, ink)
            assertTrue(ColorUtils.calculateContrast(corrected, fill) >= 4.5)
            assertInk(label, corrected)
            assertEquals(underlay, label.paintedTextSurface!!.asLong)
        } finally { motion.close() }
    }

    private fun palette(dark: Boolean): NativeColorScheme = if (dark)
        NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 30) { error("No resources") }
            .copy(surfacePreset = NativeDarkSurfacePreset.Amoled)
        else NativeColorScheme.fallback(false)

    private fun grainRange(fill: Int, intensity: Float): Long = NoiseDrawable(fill).apply {
        setNoiseIntensity(intensity)
    }.paintedTextSurface

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun assertInk(label: ParagraphView, ink: Int) {
        label.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        label.layout(0, 0, 240, 80)
        val bitmap = Bitmap.createBitmap(240, 80, Bitmap.Config.ARGB_8888)
        try {
            label.draw(Canvas(bitmap))
            val pixels = IntArray(240 * 80)
            bitmap.getPixels(pixels, 0, 240, 0, 0, 240, 80)
            assertTrue("Expected visible glyph ink ${Integer.toHexString(ink)}", pixels.any {
                Color.alpha(it) != 0 && (it and 0xffffff) == (ink and 0xffffff)
            })
        } finally { bitmap.recycle() }
    }
}
