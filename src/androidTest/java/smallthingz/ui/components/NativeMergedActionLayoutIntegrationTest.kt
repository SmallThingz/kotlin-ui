package smallthingz.ui.components

import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.roundToInt
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.reverb.settings.ReverbQuickTileEditor
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
            listOf(ReverbSheetButton(context, host, env.theme, false, "Cancel", host.requestAnimatedDismiss),
                ReverbSheetButton(context, host, env.theme, true, "Clear buffer", {},
                    leadingIcon = ReverbIcon.Delete, errorColors = true))
        }
        val tile = ReverbTileDeleteSheetContent(context, env, { root }, "Delete tile?", "Cancel", "Delete", {}, {})
        try {
            val actions = sheet.javaClass.getDeclaredField("actions").apply { isAccessible = true }.get(sheet)
            assertTrue(actions is NativeMergedActionLayout)
            val row = actions as ReverbSheetActions
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

    @Test fun mergedRenameKeepsTwoAccessibilityActionsAndBusyAdmission() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var rename: ReverbInlineRecordingRename
        lateinit var runtime: NativeModalWindowRuntime
        lateinit var env: ReverbEnvironment
        lateinit var root: FrameLayout
        var layoutListener: View.OnLayoutChangeListener? = null
        val layoutReady = CountDownLatch(1)
        var cancelled = 0
        var saved = 0
        try {
            instrumentation.runOnMainSync {
                runtime = NativeModalWindowRuntime(activity)
                env = ReverbEnvironment(activity, false)
                root = FrameLayout(activity)
                rename = ReverbInlineRecordingRename(activity, env, runtime.scope, root, "Name", "Cancel", "Save",
                    { "Recording" }, {}, { cancelled++ }, { saved++ })
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
        } finally {
            instrumentation.runOnMainSync {
                layoutListener?.let(root::removeOnLayoutChangeListener)
                try { rename.close(); runtime.close() } finally { activity.finish() }
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
