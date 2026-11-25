package smallthingz.ui.components

import android.content.Context
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.Window
import android.view.WindowManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.geometry.Offset
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeActionDescriptionView
import smallthingz.ui.unit.Density
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeActionSheetHoverIntegrationTest {
    @Test fun sheetWindowUsesExplicitAnchorThemeEvenWhenNightConfigurationDisagrees() {
        for (light in listOf(true, false)) {
            val night = if (light) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val theme = if (light) android.R.style.Theme_DeviceDefault_Light else android.R.style.Theme_DeviceDefault
            withSheet(anchorContext = { activity ->
                ContextThemeWrapper(activity, theme).apply {
                    applyOverrideConfiguration(Configuration().apply { uiMode = night })
                }
            }) { fixture ->
                main {
                    val window = requireNotNull(fixture.sheet.window)
                    val context = window.decorView.context
                    assertEquals(night, context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme,
                        android.R.attr.windowIsFloating, android.R.attr.windowBackground))
                    try {
                        assertTrue(attrs.hasValue(0))
                        assertEquals(light, attrs.getBoolean(0, !light))
                        assertFalse(attrs.getBoolean(1, true))
                        assertEquals(android.R.color.transparent, attrs.getResourceId(2, 0))
                    } finally {
                        attrs.recycle()
                    }
                    assertTrue(window.hasFeature(Window.FEATURE_NO_TITLE))
                    assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window.attributes.width)
                    assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window.attributes.height)
                }
            }
        }
    }

    @Test fun sheetRootKeepsTheScrimOutOfForegroundHoverDuringMotionAndReflow() = withSheet { fixture ->
        main {
            val (sheet, root, surface, scrim, editor) = fixture
            val scrimEvents = arrayListOf<Int>()
            val hoverEnter = MotionEvent.ACTION_HOVER_ENTER
            val hoverMove = MotionEvent.ACTION_HOVER_MOVE
            val hoverExit = MotionEvent.ACTION_HOVER_EXIT
            scrim.setOnHoverListener { _, event -> scrimEvents += event.actionMasked; true }
            val backdrop = backdropCenter(fixture)
            val foreground = centerInRoot(root, editor)
            hover(root, hoverEnter, backdrop)
            hover(root, hoverMove, foreground)
            hover(root, hoverMove, foreground)
            assertTrue(hoverEnter in editor.hoverEvents)
            assertEquals(listOf(hoverEnter, hoverExit), scrimEvents)

            hover(root, hoverMove, backdrop)
            hover(root, hoverExit, backdrop)
            assertEquals(listOf(hoverEnter, hoverExit, hoverEnter, hoverMove, hoverExit), scrimEvents)
            assertTrue(scrim.accessibilityTraversalAfter == surface.id)
            assertTrue(scrim.createAccessibilityNodeInfo().actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

            val before = sheet.contentContext.accessibility.sourceNode(scrim).boundsInScreen
            surface.applyMotion(surface.top.toFloat(), surface.top.toFloat(), .6f)
            val after = sheet.contentContext.accessibility.sourceNode(scrim).boundsInScreen
            assertTrue(after.bottom > before.bottom)
            assertBackdropDoesNotCoverSheet(fixture)
            scrimEvents.clear()
            hover(root, hoverEnter, centerInRoot(root, editor))
            hover(root, hoverExit, centerInRoot(root, editor))
            assertTrue(scrimEvents.isEmpty())

            // Observe the actual IME-available layout after an inset change without opening an IME.
            val ime = (80f * root.resources.displayMetrics.density).roundToInt()
            root.insets(WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).build())
            val width = root.width
            val height = root.height
            root.measure(exact(width), exact(height))
            root.layout(root.left, root.top, root.left + width, root.top + height)
            surface.offsetTopAndBottom(root.sourceSemanticsHeight - surface.height - surface.top)
            surface.applyMotion(surface.top.toFloat(), surface.top.toFloat(), 0f)
            assertEquals(height - ime, scrim.height)
            assertBackdropDoesNotCoverSheet(fixture)
            val movedEditor = centerInRoot(root, editor)
            hover(root, hoverEnter, movedEditor)
            hover(root, hoverExit, movedEditor)
            assertTrue(scrimEvents.isEmpty())
            val movedBackdrop = backdropCenter(fixture)
            hover(root, hoverEnter, movedBackdrop)
            hover(root, hoverExit, movedBackdrop)
            assertEquals(listOf(hoverEnter, hoverExit), scrimEvents)
        }
    }

    @Test fun headerCloseAndPressedBackdropRetainTheirDismissalPaths() {
        withSheet { fixture ->
            main {
                val close = requireNotNull(fixture.surface.appContent.close) {
                    "The titled action sheet must expose its close action"
                }
                tap(fixture.root, centerInRoot(fixture.root, close.visual))
            }
            assertTrue(fixture.dismissed.await(3, TimeUnit.SECONDS))
        }
        withSheet { fixture ->
            main { tap(fixture.root, backdropCenter(fixture)) }
            assertTrue(fixture.dismissed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun sheetWithoutHeaderCloseUsesFullTitleWidthAndKeepsBackdropDismissal() {
        withSheet(showHeaderClose = false) { fixture ->
            main {
                val app = fixture.surface.appContent
                val title = app.titleView
                val header = title.parent as ViewGroup
                assertNull(app.close)
                assertEquals(1, header.childCount)
                assertEquals(header.measuredWidth, title.measuredWidth)
                assertEquals(title.measuredHeight, header.measuredHeight)
                assertTrue(fixture.scrim.createAccessibilityNodeInfo().actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                })
                tap(fixture.root, backdropCenter(fixture))
            }
            assertTrue(fixture.dismissed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun sheetWithoutHeaderCloseRetainsTheDragHandleTouchTarget() {
        withSheet(showHeaderClose = false) { fixture ->
            main {
                val handle = fixture.surface.column.handle
                tap(fixture.root, centerInRoot(fixture.root, handle))
            }
            assertTrue(fixture.dismissed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun sheetWithoutHeaderCloseKeepsBackDismissal() {
        withSheet(showHeaderClose = false) { fixture ->
            main { fixture.sheet.onBackPressedDispatcher.onBackPressed() }
            assertTrue(fixture.dismissed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun topEntryUsesTheTopAnchorAndRoundsOnlyTheExposedCorners() {
        withSheet(showHeaderClose = false, entryEdge = NativeDialogEdge.TOP) { fixture ->
            main {
                val surface = fixture.surface
                assertEquals(0, surface.top)
                assertEquals(surface.column.content.bottom, surface.column.handle.top)
                assertTrue(surface.containsShape(Offset(0f, 0f)))
                assertFalse(surface.containsShape(Offset(0f, surface.height - 1f)))
                assertBackdropDoesNotCoverSheet(fixture)
            }
        }
    }

    @Test fun topEntryDismissesOnlyWhenTheHandleIsDraggedTowardTheTop() {
        withSheet(showHeaderClose = false, entryEdge = NativeDialogEdge.TOP) { fixture ->
            fun dragHandle(towardTop: Boolean) = main {
                val point = centerInRoot(fixture.root, fixture.surface.column.handle)
                val distance = if (towardTop) -point.second + 1f
                    else minOf(point.second, fixture.root.sourceSemanticsHeight - point.second - 1f)
                drag(fixture.root, point, distance)
            }
            dragHandle(towardTop = false)
            assertFalse(fixture.dismissed.await(600, TimeUnit.MILLISECONDS))
            dragHandle(towardTop = true)
            assertTrue(fixture.dismissed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun optedInTitleCloseDeliversTheBoundOwnerAfterHiding() = withSheet { fixture ->
        val settled = CountDownLatch(1)
        main {
            fixture.sheet.setAnimateCloseButton(true)
            fixture.sheet.bind("Rename") {
                assertTrue(fixture.surface.top >= fixture.root.sourceSemanticsHeight)
                settled.countDown()
            }
            tap(fixture.root, centerInRoot(fixture.root, checkNotNull(fixture.surface.appContent.close).visual))
            assertFalse(fixture.button.performClick())
            fixture.button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
            assertEquals(1L, fixture.buttonActivated.count)
        }
        assertTrue(settled.await(3, TimeUnit.SECONDS))
        assertEquals(1L, fixture.dismissed.count)
    }

    @Test fun busyOwnerInterruptsAnExplicitActionWithoutDeliveringIt() = withSheet { fixture ->
        var completed = 0
        var interrupted = 0
        main {
            fixture.sheet.setAnimateCloseButton(true)
            fixture.sheet.setDismissalAdmission { false }
            fixture.sheet.requestAnimatedDismiss({ completed++ }, { interrupted++ })
            assertEquals(0, completed)
            assertEquals(1, interrupted)
            assertEquals(1L, fixture.dismissed.count)
        }
    }

    @Test fun sensitiveEscapeDuringTitleCloseDeliversOnlyOneHiddenOwner() = withSheet { fixture ->
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val hidden = CountDownLatch(1)
        var deliveries = 0
        main {
            fixture.sheet.protectSensitiveInput()
            fixture.sheet.setAnimateCloseButton(true)
            fixture.sheet.bind("Sensitive confirmation") {
                assertTrue(fixture.surface.top >= fixture.root.sourceSemanticsHeight)
                deliveries++
                hidden.countDown()
            }
            tap(fixture.root, centerInRoot(fixture.root, checkNotNull(fixture.surface.appContent.close).visual))
            assertTrue(fixture.sheet.hasCurrentWindowFocus)
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                assertTrue(fixture.sheet.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_ESCAPE)))
            }
            assertEquals(0, deliveries)
        }
        assertTrue(hidden.await(3, TimeUnit.SECONDS))
        main { assertEquals(1, deliveries) }
    }

    private class Editor(context: Context) : EditText(context) {
        val hoverEvents = arrayListOf<Int>()

        override fun onHoverEvent(event: MotionEvent): Boolean {
            hoverEvents += event.actionMasked
            // Reproduce accessibility hover falling through an otherwise real editable child.
            return false
        }
    }

    private data class Fixture(val sheet: NativeActionSheet, val root: SheetRoot, val surface: NativeSheetSurface,
        val scrim: SheetScrim, val editor: Editor, val dismissed: CountDownLatch, val entryEdge: NativeDialogEdge,
        val button: NativeMaterialButtonView, val buttonActivated: CountDownLatch)

    private fun withSheet(
        showHeaderClose: Boolean = true,
        entryEdge: NativeDialogEdge = NativeDialogEdge.BOTTOM,
        anchorContext: (Context) -> Context = { it },
        test: (Fixture) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var sheet: NativeActionSheet? = null
        var footer: NativeMaterialButtonView? = null
        try {
            lateinit var fixture: Fixture
            main {
                val anchor = FrameLayout(anchorContext(activity)).apply {
                    if (!showHeaderClose) layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                activity.setContentView(anchor)
                val appearance = NativeUiAppearance.Default
                val style = appearance.modals.actionSheetStyle(Density(activity), createFontFamilyResolver(activity),
                    NativeColorScheme.fallback(false))
                val dismissed = CountDownLatch(1)
                val dialog = if (showHeaderClose) {
                    val glyph = NativeActionDescriptionView(activity, "Close")
                    NativeActionSheet(anchor, style, "Rename", dismissed::countDown,
                        NativeActionSheetIcon(glyph, { cursor, node -> glyph.bindAccessibility(cursor) { node(glyph) } }, {}))
                } else {
                    NativeActionSheet(anchor, style, "Rename", dismissed::countDown, entryEdge = entryEdge)
                }
                sheet = dialog
                val editor = Editor(dialog.context).apply {
                    showSoftInputOnFocus = false
                    setText("Recording name")
                }
                val buttonActivated = CountDownLatch(1)
                val button = NativeMaterialButtonView(dialog.context, appearance.buttons.filled,
                    Density(activity), createFontFamilyResolver(activity), onClick = buttonActivated::countDown)
                footer = button
                dialog.setContent(editor, button, emptyList(), scrollable = true)
                val surface = dialog.contentView as NativeSheetSurface
                val root = surface.parent as SheetRoot
                fixture = Fixture(dialog, root, surface, root.getChildAt(0) as SheetScrim, editor, dismissed, entryEdge,
                    button, buttonActivated)
                dialog.show()
            }
            awaitSettledLayout(fixture)
            test(fixture)
        } finally {
            main { try { sheet?.dismiss() } finally { try { footer?.close() } finally { activity.finish() } } }
        }
    }

    private fun awaitSettledLayout(fixture: Fixture) {
        lateinit var state: NativeActionSheetState
        main {
            state = NativeActionSheet::class.java.getDeclaredField("state")
                .apply { isAccessible = true }.get(fixture.sheet) as NativeActionSheetState
        }
        val end = SystemClock.uptimeMillis() + 4000L
        do {
            var settled = false
            main {
                val atAnchor = if (fixture.entryEdge == NativeDialogEdge.TOP) fixture.surface.top == 0
                    else fixture.surface.bottom == fixture.root.sourceSemanticsHeight
                // Pixel-rounded placement can reach the anchor before show() commits EXPANDED.
                // A handle tap during that gap requests show() again instead of dismissal.
                settled = state.currentValue == NativeSheetValue.EXPANDED &&
                    state.targetValue == NativeSheetValue.EXPANDED && !state.animationRunning &&
                    fixture.editor.isLaidOut && fixture.scrim.opacity >= .99f && atAnchor
            }
            if (settled) return
            Thread.sleep(20)
        } while (SystemClock.uptimeMillis() < end)
        error("Sheet never finished opening at the visible anchor")
    }

    private fun assertBackdropDoesNotCoverSheet(fixture: Fixture) {
        val accessibility = fixture.sheet.contentContext.accessibility
        val backdrop = accessibility.sourceNode(fixture.scrim)
        val surface = accessibility.sourceNode(fixture.surface.painted)
        assertTrue(backdrop.present && surface.present)
        assertFalse(Rect.intersects(backdrop.boundsInScreen, surface.boundsInScreen))
    }

    private fun backdropCenter(fixture: Fixture): Pair<Float, Float> {
        val node = fixture.sheet.contentContext.accessibility.sourceNode(fixture.scrim)
        check(node.present)
        val rootLocation = IntArray(2).also(fixture.root::getLocationOnScreen)
        return (node.boundsInScreen.exactCenterX() - rootLocation[0]) to (node.boundsInScreen.exactCenterY() - rootLocation[1])
    }

    private fun centerInRoot(root: View, view: View): Pair<Float, Float> {
        val bounds = Rect()
        check(view.getGlobalVisibleRect(bounds))
        val location = IntArray(2).also(root::getLocationOnScreen)
        val decor = IntArray(2).also(root.rootView::getLocationOnScreen)
        return (bounds.exactCenterX() + decor[0] - location[0]) to (bounds.exactCenterY() + decor[1] - location[1])
    }

    private fun hover(root: SheetRoot, action: Int, point: Pair<Float, Float>) {
        val time = SystemClock.uptimeMillis()
        val event = motion(time, action, point)
        try { root.dispatchGenericMotionEvent(event) } finally { event.recycle() }
    }

    private fun tap(root: SheetRoot, point: Pair<Float, Float>) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = motion(time, action, point)
            try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    private fun drag(root: SheetRoot, point: Pair<Float, Float>, distance: Float) {
        val down = SystemClock.uptimeMillis() - 64L
        for (step in 0..4) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                4 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = motion(down, action, point.first to (point.second + distance * step / 4f), down + step * 16L)
            try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    private fun motion(
        down: Long,
        action: Int,
        point: Pair<Float, Float>,
        eventTime: Long = SystemClock.uptimeMillis(),
    ): MotionEvent = MotionEvent.obtain(
        down, eventTime, action, 1,
        arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
        arrayOf(MotionEvent.PointerCoords().apply { x = point.first; y = point.second; pressure = 1f; size = 1f }),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)

    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
