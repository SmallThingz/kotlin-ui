package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import smallthingz.ui.reverb.*
import smallthingz.ui.scroll.*
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(AndroidJUnit4::class)
class NativeMergedActionOwnershipIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun shortNativeViewportMeasuresNaturalTargetsAndReachesFinalAction() = withActivity { activity ->
        lateinit var runtime: NativeModalWindowRuntime
        lateinit var viewport: NativeVerticalViewport
        lateinit var row: NativeMergedActionLayout
        lateinit var buttons: List<NativeMaterialButtonView>
        val state = NativeVerticalScrollState()
        var accepted = 0
        try {
            main {
                runtime = NativeModalWindowRuntime(activity)
                val density = Density(1f, 2f)
                val palette = NativeColorScheme.fallback(true)
                buttons = listOf("Cancel operation", "Keep original", "Save a copy").map { label ->
                    NativeMaterialButtonView(activity, NativeUiAppearance.Default.buttons.text,
                        density, createFontFamilyResolver(activity)) { accepted++ }.apply {
                        bind(label, true, palette.buttonPalette)
                    }
                }
                row = NativeMergedActionLayout(activity, buttons, palette)
                viewport = NativeVerticalViewport(activity, state, density, runtime.frames, runtime.scope, NoNestedScroll)
                viewport.addView(row)
                activity.setContentView(FrameLayout(activity).apply {
                    addView(viewport, FrameLayout.LayoutParams(120, 60))
                })
            }
            instrumentation.waitForIdleSync()
            main {
                assertEquals(60, viewport.height)
                assertEquals(row.height, state.contentSize)
                assertEquals(row.height - viewport.height, state.maxValue)
                assertTrue(state.maxValue > 0)
                buttons.forEach { assertTrue(it.height >= 48) }
                assertEquals(buttons[0].bottom, buttons[1].top)
                assertEquals(buttons[1].bottom, buttons[2].top)
                state.scrollBy(state.maxValue.toFloat())
                viewport.requestLayout()
            }
            instrumentation.waitForIdleSync()
            main {
                assertEquals(state.maxValue, state.value)
                val visible = Rect()
                assertTrue(buttons.last().getGlobalVisibleRect(visible))
                val viewportBounds = Rect()
                assertTrue(viewport.getGlobalVisibleRect(viewportBounds))
                assertEquals(viewportBounds.bottom, visible.bottom)
                assertEquals(0, accepted)
                assertTrue(buttons.last().performClick())
                assertEquals(1, accepted)
            }
        } finally { main { buttons.forEach { it.close() }; runtime.close() } }
    }

    @Test fun singleActionCallersScrollWholeContentAndKeepLegacyMetadataNatural() = withActivity { activity ->
        for (information in listOf(false, true)) {
            lateinit var owner: AutoCloseable
            lateinit var dialog: NativeActionSheet
            lateinit var actions: ReverbSheetActions
            var accepted = 0
            try {
                main {
                    val anchor = FrameLayout(activity)
                    activity.setContentView(anchor)
                    val theme = ReverbEnvironment(Density(1f, 2f), createFontFamilyResolver(activity),
                        NativeColorScheme.fallback(false)).theme
                    owner = if (information) ReverbRecordingInfoSheet(anchor, theme, "Recording information", "Close",
                        "A long recording filename", List(7) { "Metadata $it" to "Full metadata value $it" }, { accepted++ })
                    else ReverbClearProgressSheet(anchor, theme, ReverbClearProgressPresentation("Clearing buffer",
                        "Deleting recorded audio. ".repeat(12), "Cancellation remains available.", "Cancel", true,
                        .5f, 1L, false), { accepted++ })
                    dialog = owner.javaClass.getDeclaredField("dialog").apply { isAccessible = true }.get(owner) as NativeActionSheet
                    actions = owner.javaClass.getDeclaredField("actions").apply { isAccessible = true }.get(owner) as ReverbSheetActions
                    dialog.show()
                }
                instrumentation.waitForIdleSync()
                main {
                    // The actual caller must install the outer scroll owner, not just a tall action row.
                    val binding = NativeActionSheet::class.java.getDeclaredField("contentScroll")
                        .apply { isAccessible = true }.get(dialog) as? NativeVerticalPhaseBinding
                    assertNotNull("Caller must make its single action reachable", binding)
                    val viewport = checkNotNull(binding).dragNode.view as NativeVerticalViewport
                    val info = if (information) owner.javaClass.getDeclaredField("body")
                        .apply { isAccessible = true }.get(owner) as ReverbRecordingInfoBody else null
                    info?.let {
                        it.measure(exact(220), View.MeasureSpec.makeMeasureSpec(90, View.MeasureSpec.AT_MOST))
                        assertTrue(it.measuredHeight <= 90)
                        assertTrue(it.getChildAt(0).measuredHeight > 0)
                    }
                    fun layoutViewport() {
                        viewport.measure(exact(220), exact(100))
                        viewport.layout(viewport.left, viewport.top, viewport.left + 220, viewport.top + 100)
                    }
                    layoutViewport()
                    assertEquals(100, viewport.height)
                    assertTrue(viewport.state.maxValue > 0)
                    info?.let {
                        assertTrue("Filename keeps its natural height", it.getChildAt(0).height > 0)
                        assertTrue("Metadata does not collapse under UNSPECIFIED", it.viewport.height > 0)
                        assertEquals(it.viewport.state.contentSize, it.viewport.height)
                        assertEquals(0, it.viewport.state.maxValue)
                        val rows = it.viewport.getChildAt(0) as android.view.ViewGroup
                        assertEquals(7, rows.childCount)
                        for (index in 0 until rows.childCount) assertTrue(rows.getChildAt(index).height > 0)
                    }
                    val button = actions.buttons.single().accessibilitySemanticView
                    assertTrue(button.height >= 48)
                    assertEquals(viewport.getChildAt(0).height - viewport.height, viewport.state.maxValue)
                    viewport.state.scrollBy(viewport.state.maxValue.toFloat())
                    viewport.requestLayout()
                    layoutViewport()
                    val rect = Rect(0, 0, button.width, button.height)
                    viewport.offsetDescendantRectToMyCoords(button, rect)
                    assertTrue("Final action is fully reachable", rect.top >= 0 && rect.bottom <= viewport.height)
                    assertEquals(0, accepted)
                    assertTrue(button.performClick())
                    assertEquals(1, accepted)
                }
            } finally { main { owner.close() } }
        }
    }

    @Test fun sheetPhaseTreeUsesRealRoundedSurfaceInBothDirectionsAndStandaloneKeepsShape() = withActivity { activity ->
        lateinit var sheet: NativeActionSheet
        lateinit var actions: ReverbSheetActions
        lateinit var standalone: ReverbSheetButton
        var accepted = 0
        try {
            main {
                val anchor = FrameLayout(activity)
                activity.setContentView(anchor)
                val theme = ReverbEnvironment(activity, false).theme
                sheet = createReverbActionSheet(anchor, theme, "Actions", {})
                val host = sheet.contentContext
                standalone = ReverbSheetButton(activity, host, theme, true, "Information link", {})
                val buttons = listOf(
                    ReverbSheetButton(activity, host, theme, false, "Cancel", { accepted++ }),
                    ReverbSheetButton(activity, host, theme, true, "Confirm", { accepted++ }),
                )
                actions = ReverbSheetActions(activity, host, theme, buttons)
                sheet.setContent(View(activity), actions, listOf(actions.hitLayout), scrollable = true)
                sheet.show()
            }
            instrumentation.waitForIdleSync()
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                main { actions.layoutDirection = direction; actions.requestLayout() }
                instrumentation.waitForIdleSync()
                main {
                    val surface = actions.actionSurface
                    assertTrue(surface.isLaidOut)
                    assertEquals(if (direction == View.LAYOUT_DIRECTION_RTL) 0 else actions.width - surface.width, surface.left)
                    val surfaceHit = actions.hitLayout.childrenInHitOrder.single()
                    assertEquals(surface.width.toFloat(), surfaceHit.size.width, 0f)
                    assertEquals(0f, surfaceHit.minimumTouchTarget.width, 0f)
                    assertFalse(surfaceHit.withinLayerBounds(Offset(.5f, .5f)))
                    assertFalse(actions.containsPointerLayer(surface.left + .5f, surface.top + .5f))
                    assertTrue(actions.containsPointerLayer(surface.left + surface.width / 2f,
                        surface.top + surface.height / 2f))
                    if (surface.left > 0) assertFalse(actions.containsPointerLayer(surface.left / 2f, surface.height / 2f))
                    assertFalse(surfaceHit.withinLayerBounds(Offset(surface.width - .5f, .5f)))
                    assertTrue(surfaceHit.withinLayerBounds(Offset(surface.width / 2f, surface.height / 2f)))
                    val root = sheet.contentContext.inputRoot
                    for (tool in listOf(MotionEvent.TOOL_TYPE_MOUSE, MotionEvent.TOOL_TYPE_STYLUS)) {
                        val before = accepted
                        tap(root, surface, .5f, .5f, tool)
                        tap(root, surface, surface.width - .5f, .5f, tool)
                        tap(root, surface, .5f, surface.height - .5f, tool)
                        tap(root, surface, surface.width - .5f, surface.height - .5f, tool)
                        assertEquals(before, accepted)
                        val target = actions.buttons.first().accessibilitySemanticView
                        tap(root, target, target.width / 2f, target.height / 2f, tool)
                        assertEquals(before + 1, accepted)
                    }
                    val grouped = actions.buttons.first().accessibilitySemanticView as NativeMaterialButtonView
                    val original = standalone.accessibilitySemanticView as NativeMaterialButtonView
                    standalone.measure(exact(240), exact(60)); standalone.layout(0, 0, 240, 60)
                    assertFalse(original.containsPointerLayer(.5f, .5f))
                    assertTrue(grouped.containsPointerLayer(.5f, .5f))
                    val outline = Outline()
                    original.outlineProvider.getOutline(original, outline)
                    assertTrue(outline.radius > 0f)
                    grouped.outlineProvider.getOutline(grouped, outline)
                    assertEquals(0f, outline.radius, 0f)
                    assertFalse(grouped.isLayoutRequested)
                    grouped.joinActionSurface()
                    assertFalse("Repeated join is a no-op", grouped.isLayoutRequested)
                }
            }
        } finally { main { standalone.close(); actions.close(); sheet.dismiss() } }
    }

    private fun tap(root: View, target: View, x: Float, y: Float, tool: Int) {
        val coordinates = NativeWindowPointerCoordinates { root }
        coordinates.beginFromParent(Offset.Zero)
        val localToRoot = Matrix()
        assertTrue(coordinates.coordinates(target).rootToLocal.invert(localToRoot))
        val point = floatArrayOf(x, y)
        localToRoot.mapPoints(point)
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = point[0]; this.y = point[1]; pressure = 1f; size = 1f })
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 1 else 0, action,
                1, props, coords, 0, if (tool == MotionEvent.TOOL_TYPE_MOUSE && action == MotionEvent.ACTION_DOWN)
                    MotionEvent.BUTTON_PRIMARY else 0, 1f, 1f, 0, 0,
                if (tool == MotionEvent.TOOL_TYPE_MOUSE) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_STYLUS, 0)
            try { root.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun withActivity(action: (Activity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { action(activity) } finally { main { activity.finish() } }
    }
    private object NoNestedScroll : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override suspend fun preFling(availableY: Float) = 0f
        override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
    }
}
