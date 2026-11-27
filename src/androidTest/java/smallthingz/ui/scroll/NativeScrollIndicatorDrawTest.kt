package smallthingz.ui.scroll

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.components.withRetiringTestActivity
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.pointer.PointerType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeScrollIndicatorDrawTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val style get() = NativeUiAppearance.Default.scrollIndicator

    @Test fun physicalRightAndDpGeometryHoldInLtrAndRtl() = main {
        for (direction in intArrayOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val mdpi = list(160, direction).apply { adapter = Rows(16) }
            val xhdpi = list(320, direction).apply { adapter = Rows(16) }
            for (list in listOf(mdpi, xhdpi)) {
                list.setPadding(7, 0, 13, 0)
                layout(list, 120, 96)
                assertEquals(direction, list.layoutDirection)
                revealAtTop(list)
            }
            assertThumb(mdpi, Rect(116, 0, 118, 24))
            assertThumb(xhdpi, Rect(112, 0, 116, 48))
        }
    }

    @Test fun realRowsUpdateTheThumbWhenScrolledResizedAndRebound() = main {
        val list = list(160, View.LAYOUT_DIRECTION_LTR)
        list.adapter = Rows(0)
        layout(list, 120, 96)
        assertThumb(list, null)

        list.adapter = Rows(4)
        layout(list, 120, 96)
        assertThumb(list, null)

        list.adapter = Rows(16)
        layout(list, 120, 96)
        revealAtTop(list)
        assertThumb(list, Rect(116, 0, 118, 24))
        list.scrollBy(0, 144)
        assertThumb(list, Rect(116, 36, 118, 60))

        val custom = NativeScrollIndicatorStyle(widthDp = 4f, rightInsetDp = 5f, minThumbHeightDp = 30f)
        list.bindScrollIndicator(custom, Color.BLUE)
        assertThumb(list, Rect(111, 33, 115, 63), Color.BLUE)
        list.scrollBy(0, 144)
        assertThumb(list, Rect(111, 66, 115, 96), Color.BLUE)
        list.bindScrollIndicator(null, Color.BLUE)
        assertThumb(list, null)

        list.bindScrollIndicator(style, Color.RED)
        list.scrollBy(0, -288)
        layout(list, 90, 192)
        assertThumb(list, Rect(86, 0, 88, 96))
        layout(list, 90, 384)
        assertThumb(list, null)
    }

    @Test fun indicatorSurvivesHardwareSkipDrawRequests() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: LinearLayout? = null
        var dialog: Dialog? = null
        withRetiringTestActivity(activity, root = { host }, cleanup = { dialog?.dismiss() }) {
            lateinit var bound: NativeSourceRecyclerView
            lateinit var control: NativeSourceRecyclerView
            lateinit var fixtureWindow: Window
            val laidOut = CountDownLatch(1)
            main {
                // Expanded Dark Theme can invert light windows despite View opt-outs. Select
                // a genuinely dark theme before this test-local hardware window first attaches.
                val fixture = Dialog(activity, android.R.style.Theme_Material_NoActionBar).also { dialog = it }
                if (Build.VERSION.SDK_INT >= 29) {
                    val lightTheme = TypedValue()
                    assertTrue(fixture.context.theme.resolveAttribute(android.R.attr.isLightTheme, lightTheme, true))
                    assertEquals(TypedValue.TYPE_INT_BOOLEAN, lightTheme.type)
                    assertEquals("PixelCopy fixture requires a dark window theme", 0, lightTheme.data)
                }
                fixtureWindow = requireNotNull(fixture.window)
                fixtureWindow.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                val root = LinearLayout(fixture.context).apply {
                    orientation = LinearLayout.VERTICAL
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    // Match the Library/Incidents host policy for explicit palette colors.
                    if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
                    setBackgroundColor(Color.WHITE)
                }
                host = root
                fun addList() = recycler(fixture.context, 160, View.LAYOUT_DIRECTION_LTR).apply {
                    adapter = Rows(16)
                    isFocusable = false
                    defaultFocusHighlightEnabled = false
                    root.addView(this, LinearLayout.LayoutParams(120, 96))
                }
                bound = addList()
                control = addList()
                control.doOnLayout { laidOut.countDown() }
                fixture.setContentView(root)
                fixture.show()
            }
            assertTrue("Hardware fixture did not lay out", laidOut.await(5, TimeUnit.SECONDS))
            // Establish the fixed-color control before testing scrollbar painting or fading.
            assertWindowThumb(fixtureWindow, bound, control, Color.WHITE)

            main {
                if (Build.VERSION.SDK_INT >= 29) {
                    assertFalse("Fixture root must preserve explicit ARGB colors", requireNotNull(host).isForceDarkAllowed)
                }
                for (list in listOf(bound, control)) {
                    assertTrue(list.isAttachedToWindow && list.isHardwareAccelerated)
                    assertTrue(list.canScrollVertically(1))
                    assertEquals(View.OVER_SCROLL_NEVER, list.overScrollMode)
                    assertNull(list.background)
                    assertNull(list.foreground)
                    assertFalse(list.hasFocus())
                }
                bound.setWillNotDraw(true)
                assertTrue("Fixture must enter draw suppression before binding", bound.willNotDraw())
                bound.bindScrollIndicator(style, Color.RED)
                assertFalse(bound.willNotDraw())
                revealAtTop(bound)
            }
            assertWindowThumb(fixtureWindow, bound, control, Color.RED)

            main {
                // A different tint makes PixelCopy reject a stale pre-removal frame.
                bound.bindScrollIndicator(style, Color.BLUE)
                for (list in listOf(bound, control)) {
                    val decoration = object : RecyclerView.ItemDecoration() {}
                    list.addItemDecoration(decoration)
                    assertEquals(1, list.itemDecorationCount)
                    list.removeItemDecoration(decoration)
                    assertEquals(0, list.itemDecorationCount)
                }
                assertTrue("Unbound RecyclerView must take its stock reset branch", control.willNotDraw())
                assertFalse("Bound thumb must keep hardware drawing enabled", bound.willNotDraw())
                revealAtTop(bound)
            }
            assertWindowThumb(fixtureWindow, bound, control, Color.BLUE)
            assertWindowThumb(fixtureWindow, bound, control, Color.WHITE)
            main {
                assertFalse(bound.canStartScrollIndicatorDrag(117f, 12f))
                revealAtTop(bound)
            }
            assertWindowThumb(fixtureWindow, bound, control, Color.BLUE)

            main {
                bound.bindScrollIndicator(null, Color.BLUE)
                assertTrue("Unbind restores the latest suppressed draw request", bound.willNotDraw())
            }
            assertWindowThumb(fixtureWindow, bound, control, Color.WHITE)

            main {
                bound.bindScrollIndicator(style, Color.BLUE)
                bound.addItemDecoration(object : RecyclerView.ItemDecoration() {})
                bound.bindScrollIndicator(null, Color.BLUE)
                assertFalse("Unbind honors a later draw request from decoration addition", bound.willNotDraw())
            }
        }
    }

    @Test fun rtlThumbDragStopsKineticsAndSeeksBothEndsWithBoundedRowBinds() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: LinearLayout? = null
        withRetiringTestActivity(activity, root = { host }) {
            val laidOut = CountDownLatch(1)
            lateinit var list: NativeSourceRecyclerView
            val rows = Rows(1_000) { position -> if (position < 8) 24 else 160 }
            main {
                val root = LinearLayout(activity).also { host = it }
                list = recycler(activity, 160, View.LAYOUT_DIRECTION_RTL).apply {
                    val viewport = this
                    layoutManager = object : LinearLayoutManager(context) {
                        override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
                            if (!state.isPreLayout) viewport.applyPendingScrollIndicatorPlacement(this, state.itemCount)
                            super.onLayoutChildren(recycler, state)
                        }
                    }
                    setBackgroundColor(Color.WHITE)
                    adapter = rows
                    root.addView(this, LinearLayout.LayoutParams(120, 96))
                    doOnLayout { laidOut.countDown() }
                }
                activity.setContentView(root)
            }
            assertTrue(laidOut.await(5, TimeUnit.SECONDS))
            main {
                list.bindScrollIndicator(style, Color.RED)
                revealAtTop(list)
                assertThumb(list, Rect(116, 0, 118, 24))
                assertTrue(list.canStartScrollIndicatorDrag(117f, 12f))
                assertFalse(list.canStartScrollIndicatorDrag(114f, 12f))
                assertFalse(list.canStartScrollIndicatorDrag(4f, 12f))
                assertFalse(list.canStartScrollIndicatorDrag(117f, 60f))
                assertFalse(list.canStartScrollIndicatorDrag(110f, 12f))

                list.setPadding(7, 0, 16, 0)
                layout(list, 120, 96)
                assertThumb(list, Rect(116, 0, 118, 24))
                assertTrue(list.canStartScrollIndicatorDrag(110f, 12f))
                assertFalse(list.canStartScrollIndicatorDrag(103f, 12f))
                assertFalse(list.canStartScrollIndicatorDrag(110f, 60f))

                var userScrolls = 0
                var stops = 0
                list.sourceOnUserScroll = { userScrolls++ }
                list.sourceStopScroll = { stops++ }
                val node = list.scrollIndicatorPointerNode
                fun pointer(y: Float, previousY: Float, pressed: Boolean, previouslyPressed: Boolean): Boolean {
                    val position = Offset(110f, y)
                    val change = NativeDragPointerChange(0L, SystemClock.uptimeMillis(),
                        position, Offset(110f, previousY), pressed, previouslyPressed, PointerType.Touch, position)
                    node.dispatch(NativeDragPointerEvent(listOf(change), Offset.Zero), NativePointerPass.INITIAL)
                    return change.consumed
                }

                val before = rows.binds
                assertTrue(pointer(12f, 12f, pressed = true, previouslyPressed = false))
                assertTrue(list.isScrollIndicatorDragging)
                assertFalse(list.canStartScrollIndicatorDrag(117f, 12f))
                assertEquals(1, userScrolls)
                assertEquals(1, stops)
                assertFalse(list.canScrollVertically(-1))
                assertTrue(pointer(48f, 12f, pressed = true, previouslyPressed = true))
                assertTrue(list.hasPendingScrollIndicatorPlacement)
                layout(list, 120, 96)
                val manager = list.layoutManager as LinearLayoutManager
                assertFalse(list.hasPendingScrollIndicatorPlacement)
                assertTrue(manager.findFirstVisibleItemPosition() in 400..600)
                assertTrue("Dragging to the middle bound ${rows.binds - before} rows", rows.binds - before < 24)

                val beforeEnd = rows.binds
                rows.prependRow()
                // The endpoint coordinate arrives on UP without an intervening MOVE.
                assertTrue(pointer(92f, 48f, pressed = false, previouslyPressed = true))
                assertFalse(list.isScrollIndicatorDragging)
                assertTrue(list.hasPendingScrollIndicatorPlacement)
                layout(list, 120, 96)
                assertFalse(list.hasPendingScrollIndicatorPlacement)
                assertFalse(list.canScrollVertically(1))
                val last = checkNotNull(manager.findViewByPosition(rows.itemCount - 1))
                assertEquals(list.height, manager.getDecoratedBottom(last))
                assertTrue("Dragging to the end bound ${rows.binds - beforeEnd} rows", rows.binds - beforeEnd < 24)
                assertThumb(list, Rect(116, 72, 118, 96))

                assertTrue(pointer(84f, 84f, pressed = true, previouslyPressed = false))
                assertTrue(pointer(-24f, 84f, pressed = true, previouslyPressed = true))
                layout(list, 120, 96)
                assertFalse(list.canScrollVertically(-1))
                assertTrue(pointer(-24f, -24f, pressed = false, previouslyPressed = true))
                assertThumb(list, Rect(116, 0, 118, 24))

                assertTrue(pointer(12f, 12f, pressed = true, previouslyPressed = false))
                assertTrue(pointer(48f, 12f, pressed = true, previouslyPressed = true))
                assertTrue(list.hasPendingScrollIndicatorPlacement)
                val now = SystemClock.uptimeMillis()
                val properties = Array(2) { id ->
                    MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
                }
                val coords = Array(2) { index ->
                    MotionEvent.PointerCoords().apply { x = 110f; y = if (index == 0) 92f else 48f }
                }
                val canceledLift = MotionEvent.obtain(now, now, MotionEvent.ACTION_POINTER_UP, 2, properties,
                    coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.FLAG_CANCELED)
                try {
                    node.bindMotion(canceledLift, null, 1)
                    assertTrue(pointer(92f, 48f, pressed = false, previouslyPressed = true))
                } finally {
                    node.bindMotion(null, null, 0)
                    canceledLift.recycle()
                }
                assertFalse(list.isScrollIndicatorDragging)
                assertFalse(list.hasPendingScrollIndicatorPlacement)
                layout(list, 120, 96)
                assertFalse(list.canScrollVertically(-1))

                assertTrue(pointer(12f, 12f, pressed = true, previouslyPressed = false))
                assertTrue(pointer(120f, 12f, pressed = true, previouslyPressed = true))
                assertTrue(list.hasPendingScrollIndicatorPlacement)
                node.cancel()
                assertFalse(list.isScrollIndicatorDragging)
                assertFalse(list.hasPendingScrollIndicatorPlacement)
                assertFalse(pointer(121f, 120f, pressed = true, previouslyPressed = true))
                layout(list, 120, 96)
                assertFalse(list.canScrollVertically(-1))
            }
        }
    }

    @Test fun replacingAdapterOrLayoutManagerRetiresHeldThumbAndPendingPlacement() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: LinearLayout? = null
        withRetiringTestActivity(activity, root = { host }) {
            val laidOut = CountDownLatch(1)
            lateinit var list: NativeSourceRecyclerView
            fun manager() = object : LinearLayoutManager(list.context) {
                override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
                    if (!state.isPreLayout) list.applyPendingScrollIndicatorPlacement(this, state.itemCount)
                    super.onLayoutChildren(recycler, state)
                }
            }
            main {
                val root = LinearLayout(activity).also { host = it }
                list = recycler(activity, 160, View.LAYOUT_DIRECTION_LTR)
                list.layoutManager = manager()
                list.adapter = Rows(1_000)
                list.setBackgroundColor(Color.WHITE)
                root.addView(list, LinearLayout.LayoutParams(120, 96))
                list.doOnLayout { laidOut.countDown() }
                activity.setContentView(root)
            }
            assertTrue(laidOut.await(5, TimeUnit.SECONDS))
            main {
                assertTrue(list.isAttachedToWindow && list.isShown)
                list.bindScrollIndicator(style, Color.RED)
                val node = list.scrollIndicatorPointerNode
                fun pointer(y: Float, previousY: Float, pressed: Boolean, previouslyPressed: Boolean): Boolean {
                    val position = Offset(117f, y)
                    val change = NativeDragPointerChange(0L, SystemClock.uptimeMillis(),
                        position, Offset(117f, previousY), pressed, previouslyPressed, PointerType.Touch, position)
                    node.dispatch(NativeDragPointerEvent(listOf(change), Offset.Zero), NativePointerPass.INITIAL)
                    return change.consumed
                }

                for (replacement in listOf("adapter", "swapAdapter", "layoutManager")) {
                    (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
                    layout(list, 120, 96)
                    revealAtTop(list)
                    assertThumb(list, Rect(116, 0, 118, 24))
                    assertTrue(pointer(12f, 12f, pressed = true, previouslyPressed = false))
                    assertTrue(pointer(48f, 12f, pressed = true, previouslyPressed = true))
                    assertTrue(list.isScrollIndicatorDragging)
                    assertTrue(list.hasPendingScrollIndicatorPlacement)

                    when (replacement) {
                        "adapter" -> list.adapter = Rows(600)
                        "swapAdapter" -> list.swapAdapter(Rows(800), true)
                        "layoutManager" -> list.layoutManager = manager()
                    }
                    assertFalse("$replacement retained pointer ownership", list.isScrollIndicatorDragging)
                    assertFalse(list.hasPendingScrollIndicatorPlacement)
                    val currentManager = list.layoutManager as LinearLayoutManager
                    currentManager.scrollToPositionWithOffset(0, 0)
                    layout(list, 120, 96)
                    assertEquals(0, currentManager.findFirstVisibleItemPosition())
                    val intendedOffset = list.computeVerticalScrollOffset()

                    assertFalse(pointer(120f, 48f, pressed = true, previouslyPressed = true))
                    assertFalse(pointer(120f, 120f, pressed = false, previouslyPressed = true))
                    assertFalse(list.isScrollIndicatorDragging)
                    assertFalse(list.hasPendingScrollIndicatorPlacement)
                    layout(list, 120, 96)
                    assertEquals("$replacement accepted a stale seek",
                        intendedOffset, list.computeVerticalScrollOffset())

                    revealAtTop(list)
                    assertThumb(list, Rect(116, 0, 118, 24))
                    assertTrue(pointer(12f, 12f, pressed = true, previouslyPressed = false))
                    assertTrue(pointer(120f, 12f, pressed = true, previouslyPressed = true))
                    assertTrue(pointer(120f, 120f, pressed = false, previouslyPressed = true))
                    assertFalse(list.isScrollIndicatorDragging)
                    assertTrue(list.hasPendingScrollIndicatorPlacement)
                    layout(list, 120, 96)
                    assertFalse(list.hasPendingScrollIndicatorPlacement)
                    assertFalse(list.canScrollVertically(1))
                }
            }
        }
    }

    @SdkSuppress(minSdkVersion = 28, maxSdkVersion = 28)
    @Test fun pieRetiresTheFadeWakeOnVisibilityAndPresentationLoss() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: LinearLayout? = null
        withRetiringTestActivity(activity, root = { host }) {
            lateinit var list: NativeSourceRecyclerView
            val laidOut = CountDownLatch(1)
            val windowHidden = CountDownLatch(1)
            val windowShown = CountDownLatch(1)
            var observeWindow = false
            var hadPendingWakeAtWindowHide = false
            val fadeDelay = 1_000
            main {
                val root = object : LinearLayout(activity) {
                    override fun onWindowVisibilityChanged(visibility: Int) {
                        super.onWindowVisibilityChanged(visibility)
                        if (!observeWindow) return
                        if (visibility == View.VISIBLE) {
                            windowShown.countDown()
                        } else {
                            // A ViewGroup receives the window transition before its children.
                            hadPendingWakeAtWindowHide = legacyWakePending(list)
                            windowHidden.countDown()
                        }
                    }
                }.also { host = it }
                list = recycler(activity, 160, View.LAYOUT_DIRECTION_LTR).apply {
                    adapter = Rows(16)
                    setBackgroundColor(Color.WHITE)
                    scrollBarDefaultDelayBeforeFade = fadeDelay
                    bindScrollIndicator(style, Color.RED)
                    root.addView(this, LinearLayout.LayoutParams(120, 96))
                    doOnLayout { laidOut.countDown() }
                }
                activity.setContentView(root)
            }
            assertTrue("Fixture did not lay out", laidOut.await(5, TimeUnit.SECONDS))

            main {
                revealAtTop(list)
                assertThumb(list, Rect(116, 0, 118, 24))
                assertTrue(list.canStartScrollIndicatorDrag(117f, 12f))
                assertTrue(legacyWakePending(list))

                list.scrollIndicatorPresentationActive = false
                assertTrue(list.isAttachedToWindow && list.isShown)
                assertFalse(legacyWakePending(list))
                assertFalse(list.canStartScrollIndicatorDrag(117f, 12f))
                revealAtTop(list)
                assertThumb(list, null)
                assertFalse(legacyWakePending(list))

                list.scrollIndicatorPresentationActive = true
                assertThumb(list, null)
                revealAtTop(list)
                assertThumb(list, Rect(116, 0, 118, 24))
                assertTrue(legacyWakePending(list))

                // Incidents can hide the attached list when history becomes empty. A parent
                // can also hide the list without changing the window or presentation binding.
                for (hidden in listOf(list, requireNotNull(host))) {
                    hidden.visibility = View.GONE
                    assertTrue(list.isAttachedToWindow && list.windowVisibility == View.VISIBLE)
                    assertFalse(list.isShown)
                    assertFalse(legacyWakePending(list))
                    assertFalse(list.canStartScrollIndicatorDrag(117f, 12f))
                    list.scrollBy(0, 1)
                    assertEquals(1, list.computeVerticalScrollOffset())
                    list.scrollBy(0, -1)
                    assertThumb(list, null)
                    assertFalse(legacyWakePending(list))

                    hidden.visibility = View.VISIBLE
                    assertTrue(list.isShown)
                    assertFalse(list.canStartScrollIndicatorDrag(117f, 12f))
                    assertThumb(list, null)
                    assertFalse(legacyWakePending(list))
                    revealAtTop(list)
                    assertThumb(list, Rect(116, 0, 118, 24))
                    assertTrue(legacyWakePending(list))
                }

                observeWindow = true
                activity.setVisible(false)
            }
            assertTrue("Fixture window stayed visible", windowHidden.await(5, TimeUnit.SECONDS))
            assertTrue("Window must hide before the scheduled fade wake", hadPendingWakeAtWindowHide)
            main {
                assertTrue(list.isAttachedToWindow && list.windowVisibility != View.VISIBLE)
                assertFalse(legacyWakePending(list))
                assertFalse(list.canStartScrollIndicatorDrag(117f, 12f))
                revealAtTop(list)
                assertThumb(list, null)
                assertFalse(legacyWakePending(list))
            }

            val afterFade = fadeDelay + ViewConfiguration.getScrollBarFadeDuration() + 100L
            SystemClock.sleep(afterFade)
            main {
                assertFalse(legacyWakePending(list))
                activity.setVisible(true)
            }
            assertTrue("Fixture window stayed hidden", windowShown.await(5, TimeUnit.SECONDS))
            main {
                assertThumb(list, null)
                assertFalse(list.canStartScrollIndicatorDrag(117f, 12f))
                revealAtTop(list)
                assertThumb(list, Rect(116, 0, 118, 24))
                assertTrue(list.canStartScrollIndicatorDrag(117f, 12f))
                assertTrue(legacyWakePending(list))
            }
            SystemClock.sleep(afterFade)
            main {
                assertThumb(list, null)
                assertFalse(list.canStartScrollIndicatorDrag(117f, 12f))
                assertFalse(legacyWakePending(list))
            }
        }
    }

    private fun legacyWakePending(list: NativeSourceRecyclerView): Boolean {
        // Observe the shared Runnable's ownership without adding test-only runtime APIs.
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }
        val indicator = checkNotNull(field(list, "scrollIndicator").get(list))
        val fade = checkNotNull(field(indicator, "legacyFade").get(indicator))
        return field(fade, "wakePending").getBoolean(fade)
    }

    private fun revealAtTop(list: RecyclerView) {
        list.scrollBy(0, 1)
        list.scrollBy(0, -1)
    }

    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun assertWindowThumb(
        window: Window,
        bound: RecyclerView,
        control: RecyclerView,
        expected: Int,
    ) {
        val source = Rect()
        var windowState = ""
        main {
            val boundLocation = IntArray(2).also(bound::getLocationInWindow)
            val controlLocation = IntArray(2).also(control::getLocationInWindow)
            assertEquals(boundLocation[0], controlLocation[0])
            // Capture the same interior thumb position from each 120×96 mdpi list.
            val x = boundLocation[0] + 117
            source.set(x, boundLocation[1] + 12, x + 1, controlLocation[1] + 13)
            val forceDarkState = if (Build.VERSION.SDK_INT >= 29) {
                val value = TypedValue()
                val resolved = window.context.theme.resolveAttribute(android.R.attr.forceDarkAllowed, value, true)
                val themeAllowed = when {
                    !resolved -> "unresolved"
                    value.type == TypedValue.TYPE_INT_BOOLEAN -> (value.data != 0).toString()
                    else -> "type=${value.type},data=${value.data}"
                }
                "themeForceDarkAllowed=$themeAllowed " +
                    "viewForceDarkAllowed=${bound.isForceDarkAllowed}/${control.isForceDarkAllowed} " +
                    "parentForceDarkAllowed=${(bound.parent as View).isForceDarkAllowed} " +
                    "decorForceDarkAllowed=${window.decorView.isForceDarkAllowed}"
            } else "forceDarkAllowed=unavailable(api<29)"
            windowState = "source=$source boundOrigin=${boundLocation.contentToString()} " +
                "controlOrigin=${controlLocation.contentToString()} " +
                "boundSize=${bound.width}x${bound.height} controlSize=${control.width}x${control.height} " +
                "density=${bound.resources.displayMetrics.density} " +
                "shown=${bound.isShown}/${control.isShown} focus=${bound.hasWindowFocus()} " +
                "windowVisibility=${bound.windowVisibility} hardware=${bound.isHardwareAccelerated} " +
                "willNotDraw=${bound.willNotDraw()}/${control.willNotDraw()} " +
                "uiMode(window/bound/control)=${window.context.resources.configuration.uiMode}/" +
                "${bound.resources.configuration.uiMode}/${control.resources.configuration.uiMode} $forceDarkState"
        }
        val handler = Handler(Looper.getMainLooper())
        val started = SystemClock.uptimeMillis()
        val deadline = started + 5_000
        var result = PixelCopy.ERROR_SOURCE_NO_DATA
        var boundPixel = Color.TRANSPARENT
        var controlPixel = Color.TRANSPARENT
        var attempts = 0
        var completed = 0
        var firstCopy = "none"
        var lastCopy = "none"
        fun diagnostics() = "expected=0x${Integer.toHexString(expected)} " +
            "elapsedMs=${SystemClock.uptimeMillis() - started} attempts=$attempts completed=$completed " +
            "firstCompleted=[$firstCopy] lastCompleted=[$lastCopy] $windowState"
        while (SystemClock.uptimeMillis() < deadline) {
            val image = Bitmap.createBitmap(source.width(), source.height(), Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var requested = 0L
            attempts++
            main {
                requested = SystemClock.uptimeMillis()
                PixelCopy.request(window, Rect(source), image, { status ->
                    result = status
                    copied.countDown()
                }, handler)
            }
            val waitMillis = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            val didComplete = copied.await(waitMillis, TimeUnit.MILLISECONDS)
            assertTrue("PixelCopy request pending at deadline, requestStartedMs=${requested - started} " +
                "waitBudgetMs=$waitMillis ${diagnostics()}", didComplete)
            try {
                if (result == PixelCopy.SUCCESS) {
                    boundPixel = image.getPixel(0, 0)
                    controlPixel = image.getPixel(0, image.height - 1)
                }
                completed++
                lastCopy = "status=$result elapsedMs=${SystemClock.uptimeMillis() - started} " +
                    if (result == PixelCopy.SUCCESS)
                        "bound=0x${Integer.toHexString(boundPixel)} control=0x${Integer.toHexString(controlPixel)}"
                    else "pixels=unavailable"
                if (completed == 1) firstCopy = lastCopy
                if (result == PixelCopy.SUCCESS && boundPixel == expected && controlPixel == Color.WHITE) return
            } finally {
                image.recycle()
            }
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(minOf(16L, remaining))
        }
        assertEquals("Window PixelCopy status ${diagnostics()}", PixelCopy.SUCCESS, result)
        assertEquals("Bound RecyclerView pixel ${diagnostics()}", expected, boundPixel)
        assertEquals("Unbound control pixel ${diagnostics()}", Color.WHITE, controlPixel)
    }

    private fun list(density: Int, direction: Int) = recycler(instrumentation.context, density, direction).apply {
        setBackgroundColor(Color.WHITE)
        bindScrollIndicator(style, Color.RED)
    }

    private fun recycler(base: Context, density: Int, direction: Int): NativeSourceRecyclerView {
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = density
        })
        return NativeSourceRecyclerView(context).apply {
            layoutDirection = direction
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
        }
    }

    private fun layout(list: RecyclerView, width: Int, height: Int) {
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, width, height)
    }

    private fun assertThumb(list: RecyclerView, bounds: Rect?, color: Int = Color.RED) {
        val bitmap = Bitmap.createBitmap(list.width, list.height, Bitmap.Config.ARGB_8888)
        try {
            list.draw(Canvas(bitmap))
            for (y in 0 until list.height) for (x in 0 until list.width) {
                if (bitmap.getPixel(x, y) != Color.WHITE) {
                    assertTrue("Unexpected painted pixel ($x, $y), expected thumb at $bounds",
                        bounds?.contains(x, y) == true)
                }
            }
            if (bounds != null) {
                for (x in bounds.left until bounds.right) {
                    assertEquals(color, bitmap.getPixel(x, bounds.centerY()))
                }
                // The end rows can be partially covered by the rounded, antialiased cap.
                assertTrue(bitmap.getPixel(bounds.centerX(), bounds.top) != Color.WHITE)
                assertTrue(bitmap.getPixel(bounds.centerX(), bounds.bottom - 1) != Color.WHITE)
                if (bounds.width() >= 4) {
                    assertTrue(bitmap.getPixel(bounds.left, bounds.top) != color)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class Row(view: View) : RecyclerView.ViewHolder(view)

    private class Rows(private var count: Int, private val heightAt: (Int) -> Int = { 24 }) :
        RecyclerView.Adapter<Row>() {
        var binds = 0
            private set

        override fun getItemCount() = count

        fun prependRow() {
            count++
            notifyItemInserted(0)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(View(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24)
        })
        override fun onBindViewHolder(holder: Row, position: Int) {
            binds++
            holder.itemView.layoutParams.height = heightAt(position)
        }
    }
}
