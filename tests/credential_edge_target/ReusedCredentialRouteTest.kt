package smallthingz.ui.common

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.components.NativeInputGate
import smallthingz.ui.components.NativeLabeledTextField
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbStandardIconButtonView
import kotlin.math.roundToInt

/** Exact existing routing method/assertions/helpers, with only cloud Activity and layout lifecycle adaptation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36], application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReusedCredentialRouteTest {
    private enum class Field { CONNECTOR, CLIENT }
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private val activityHarness = object {
        val context: Context get() = activity
        fun startActivitySync(ignored: Intent): Activity = activity
        fun runOnMainSync(action: () -> Unit) = action()
    }
    @Before fun attach() {
        RuntimeEnvironment.setQualifiers("en-rUS-w380dp-h800dp-port-mdpi")
        Instrumentation().setInTouchMode(true)
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        activity = controller.get()
        controller.windowFocusChanged(true)
    }
    @After fun detach() { controller.pause().stop().destroy() }
    private fun awaitDraw(view: View) {
        // Exact existing geometry and input assertions run after synchronous framework measure/layout.
        // This replaces device draw-latch scheduling only; no pointer dispatch is replaced.
        view.measure(View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 380, 800)
        assertTrue(view.isAttachedToWindow && view.hasWindowFocus())
    }
    @Test fun rootDispatchRoutesRevealMarginsWithoutEditingAcrossResizeRtlAndHiddenActions() {
        val instrumentation = activityHarness
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, Activity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val reveal = CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.REVEAL)
        val copy = CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.COPY)
        val reroll = CredentialActionTarget(Field.CONNECTOR, CredentialFieldAction.REROLL)
        val admitted = listOf(CredentialActionState(reveal, "Show"), CredentialActionState(copy, "Copy"),
            CredentialActionState(reroll, "Regenerate"))
        val state = CredentialFieldState(Field.CONNECTOR, "synthetic-credential")
        val calls = mutableListOf<CredentialActionTarget<Field>>()
        val editorEvents = mutableListOf<Int>()
        val edits = mutableListOf<Pair<Field, String>>()
        var group: CredentialFieldGroup<Field>? = null
        lateinit var host: AdmissionHost
        lateinit var field: NativeLabeledTextField
        lateinit var row: LinearLayout
        lateinit var button: View
        lateinit var retainedText: Editable
        var wide = 0
        var narrow = 0
        val density = activity.resources.displayMetrics.density
        fun px(value: Int) = (value * density).roundToInt()
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        try {
            main {
                val installed = CredentialFieldGroup(activity, ReverbDesign.theme(activity, false).controls,
                    listOf(CredentialFieldSpec(Field.CONNECTOR, "Connector")), setOf(reveal, copy, reroll),
                    onAction = { calls += it }) { id, value -> edits += id to value }
                group = installed
                installed.bind(listOf(state), admitted)
                field = descendants(installed.view).filterIsInstance<NativeLabeledTextField>().single()
                row = field.trailingHost.getChildAt(0) as LinearLayout
                button = (row.getChildAt(0) as ReverbStandardIconButtonView).visual
                field.editor.showSoftInputOnFocus = false
                // Record routing without opening an IME or letting a misrouted tap edit the fixture.
                field.editor.setOnTouchListener { _, event -> editorEvents += event.actionMasked; true }
                host = AdmissionHost(activity).apply {
                    isFocusableInTouchMode = true
                    addView(installed.view, FrameLayout.LayoutParams(-1, -2))
                }
                activity.setContentView(host)
            }
            awaitDraw(host)
            main {
                // Stay within the actual owned window; do not inject coordinates outside its root.
                wide = minOf(host.width, px(320))
                assertTrue("Fixture needs room for three 48dp actions and field insets", wide >= px(224))
                narrow = minOf(px(224), wide - px(16))
                assertTrue(field.editor.requestFocus())
                field.editor.setSelection(2, 7)
                retainedText = field.editor.text
            }
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                for (width in listOf(wide, narrow, wide)) {
                    main {
                        host.layoutDirection = direction
                        requireNotNull(group).view.layoutParams = FrameLayout.LayoutParams(width, -2)
                        requireNotNull(group).bind(listOf(state), admitted)
                    }
                    awaitDraw(host)
                    main {
                        assertTrue(button.isShown && button.hasWindowFocus())
                        assertEquals(px(40), button.height)
                        assertEquals(px(48), row.height)
                        val buttonBounds = boundsIn(host, button)
                        val rowBounds = boundsIn(host, row)
                        val x = buttonBounds.exactCenterX()
                        val stripYs = listOf(rowBounds.top + .5f, rowBounds.bottom - .5f)
                        assertTrue(stripYs.first() < buttonBounds.top)
                        assertTrue(stripYs.last() >= buttonBounds.bottom)
                        for (y in stripYs) {
                            assertTrue(x >= 0 && x < host.width && y >= 0 && y < host.height)
                            calls.clear(); editorEvents.clear()
                            assertTrue(rootTap(host, x, y))
                            // With the unwrapped 40dp button these root-dispatched taps reach the
                            // field's editor delegate instead, despite direct performClick passing.
                            assertEquals("Reveal strip must not reach the editor", emptyList<Int>(), editorEvents)
                            assertEquals(listOf(reveal), calls)
                        }
                        assertSame(retainedText, field.editor.text)
                        assertTrue(field.editor.hasFocus())
                        assertEquals(2, field.editor.selectionStart)
                        assertEquals(7, field.editor.selectionEnd)
                        // Additional coverage absent from the original method: every action's
                        // left/right margins, and COPY/REROLL top/bottom margins, through root dispatch.
                        for ((slot, expected) in (0 until row.childCount).map {
                            row.getChildAt(it) as ReverbStandardIconButtonView
                        }.zip(listOf(reveal, copy, reroll))) {
                            val bounds = boundsIn(host, slot)
                            assertEquals(px(40), slot.visual.width)
                            assertEquals(px(40), slot.visual.height)
                            assertEquals(px(24), slot.visual.glyph.width)
                            assertEquals(px(24), slot.visual.glyph.height)
                            val points = mutableListOf(bounds.left + .5f to bounds.exactCenterY(),
                                bounds.right - .5f to bounds.exactCenterY())
                            if (expected != reveal) {
                                points += bounds.exactCenterX() to bounds.top + .5f
                                points += bounds.exactCenterX() to bounds.bottom - .5f
                            }
                            for ((edgeX, edgeY) in points) {
                                calls.clear(); editorEvents.clear()
                                assertTrue(rootTap(host, edgeX, edgeY))
                                assertEquals("Only the intended edge action dispatches", listOf(expected), calls)
                                assertTrue("No new editor pointer event", editorEvents.isEmpty())
                                assertSame(retainedText, field.editor.text)
                                assertTrue(field.editor.hasFocus())
                                assertEquals(2, field.editor.selectionStart)
                                assertEquals(7, field.editor.selectionEnd)
                            }
                            // Parent-delivered cancellation retires a band press. This is not a
                            // claim of physical ScrollView interception or gesture-navigation coverage.
                            calls.clear(); editorEvents.clear()
                            val down = SystemClock.uptimeMillis()
                            val edgeX = bounds.left + .5f; val edgeY = bounds.exactCenterY()
                            assertTrue(dispatchFinger(host, MotionEvent.ACTION_DOWN, edgeX, edgeY, down))
                            assertTrue(dispatchFinger(host, MotionEvent.ACTION_CANCEL, edgeX, edgeY, down))
                            assertTrue(calls.isEmpty())
                            assertTrue(editorEvents.isEmpty())
                            assertSame(retainedText, field.editor.text)
                            assertTrue(field.editor.hasFocus())
                            assertEquals(2, field.editor.selectionStart)
                            assertEquals(7, field.editor.selectionEnd)
                        }
                        val target = button.parent as ReverbStandardIconButtonView
                        assertEquals(px(48), target.height)
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, target.importantForAccessibility)
                        assertRevealAccessibilityChild(target, button)
                        assertEquals("Show", button.createAccessibilityNodeInfo()?.contentDescription?.toString())
                        val icons = descendants(row).filterIsInstance<ReverbStandardIconButtonView>().drop(1).toList()
                        for ((icon, expected) in icons.zip(listOf(copy, reroll))) {
                            calls.clear(); editorEvents.clear()
                            val bounds = boundsIn(host, icon.visual)
                            assertTrue(rootTap(host, bounds.exactCenterX(), bounds.exactCenterY()))
                            assertEquals(listOf(expected), calls)
                            assertTrue(editorEvents.isEmpty())
                        }
                    }
                }
            }
            main {
                requireNotNull(group).bind(listOf(state), admitted.map {
                    CredentialActionState(it.target, it.label, enabled = it.target != reveal)
                })
            }
            awaitDraw(host)
            main {
                val bounds = boundsIn(host, button.parent as ReverbStandardIconButtonView)
                calls.clear(); editorEvents.clear()
                assertTrue(rootTap(host, bounds.exactCenterX(), bounds.top + .5f))
                assertTrue("Disabled Reveal consumes its target without invoking an action", calls.isEmpty())
                assertTrue("Disabled Reveal must not fall through to the editor", editorEvents.isEmpty())
                requireNotNull(group).bind(listOf(state), admitted)
            }
            awaitDraw(host)
            lateinit var target: ReverbStandardIconButtonView
            var downTime = 0L
            var pendingX = 0f
            var pendingY = 0f
            main {
                target = button.parent as ReverbStandardIconButtonView
                val bounds = boundsIn(host, target)
                pendingX = bounds.exactCenterX(); pendingY = bounds.top + .5f
                downTime = SystemClock.uptimeMillis()
                calls.clear(); editorEvents.clear()
                assertTrue(dispatchFinger(host, MotionEvent.ACTION_DOWN, pendingX, pendingY, downTime))
                requireNotNull(group).bind(listOf(state), listOf(CredentialActionState(copy, "Copy")))
            }
            awaitDraw(host)
            main {
                dispatchFinger(host, MotionEvent.ACTION_UP, pendingX, pendingY, downTime)
                assertTrue("Hiding Reveal retires the pending gesture", calls.isEmpty())
                assertEquals(View.GONE, target.visibility)
                assertFalse(button.isShown)
                assertEquals(px(48), row.width)
                assertSame(row, field.trailingHost.getChildAt(0))
                assertEquals(1, (0 until row.childCount).count { row.getChildAt(it).visibility == View.VISIBLE })
                requireNotNull(group).bind(listOf(state))
            }
            awaitDraw(host)
            main {
                assertEquals(View.GONE, field.trailingHost.visibility)
                assertEquals(0, field.trailingHost.childCount)
                assertFalse(target.isShown)
                calls.clear(); editorEvents.clear()
                val bounds = boundsIn(host, field.editor)
                assertTrue(rootTap(host, bounds.exactCenterX(), bounds.exactCenterY()))
                assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), editorEvents)
                assertTrue("Hidden actions leave the editor usable", calls.isEmpty())
                requireNotNull(group).bind(listOf(CredentialFieldState(Field.CONNECTOR, state.value,
                    enabled = false, revealed = true)), listOf(CredentialActionState(reveal, "Hide")))
            }
            awaitDraw(host)
            main {
                assertSame(row, field.trailingHost.getChildAt(0))
                assertSame(target, button.parent)
                assertTrue(target.isShown)
                assertFalse(field.editor.isEnabled)
                assertNull(field.editor.transformationMethod)
                assertEquals("Hide", target.visual.glyph.contentDescription)
                assertGlyph(target, ReverbIcon.EyeOff)
                calls.clear(); editorEvents.clear()
                val bounds = boundsIn(host, target)
                assertTrue(rootTap(host, bounds.exactCenterX(), bounds.top + .5f))
                assertEquals(listOf(reveal), calls)
                assertTrue(editorEvents.isEmpty())
                assertTrue(edits.isEmpty())
            }
        } finally {
            main { try { group?.close() } finally { activity.finish() } }
        }
    }

    private fun assertRevealAccessibilityChild(target: ReverbStandardIconButtonView, button: View) {
        assertTrue(button.filterTouchesWhenObscured)
        if (Build.VERSION.SDK_INT >= 34) {
            // Android infers sensitive accessibility data from obscured-touch protection. A direct
            // in-process query is not an accessibility-tool request, so the platform filters this
            // child before the standalone delegate is reached. Verify that protection first.
            assertTrue(button.isAccessibilityDataSensitive)
            assertEquals(emptyList<View>(), ArrayList<View>().also { target.addChildrenForAccessibility(it) })
            // Query only this synthetic action's structure, never the credential editor or window.
            // No global service/fetch flags, obscured-touch flags, geometry or input policy change.
            button.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_NO)
        }
        try {
            val actions = ArrayList<View>().also { target.addChildrenForAccessibility(it) }
            assertEquals(listOf(button), actions)
        } finally {
            if (Build.VERSION.SDK_INT >= 34) {
                button.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_AUTO)
                assertTrue(button.isAccessibilityDataSensitive)
            }
        }
        assertTrue(button.filterTouchesWhenObscured)
    }

    private fun boundsIn(root: ViewGroup, child: View) = Rect(0, 0, child.width, child.height).also {
        root.offsetDescendantRectToMyCoords(child, it)
    }

    private fun rootTap(root: View, x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = dispatchFinger(root, MotionEvent.ACTION_DOWN, x, y, downTime)
        val up = dispatchFinger(root, MotionEvent.ACTION_UP, x, y, downTime)
        return down && up
    }

    private fun dispatchFinger(root: View, action: Int, x: Float, y: Float, downTime: Long): Boolean {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private class AdmissionHost(context: Context) : FrameLayout(context), NativeInputGate {
        var admitted = true
        var admissionChecks = 0
        var onAdmission: (() -> Unit)? = null
        override val acceptsNativeInput: Boolean
            get() {
                admissionChecks++
                onAdmission?.invoke()
                return admitted
            }
    }

    private fun assertGlyph(button: ReverbStandardIconButtonView, icon: ReverbIcon) {
        val actual = button.visual.glyph
        assertTrue(actual.width > 0 && actual.height > 0)
        val expected = ReverbGlyphView(button.context, icon, Color.BLACK)
        expected.measure(View.MeasureSpec.makeMeasureSpec(actual.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(actual.height, View.MeasureSpec.EXACTLY))
        expected.layout(0, 0, actual.width, actual.height)
        fun coverage(view: View): IntArray {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val pixels = IntArray(view.width * view.height)
                bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
                return IntArray(pixels.size) { if (Color.alpha(pixels[it]) == 0) 0 else 1 }
            } finally { bitmap.recycle() }
        }
        assertArrayEquals(coverage(expected), coverage(actual))
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
