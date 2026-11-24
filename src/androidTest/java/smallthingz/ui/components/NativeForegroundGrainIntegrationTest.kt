package smallthingz.ui.components

import android.graphics.Color
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable

@RunWith(AndroidJUnit4::class)
class NativeForegroundGrainIntegrationTest {
    private val preset = NativeDarkSurfacePreset.Amoled

    @Test fun groupIntensityOnlyChangesNotifyInkOnceAndRestoreFlatLightPaint() = onMain {
        var notifications = 0
        val group = NativeGroupLayout(it, onPaintedSurfaceChanged = { notifications++ })
        group.setPaletteImmediately(Color.BLACK, Color.TRANSPARENT)
        val flat = group.paintedTextSurface
        notifications = 0
        group.setSurfacePreset(preset)
        assertEquals(1, notifications)
        assertNotEquals(flat, group.paintedTextSurface)
        assertEquals(textSurfaceRange(Color.BLACK, 0xff090909.toInt()), group.paintedTextSurface)
        group.setSurfacePreset(preset)
        assertEquals(1, notifications)
        group.setSurfacePreset(null)
        assertEquals(2, notifications)
        assertEquals(flat, group.paintedTextSurface)
        group.setBackgroundProgress(.5f, Color.WHITE)
        assertEquals(0L, group.paintedTextSurface)
    }

    @Test fun surfaceRetainsRippleDuringIntensityChangesAndStyleRebind() = onMain {
        val style = NativeSurfaceStyle(Color.BLACK, rippleColor = Color.WHITE)
        val surface = NativeSurface(it, style)
        surface.setSurfacePreset(preset)
        val retained = surface.background
        val half = surface.paintedTextSurface
        surface.applyStyle(style.copy(outlineColor = Color.GRAY))
        assertSame(retained, surface.background)
        assertEquals(half, surface.paintedTextSurface)
        surface.setSurfacePreset(preset.copy(foregroundGrain = 1f))
        assertSame(retained, surface.background)
        assertNotEquals(half, surface.paintedTextSurface)
        surface.setSurfacePreset(null)
        assertEquals(textSurfaceRange(Color.BLACK), surface.paintedTextSurface)
    }

    @Test fun controlPaletteRebindKeepsTheExistingBackgroundAndRecipeGroupsReceiveGrain() = onMain {
        val colors = preset.colorScheme().resolve(true, 30) { error("Unused") }
        val button = Button(it)
        ControlStyle(it, colors.pageControls).applyButton(button, filled = true)
        val background = button.background
        ControlStyle(it, colors.copy(surfacePreset = preset.copy(foregroundGrain = 1f)).pageControls)
            .applyButton(button, filled = true)
        assertSame(background, button.background)
        ControlStyle(it, NativeColorScheme.fallback(false).pageControls).applyButton(button, filled = true)
        assertSame(background, button.background)

        val group = NativeGroupLayout(it)
        NativeButtonRecipes().trimActions.bindSurface(group, colors)
        assertNotEquals(textSurfaceRange(group.paintedFillColor), group.paintedTextSurface)
        NativeGroupSurfaceRecipes.Default.rangeTimeBubble.bind(group, colors, false, 1f)
        assertNotEquals(textSurfaceRange(group.paintedFillColor), group.paintedTextSurface)
    }

    @Test fun drawableRangeTracksIntensitySeedAndUnknownOutputEffects() = onMain {
        val drawable = NoiseDrawable(Color.BLACK)
        drawable.setNoiseIntensity(.5f)
        assertEquals(textSurfaceRange(Color.BLACK, 0xff090909.toInt()), drawable.paintedTextSurface)
        drawable.setGrainSeed(null)
        assertEquals(textSurfaceRange(Color.BLACK), drawable.paintedTextSurface)
        drawable.setGrainSeed(NoiseDrawable.SEED_BACKGROUND)
        drawable.alpha = 100
        assertEquals(0L, drawable.paintedTextSurface)
        drawable.alpha = 255
        drawable.setNoiseIntensity(0f)
        assertEquals(textSurfaceRange(Color.BLACK), drawable.paintedTextSurface)
    }

