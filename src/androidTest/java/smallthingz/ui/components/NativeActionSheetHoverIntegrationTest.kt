package smallthingz.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeActionDescriptionView
import smallthingz.ui.unit.Density
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeActionSheetHoverIntegrationTest {
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

    private class Editor(context: Context) : EditText(context) {
        val hoverEvents = arrayListOf<Int>()

        override fun onHoverEvent(event: MotionEvent): Boolean {
            hoverEvents += event.actionMasked
            // Reproduce accessibility hover falling through an otherwise real editable child.
            return false
        }
    }

    private data class Fixture(val sheet: NativeActionSheet, val root: SheetRoot, val surface: NativeSheetSurface,
        val scrim: SheetScrim, val editor: Editor, val dismissed: CountDownLatch)

    private fun withSheet(test: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var sheet: NativeActionSheet? = null
        try {
            lateinit var fixture: Fixture
            main {
                val anchor = FrameLayout(activity)
                activity.setContentView(anchor)
                val appearance = NativeUiAppearance.Default
                val style = appearance.modals.actionSheetStyle(Density(activity), createFontFamilyResolver(activity),
                    NativeColorScheme.fallback(false))
                val glyph = NativeActionDescriptionView(activity, "Close")
                val dismissed = CountDownLatch(1)
                val dialog = NativeActionSheet(anchor, style, "Rename", dismissed::countDown,
                    NativeActionSheetIcon(glyph, { cursor, node -> glyph.bindAccessibility(cursor) { node(glyph) } }, {}))
                sheet = dialog
                val editor = Editor(dialog.context).apply {
                    showSoftInputOnFocus = false
                    setText("Recording name")
                }
                dialog.setContent(editor, View(dialog.context), emptyList(), scrollable = true)
                val surface = dialog.contentView as NativeSheetSurface
                val root = surface.parent as SheetRoot
                fixture = Fixture(dialog, root, surface, root.getChildAt(0) as SheetScrim, editor, dismissed)
                dialog.show()
            }
            awaitSettledLayout(fixture)
            test(fixture)
        } finally {
            main { try { sheet?.dismiss() } finally { activity.finish() } }
        }
    }

    private fun awaitSettledLayout(fixture: Fixture) {
        val end = SystemClock.uptimeMillis() + 4000L
        do {
            var settled = false
            main {
                settled = fixture.editor.isLaidOut && fixture.scrim.opacity >= .99f &&
                    fixture.surface.bottom == fixture.root.sourceSemanticsHeight
            }
            if (settled) return
            Thread.sleep(20)
        } while (SystemClock.uptimeMillis() < end)
        error("Sheet never reached the visible anchor")
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

    private fun motion(down: Long, action: Int, point: Pair<Float, Float>): MotionEvent = MotionEvent.obtain(
        down, SystemClock.uptimeMillis(), action, 1,
        arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
        arrayOf(MotionEvent.PointerCoords().apply { x = point.first; y = point.second; pressure = 1f; size = 1f }),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)

    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
