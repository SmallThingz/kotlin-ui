package smallthingz.ui.components

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.R
import smallthingz.ui.components.blob.AudioReactiveBlobView
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeNestedPointerRoot
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.home.HomeBufferSegment
import smallthingz.ui.reverb.home.ReverbHomeActions
import smallthingz.ui.reverb.home.ReverbHomeBlobAttachment
import smallthingz.ui.reverb.home.ReverbHomeBodyOwner
import smallthingz.ui.reverb.home.ReverbHomeBodyState
import smallthingz.ui.reverb.home.ReverbHomeBufferMetrics
import smallthingz.ui.reverb.home.ReverbHomeCaptureState
import smallthingz.ui.reverb.home.ReverbHomeFlipFrame
import smallthingz.ui.reverb.home.ReverbHomeReadoutCache
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.geometry.Rect as UiRect

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-w360dp-h640dp-464dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbHomeNarrowLayoutIntegrationTest {
    @Test fun compactHomeKeepsActionsAndTabsReachableAcrossRtlAndResize() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val flags = activity.applicationInfo.flags
        activity.applicationInfo.flags = flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        try {
            val root = FrameLayout(activity)
            activity.setContentView(root)
            controller.windowFocusChanged(true)
            NativeModalWindowRuntime(activity).use { runtime ->
                verifyHome(root, runtime)
            }
        } finally {
            controller.pause().stop().destroy()
            activity.applicationInfo.flags = flags
        }
    }

    private fun verifyHome(root: FrameLayout, runtime: NativeModalWindowRuntime) {
        val context = root.context
        val environment = ReverbEnvironment(Density(2.9f), createFontFamilyResolver(context),
            NativeColorScheme.fallback(false))
        val blob = object : ReverbHomeBlobAttachment {
            override fun attach(view: AudioReactiveBlobView) = Unit
            override fun detach(view: AudioReactiveBlobView) = Unit
            override fun currentBaseRadiusFraction(): Float? = null
        }
        val history = ReverbHomeBufferMetrics(1f, 1L)
        val state = ReverbHomeBodyState<Int, Unit>(null, history, history, oneShotEnabled = true,
            oneShotFull = false, loopingEnabled = true, retentionMode = Unit, listening = false,
            saving = false, serviceConnected = true, serviceStateHydrated = true, preparingRange = false,
            oneShotController = blob, loopingController = blob, visualizerVisible = false)
        val calls = mutableListOf<String>()
        val callbacks = ReverbHomeActions<Int>(
            selectBuffer = { calls += "select $it" },
            activateBuffer = { calls += "activate $it" },
            openBufferSettings = { calls += "settings $it" },
            exportFull = { calls += "export $it" },
            exportCustom = { calls += "range $it" },
            clearBuffer = { calls += "clear $it" },
            openLibrary = { calls += "library" },
        )

        ReverbHomeBodyOwner(context, environment, state, 0, callbacks, runtime.scope, { root }, 0, 1,
            ReverbHomeReadoutCache({ true }, { "time" }, { "size" }),
            { slot, _, _, listening, active ->
                if (listening && slot == active) ReverbHomeCaptureState.RECORDING else ReverbHomeCaptureState.READY
            }, { _, _, _ -> }).use { owner ->
            val body = owner.view
            root.addView(body)
            try {
                val controls = body.getChildAt(1) as NativeCaptureControlSurface
                val content = controls.getChildAt(0) as NativeNestedPointerRoot
                val tabs = (0..1).map { content.getChildAt(it) as HomeBufferSegment }
                val actions = (2..5).map { content.getChildAt(it) as ViewGroup }
                val glyphs = actions.map { it.getChildAt(0) }
                val geometry = NativePlainTextViewGeometry(body, NativeTextSemanticsClipPolicy { view ->
                    if (view === controls) NativeTextSemanticsClip.Rectangle(localBounds(view))
                    else NativeTextSemanticsClip.Unclipped
                }, rootContentClip = { localBounds(body) })
                val semantics = tabs.map { tab ->
                    tab.mergedRowAccessibility.bindGeometry(geometry, null, { child ->
                        geometry.capture(child, Offset.Zero, localBounds(child))
                            ?.visibleBoundsInRoot?.isEmpty == false
                    }) { true }
                }
                try {
                    place(body, 846)
                    assertEquals(52, controls.left)
                    assertEquals(742, controls.width)
                    val wideActions = actions.map(::bounds)
                    assertEquals(listOf(Rect(23, 145, 180, 302), Rect(203, 145, 360, 302),
                        Rect(383, 145, 540, 302), Rect(563, 145, 719, 302)), wideActions)
                    val wideTabs = tabs.map(::bounds)
                    assertEquals(listOf(37 to 362, 379 to 704), wideTabs.map { it.left to it.right })
                    val wideTabContent = tabs.map { tab -> (0..1).map { bounds(tab.getChildAt(it)) } }

                    for ((width, expected) in listOf(559 to listOf(140, 140, 140, 139),
                        560 to List(4) { 140 }, 576 to listOf(141, 140, 140, 140))) {
                        place(body, width)
                        assertEquals(0, controls.left)
                        assertEquals(width, controls.width)
                        assertEquals(expected, actions.map { it.width })
                    }
                    assertEquals(Rect(0, 843, 576, 1168), bounds(controls))
                    assertEquals(Rect(52, 0, 524, 843), bounds(body.swipeSurface))
                    assertEquals(425, body.blobInputView.width)
                    assertEquals(listOf(263, 262), tabs.map { it.width })
                    val narrowActions = actions.map(::bounds)
                    val narrowTabs = tabs.map(::bounds)
                    val actionLabels = listOf(R.string.record_all_memory, R.string.export_range_title,
                        R.string.clear_buffer, R.string.files_tab)
                    val tabLabels = listOf(R.string.buffer_one_shot, R.string.buffer_loop)

                    // Bypass only the animation owner so the assertions can inspect a fixed rendered face.
                    body.bind(state, 0, ReverbHomeFlipFrame(), callbacks)
                    for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                        body.layoutDirection = direction
                        place(body, 576)
                        val rtl = direction == View.LAYOUT_DIRECTION_RTL
                        assertEquals(narrowActions.map { if (rtl) mirror(it, 576) else it }, actions.map(::bounds))
                        assertEquals(narrowTabs.map { if (rtl) mirror(it, 576) else it }, tabs.map(::bounds))
                        tabs.forEachIndexed { index, tab ->
                            assertEquals(direction, tab.layoutDirection)
                            assertTab(tab, context.getString(tabLabels[index]), selected = index == 0, fullLabel = true)
                        }
                        val hit = content.parentPointerLayout(Offset.Zero)
                        assertFalse(hit.withinLayerBounds(Offset(0.5f, 324.5f)))
                        actions.forEachIndexed { index, action ->
                            val glyph = glyphs[index]
                            val node = checkNotNull(action.createAccessibilityNodeInfo())
                            assertTrue(node.isVisibleToUser && node.isEnabled && node.isClickable)
                            assertEquals(context.getString(actionLabels[index]),
                                checkNotNull(glyph.createAccessibilityNodeInfo()).contentDescription.toString())
                            assertEquals(73, glyph.width)
                            assertEquals(73, glyph.height)
                            val centerY = (action.top + action.bottom) / 2f
                            assertTrue(hit.withinLayerBounds(Offset(action.left + 0.5f, centerY)))
                            assertTrue(hit.withinLayerBounds(Offset(action.right - 0.5f, centerY)))
                            for (x in listOf(glyph.left, glyph.right)) for (y in listOf(glyph.top, glyph.bottom)) {
                                assertTrue("Glyph $index is inside the parent shape in direction $direction",
                                    hit.withinLayerBounds(Offset((action.left + x).toFloat(), (action.top + y).toFloat())))
                            }
                            tap(controls, action)
                        }
                        assertEquals(listOf("export 0", "range 0", "clear 0", "library"), calls)
                        calls.clear()
                        tap(controls, tabs[1])
                        assertTrue(tabs[1].performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                        assertTrue(actions[3].performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                        assertEquals(listOf("select 1", "select 1", "library"), calls)
                        calls.clear()
                    }

                    val library = actions.last()
                    assertTrue(library.requestFocusFromTouch())
                    assertTrue(library.requestFocus())
                    assertSame(library, root.findFocus())
                    assertTrue(root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
                    assertTrue(root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)))
                    assertEquals(listOf("library"), calls)
                    calls.clear()

                    body.bind(state.copy(listening = true, activeBuffer = 1), 1, ReverbHomeFlipFrame(45f), callbacks)
                    assertFalse(actions[2].isEnabled)
                    assertTrue(actions[3].isEnabled)
                    tap(controls, actions[0])
                    tap(controls, actions[2])
                    tap(controls, actions[3])
                    assertEquals(listOf("export 1", "library"), calls)
                    assertEquals(listOf(45f, 45f, 45f, 0f), glyphs.map { it.rotationY })

                    body.layoutDirection = View.LAYOUT_DIRECTION_LTR
                    place(body, 51)
                    assertTrue(tabs.all { it.width > 0 })
                    place(body, 846)
                    assertEquals(wideActions, actions.map(::bounds))
                    assertEquals(wideTabs, tabs.map(::bounds))
                    assertEquals(wideTabContent, tabs.map { tab -> (0..1).map { bounds(tab.getChildAt(it)) } })
                    (tabs + actions).forEachIndexed { index, child -> assertSame(child, content.getChildAt(index)) }
                    assertTab(tabs[1], context.getString(R.string.buffer_loop), selected = true)
                    assertEquals(0f, glyphs[3].rotationY, 0f)
                } finally {
                    semantics.forEach { it.close() }
                }
            } finally {
                root.removeView(body)
            }
        }
    }

    private fun assertTab(tab: HomeBufferSegment, name: String, selected: Boolean, fullLabel: Boolean = false) {
        val label = tab.getChildAt(1) as ParagraphView
        assertTrue(label.width > 0)
        if (fullLabel) assertTrue("Full label width for $name", label.width >= label.maxIntrinsicTextWidth())
        val children = arrayListOf<View>()
        tab.addChildrenForAccessibility(children)
        assertEquals(listOf(label, tab.mergedRowAccessibility.roleView), children)
        assertEquals(name, label.createAccessibilityNodeInfo()?.text.toString())
        val node = checkNotNull(tab.createAccessibilityNodeInfo())
        assertTrue(node.isVisibleToUser && node.isEnabled)
        assertEquals(selected, node.isSelected)
        assertEquals(!selected, node.isClickable)
    }

    private fun tap(controls: View, action: View) {
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val center = MotionEvent.PointerCoords().apply {
            x = (action.left + action.right) / 2f
            y = (action.top + action.bottom) / 2f
            pressure = 1f
            size = 1f
        }
        val down = SystemClock.uptimeMillis()
        for (actionCode in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, down, actionCode, 1, arrayOf(pointer), arrayOf(center),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                controls.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun place(body: View, width: Int) {
        body.measure(exact(width), exact(1220))
        body.layout(0, 0, width, 1220)
    }

    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)
    private fun mirror(bounds: Rect, width: Int) = Rect(width - bounds.right, bounds.top, width - bounds.left, bounds.bottom)
    private fun localBounds(view: View) = UiRect(0f, 0f, view.width.toFloat(), view.height.toFloat())
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
