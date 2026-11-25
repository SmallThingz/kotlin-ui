package smallthingz.ui.components

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.icons.MaterialRoundedIconDrawable
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeDialogActionsIntegrationTest {
    @Test fun shortLabelsShareWidthAndLongLabelsGrow() = onMain {
        withActions("OK", "OK", 420, 1f) { row, first, second ->
            assertEquals(LinearLayout.HORIZONTAL, row.orientation)
            assertEquals(first.button.measuredWidth, second.button.measuredWidth)
            // Compare the mounted standalone pair to the existing joined Trim recipe for the same label and glyph.
            val density = Density(row.context)
            val appearance = NativeUiAppearance.Default
            val colors = appearance.resolveColorScheme(row.context, false)
            val reference = NativeMaterialButtonView(row.context, appearance.buttons.trimActions.cancel,
                density, createFontFamilyResolver(row.context), onClick = {})
            try {
                reference.setLeadingIcon(MaterialRoundedIconDrawable(MaterialRoundedIcon.Close, density.density, colors.ink))
                reference.bind("OK", true, colors.buttonPalette)
                reference.joinActionSurface()
                assertEquals(reference.preferredWidth(false), first.button.measuredWidth)
            } finally { reference.close() }
            assertTrue(first.measuredHeight >= dp(first, 48))
        }
        withActions("Cancel", "Replace installed plugin", 600, 1f) { _, first, second ->
            assertTrue(second.button.measuredWidth > first.button.measuredWidth)
        }
    }

    @Test fun narrowAndLargeTextStackWithoutReducingPaddingOrTargets() = onMain {
        for (scale in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            withActions("Cancel", "Replace installed plugin", 136, scale, rtl) { row, first, second ->
                assertEquals(LinearLayout.VERTICAL, row.orientation)
                assertFalse(first.button.compactPadding)
                assertFalse(second.button.compactPadding)
                assertTrue(first.measuredWidth <= row.measuredWidth)
                assertTrue(second.measuredWidth <= row.measuredWidth)
                assertTrue(second.top >= first.bottom + dp(row, NativeScreenSpacing.CONTROL_GAP_DP))
                assertTrue(first.measuredHeight >= dp(row, 48))
            }
        }
    }

    @Test fun standaloneFooterOwnsClearanceInActionSheet() = withSheetActivity { activity ->
        val appearance = NativeUiAppearance.Default
        val style = appearance.modals.actionSheetStyle(Density(activity), createFontFamilyResolver(activity),
            appearance.resolveColorScheme(activity, false))
        val sheet = NativeActionSheet(View(activity), style, "Delete", {})
        try {
            val surface = sheet.contentView as NativeSheetSurface
            val body = FrameLayout(sheet.context).apply { minimumHeight = dp(surface, 32) }
            val bounds = android.graphics.Rect()
            fun gapBelow(view: View): Int {
                view.getDrawingRect(bounds)
                surface.offsetDescendantRectToMyCoords(view, bounds)
                return surface.height - bounds.bottom
            }
            fun layout(inset: Int) {
                surface.insets.update(0, inset)
                surface.measure(View.MeasureSpec.makeMeasureSpec(dp(surface, 360), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(surface, 640), View.MeasureSpec.AT_MOST))
                surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
            }
            withPair(sheet.context, "Cancel", "Delete") { row, first, second ->
                sheet.setContent(body, row, emptyList())
                for (inset in listOf(0, dp(surface, 19))) {
                    layout(inset)
                    assertTrue(first.button.height > 0 && second.button.height > 0)
                    assertEquals(inset, gapBelow(row))
                    assertEquals(inset + dp(surface, 6), minOf(gapBelow(first.button), gapBelow(second.button)))
                }
            }
            val ordinary = FrameLayout(sheet.context).apply { minimumHeight = dp(surface, 40) }
            sheet.setContent(body, ordinary, emptyList())
            layout(0)
            assertEquals((style.recipe.bottomPaddingDp * style.textDensity.density).roundToInt(), gapBelow(ordinary))
        } finally { sheet.dismiss() }
    }

    @Test fun pinnedActionsRemainVisibleWhenTheFormShrinksForIme() = withSheetActivity { activity ->
        for (scale in listOf(1f, 2f)) for (rtl in listOf(false, true)) for (widthDp in listOf(220, 360)) {
            val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                fontScale = scale
                densityDpi = 200
            })
            val appearance = NativeUiAppearance.Default
            val style = appearance.modals.actionSheetStyle(Density(context), createFontFamilyResolver(context),
                appearance.resolveColorScheme(context, false))
            val sheet = NativeActionSheet(View(context), style, "Add catalog", {})
            try {
                val surface = sheet.contentView as NativeSheetSurface
                surface.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                val body = FrameLayout(context).apply { minimumHeight = dp(surface, 720) }
                withPair(context, "Cancel", "Add") { row, first, second ->
                    sheet.setContentWithPinnedActions(body, row, emptyList(), emptyList())
                    val viewport = surface.column.content as smallthingz.ui.scroll.NativeVerticalViewport
                    // Large window -> IME-sized window -> recovery, on the same retained form/actions.
                    for (heightDp in listOf(640, 240, 640)) {
                        surface.insets.update(0, dp(surface, 19))
                        surface.measure(View.MeasureSpec.makeMeasureSpec(dp(surface, widthDp), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(dp(surface, heightDp), View.MeasureSpec.AT_MOST))
                        surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
                        assertTrue(surface.height <= dp(surface, heightDp))
                        assertTrue("The form keeps a scroll viewport", viewport.height > 0)
                        assertTrue(viewport.getChildAt(0).height > viewport.height)
                        assertSame(surface.appContent, viewport.getChildAt(0))
                        assertSame(body, surface.appContent.getChildAt(1))
                        for (target in listOf(first, second)) {
                            val rect = android.graphics.Rect()
                            target.getDrawingRect(rect)
                            surface.offsetDescendantRectToMyCoords(target, rect)
                            assertTrue("Action is below visible form", rect.top >= viewport.bottom)
                            assertTrue("Action stays above the bottom inset", rect.bottom <= surface.height - dp(surface, 19))
                            assertTrue(target.height >= dp(surface, 48))
                            assertTrue(target.width <= surface.width)
                        }
                        val rect = android.graphics.Rect()
                        row.getDrawingRect(rect)
                        surface.offsetDescendantRectToMyCoords(row, rect)
                        assertEquals(dp(surface, 19), surface.height - rect.bottom)
                    }
                }
            } finally { sheet.dismiss() }
        }
    }

    @Test fun pinnedFooterRetainsSpacingAcrossContentAndErrorChanges() = withSheetActivity { activity ->
        val appearance = NativeUiAppearance.Default
        val style = appearance.modals.actionSheetStyle(Density(activity), createFontFamilyResolver(activity),
            appearance.resolveColorScheme(activity, false))
        for (edge in NativeDialogEdge.values()) {
            val sheet = NativeActionSheet(View(activity), style, "Add catalog", {}, entryEdge = edge)
            try {
                val surface = sheet.contentView as NativeSheetSurface
                val body = FrameLayout(activity)
                withPair(activity, "Cancel", "Add") { row, first, second ->
                    sheet.setContentWithPinnedActions(body, row, emptyList(), emptyList())
                    var bodyRemovals = 0
                    surface.appContent.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                        override fun onChildViewAdded(parent: View, child: View) = Unit
                        override fun onChildViewRemoved(parent: View, child: View) {
                            if (child === body) bodyRemovals++
                        }
                    })
                    for (bodyDp in listOf(48, 400, 72, 48)) {
                        body.minimumHeight = dp(body, bodyDp) // initial, loading/error content, recovered form
                        sheet.setContentWithPinnedActions(body, row, emptyList(), emptyList())
                        assertEquals("Rebinding must keep the form mounted", 0, bodyRemovals)
                        surface.measure(View.MeasureSpec.makeMeasureSpec(dp(surface, 360), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(dp(surface, 240), View.MeasureSpec.AT_MOST))
                        surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
                        assertTrue(first.height >= dp(first, 48) && second.height >= dp(second, 48))
                        val footer = row.parent as ViewGroup
                        assertEquals(0, footer.height - row.bottom)
                        val paintedBottom = maxOf(first.top + first.button.bottom, second.top + second.button.bottom)
                        assertEquals(dp(row, 6), row.height - paintedBottom)
                        assertSame(body, surface.appContent.getChildAt(1))
                    }
                }
            } finally { sheet.dismiss() }
        }
    }

    @Test fun pinnedActionsScrollWhenTheStackCannotFitAndRepinOnRecovery() = withSheetActivity { activity ->
        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            densityDpi = 200
            fontScale = 2f
        })
        val appearance = NativeUiAppearance.Default
        val style = appearance.modals.actionSheetStyle(Density(context), createFontFamilyResolver(context),
            appearance.resolveColorScheme(context, false))
        for (edge in NativeDialogEdge.values()) {
            val sheet = NativeActionSheet(View(context), style, "Add catalog", {}, entryEdge = edge)
            try {
                val surface = sheet.contentView as NativeSheetSurface
                val body = FrameLayout(context).apply { minimumHeight = dp(surface, 200) }
                withPair(context, "Cancel", "Replace installed plugin") { row, first, second ->
                    sheet.setContentWithPinnedActions(body, row, emptyList(), emptyList())
                    val viewport = surface.column.content as smallthingz.ui.scroll.NativeVerticalViewport
                    val bottomInset = dp(surface, 19)
                    val topInset = dp(surface, 11)
                    fun layout(available: Int) {
                        surface.insets.update(topInset, bottomInset)
                        surface.measure(View.MeasureSpec.makeMeasureSpec(dp(surface, 136), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(available + topInset + bottomInset, View.MeasureSpec.AT_MOST))
                        surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
                        assertTrue(surface.height <= available + topInset + bottomInset)
                    }
                    fun boundsWithin(parent: ViewGroup, child: View): android.graphics.Rect =
                        android.graphics.Rect().also { rect ->
                            child.getDrawingRect(rect)
                            parent.offsetDescendantRectToMyCoords(child, rect)
                        }

                    layout(dp(surface, 640))
                    assertEquals(LinearLayout.VERTICAL, row.orientation)
                    val footer = row.parent as View
                    val naturalHeight = footer.height
                    val firstHeight = first.height
                    val secondHeight = second.height
                    assertTrue(firstHeight >= dp(surface, 48) && secondHeight >= dp(surface, 48))
                    assertSame(surface.column, footer.parent)

                    layout(naturalHeight + dp(surface, 12))
                    assertSame(surface.column, footer.parent)
                    assertEquals(View.GONE, surface.column.handle.visibility)
                    assertEquals(firstHeight, first.height)
                    assertEquals(secondHeight, second.height)
                    assertEquals(bottomInset, surface.height - boundsWithin(surface, footer).bottom)

                    val shortHeight = maxOf(firstHeight, secondHeight) + dp(surface, 3)
                    assertTrue(shortHeight < naturalHeight)
                    layout(shortHeight)
                    assertSame(surface.appContent, footer.parent)
                    assertSame(body, surface.appContent.getChildAt(1))
                    assertSame(viewport, surface.column.content)
                    assertEquals(shortHeight, viewport.height)
                    assertTrue(viewport.state.canScrollForward)
                    for (target in listOf(first, second)) {
                        val bottom = boundsWithin(surface.appContent, target).bottom
                        val desired = (bottom - viewport.height).coerceAtLeast(0)
                        viewport.state.scrollBy((desired - viewport.state.value).toFloat())
                        viewport.forceLayout()
                        viewport.measure(View.MeasureSpec.makeMeasureSpec(viewport.width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(viewport.height, View.MeasureSpec.EXACTLY))
                        viewport.layout(viewport.left, viewport.top, viewport.right, viewport.bottom)
                        val targetBounds = boundsWithin(surface, target)
                        val viewportBounds = boundsWithin(surface, viewport)
                        assertTrue("Scrolled action must be entirely inside the viewport",
                            targetBounds.top >= viewportBounds.top && targetBounds.bottom <= viewportBounds.bottom)
                    }

                    layout(dp(surface, 640))
                    assertSame(surface.column, footer.parent)
                    assertSame(viewport, surface.column.content)
                    assertEquals(View.VISIBLE, surface.column.handle.visibility)
                    assertEquals(firstHeight, first.height)
                    assertEquals(secondHeight, second.height)
                }
            } finally { sheet.dismiss() }
        }
    }

    @Test fun explicitSheetButtonsExposeTheirLabelsAndCancelAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        withRetiringTestActivity(activity) {
            lateinit var sheet: NativeActionSheet
            lateinit var cancel: NativeMaterialButtonView
            lateinit var add: NativeMaterialButtonView
            lateinit var cancelLease: AutoCloseable
            lateinit var addLease: AutoCloseable
            var cancels = 0
            var ownedSheet: NativeActionSheet? = null
            val resources = ArrayList<AutoCloseable>()
            try {
                onMain {
                    val appearance = NativeUiAppearance.Default
                    val colors = appearance.resolveColorScheme(activity, false)
                    val density = Density(activity)
                    val fonts = createFontFamilyResolver(activity)
                    val anchor = FrameLayout(activity)
                    activity.setContentView(anchor)
                    sheet = NativeActionSheet(anchor, appearance.modals.actionSheetStyle(density, fonts, colors),
                        "Add catalog", {})
                    ownedSheet = sheet
                    val host = sheet.contentContext
                    cancel = NativeMaterialButtonView(activity, appearance.buttons.text.forDialogAction(), density,
                        fonts, NativeButtonInputPolicy(inputRoot = { host.inputRoot }), { cancels++ }).also(resources::add)
                    add = NativeMaterialButtonView(activity, appearance.buttons.filled.forDialogAction(), density,
                        fonts, NativeButtonInputPolicy(inputRoot = { host.inputRoot }), {}).also(resources::add)
                    cancel.bind("Cancel", true, colors.buttonPalette)
                    add.bind("Add", false, colors.buttonPalette)
                    cancelLease = host.bindButtonAccessibility(cancel).also(resources::add)
                    addLease = host.bindButtonAccessibility(add).also(resources::add)
                    val body = FrameLayout(activity).apply { minimumHeight = dp(anchor, 400) }
                    sheet.setContentWithPinnedActions(body,
                        NativeDialogActionLayout(NativeButtonTouchTarget(cancel), NativeButtonTouchTarget(add)),
                        emptyList(), emptyList())
                    sheet.show()
                }
                instrumentation.waitForIdleSync()
                // Await the real sheet entrance; no input injection or admission override.
                val deadline = android.os.SystemClock.uptimeMillis() + 3000
                var ready = false
                while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                    onMain { ready = sheet.hasCurrentWindowFocus && cancel.width > 0 &&
                        sheet.contentContext.accessibility.sourceNode(cancel).visibleToUser }
                    if (!ready) android.os.SystemClock.sleep(20)
                }
                onMain {
                    assertTrue("Sheet must be visibly attached", ready)
                    val info = requireNotNull(cancel.createAccessibilityNodeInfo()) { "Missing Cancel button node" }
                    try {
                        assertTrue(info.isVisibleToUser)
                        assertTrue(info.actionList.contains(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
                        assertTrue(cancel.mergedRowAccessibility.isNodePresent())
                    } finally { info.recycle() }
                    val label = requireNotNull(cancel.label.createAccessibilityNodeInfo()) { "Missing Cancel label node" }
                    try { assertEquals("Cancel", label.text.toString()) } finally { label.recycle() }
                    assertTrue(cancel.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertEquals(1, cancels)
                    assertFalse(add.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertEquals(1, cancels)
                }
            } finally {
                onMain {
                    try { ownedSheet?.dismiss() } finally { resources.asReversed().forEach(AutoCloseable::close) }
                }
            }
        }
    }

    private fun withSheetActivity(check: (android.app.Activity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        withRetiringTestActivity(activity) { onMain { check(activity) } }
    }

    @Test fun themeAwareSwitchKeepsTheSharedThinOutline() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) {
            val colors = NativeUiAppearance.Default.resolveColorScheme(context, dark)
            val mark = NativeMaterialSwitchView(context, colors)
            mark.measure(View.MeasureSpec.makeMeasureSpec(dp(mark, 100), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(mark, 60), View.MeasureSpec.AT_MOST))
            mark.layout(0, 0, mark.measuredWidth, mark.measuredHeight)
            val field = NativeMaterialSwitchView::class.java.getDeclaredField("borderWidth").apply { isAccessible = true }
            assertEquals(context.resources.displayMetrics.density, field.getFloat(mark), .01f)
        }
    }

    private fun onMain(action: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).roundToInt()

    private fun withActions(firstLabel: String, secondLabel: String, widthDp: Int, fontScale: Float,
        rtl: Boolean = false, check: (NativeDialogActionLayout, NativeButtonTouchTarget, NativeButtonTouchTarget) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { this.fontScale = fontScale; densityDpi = 200 })
        withPair(context, firstLabel, secondLabel) { row, first, second ->
            row.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            val host = FrameLayout(context)
            val hostBottom = dp(host, 11)
            host.setPaddingRelative(0, 0, 0, hostBottom)
            host.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            host.measure(View.MeasureSpec.makeMeasureSpec(dp(row, widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            host.layout(0, 0, host.measuredWidth, host.measuredHeight)
            val paintedBottom = maxOf(first.top + first.button.bottom, second.top + second.button.bottom)
            val clearance = (NativeMergedActionLayout.ACTION_FOOTER_BOTTOM_PADDING_DP *
                context.resources.displayMetrics.density).roundToInt()
            assertEquals(clearance, row.height - paintedBottom)
            assertEquals(hostBottom, host.height - row.bottom)
            check(row, first, second)
        }
    }

    private fun withPair(context: android.content.Context, firstLabel: String, secondLabel: String,
        check: (NativeDialogActionLayout, NativeButtonTouchTarget, NativeButtonTouchTarget) -> Unit) {
        val appearance = NativeUiAppearance.Default
        val colors = appearance.resolveColorScheme(context, false)
        val density = Density(context)
        val fonts = createFontFamilyResolver(context)
        fun button(label: String, recipe: NativeButtonRecipe, icon: MaterialRoundedIcon) =
            NativeButtonTouchTarget(NativeMaterialButtonView(context, recipe.forDialogAction(), density, fonts, onClick = {}).apply {
                setLeadingIcon(MaterialRoundedIconDrawable(icon, density.density, colors.ink))
                bind(label, true, colors.buttonPalette)
            })
        val first = button(firstLabel, appearance.buttons.text, MaterialRoundedIcon.Close)
        val second = button(secondLabel, appearance.buttons.destructive, MaterialRoundedIcon.Delete)
        try {
            check(NativeDialogActionLayout(first, second), first, second)
        } finally { first.button.close(); second.button.close() }
    }
}