    @Test fun segmentedExplicitOverridesSurvivePaletteAndPresetChangesWithoutRecreatingChoices() = onMain { context ->
        val dark = preset.colorScheme().resolve(true, 30) { error("Unused") }
        val control = NativeSegmentedControl(context, dark.pageControls)
        val options = listOf(NativeChoice("first", "First"), NativeChoice("second", "Second"))
        var requests = 0
        control.onSelectionRequest = { requests++; true }
        try {
            control.bind(options, "first", dark.pageControls)
            control.setNoiseIntensity(.2f)
            control.setOutlineWidth(2f)
            val surface = control.getChildAt(0) as NativeSurface
            val chips = descendants(control).filterIsInstance<NativeChip>().toList()
            for (palette in listOf(dark.pageControls.copy(content = Color.YELLOW),
                NativeColorScheme.fallback(false).pageControls, dark.pageControls)) {
                control.bind(options, "first", palette)
                val expected = NoiseDrawable(surface.paintedFillColor).apply { setNoiseIntensity(.2f) }
                assertEquals(expected.paintedTextSurface, surface.paintedTextSurface)
                val applied = field(surface, "appliedStyle") as NativeSurfaceStyle
                assertEquals(2f, applied.outlineWidthDp, 0f)
                val retained = descendants(control).filterIsInstance<NativeChip>().toList()
                assertEquals(chips.size, retained.size)
                chips.indices.forEach { assertSame(chips[it], retained[it]) }
            }
            assertEquals(0, requests)
        } finally { control.close() }
    }

    @Test fun libraryEnvironmentOnlyRebindUpdatesGrainAndRetainsRowExpansionAndActions() = onMain { context ->
        val dark = preset.colorScheme().resolve(true, 30) { error("Unused") }
        val env = smallthingz.ui.reverb.ReverbEnvironment(smallthingz.ui.unit.Density(context),
            smallthingz.ui.text.font.createFontFamilyResolver(context), dark)
        val runtime = NativeModalWindowRuntime(context)
        try {
            val row = smallthingz.ui.reverb.library.ReverbLibraryRecordingRow(context, env, runtime.scope, android.view.View(context))
            val model = smallthingz.ui.reverb.library.LibraryRecordingRow("row", "Title", "Detail", null,
                false, false, true, null, expanded = true)
            var activations = 0
            val actions = smallthingz.ui.reverb.library.LibraryRecordingRowActions({ activations++ }, null, null)
            try {
                row.bind(model, actions)
                val expansion = android.view.View(context)
                row.setExpansionView(expansion)
                val expansionParent = expansion.parent
                val surface = row.getChildAt(0) as NativeGroupLayout
                val disc = descendants(row).filterIsInstance<NativeLeadingDisc>().single()
                for (palette in listOf(NativeColorScheme.fallback(false), dark)) {
                    row.setEnvironment(env.withPalette(palette))
                    val expected = NoiseDrawable(surface.paintedFillColor).apply {
                        setNoiseIntensity(palette.surfacePreset?.foregroundGrain ?: 0f)
                    }
                    assertEquals(expected.paintedTextSurface, surface.paintedTextSurface)
                    val grain = field(disc, "grain") as smallthingz.ui.graphics.ViewGrain
                    val expectedDisc = NoiseDrawable(Color.BLACK).apply {
                        setNoiseIntensity(palette.surfacePreset?.foregroundGrain ?: 0f)
                    }
                    assertEquals(expectedDisc.paintedTextSurface, grain.surfaceRange(Color.BLACK))
                    assertSame(disc, descendants(row).filterIsInstance<NativeLeadingDisc>().single())
                    assertSame(model, field(row, "row"))
                    assertSame(actions, field(row, "actions"))
                    assertSame(expansionParent, expansion.parent)
                    assertEquals("row", row.recordingId)
                }
                assertEquals(0, activations)
            } finally {
                row.unbind()
            }
        } finally { runtime.close() }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(owner)
    }

    private fun descendants(view: android.view.View): Sequence<android.view.View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun onMain(block: (android.content.Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { block(instrumentation.targetContext) }
    }
}
