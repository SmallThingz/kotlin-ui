package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.ReverbAboutContentView
import smallthingz.ui.reverb.ReverbAboutDetails
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.reverbActionSheetStyle
import smallthingz.ui.scroll.NativeVerticalScrollState
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
        val menu = NativeMenuContent(context, Density(1f), TextStyle(fontSize = 24.sp),
            createFontFamilyResolver(context), NativeMenuColors(Color.WHITE, Color.WHITE, Color.BLACK,
                Color.BLACK, Color.BLACK), NativeAnchoredMenuRecipe(), icons, NativeVerticalScrollState(),
            runtime.frames, runtime.scope, { false })
        try {
            assertEquals(grainRange(Color.WHITE, 0f), menu.paintedTextSurface)
            for (dark in listOf(true, false, true, false)) {
                val palette = palette(dark)
                val intensity = palette.surfacePreset?.foregroundGrain ?: 0f
                val colors = NativeMenuColors(palette.raised, palette.raised, palette.ink,
                    palette.muted, palette.primary, intensity)
                // A stale/equal ink target must still be corrected against the current paint.
                menu.bindActions(listOf(NativeMenuAction("Copy", 0, palette.raised, palette.ink) {}), colors)
                val label = descendants(menu).filterIsInstance<ParagraphView>().single()
                assertEquals(grainRange(palette.raised, intensity), menu.paintedTextSurface)
                assertEquals(menu.paintedTextSurface, label.paintedTextSurface!!.asLong)
                assertInk(label, textSurfaceForeground(menu.paintedTextSurface, palette.raised))
                menu.bindGrain(intensity)
                assertFalse("An unchanged grain bind must not dirty settled text", label.isDirty)
            }
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
                assertEquals(grainRange(ReverbDesign.modalSurface(next.palette),
                    next.palette.surfacePreset?.foregroundGrain ?: 1f), panel.paintedTextSurface)
                val repository = descendants(about).filterIsInstance<NativeGroupLayout>().first()
                assertEquals(grainRange(ReverbDesign.modalLinkSurface(next.palette),
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
            if (dark) assertNotNull(current.recipe.grainSeed) else assertNull(current.recipe.grainSeed)
            assertEquals(2f, current.recipe.handleHeightDp, 0f)
            assertEquals(16f, current.recipe.handleVerticalPaddingDp, 0f)
            assertEquals(grainRange(current.palette.surfaceContainerLow, if (dark) current.recipe.grainIntensity else 0f),
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
