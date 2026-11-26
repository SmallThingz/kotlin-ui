package smallthingz.ui.components

import kotlin.math.roundToInt
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
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
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbMenuIcons
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

    @Test fun reverbMenuGlyphsKeepContrastAcrossRetainedPaletteFrames() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val runtime = NativeModalWindowRuntime(context)
        val appearance = ReverbDesign.appearance
        val light = palette(false)
        val dark = palette(true)
        val menu = NativeMenuContent(context, Density(1f), appearance.typography.buttonLabelStyle,
            createFontFamilyResolver(context), light.menuColors(), appearance.modals.anchoredPanel,
            ReverbMenuIcons, NativeVerticalScrollState(), runtime.frames, runtime.scope, { false })
        fun semanticTints(scheme: NativeColorScheme) = listOf(scheme.onSurface, scheme.onSurfaceVariant, scheme.error)
        fun bind(scheme: NativeColorScheme) {
            val (share, copy, delete) = semanticTints(scheme)
            menu.bindActions(listOf(
                NativeMenuAction("Share", ReverbIcon.Share, scheme.onSurface, share) {},
                NativeMenuAction("Copy", ReverbIcon.Copy, scheme.onSurface, copy) {},
                NativeMenuAction("Delete", ReverbIcon.Delete, scheme.error, delete) {},
            ), scheme.menuColors())
            menu.measure(atMost(320), atMost(320))
            menu.layout(0, 0, menu.measuredWidth, menu.measuredHeight)
        }
        fun glyphs() = descendants(menu).filterIsInstance<ReverbGlyphView>()
        try {
            bind(light)
            val retainedRows = menu.column.rows.toList()
            val retainedGlyphs = glyphs()
            val views = listOf(menu) + retainedRows + retainedGlyphs
            fun bounds() = views.map { Rect(it.left, it.top, it.right, it.bottom) }
            val initialBounds = bounds()
            fun assertRetainedGeometry() {
                assertEquals(retainedRows, menu.column.rows)
                assertEquals(retainedGlyphs, glyphs())
                assertEquals(initialBounds, bounds())
            }
            fun paintedTints() = retainedGlyphs.map(::opaqueMenuGlyphTint)
            assertEquals(semanticTints(light), paintedTints())

            // Drive the real paint owners synchronously. Detached palette binds settle immediately.
            val fillField = NativeMenuSurface::class.java.getDeclaredField("fillMotion").apply { isAccessible = true }
            val tintField = ReverbGlyphView::class.java.getDeclaredField("colorTransition").apply { isAccessible = true }
            val fillMotion = fillField.get(menu) as ViewColorTransition
            val tintMotions = retainedGlyphs.map { tintField.get(it) as ViewColorTransition }
            for ((from, to, tenth) in listOf(Triple(light, dark, 4), Triple(dark, light, 3))) {
                bind(to)
                assertRetainedGeometry()
                val fromFill = from.menuColors().surfaceContainerHigh
                val toFill = to.menuColors().surfaceContainerHigh
                val samples = ColorTransition(intArrayOf(fromFill, *semanticTints(from).toIntArray()))
                samples.retarget(intArrayOf(toFill, *semanticTints(to).toIntArray()), 0L)
                samples.frame(ColorTransition.DURATION_NANOS * tenth / 10)
                assertTrue(samples.isInterpolating)
                val fill = samples.color(0)
                assertNotEquals(fromFill, fill)
                assertNotEquals(toFill, fill)
                val rawTints = tintMotions.indices.map { samples.color(it + 1) }
                fillMotion.snap(fill)
                tintMotions.forEachIndexed { index, motion -> motion.snap(rawTints[index]) }

                val grain = to.menuColors().grainIntensity
                val underlay = menu.paintedTextSurface
                assertEquals(grainRange(fill, grain), underlay)
                val painted = paintedTints()
                for ((index, raw) in rawTints.withIndex()) {
                    assertTrue("The uncorrected tint must have low contrast", ColorUtils.calculateContrast(raw, fill) < 3.0)
                    assertNotEquals("A palette frame must correct the actual glyph pixels", raw, painted[index])
                    assertTrue(ColorUtils.calculateContrast(painted[index], (underlay ushr 32).toInt()) >= 4.5)
                    assertTrue(ColorUtils.calculateContrast(painted[index], underlay.toInt()) >= 4.5)
                }

                // Replay the old adapter's opt-out on the same glyph to verify the pixel probe.
                val share = retainedGlyphs.first()
                share.useTextSurfaceContrast = false
                assertEquals(rawTints.first(), opaqueMenuGlyphTint(share))
                share.useTextSurfaceContrast = true
                assertEquals(painted, paintedTints())
                assertTrue(retainedGlyphs.none { it.isDirty })

                // Keep the glyph tint motions idle while publishing two kinds of underlay changes.
                fillMotion.snap(Color.WHITE)
                assertTrue("A fill-only update must dirty the glyph leaves", retainedGlyphs.all { it.isDirty })
                val lightUnderlay = menu.paintedTextSurface
                val repainted = paintedTints()
                assertNotEquals(painted, repainted)
                assertEquals(rawTints.map { textSurfaceForeground(lightUnderlay, it) }, repainted)
                assertTrue(retainedGlyphs.none { it.isDirty })
                menu.bindGrain(if (grain == 0f) .5f else 0f)
                assertNotEquals(lightUnderlay, menu.paintedTextSurface)
                assertTrue("A grain-only update must dirty the glyph leaves", retainedGlyphs.all { it.isDirty })

                bind(to)
                assertEquals(semanticTints(to), paintedTints())
                assertRetainedGeometry()
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

    @Test fun sharedHandleContrastTracksSheetAndAboutCurrentPaintWithoutGeometryChanges() = mainChecked {
        val context = instrumentation.targetContext
        val runtime = NativeModalWindowRuntime(context)
        val resolver = createFontFamilyResolver(context)
        fun theme(dark: Boolean) = ReverbDesign.windowTheme(Density(1f), resolver, palette(dark))
        val light = theme(false)
        val dark = theme(true)
        val handle = NativeSheetDragHandle(context, reverbActionSheetStyle(light), { null }, runtime, {}, {})
        val sheet = NativeSheetSurface.PaintedSurface(context, reverbActionSheetStyle(light), NativeDialogEdge.BOTTOM)
        sheet.clip.addView(handle)
        val about = ReverbAboutContentView(context, light,
            ReverbAboutDetails("Reverb", "1.0", "Repository", "Open repository", "Unavailable"), {}, { false }, runtime, 0)
        val aboutHandle = descendants(about).filterIsInstance<NativeSheetDragHandle>().single()
        val panel = descendants(about).filterIsInstance<NativeFloatingPanelSurface>().single()
        fun assertMark(target: NativeSheetDragHandle, surface: Long) {
            target.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            target.layout(0, 0, target.measuredWidth, target.measuredHeight)
            assertEquals(32, target.mark.width)
            assertEquals(2, target.mark.height)
            assertEquals(34, target.height)
            assertEquals(surface, NativeModalTextSurface.sample(target.mark, forHandle = true))
            val bitmap = Bitmap.createBitmap(32, 2, Bitmap.Config.ARGB_8888)
            try {
                target.mark.draw(Canvas(bitmap))
                val ink = bitmap.getPixel(16, 1)
                assertEquals(255, Color.alpha(ink))
                for (fill in listOf((surface ushr 32).toInt(), surface.toInt())) {
                    assertTrue("Shared handle contrast ${ColorUtils.calculateContrast(ink, fill)} below 3:1",
                        ColorUtils.calculateContrast(ink, fill) >= 3.0)
                }
            } finally { bitmap.recycle() }
        }
        try {
            for (next in listOf(light, dark, light)) {
                sheet.updateStyle(reverbActionSheetStyle(next))
                handle.updateStyle(reverbActionSheetStyle(next))
                about.bind(next, ReverbAboutDetails("Reverb", "1.0", "Repository", "Open repository", "Unavailable"))
                assertMark(handle, sheet.paintedTextSurface)
                assertMark(aboutHandle, panel.paintedTextSurface)
            }
            // Public sheet contentBackground covers the mark as well as the body.
            val customFill = 0xffdddddd.toInt()
            val customLayer = FrameLayout(context).apply { background = android.graphics.drawable.ColorDrawable(customFill) }
            sheet.clip.removeView(handle)
            sheet.clip.addView(customLayer)
            customLayer.addView(handle)
            handle.mark.bindColor(customFill)
            assertMark(handle, textSurfaceRange(customFill))
            assertEquals("Ordinary paragraph ownership must not opt into a caller background", 0L,
                NativeModalTextSurface.sample(handle.mark))
            customLayer.removeView(handle)
            sheet.clip.removeView(customLayer)
            sheet.clip.addView(handle)

            // Drive actual detached paint owners at the same intermediate theme sample.
            val sheetMotion = (sheet.javaClass.getDeclaredField("colorMotion\$delegate").apply { isAccessible = true }
                .get(sheet) as Lazy<*>).value as ViewColorTransition
            val panelMotion = NativeFloatingPanelSurface::class.java.getDeclaredField("fillMotion")
                .apply { isAccessible = true }.get(panel) as ViewColorTransition
            for ((from, to) in listOf(light to dark, dark to light)) {
                val source = reverbActionSheetStyle(from)
                val destination = reverbActionSheetStyle(to)
                sheet.updateStyle(destination)
                handle.updateStyle(destination)
                about.bind(to, ReverbAboutDetails("Reverb", "1.0", "Repository", "Open repository", "Unavailable"))
                val samples = ColorTransition(intArrayOf(source.palette.surfaceContainerLow,
                    from.appearance.modals.floating.surfaceColor(from.palette), source.palette.onSurfaceVariant))
                samples.retarget(intArrayOf(destination.palette.surfaceContainerLow,
                    to.appearance.modals.floating.surfaceColor(to.palette), destination.palette.onSurfaceVariant), 0L)
                samples.frame(ColorTransition.DURATION_NANOS * 3 / 10)
                assertTrue(samples.isInterpolating)
                sheetMotion.snap(samples.color(0))
                panelMotion.snap(samples.color(1))
                handle.mark.bindColor(samples.color(2))
                aboutHandle.mark.bindColor(samples.color(2))
                assertMark(handle, sheet.paintedTextSurface)
                assertMark(aboutHandle, panel.paintedTextSurface)
            }
        } finally {
            try { handle.close() } finally { try { about.dispose() } finally { runtime.close() } }
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    @Test fun sheetCentersActionsInsideAsymmetricSafeSpanInBothDirections() = mainChecked {
        val context = instrumentation.targetContext
        val runtime = NativeModalWindowRuntime(context)
        val style = reverbActionSheetStyle(ReverbDesign.windowTheme(Density(1f),
            createFontFamilyResolver(context), palette(false)))
        val pointer = SheetPointerOwner(1f)
        val state = NativeActionSheetState({ 1f }, {})
        val handle = NativeSheetDragHandle(context, style, { null }, runtime, {}, {})
        val content = NativeSheetContent(context, style, "Confirm", { null }, null, {})
        val action = NativeMaterialButtonView(context, NativeUiAppearance.Default.buttons.filled,
            Density(1f), createFontFamilyResolver(context), onClick = {})
        content.setContent(View(context).apply { minimumHeight = 80 }, action)
        val surface = NativeSheetSurface(context, style, handle, content, null)
        val scrim = SheetScrim(context, style.palette.scrim, style.recipe.scrimAlpha, {})
        val root = SheetRoot(context, pointer, state, surface, scrim, 1f, 640f, {}, {})
        try {
            for (rtl in listOf(false, true)) for ((left, right) in listOf(120 to 0, 0 to 120, 80 to 24)) {
                root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                root.insets(androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(), androidx.core.graphics.Insets.of(left, 0, right, 0))
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 40)).build())
                for (maximum in listOf(640f, 240f)) {
                    root.bindMaxWidth(maximum)
                    root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                    state.dispatchRawDelta(-800f)
                    root.layout(0, 0, 600, 800)
                    assertEquals(40, surface.insets.contentBottom)
                    val safeWidth = 600 - left - right
                    assertEquals(minOf(safeWidth, maximum.toInt()), surface.width)
                    assertEquals(left + ((safeWidth - surface.width) * .5f).roundToInt(), surface.left)
                    assertEquals(600, scrim.width)
                    val bounds = Rect(0, 0, action.width, action.height)
                    root.offsetDescendantRectToMyCoords(action, bounds)
                    assertTrue(bounds.left >= left && bounds.right <= 600 - right)
                    assertTrue(action.width > 0 && action.height > 0)
                    assertTrue(bounds.bottom <= 800 - 40)
                }
            }
        } finally { try { action.close() } finally { try { handle.close() } finally { try { pointer.close() } finally { runtime.close() } } } }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    @Test fun aboutMergesAsymmetricCutoutsAndBarsWithoutDoublePadding() = mainChecked {
        val context = instrumentation.targetContext
        val runtime = NativeModalWindowRuntime(context)
        val theme = ReverbDesign.windowTheme(Density(1f), createFontFamilyResolver(context), palette(false))
        val about = ReverbAboutContentView(context, theme,
            ReverbAboutDetails("Reverb", "1.0", "Repository", "Open repository", "Unavailable"), {}, { false }, runtime, 0)
        try {
            for (rtl in listOf(false, true)) for ((left, right) in listOf(120 to 0, 0 to 120)) {
                about.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(about, androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(), androidx.core.graphics.Insets.of(left, 30, right, 0))
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(16, 0, 0, 40))
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 24, 0, 0)).build())
                about.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
                about.layout(0, 0, about.measuredWidth, about.measuredHeight)
                val surface = about.predictiveMotionTarget
                val bounds = Rect(0, 0, surface.width, surface.height)
                about.offsetDescendantRectToMyCoords(surface, bounds)
                assertEquals(maxOf(16, left), bounds.left)
                assertEquals(600 - right, bounds.right)
                assertEquals(30, bounds.top)
                assertEquals(40, about.height - bounds.bottom)
                for (action in descendants(about).filter { it is NativeGroupLayout || it is NativeSheetDragHandle }) {
                    val actionBounds = Rect(0, 0, action.width, action.height)
                    about.offsetDescendantRectToMyCoords(action, actionBounds)
                    assertTrue(actionBounds.left >= bounds.left && actionBounds.right <= bounds.right)
                }
            }
        } finally { try { about.dispose() } finally { runtime.close() } }
    }

    private fun mainChecked(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
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

    private fun opaqueMenuGlyphTint(glyph: ReverbGlyphView): Int {
        val bitmap = Bitmap.createBitmap(glyph.width, glyph.height, Bitmap.Config.ARGB_8888)
        try {
            glyph.draw(Canvas(bitmap))
            val pixels = IntArray(glyph.width * glyph.height)
            bitmap.getPixels(pixels, 0, glyph.width, 0, 0, glyph.width, glyph.height)
            val opaqueColors = pixels.filter { Color.alpha(it) == 255 }.toSet()
            assertEquals("Expected one opaque glyph tint", 1, opaqueColors.size)
            return opaqueColors.single()
        } finally { bitmap.recycle() }
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
