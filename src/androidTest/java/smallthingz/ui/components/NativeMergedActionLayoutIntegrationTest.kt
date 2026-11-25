package smallthingz.ui.components

import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.roundToInt
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.reverb.settings.ReverbQuickTileEditor
import smallthingz.ui.reverb.settings.ReverbCalibrationGraph
import smallthingz.ui.reverb.settings.ReverbCalibrationGraphPalette
import smallthingz.ui.reverb.settings.ReverbSettingsActionView
import smallthingz.ui.reverb.settings.ReverbSilenceCalibrationActions
import smallthingz.ui.reverb.settings.ReverbSilenceCalibrationLabels
import smallthingz.ui.reverb.settings.ReverbSilenceCalibrationState
import smallthingz.ui.reverb.settings.ReverbSilenceSettingsView
import smallthingz.ui.text.view.ParagraphView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.reverb.*
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual-view geometry/state checks, including one attached action-admission fixture. */
@RunWith(AndroidJUnit4::class)
class NativeMergedActionLayoutIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun environment() = ReverbEnvironment(Density(1f, 2f), createFontFamilyResolver(context),
        NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 30) { error("No resources") })

    @Test fun filledAndTextActionsShareInsetsAndMinimumWithoutEqualWidths() = instrumentation.runOnMainSync {
        val env = environment()
        val recipes = env.appearance.buttons
        for (density in listOf(1f, 1.25f)) {
            val textDensity = Density(density, 2f)
            fun dp(value: Int) = (value * density).roundToInt()
            val buttons = listOf(recipes.text, recipes.destructive).map { recipe ->
                NativeMaterialButtonView(context, recipe, textDensity, env.sharedFontResolver) {}
            }
            try {
                val row = NativeMergedActionLayout(context, buttons, env.palette).asActionFooter(density)
                val roles = buttons.map { it.mergedRowAccessibility.roleView }
                for (label in listOf("", "Cancel", "Clear", "Save a copy")) {
                    buttons.forEach { it.bind(label, true, env.palette.buttonPalette) }
                    measure(row, 1600)
                    assertEquals(buttons[0].width, buttons[1].width)
                    for (button in buttons) {
                        assertEquals(dp(48), button.actionMinimumWidthPx)
                        assertEquals(maxOf(dp(48), 2 * dp(12) + button.label.measuredWidth), button.width)
                        assertEquals(button.preferredWidth(false), button.width)
                        assertEquals(button.preferredWidth(false), button.preferredWidth(true))
                    }
                }
                buttons[0].bind("Cancel", true, env.palette.buttonPalette)
                buttons[1].bind("Save a copy", false, env.palette.buttonPalette)
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    row.layoutDirection = direction
                    measure(row, 1600)
                    assertTrue(buttons[1].width > buttons[0].width)
                    assertEquals(buttons.sumOf { it.width }, row.actionSurface.width)
                    assertEquals(dp(6), row.height - row.actionSurface.bottom)
                    buttons.forEachIndexed { index, button ->
                        assertSame(roles[index], button.mergedRowAccessibility.roleView)
                        val paragraph = checkNotNull(button.label.currentParagraph)
                        assertFalse(paragraph.didExceedMaxLines)
                        assertEquals(button.label.getText().length, paragraph.getLineEnd(paragraph.lineCount - 1))
                    }
                    assertTrue(buttons[0].isEnabled)
                    assertFalse(buttons[1].isEnabled)
                    assertFalse(buttons[1].performClick())
                }
            } finally { buttons.forEach { it.close() } }
        }
    }

    @Test fun joiningNormalizesCustomLabelGeometryButPreservesStandaloneAndIcons() = instrumentation.runOnMainSync {
        val env = environment()
        val recipes = env.appearance.buttons
        // Local resources deliberately differ from the retained button density; no device setting changes.
        val geometryContext = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply { densityDpi = 320 })
        assertEquals(2f, geometryContext.resources.displayMetrics.density, 0f)
        val custom = recipes.text.copy(surface = recipes.text.surface.copy(
            minimumWidthDp = 96, horizontalPaddingDp = 28))
        val buttons = listOf(recipes.text, custom, recipes.footerContinue, recipes.raisedIcon).map { recipe ->
            NativeMaterialButtonView(geometryContext, recipe, Density(1.25f), env.sharedFontResolver) {}.apply {
                bind("Save a copy", true, env.palette.buttonPalette)
            }
        }
        fun dp(value: Int) = (value * 1.25f).roundToInt()
        try {
            assertEquals(maxOf(dp(96), 2 * dp(28) + buttons[1].label.maxIntrinsicTextWidth()),
                buttons[1].preferredWidth(false))
            assertTrue(buttons[1].preferredWidth(false) > buttons[0].preferredWidth(false))
            // Standalone assigned controls still use resource-density insets and obey the assigned bounds.
            assertEquals(2 * (recipes.footerContinue.surface.horizontalPaddingDp * 2f).roundToInt() +
                buttons[2].label.maxIntrinsicTextWidth(), buttons[2].preferredWidth(false))
            buttons[2].measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
            assertEquals(240, buttons[2].measuredWidth)
            assertEquals(80, buttons[2].measuredHeight)
            val iconWidth = buttons[3].preferredWidth(false)
            buttons.forEach { it.joinActionSurface() }
            buttons.take(3).forEach {
                it.bindTypography(Density(1f), env.sharedFontResolver)
                it.bindTypography(Density(1.25f), env.sharedFontResolver)
            }
            assertEquals(iconWidth, buttons[3].preferredWidth(false))
            assertEquals(dp(48), iconWidth)
            val expectedWidth = buttons[0].preferredWidth(false)
            val narrow = dp(100)
            val expectedHeight = buttons[0].intrinsicHeight(narrow)
            for (button in buttons.take(3)) {
                assertEquals(dp(48), button.actionMinimumWidthPx)
                assertEquals(expectedWidth, button.preferredWidth(false))
                assertEquals(expectedWidth, button.preferredWidth(true))
                assertEquals(expectedHeight, button.intrinsicHeight(narrow))
                button.measure(View.MeasureSpec.makeMeasureSpec(narrow, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertEquals(expectedHeight, button.measuredHeight)
                button.measure(View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertEquals(expectedWidth, button.measuredWidth)
            }
        } finally { buttons.forEach { it.close() } }
    }

    @Test fun narrowLargeTextRtlDisabledAndGoneActionsKeepIndependentGeometry() = instrumentation.runOnMainSync {
        val env = environment()
        val recipe = env.appearance.buttons.trimActions.save.button
        val buttons = listOf("Cancel operation", "Save a copy", "Keep original").map { label ->
            NativeMaterialButtonView(context, recipe, env.textDensity, env.sharedFontResolver) {}.apply {
                bind(label, label != "Save a copy", env.palette.buttonPalette)
            }
        }
        val row = NativeMergedActionLayout(context, buttons, env.palette)
        try {
            measure(row, 180)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row.importantForAccessibility)
            for (button in buttons) {
                assertTrue(button.height >= 48)
                assertTrue(button.mergedRowAccessibility.roleView.parent != null)
            }
            assertEquals(buttons[0].bottom, buttons[1].top)
            assertEquals(buttons[1].bottom, buttons[2].top)
            assertFalse(buttons[1].isEnabled)
            assertFalse(buttons[1].performClick())
            assertTrue((buttons[1].layoutParams as NativeGroupLayout.LayoutParams).dividerTop)
            row.layoutDirection = View.LAYOUT_DIRECTION_RTL
            measure(row, 1600)
            assertTrue(buttons[0].left > buttons[1].left)
            assertTrue(buttons[1].left > buttons[2].left)
            assertTrue((buttons[1].layoutParams as NativeGroupLayout.LayoutParams).dividerStart)
            buttons[0].visibility = View.GONE
            buttons[1].visibility = View.GONE
            measure(row, 180)
            val last = buttons[2].layoutParams as NativeGroupLayout.LayoutParams
            assertFalse(last.dividerTop || last.dividerStart)
            assertEquals(buttons[2].height, row.height)
            buttons[1].visibility = View.VISIBLE
            measure(row, 180)
            assertEquals(0, buttons[1].top)
            assertEquals(buttons[1].bottom, buttons[2].top)
            // A generic hard bound clips; it never compresses or overreports targets.
            buttons[0].visibility = View.VISIBLE
            measure(row, 120, 30)
            assertEquals(30, row.height)
            assertEquals(0, row.actionSurface.top)
            assertTrue(row.clipChildren)
            assertTrue(row.actionSurface.height > row.height)
            assertFalse("Clipped overflow cannot enter a recursive phase tree", row.containsPointerLayer(60f, 31f))
            assertTrue(row.containsPointerLayer(60f, 15f))
            buttons.forEach { assertTrue(it.height >= 48) }
            row.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(30, View.MeasureSpec.EXACTLY))
            assertEquals(30, row.measuredHeight)
            // A horizontal fallback is valid only after actual wrapped labels fit the height.
            buttons.zip(listOf("Cancel", "Save", "Keep")).forEach { (button, label) ->
                button.bind(label, button.isEnabled, env.palette.buttonPalette)
            }
            measure(row, 220, 130)
            assertTrue(row.height <= 130)
            assertEquals(buttons[0].top, buttons[1].top)
            assertEquals(buttons[1].top, buttons[2].top)
            buttons.forEach { assertTrue(it.width >= 48 && it.height >= 48) }
            assertTrue((buttons[1].layoutParams as NativeGroupLayout.LayoutParams).dividerStart)
            buttons.forEach { it.visibility = View.GONE }
            measure(row, 180)
            assertEquals(0, row.height)
        } finally { buttons.forEach { it.close() } }
    }

    @Test fun confirmationContentHasTopClearanceAndBalancedActionGap() = instrumentation.runOnMainSync {
        val base = environment()
        for (density in listOf(1f, 1.25f)) for (custom in listOf(false, true)) {
            val recipe = if (custom) base.appearance.modals.sheet.copy(
                topPaddingDp = 11f, actionsGapDp = 9f, bodyGapDp = 13f,
                horizontalPaddingDp = 27f, bottomPaddingDp = 29f,
            ) else base.appearance.modals.sheet
            val env = ReverbEnvironment.withTestAppearance(Density(density, 2f), base.sharedFontResolver,
                base.palette, base.appearance.copy(modals = base.appearance.modals.copy(sheet = recipe)))
            val root = FrameLayout(context)
            fun dp(value: Int) = (value * density).roundToInt()
            fun check(dialog: NativeActionSheet) {
                // Read the actual retained content, then assert laid-out edges rather than recipe fields.
                val content = NativeActionSheet::class.java.getDeclaredField("appContent")
                    .apply { isAccessible = true }.get(dialog) as NativeSheetContent
                val header = content.getChildAt(0)
                val body = content.getChildAt(1)
                val actions = content.getChildAt(2) as NativeMergedActionLayout
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    content.layoutDirection = direction
                    for (width in listOf(dp(220), dp(600))) {
                        measure(content, width)
                        assertEquals(direction, content.layoutDirection)
                        assertEquals(dp(if (custom) 11 else 6), header.top)
                        assertEquals(dp(if (custom) 13 else 12), body.top - header.bottom)
                        assertEquals(dp(if (custom) 9 else 6),
                            actions.top + actions.actionSurface.top - body.bottom)
                        assertEquals(dp(6), content.height - actions.top - actions.actionSurface.bottom)
                        assertEquals(dp(if (custom) 27 else 24), body.left)
                        assertEquals(width - body.left, body.right)
                        assertTrue(body.height > 0)
                        assertTrue(actions.actionSurface.height > 0)
                        assertEquals(0, content.height - actions.bottom)
                    }
                }
            }
            fun checkOwner(owner: AutoCloseable) {
                try {
                    check(owner.javaClass.getDeclaredField("dialog").apply { isAccessible = true }
                        .get(owner) as NativeActionSheet)
                } finally { owner.close() }
            }
            // Both actual capture routes use this same presentation with distinct titles/messages.
            for (title in listOf("Discard one-shot?", "Discard loop?")) {
                checkOwner(ReverbTextSheet(root, env.theme, title, "Discard the captured audio?", {}) { host ->
                    listOf(ReverbSheetButton(context, host, env.theme, false, "Cancel", {}),
                        ReverbSheetButton(context, host, env.theme, true, "Discard", {},
                            leadingIcon = ReverbIcon.Delete, errorColors = true))
                })
            }
            checkOwner(ReverbRecordingRenameSheet(root, env, "Rename", "Cancel", "Recording", null,
                { "Recording" }, { true }, {}, {}, {}))
            checkOwner(ReverbConfirmationSheet(root, env.theme, "Confirm", "Confirm this operation?",
                "Cancel", "Confirm", {}, {}))
            val dialog = createReverbActionSheet(root, env.theme, "Delete tile?", {})
            val tile = ReverbTileDeleteSheetContent(context, env, { root }, "Delete this tile?", "Cancel", "Delete", {}, {})
            try {
                tile.bind(env, canCancel = true, canConfirm = true)
                dialog.setContent(tile.body, tile.actions, emptyList(), scrollable = true)
                check(dialog)
            } finally { try { dialog.dismiss() } finally { tile.close() } }
        }
    }

    @Test fun inlineRenameSharesSheetClearanceAndRetainsFieldInsetsOnRebind() = instrumentation.runOnMainSync {
        val base = environment()
        for (density in listOf(1f, 1.25f)) for (fieldPadding in listOf(16f, 19f)) {
            val appearance = base.appearance.copy(outlinedField = base.appearance.outlinedField.copy(paddingDp = fieldPadding))
            fun environment(custom: Boolean) = ReverbEnvironment.withTestAppearance(Density(density, 2f),
                base.sharedFontResolver, base.palette, if (!custom) appearance else appearance.copy(
                    modals = appearance.modals.copy(sheet = appearance.modals.sheet.copy(topPaddingDp = 11f, actionsGapDp = 9f))))
            val env = environment(false)
            val runtime = NativeModalWindowRuntime(context)
            val rename = ReverbInlineRecordingRename(context, env, runtime.scope, FrameLayout(context),
                "Name", "Cancel", "Save", { "Recording" }, {}, {}, {})
            val field = rename.outlinedField
            val editor = rename.editor
            val footer = descendants(rename).filterIsInstance<NativeMergedActionLayout>().single()
            fun dp(value: Float) = (value * density).roundToInt()
            try {
                for (custom in listOf(false, true, false)) for (error in listOf(null, "Name already exists")) {
                    rename.bind(environment(custom), "Recording", error, false)
                    for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                        rename.layoutDirection = direction
                        for (width in listOf(dp(220f), dp(600f))) {
                            measure(rename, width)
                            assertSame(field, rename.outlinedField)
                            assertSame(editor, rename.editor)
                            assertSame(footer, descendants(rename).filterIsInstance<NativeMergedActionLayout>().single())
                            assertEquals(dp(if (custom) 11f else 6f), field.top)
                            assertEquals(dp(fieldPadding), field.left)
                            assertEquals(width - dp(fieldPadding), field.right)
                            assertEquals(dp(fieldPadding), field.paddingTop)
                            assertEquals(dp(fieldPadding), field.paddingBottom)
                            val lastBody = if (error == null) field else rename.getChildAt(1)
                            assertEquals(dp(if (custom) 9f else 6f), footer.top - lastBody.bottom)
                            assertEquals(dp(6f), rename.height - footer.top - footer.actionSurface.bottom)
                            assertTrue(field.height >= dp(56f))
                            descendants(footer).filterIsInstance<NativeMaterialButtonView>().forEach {
                                assertTrue(it.height >= dp(48f))
                            }
                        }
                    }
                }
            } finally { try { rename.close() } finally { runtime.close() } }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun actualInlineRenameAndTrimShareGroupAndTrimReservesBottomSpace() = instrumentation.runOnMainSync {
        val base = environment()
        // Legacy per-instance spacing cannot override the fixed shared footer policy.
        val env = ReverbEnvironment.withTestAppearance(base.textDensity, base.sharedFontResolver, base.palette,
            base.appearance.copy(listDecorations = base.appearance.listDecorations.copy(
                recordingPlayer = base.appearance.listDecorations.recordingPlayer.copy(trimActionsBottomPaddingDp = 31f))))
        val runtime = NativeModalWindowRuntime(context)
        val root = FrameLayout(context)
        val rename = ReverbInlineRecordingRename(context, env, runtime.scope, root, "Name", "Cancel", "Save",
            { "Recording" }, {}, {}, {})
        val fine = NativeSpringFineSeekView(context, NativeSpringFineSeekEnvironment(env.palette, env.textDensity,
            8f, runtime.frames, runtime.scope, root, { false }), env.appearance.springFineSeek,
            { "Play" }, { _, _ -> IdleFineSeekInput() })
        val labels = ReverbPlayerLabels(android.R.string.copy, android.R.string.cancel, android.R.string.copy,
            android.R.string.copy, android.R.string.copy, android.R.string.copy, android.R.string.copy)
        val player = ReverbLibraryPlayerView(context, env, fine, root, runtime.frames, runtime.scope, labels, { 0f })
        val state = LibraryPlayerUiState("fixture", true, false, 10000, 0, true, false, 0, 8000,
            InlineFineSeekTarget.TRIM_START, null, true, false, 0f, 1f)
        val actions = LibraryPlayerActions({ _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> true }, {}, {})
        val binding = object : NativeSpringFineSeekBinding {
            override val enabled = true
            override val isPlaying = false
            override val interactionKey = "fixture"
            override val onTogglePlayback: () -> Unit = {}
        }
        try {
            for (width in listOf(220, 700)) {
                measure(rename, width)
                assertEquals(1, descendants(rename).filterIsInstance<NativeMergedActionLayout>().size)
                assertEquals(2, descendants(rename).filterIsInstance<NativeMaterialButtonView>().size)
                descendants(rename).filterIsInstance<NativeMaterialButtonView>().forEach { button ->
                    val recipe = NativeMaterialButtonView::class.java.getDeclaredField("recipe")
                        .apply { isAccessible = true }.get(button) as NativeButtonRecipe
                    assertEquals(env.appearance.buttons.text.enabledPaint, recipe.enabledPaint)
                    assertEquals(env.appearance.buttons.text.disabledPaint, recipe.disabledPaint)
                }
                player.bind(env, state, actions, binding)
                measure(player, width)
                val trim = descendants(player).filterIsInstance<PlayerTrimButtons>().single()
                assertEquals(1, descendants(trim).filterIsInstance<NativeMergedActionLayout>().size)
                val column = trim.parent as ViewGroup
                assertEquals(0, column.height - trim.bottom)
                val trimFooter = descendants(trim).filterIsInstance<NativeMergedActionLayout>().single()
                val renameFooter = descendants(rename).filterIsInstance<NativeMergedActionLayout>().single()
                for (footer in listOf(trimFooter, renameFooter)) {
                    assertTrue(footer.isActionFooter)
                    assertEquals(6, footer.height - footer.actionSurface.bottom)
                }
                assertEquals(0, rename.height - renameFooter.bottom)
                assertEquals(0, trim.top - fine.bottom)
                descendants(trim).filterIsInstance<NativeMaterialButtonView>().forEach { assertTrue(it.height >= 48) }
            }
            rename.bind(env, "Recording", null, true)
            descendants(rename).filterIsInstance<NativeMaterialButtonView>().forEach { assertFalse(it.isEnabled) }
            rename.bind(env, "", null, false)
            val renameButtons = descendants(rename).filterIsInstance<NativeMaterialButtonView>()
            assertTrue(renameButtons[0].isEnabled)
            assertFalse(renameButtons[1].isEnabled)
            player.bind(env, state.copy(trimSaving = true), actions, binding)
            descendants(player).filterIsInstance<NativeMaterialButtonView>().forEach { assertFalse(it.isEnabled) }
            player.bind(env, state.copy(trimMode = false), actions, binding)
            measure(player, 220)
            assertFalse(descendants(player).any { it is PlayerTrimButtons })
        } finally { rename.close(); player.release(); runtime.close() }
    }

    @Test fun actualBufferDeleteAndTileDeleteConsumeCommonActionLayout() = instrumentation.runOnMainSync {
        val env = environment()
        val root = FrameLayout(context)
        val sheet = ReverbTextSheet(root, env.theme, "Clear buffer", "Delete this buffer?", {}) { host ->
            listOf(ReverbSheetButton(context, host, env.theme, false, "Cancel", host.requestAnimatedDismiss,
                    leadingIcon = ReverbIcon.Close),
                ReverbSheetButton(context, host, env.theme, true, "Clear buffer", {},
                    leadingIcon = ReverbIcon.Delete, errorColors = true))
        }
        val tile = ReverbTileDeleteSheetContent(context, env, { root }, "Delete tile?", "Cancel", "Delete", {}, {})
        try {
            val actions = sheet.javaClass.getDeclaredField("actions").apply { isAccessible = true }.get(sheet)
            assertTrue(actions is NativeMergedActionLayout)
            val row = actions as ReverbSheetActions
            val surfaces = row.buttons.map { it.accessibilitySemanticView as NativeMaterialButtonView }
            assertEquals(ReverbIcon.Close, actionGlyph(surfaces[0])?.icon)
            assertEquals(ReverbIcon.Delete, actionGlyph(surfaces[1])?.icon)
            measure(row, 1600)
            val geometry = env.appearance.buttons.text.surface
            val extra = 2 * joinedActionSurface.horizontalPaddingDp + geometry.iconSizeDp + geometry.iconGapDp
            // This fixture binds density1/fontScale2: both actual glyph slots and insets are identical.
            for (surface in surfaces) {
                assertTrue(surface.label.measuredWidth + extra >= surface.actionMinimumWidthPx)
                assertEquals(extra, surface.width - surface.label.measuredWidth)
            }
            assertEquals(surfaces[1].label.measuredWidth - surfaces[0].label.measuredWidth,
                surfaces[1].width - surfaces[0].width)
            row.buttons[1].bind("Save a copy", true) {}
            measure(row, 1600)
            assertTrue(surfaces[1].width > surfaces[0].width)
            assertEquals(extra, surfaces[1].width - surfaces[1].label.measuredWidth)
            val paragraph = checkNotNull(surfaces[1].label.currentParagraph)
            assertFalse(paragraph.didExceedMaxLines)
            assertEquals("Save a copy".length, paragraph.getLineEnd(paragraph.lineCount - 1))
            measure(row, 160)
            assertEquals(row.buttons[0].bottom, row.buttons[1].top)
            row.buttons[1].bind("Clear buffer", true) {}
            measure(row, 160)
            assertTrue(row.isActionFooter)
            assertEquals(6, row.height - row.actionSurface.bottom)
            assertEquals(2, row.buttons.size)
            val views = row.buttons.map { it.accessibilitySemanticView }
            assertNotSame(views[0], views[1])
            assertTrue(views.all { it.height >= 48 })
            assertEquals(row.buttons[0].bottom, row.buttons[1].top)
            row.buttons[1].bind("Clear buffer", false) {}
            assertFalse(views[1].isEnabled)
            assertTrue(views[0].isEnabled)
            tile.bind(env, canCancel = true, canConfirm = false)
            measure(tile.actions, 160)
            assertTrue(tile.actions.isActionFooter)
            assertEquals(6, tile.actions.height - tile.actions.actionSurface.bottom)
            assertEquals(2, descendants(tile.actions).filterIsInstance<NativeMaterialButtonView>().size)
            assertTrue(descendants(tile.actions).filterIsInstance<NativeMaterialButtonView>()[0].isEnabled)
            assertFalse(descendants(tile.actions).filterIsInstance<NativeMaterialButtonView>()[1].isEnabled)
        } finally { sheet.close(); tile.close() }
    }

    private fun actionGlyph(button: NativeMaterialButtonView): ReverbIconDrawable? =
        NativeMaterialButtonView::class.java.getDeclaredField("icon").apply { isAccessible = true }
            .get(button) as ReverbIconDrawable?

    @Test fun tileDeleteGlyphRetintsInPlaceAndRebindsAtNewDensity() = instrumentation.runOnMainSync {
        val palette = NativeColorScheme.fallback(false)
        val env = ReverbEnvironment(Density(1f), createFontFamilyResolver(context), palette)
        val tile = ReverbTileDeleteSheetContent(context, env, { null }, "Tile?", "Cancel", "Delete", {}, {})
        fun checkInk(glyph: ReverbIconDrawable, ink: Int) {
            val bounds = glyph.bounds
            assertTrue(bounds.width() > 0 && bounds.height() > 0)
            val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                glyph.draw(canvas)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                // Compare the fully covered fill, including alpha, in the same premultiplied format.
                bitmap.eraseColor(ink)
                assertTrue(Color.alpha(ink) > 0)
                assertEquals(bitmap.getPixel(0, 0), pixels.maxBy { Color.alpha(it) })
            } finally { bitmap.recycle() }
        }
        try {
            tile.bind(env, true, true)
            measure(tile.actions, 400)
            val (cancel, delete) = descendants(tile.actions).filterIsInstance<NativeMaterialButtonView>()
            assertEquals(ReverbIcon.Close, actionGlyph(cancel)?.icon)
            val glyph = checkNotNull(actionGlyph(delete))
            assertEquals(ReverbIcon.Delete, glyph.icon)
            val normalInk = env.appearance.buttons.text.contentColor(palette.buttonPalette, true)
            val disabledInk = env.appearance.buttons.text.contentColor(palette.buttonPalette, false)
            assertNotEquals(normalInk, disabledInk)
            checkInk(glyph, normalInk)
            tile.bind(env, true, false)
            assertFalse(delete.isEnabled)
            assertSame(glyph, actionGlyph(delete))
            checkInk(glyph, disabledInk)

            val dense = ReverbEnvironment(Density(2f), env.sharedFontResolver, palette)
            tile.bind(dense, true, false)
            measure(tile.actions, 800)
            val rebound = checkNotNull(actionGlyph(delete))
            assertNotSame(glyph, rebound)
            assertEquals(glyph.intrinsicWidth * 2, rebound.intrinsicWidth)
            assertEquals(ReverbIcon.Delete, rebound.icon)
            checkInk(rebound, disabledInk)
        } finally { tile.close() }
    }

    @Test fun calibrationKeepsInitialLargeFontHitboxThroughHeldAndCancellableStates() = instrumentation.runOnMainSync {
        val env = environment() // density 1, font scale 2
        fun state(running: Boolean, held: Boolean) = object : ReverbSilenceCalibrationState {
            override val checked = true
            override val enabled = true
            override val calibrationAllowed = true
            override val clearance = 5f
            override val calibrating = running
            override val holdingCalibration = held
            override val calibrationProgress: Float? = null
            override val calibrationError: String? = null
            override val calibrationUnavailableReason: String? = null
        }
        val actions = object : ReverbSilenceCalibrationActions {
            override fun checkedChanged(checked: Boolean) = error("Unexpected edit")
            override fun clearanceChanged(clearance: Float) = error("Unexpected edit")
            override fun calibrate() = error("Unexpected action")
            override fun beginHeld(): Boolean = error("Unexpected action")
            override fun completeTap() = error("Unexpected action")
            override fun finishHeld() = error("Unexpected action")
            override fun cancel() = error("Unexpected action")
        }
        val labels = ReverbSilenceCalibrationLabels("Silence", "Calibrate", "Hold", "Release",
            "Cancel sampling", "Sampling", "Fit", View.generateViewId(), { "Reset" }, Int::toString, Int::toString)
        val runtime = NativeModalWindowRuntime(context)
        val graphView = View(context)
        val section = object : ReverbSilenceSettingsView(context, state(false, false), env, { null }, runtime.scope,
            labels, NativeArcDialRange(0f, 10f, 5f), { object : ReverbCalibrationGraph {
                override val view = graphView
                override fun bind(palette: ReverbCalibrationGraphPalette, editable: Boolean) = Unit
                override fun close() = Unit
            } }, actions) {
            fun update(running: Boolean, held: Boolean) = bind(state(running, held), env, graphChanged = false)
        }
        try {
            val action = descendants(section).filterIsInstance<ReverbSettingsActionView>().single()
            val visual = action.getChildAt(0) as NativeMaterialButtonView
            // Give the localized Cancel label just enough space for one bare line.
            val text = ParagraphView(context, env.appearance.typography.buttonLabelStyle, env.textDensity,
                env.sharedFontResolver, env.palette.ink).apply { setText(labels.cancel) }
            val width = text.maxIntrinsicTextWidth() + 2 * env.appearance.calibration.actionSurface.horizontalPaddingDp
            measure(action, width)
            val initialHeight = action.height
            val firstLabelHeight = action.textView.height
            assertNull(actionGlyph(visual))
            fun expect(label: String, icon: ReverbIcon?) {
                measure(action, width)
                assertEquals(label, action.textView.getText())
                assertEquals(icon, actionGlyph(visual)?.icon)
                assertEquals(initialHeight, action.height)
                assertEquals(initialHeight, visual.height)
            }
            expect(labels.calibrate, null)
            section.update(true, true)
            expect(labels.releaseToFinish, null)
            section.update(true, false)
            expect(labels.cancel, ReverbIcon.Close)
            assertTrue("The icon forced a real second line", action.textView.height > firstLabelHeight)
            section.update(false, false)
            expect(labels.calibrate, null)
        } finally { try { section.close() } finally { runtime.close() } }
    }

    @Test fun mergedRenameKeepsTwoAccessibilityActionsAndBusyAdmission() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var rename: ReverbInlineRecordingRename
        lateinit var runtime: NativeModalWindowRuntime
        lateinit var env: ReverbEnvironment
        lateinit var root: FrameLayout
        var ownedRename: ReverbInlineRecordingRename? = null
        var ownedRuntime: NativeModalWindowRuntime? = null
        var ownedRoot: FrameLayout? = null
        var layoutListener: View.OnLayoutChangeListener? = null
        val layoutReady = CountDownLatch(1)
        var cancelled = 0
        var saved = 0
        withRetiringTestActivity(activity, root = { ownedRoot }, cleanup = {
            var failure: Throwable? = null
            failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure) {
                layoutListener?.let { ownedRoot?.removeOnLayoutChangeListener(it) }
            }
            failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure) { ownedRename?.close() }
            failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure) { ownedRuntime?.close() }
            failure?.let { throw it }
        }) {
            instrumentation.runOnMainSync {
                runtime = NativeModalWindowRuntime(activity).also { ownedRuntime = it }
                env = ReverbEnvironment(activity, false)
                root = FrameLayout(activity).also { ownedRoot = it }
                rename = ReverbInlineRecordingRename(activity, env, runtime.scope, root, "Name", "Cancel", "Save",
                    { "Recording" }, {}, { cancelled++ }, { saved++ }).also { ownedRename = it }
                root.addView(rename, FrameLayout.LayoutParams(-1, -2))
                layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    val buttons = descendants(rename).filterIsInstance<NativeMaterialButtonView>()
                    if (buttons.size == 2 && buttons.all {
                            it.isAttachedToWindow && it.isShown && it.isLaidOut && it.width > 0 && it.height > 0
                        }) layoutReady.countDown()
                }.also(root::addOnLayoutChangeListener)
                activity.setContentView(root)
            }
            assertTrue("Attached rename actions must complete their real layout", layoutReady.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val buttons = descendants(rename).filterIsInstance<NativeMaterialButtonView>()
                assertEquals(2, buttons.size)
                val geometry = NativePlainTextViewGeometry(root,
                    NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
                // The app's page owner supplies this geometry for actions with a source-owned inputRoot.
                buttons[0].mergedRowAccessibility.bindGeometry(geometry) {
                    buttons[0].isAttachedToWindow && buttons[0].isShown
                }.use {
                    buttons[1].mergedRowAccessibility.bindGeometry(geometry) {
                        buttons[1].isAttachedToWindow && buttons[1].isShown
                    }.use {
                        val nodes = ArrayList<AccessibilityNodeInfo>(2)
                        try {
                            for (button in buttons) {
                                val node = checkNotNull(button.createAccessibilityNodeInfo())
                                nodes.add(node)
                                assertTrue(node.isClickable)
                                assertTrue(node.isVisibleToUser)
                            }
                            assertNotEquals("Cancel and Save retain distinct semantic nodes", nodes[0], nodes[1])
                            assertTrue(buttons[0].performClick())
                            assertTrue(buttons[1].performClick())
                            assertEquals(1, cancelled)
                            assertEquals(1, saved)
                            rename.bind(env, "Recording", null, true)
                            assertFalse(buttons[0].performClick())
                            assertFalse(buttons[1].performClick())
                            assertEquals(1, cancelled)
                            assertEquals(1, saved)
                        } finally { nodes.forEach { it.recycle() } }
                    }
                }
            }
        }
    }

    @Test fun fixedFooterSpacingClipsPaintAndLeavesGenericGroupsUnchanged() = instrumentation.runOnMainSync {
        val env = environment()
        fun group() = NativeMergedActionLayout(context,
            listOf(FooterAction(context), FooterAction(context)), env.palette)
        val ordinary = group()
        measure(ordinary, 300)
        assertFalse(ordinary.isActionFooter)
        assertEquals(0, ordinary.paddingBottom)
        assertEquals(ordinary.actionSurface.height, ordinary.height)
        val footer = group().asActionFooter()
        measure(footer, 300)
        assertEquals((6f * context.resources.displayMetrics.density).roundToInt(), footer.paddingBottom)
        val surface = footer.actionSurface
        for (density in listOf(1f, 1.25f, 2f)) {
            footer.asActionFooter(density)
            val gap = (6f * density).roundToInt()
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                footer.layoutDirection = direction
                assertEquals("RTL must actually resolve", direction, footer.layoutDirection)
                for (width in listOf(100, 300)) {
                    measure(footer, width)
                    assertSame(surface, footer.actionSurface)
                    assertEquals(gap, footer.height - surface.bottom)
                    assertFalse(footer.containsPointerLayer(width / 2f, footer.height - 1f))
                }
            }
            // A hard height limit clips natural targets and cannot paint/hit the reserved strip.
            for (height in listOf(4, 30, 100)) {
                footer.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                footer.layout(0, 0, footer.measuredWidth, footer.measuredHeight)
                assertEquals(height, footer.height)
                assertTrue(descendants(surface).filterIsInstance<FooterAction>().all { it.height >= 48 })
                val bitmap = Bitmap.createBitmap(footer.width, footer.height, Bitmap.Config.ARGB_8888)
                try {
                    footer.draw(Canvas(bitmap))
                    for (y in (height - gap).coerceAtLeast(0) until height) {
                        assertEquals("Footer strip is outside the painted surface", 0, Color.alpha(bitmap.getPixel(50, y)))
                        assertFalse(footer.containsPointerLayer(50f, y.toFloat()))
                    }
                    if (height > gap) assertTrue(Color.alpha(bitmap.getPixel(50, (height - gap) / 2)) > 0)
                } finally { bitmap.recycle() }
            }
        }
        measure(ordinary, 300)
        assertEquals(0, ordinary.height - ordinary.actionSurface.bottom)
    }

    @Test fun sheetFooterOwnsVisualGapAndHostInsetsStaySeparate() = instrumentation.runOnMainSync {
        val env = environment()
        val style = env.appearance.modals.actionSheetStyle(env.textDensity, env.sharedFontResolver, env.palette)
        for (isFooter in listOf(false, true)) {
            val actions = NativeMergedActionLayout(context,
                listOf(FooterAction(context), FooterAction(context)), env.palette)
            if (isFooter) actions.asActionFooter(1f)
            val content = NativeSheetContent(context, style, "Confirm", { null }, null, {})
            content.setContent(FooterAction(context), actions)
            measure(content, 300)
            assertEquals(if (isFooter) 0 else style.recipe.bottomPaddingDp.roundToInt(), content.height - actions.bottom)
            assertEquals(if (isFooter) 6 else 0, actions.height - actions.actionSurface.bottom)
        }
        for (inset in listOf(0, 24)) {
            val footer = NativeMergedActionLayout(context,
                listOf(FooterAction(context), FooterAction(context)), env.palette).asActionFooter(1f)
            val host = FrameLayout(context).apply {
                setPadding(0, 0, 0, inset)
                addView(footer, FrameLayout.LayoutParams(-1, -2))
            }
            measure(host, 300)
            assertEquals(inset, host.paddingBottom)
            assertEquals(inset + 6, host.height - footer.top - footer.actionSurface.bottom)
        }
    }

    @Test fun quickTileAddAndSaveUseFixedFooterWithoutLegacyBottomInset() = instrumentation.runOnMainSync {
        val env = environment()
        val root = FrameLayout(context)
        val options = listOf(SegmentOption("one", "One"), SegmentOption("two", "Two"))
        var callbacks = 0
        val editor = object : ReverbQuickTileEditor<String>(context, env, options, "one", { root },
            { callbacks++; true }, { callbacks++ }, { callbacks++ }) {
            fun bindLabel(label: String) = bindInlineLayout(env, options, "one", true,
                null, null, null, "Cancel", true, label, true)
        }
        try {
            val footer = descendants(editor).filterIsInstance<NativeMergedActionLayout>().single()
            for (label in listOf("Add", "Save")) {
                editor.bindLabel(label)
                for (width in listOf(220, 700)) {
                    measure(editor, width)
                    assertSame(footer, descendants(editor).filterIsInstance<NativeMergedActionLayout>().single())
                    assertTrue(footer.isActionFooter)
                    assertEquals(0, editor.height - footer.bottom)
                    assertEquals(6, footer.height - footer.actionSurface.bottom)
                    assertEquals(2, descendants(footer).filterIsInstance<NativeMaterialButtonView>().size)
                }
            }
            assertEquals(0, callbacks)
        } finally { editor.close() }
    }

    @Test fun permissionAllowDenyUsesFixedFooterAndRetainsConsentState() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeLargeFontPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withRetiringTestActivity(activity) {
            instrumentation.runOnMainSync { assertPermissionFooter(activity) }
        }
    }

    private fun assertPermissionFooter(activity: android.app.Activity) {
        assertEquals(1.8f, activity.resources.configuration.fontScale, .001f)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val allowLabel = "Zugriff auf die ausgewählten Inhalte erlauben"
        val denyLabel = "Zugriff auf die ausgewählten Inhalte verweigern"
        for (dark in listOf(false, true)) {
            var callbacks = 0
            val editor = android.widget.EditText(activity).apply { setText("retained"); setSelection(3) }
            val prompt = NativePermissionDialog(activity, NativeColorScheme.fallback(dark), null, "Lesegerät",
                "Zugriff auf die ausgewählten Inhalte zulassen?", denyLabel, allowLabel, editor,
                onDeny = { callbacks++ }, onAllow = { callbacks++ }, onCancel = { callbacks++ })
            var failure: Throwable? = null
            try {
                // Construct real dialog content, but never show/admit a consent window in this geometry test.
                val dialog = NativePermissionDialog::class.java.getDeclaredField("dialog")
                    .apply { isAccessible = true }.get(prompt) as android.app.Dialog
                val surface = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val footer = descendants(surface).filterIsInstance<NativeMergedActionLayout>().single()
                val viewport = descendants(surface).filterIsInstance<android.widget.ScrollView>().last()
                val panel = viewport.parent as View
                val buttons = descendants(footer).filterIsInstance<NativeMaterialButtonView>()
                assertEquals(listOf(allowLabel, denyLabel), buttons.map { it.label.getText() })
                assertNotSame(buttons[0].mergedRowAccessibility.roleView, buttons[1].mergedRowAccessibility.roleView)
                assertTrue(footer.isActionFooter)
                for (button in buttons) {
                    val recipe = NativeMaterialButtonView::class.java.getDeclaredField("recipe")
                        .apply { isAccessible = true }.get(button) as NativeButtonRecipe
                    assertEquals(NativeUiAppearance.Default.buttons.tonal.enabledPaint, recipe.enabledPaint)
                    assertEquals(NativeUiAppearance.Default.buttons.tonal.disabledPaint, recipe.disabledPaint)
                    assertFalse(button.performClick())
                }
                for ((width, height) in listOf(180 to 140, 360 to 700)) {
                    viewport.scrollTo(0, 0)
                    measure(surface, dp(width), dp(height))
                    assertTrue(surface.height <= dp(height))
                    assertTrue(viewport.height > 0)
                    assertEquals(0, panel.height - viewport.bottom)
                    assertEquals(dp(6), footer.height - footer.actionSurface.bottom)
                    for (button in buttons) {
                        assertTrue(button.width >= dp(48))
                        assertTrue(button.height >= dp(56))
                        val paragraph = checkNotNull(button.label.currentParagraph)
                        assertFalse(paragraph.didExceedMaxLines)
                        assertEquals(button.label.getText().length, paragraph.getLineEnd(paragraph.lineCount - 1))
                        assertTrue(paragraph.height <= button.label.height + 1f)
                    }
                    viewport.scrollTo(0, viewport.getChildAt(0).height)
                    assertFalse(viewport.canScrollVertically(1))
                    assertSame(viewport.getChildAt(0), footer.parent)
                    val paintedBottom = viewport.getChildAt(0).top + footer.top + footer.actionSurface.bottom - viewport.scrollY
                    assertEquals("Only the fixed gap remains at the scrolled end", dp(6), viewport.height - paintedBottom)
                }
                prompt.setAllowEnabled(false)
                prompt.setBusy(true)
                assertFalse(buttons[0].isEnabled || buttons[1].isEnabled)
                prompt.setBusy(false)
                assertFalse(buttons[0].isEnabled)
                assertTrue(buttons[1].isEnabled)
                assertEquals("retained", editor.text.toString())
                assertEquals(3, editor.selectionStart)
                assertEquals(0, callbacks)
            } catch (error: Throwable) { failure = error }
            finally { failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure, prompt::dismiss) }
            failure?.let { throw it }
            assertEquals(0, callbacks)
        }
    }

    private class FooterAction(context: Context) : View(context), NativeMergedActionChild {
        override val actionMinimumWidthPx = 48
        override fun joinActionSurface() = Unit
        override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(resolveSize(90, w), resolveSize(48, h)) }
        override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.RED) }
    }

    private fun measure(view: View, width: Int, height: Int? = null) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            if (height == null) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            else View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
    private class IdleFineSeekInput : NativeSpringFineSeekInput<NativeSpringFineSeekBinding> {
        override val horizontalPull = 0f
        override val constrainedVerticalPull = 0f
        override val dragging = false
        override val needsFrames = false
        override fun bind(value: NativeSpringFineSeekBinding, newTouchSlop: Float) = Unit
        override fun setVisualStyle(style: NativeSpringFineSeekStyle) = Unit
        override fun pointer(event: NativeDragPointerEvent, pass: NativePointerPass, geometry: NativeSpringFineSeekGeometry) = Unit
        override fun frame(frameNanos: Long) = Unit
        override fun cancel() = Unit
        override fun release() = Unit
    }
}
