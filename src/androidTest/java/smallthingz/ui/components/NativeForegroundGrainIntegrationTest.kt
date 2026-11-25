package smallthingz.ui.components

import android.graphics.Color
import android.widget.Button
import android.view.View
import kotlin.math.roundToInt
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun switchThemeRebindKeepsThinOutlineAndSameStateThumbGeometry() = onMain { base ->
        for (densityDpi in listOf(160, 240, 420)) {
            val config = android.content.res.Configuration(base.resources.configuration).apply {
                this.densityDpi = densityDpi
                fontScale = 1.3f
            }
            val context = base.createConfigurationContext(config)
            val density = context.resources.displayMetrics.density
            val dark = NativeDefaultColorScheme.resolve(true, 30) { context.getColor(it) }
            val light = NativeDefaultColorScheme.resolve(false, 30) { context.getColor(it) }
            for (initial in listOf(light, dark)) for (checked in listOf(false, true)) {
                for (enabled in listOf(false, true)) for (pressed in listOf(false, true)) {
                    for (rtl in listOf(false, true)) {
                        // Public scheme constructor: both themes must be correct on their first draw.
                        val view = NativeMaterialSwitchView(context, initial, checked, enabled)
                        view.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        view.setVisualPressed(pressed)
                        measureSwitch(view)
                        val offset = checkNotNull(field(view, "offsetMotion"))
                        val position = field(offset, "value") as Float
                        val width = view.measuredWidth
                        val height = view.measuredHeight
                        assertSwitchDraw(view, checked, outlineDp = 1f)
                        for (colors in listOf(light, dark, light)) {
                            view.bindSurfacePreset(colors.surfacePreset)
                            view.setPalette(colors.switchPalette)
                            view.setState(checked, enabled)
                            assertSame(offset, field(view, "offsetMotion"))
                            assertEquals(position, field(offset, "value") as Float, 0f)
                            assertEquals(false, field(offset, "isRunning"))
                            assertEquals(width, view.measuredWidth)
                            assertEquals(height, view.measuredHeight)
                            // New user contract replaces the old 16/24/28dp state sizes with 24dp.
                            assertSwitchDraw(view, checked, outlineDp = 1f)
                        }
                        // Explicit customization survives palette-only bind; reset restores default.
                        view.setTrackOutline(3f, Color.RED)
                        view.setPalette(dark.switchPalette)
                        assertSwitchDraw(view, checked, outlineDp = 3f)
                        // Zero width must produce NO current outline draw, never reuse prior Paint state.
                        view.setTrackOutline(0f, Color.RED)
                        assertSwitchDraw(view, checked, outlineDp = 0f)
                        view.bindSurfacePreset(dark.surfacePreset)
                        view.bindSurfacePreset(null)
                        assertSwitchDraw(view, checked, outlineDp = 1f)
                    }
                }
            }
            // The public row used by Settings keeps its label, model and external bounds on rebind.
            for (checked in listOf(false, true)) {
                val state = smallthingz.ui.common.ToggleState("Settings toggle", checked, true)
                var changes = 0
                val row = smallthingz.ui.common.ToggleRow(context, state,
                    smallthingz.ui.common.ControlTheme.resolve(context, true)) { changes++ }
                try {
                    val view = row.view
                    val switch = descendants(view).filterIsInstance<NativeMaterialSwitchView>().single()
                    fun layoutRow() {
                        view.measure(View.MeasureSpec.makeMeasureSpec((320f * density).roundToInt(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    }
                    layoutRow()
                    val bounds = listOf(view.width, view.height, switch.left, switch.top, switch.width, switch.height)
                    for (isDark in listOf(false, true, false)) {
                        row.bind(state, smallthingz.ui.common.ControlTheme.resolve(context, isDark))
                        layoutRow()
                        assertEquals(bounds, listOf(view.width, view.height, switch.left, switch.top, switch.width, switch.height))
                        assertEquals(state.label, descendants(view).filterIsInstance<smallthingz.ui.text.view.ParagraphView>()
                            .first().getText())
                        assertSwitchDraw(switch, checked, outlineDp = 1f)
                    }
                    assertEquals(0, changes)
                } finally { row.close() }
            }
        }
    }

    /** Android-only: real attached traversal and Choreographer callbacks, never manual spring frames. */
    @Test fun switchVisibleRoundTripKeepsFixedThumbThroughPressAndThemeRebind() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: android.widget.FrameLayout? = null
        withRetiringTestActivity(activity, root = { host }) {
            lateinit var view: NativeMaterialSwitchView
            onMain {
                val root = android.widget.FrameLayout(activity)
                host = root
                view = NativeMaterialSwitchView(activity, NativeDefaultColorScheme.resolve(true, 30) { activity.getColor(it) })
                root.addView(view, android.widget.FrameLayout.LayoutParams(-2, -2))
                activity.setContentView(root)
            }
            for (rtl in listOf(false, true)) {
                awaitSwitchDraw(view, false) { view.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
                awaitSwitchDraw(view, false) { view.setVisualPressed(true) }
                awaitSwitchDraw(view, false) { view.setVisualPressed(false) }
                // Released OFF -> ON: every in-flight draw must retain the fixed diameter.
                awaitSwitchDraw(view, true, expectMotion = true) { view.setState(true, true) }
                awaitSwitchDraw(view, true) {
                    val light = NativeDefaultColorScheme.resolve(false, 30) { activity.getColor(it) }
                    view.bindSurfacePreset(light.surfacePreset)
                    view.setPalette(light.switchPalette)
                    view.setVisualPressed(true)
                }
                awaitSwitchDraw(view, true) { view.setVisualPressed(false) }
                awaitSwitchDraw(view, false, expectMotion = true) { view.setState(false, true) }
                awaitSwitchDraw(view, false) {
                    val dark = NativeDefaultColorScheme.resolve(true, 30) { activity.getColor(it) }
                    view.bindSurfacePreset(dark.surfacePreset)
                    view.setPalette(dark.switchPalette)
                    view.setState(false, false)
                    view.setVisualPressed(true)
                }
                awaitSwitchDraw(view, false) {
                    view.setVisualPressed(false)
                    view.setState(false, true)
                }
            }
        }
    }

    private fun awaitSwitchDraw(view: NativeMaterialSwitchView, checked: Boolean,
        expectMotion: Boolean = false, change: () -> Unit) {
        val drawn = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        var movingDraws = 0
        val listener = android.view.ViewTreeObserver.OnDrawListener {
            if (drawn.count != 0L && view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
                view.width > 0 && !view.isLayoutRequested) {
                try {
                    val running = field(checkNotNull(field(view, "offsetMotion")), "isRunning") as Boolean
                    // Samples include the moving thumb; only its position waits for spring settlement.
                    assertSwitchDraw(view, checked, settled = !running)
                    if (running) movingDraws++
                    else {
                        if (expectMotion) assertTrue("No in-flight draw sampled", movingDraws > 0)
                        drawn.countDown()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                    drawn.countDown()
                }
            }
        }
        try {
            onMain {
                view.viewTreeObserver.addOnDrawListener(listener)
                change()
                view.invalidate()
            }
            assertTrue("Attached visible switch did not draw and settle", drawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally { onMain { view.viewTreeObserver.removeOnDrawListener(listener) } }
    }

    private fun measureSwitch(view: NativeMaterialSwitchView) {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private data class SwitchShape(val bounds: android.graphics.RectF, val rx: Float, val ry: Float,
        val alpha: Int, val stroke: Float, val style: android.graphics.Paint.Style)

    /** Capture actual draw calls by paint identity; grain draws cannot impersonate the three layers. */
    private fun assertSwitchDraw(view: NativeMaterialSwitchView, checked: Boolean,
        settled: Boolean = true, outlineDp: Float? = null) {
        val density = view.resources.displayMetrics.density
        val width = (52f * density).roundToInt()
        val height = (32f * density).roundToInt()
        val diameter = (24f * density).toInt()
        val top = ((height - diameter) / 2f).roundToInt()
        val logicalLeft = ((if (checked) 24f else 4f) * density).toInt()
        val left = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) width - diameter - logicalLeft else logicalLeft
        assertEquals(width, view.width)
        assertEquals(height, view.height)
        val paints = listOf("trackPaint", "thumbPaint", "borderPaint").map { field(view, it) as android.graphics.Paint }
        val draws = Array(3) { mutableListOf<SwitchShape>() }
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = object : android.graphics.Canvas(bitmap) {
                override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float,
                    rx: Float, ry: Float, paint: android.graphics.Paint) {
                    val index = paints.indexOfFirst { it === paint }
                    if (index >= 0) draws[index].add(SwitchShape(android.graphics.RectF(left, top, right, bottom),
                        rx, ry, paint.alpha, paint.strokeWidth, paint.style))
                    super.drawRoundRect(left, top, right, bottom, rx, ry, paint)
                }
            }
            view.draw(canvas)
            val track = draws[0].single()
            assertEquals(android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), track.bounds)
            assertEquals(height / 2f, track.rx, 0f)
            assertEquals(track.rx, track.ry, 0f)
            assertEquals(android.graphics.Paint.Style.FILL, track.style)
            assertTrue(track.alpha > 0)
            val thumb = draws[1].single()
            assertEquals(diameter.toFloat(), thumb.bounds.width(), 0f)
            assertEquals(diameter.toFloat(), thumb.bounds.height(), 0f)
            assertEquals(top.toFloat(), thumb.bounds.top, 0f)
            assertEquals(diameter / 2f, thumb.rx, 0f)
            assertEquals(thumb.rx, thumb.ry, 0f)
            assertEquals(android.graphics.Paint.Style.FILL, thumb.style)
            assertTrue(thumb.alpha > 0)
            if (settled) assertEquals(left.toFloat(), thumb.bounds.left, 0f)
            // Outside the rounded silhouette stays transparent regardless of palette or grain.
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(0, Color.alpha(bitmap.getPixel(width - 1, height - 1)))
            if (outlineDp != null) {
                if (outlineDp == 0f) assertTrue("Zero width must omit this draw", draws[2].isEmpty())
                else {
                    val border = draws[2].single() // Current draw, not a stale mutable Paint property.
                    val stroke = outlineDp * density
                    val inset = stroke / 2f
                    assertEquals(stroke, border.stroke, 0f)
                    assertEquals(android.graphics.Paint.Style.STROKE, border.style)
                    assertEquals(android.graphics.RectF(inset, inset, width - inset, height - inset), border.bounds)
                    assertEquals(height / 2f - inset, border.rx, 0f)
                    assertEquals(border.rx, border.ry, 0f)
                    if (checked) assertEquals(0, border.alpha)
                    else assertTrue("OFF outline must be drawn visibly on every bind", border.alpha > 0)
                }
            }
        } finally { bitmap.recycle() }
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
