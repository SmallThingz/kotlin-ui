package smallthingz.ui.gallery

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeToggleRow
import smallthingz.ui.components.withRetiringTestActivity
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class GalleryVideoAudioToggleIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val light = NativeColorScheme.fallback(darkTheme = false)

    @Test fun naturalWidthFitsBesideOtherActionsAndDefaultRowStillFillsTheWidth() = main {
        val context = fontContext(1f)
        val actions = GalleryActionRow(context, gapDp = 4f, verticalGapDp = 0f)
        val rotate = addRotate(actions)
        GalleryVideoAudioToggle(context, light) {}.use { mute ->
            mute.bind("Mute audio", checked = false, enabled = true)
            actions.addView(mute.view)
            val width = actions.dp(320)
            layout(actions, width)
            val row = mute.view as NativeToggleRow
            val label = row.getChildAt(0)
            val renderer = renderer(row)
            assertEquals(actions.dp(48), row.height)
            assertEquals(rotate.top, row.top)
            assertEquals(rotate.right + actions.dp(4), row.left)
            assertTrue(row.right < width)
            assertEquals(actions.dp(12), label.left)
            assertEquals(actions.dp(12), renderer.left - label.right)
            assertEquals(actions.dp(12), row.width - renderer.right)
            val naturalWidth = row.width
            layout(row, 0, MeasureSpec.UNSPECIFIED)
            assertEquals(naturalWidth, row.width)

            val defaultTitle = TextView(context).apply { text = "Default" }
            val defaultSwitch = NativeMaterialSwitchView(context, light)
            NativeToggleRow(context, defaultTitle, null, defaultSwitch, onChanged = {}).use { defaultRow ->
                layout(defaultRow, width, MeasureSpec.AT_MOST)
                assertEquals(width, defaultRow.width)
                assertEquals(actions.dp(64), defaultRow.height)
                assertEquals(actions.dp(14), defaultTitle.left)
                assertEquals(actions.dp(14), defaultRow.width - defaultSwitch.right)
            }
        }
    }

    @Test fun narrowActionWrapsWithLargeTextAndMirrorsItsLogicalInsets() = main {
        val context = fontContext(2f)
        val actions = GalleryActionRow(context, gapDp = 4f, verticalGapDp = 0f)
        val rotate = addRotate(actions)
        GalleryVideoAudioToggle(context, light) {}.use { mute ->
            mute.bind("Audio status unknown", checked = false, enabled = false)
            actions.addView(mute.view)
            layout(actions, actions.dp(152))
            val row = mute.view as NativeToggleRow
            val label = row.getChildAt(0) as TextView
            val renderer = renderer(row)
            assertTrue(row.top >= rotate.bottom)
            assertTrue(row.right <= actions.width)
            assertTrue(row.height > actions.dp(48))
            assertTrue(label.lineCount > 1)
            assertEquals(label.height + 2 * actions.dp(4), row.height)
            assertEquals(actions.dp(4), label.top)
            assertEquals(actions.dp(52), renderer.width)
            assertEquals(actions.dp(12), row.width - renderer.right)

            row.layoutDirection = View.LAYOUT_DIRECTION_RTL
            layout(actions, actions.dp(152))
            assertEquals(actions.dp(12), renderer.left)
            assertEquals(actions.dp(12), row.width - label.right)
            assertTrue(renderer.right < label.left)

            layout(actions, actions.dp(96))
            assertTrue(label.width > 0)
            assertEquals(actions.dp(52), renderer.width)
            assertTrue(label.left - renderer.right in 1 until actions.dp(12))
        }
    }

    @Test fun attachedRowRequestsOneChangePerActionAndRejectsStaleInput() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var ownedActions: GalleryActionRow? = null
        var owner: GalleryVideoAudioToggle? = null
        val requests = mutableListOf<Boolean>()
        withRetiringTestActivity(activity, root = { ownedActions }, cleanup = { owner?.close() }) {
            main {
                val mute = GalleryVideoAudioToggle(activity, light) { requests.add(it) }.also { owner = it }
                mute.bind("Mute audio", checked = true, enabled = true)
                assertTrue(requests.isEmpty())
                assertFalse(mute.view.performClick())
                val actions = GalleryActionRow(activity).also { ownedActions = it }
                actions.addView(mute.view)
                activity.setContentView(actions)
            }
            val actions = checkNotNull(ownedActions)
            val mute = checkNotNull(owner)
            val view = mute.view
            val deadline = SystemClock.uptimeMillis() + 5_000L
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                main {
                    ready = !activity.isFinishing && !activity.isDestroyed &&
                        actions.rootView === activity.window.peekDecorView() && view.parent === actions &&
                        view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
                        view.isLaidOut && !actions.isLayoutRequested && !view.isLayoutRequested &&
                        view.width > 0 && view.height > 0 && view.hasWindowFocus()
                }
                if (!ready) SystemClock.sleep(16)
            }
            assertTrue("Owned Gallery action did not lay out and gain window focus", ready)
            main {
                assertTrue("Owned Gallery window lost focus before input assertions", view.hasWindowFocus())
                assertNode(view, "Mute audio", checked = true, enabled = true)
                val down = SystemClock.uptimeMillis()
                assertTrue(touch(view, down, MotionEvent.ACTION_DOWN))
                assertTrue(touch(view, down, MotionEvent.ACTION_UP))
                assertEquals(listOf(false), requests)
                assertNode(view, "Mute audio", checked = true, enabled = true)

                mute.bind("Mute audio", checked = false, enabled = true)
                assertTrue(view.requestFocusFromTouch())
                assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)))
                assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)))
                assertEquals(listOf(false, true), requests)
                assertNode(view, "Mute audio", checked = false, enabled = true)
                assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(listOf(false, true, true), requests)

                mute.bind("Audio status unknown", checked = true, enabled = false)
                assertNode(view, "Audio status unknown", checked = true, enabled = false)
                assertFalse(view.performClick())
                assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                view.visibility = View.GONE
                mute.bind("Mute audio", checked = false, enabled = true)
                assertFalse(view.performClick())
                assertNull(view.createAccessibilityNodeInfo())
                view.visibility = View.VISIBLE

                actions.removeView(view)
                assertFalse(view.isAttachedToWindow)
                assertFalse(view.performClick())
                actions.addView(view)
                layout(actions, actions.width)
                assertSame(view, mute.view)
                assertNode(view, "Mute audio", checked = false, enabled = true)
                val staleDown = SystemClock.uptimeMillis()
                assertTrue(touch(view, staleDown, MotionEvent.ACTION_DOWN))
                mute.close()
                mute.close()
                mute.bind("Late bind", checked = true, enabled = true)
                touch(view, staleDown, MotionEvent.ACTION_UP)
                assertFalse(view.isEnabled)
                assertFalse(view.performClick())
                assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertNull(view.createAccessibilityNodeInfo())
                assertEquals(listOf(false, true, true), requests)
            }
        }
    }

    @Test fun switchPaintAndLabelFollowLightDarkAndCustomPreset() {
        NoiseTexture.prepare()
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(10)
        }
        checkNotNull(NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND)) { "Shared noise texture did not become ready" }

        main { assertThemePaint() }
    }

    private fun assertThemePaint() {
        val context = fontContext(1f)
        val dark = NativeColorScheme.fallback(darkTheme = true)
        val preset = NativeDarkSurfacePreset.Amoled.copy(foregroundGrain = 1f,
            borderWidthDp = 3f, borderLightness = 1f)
        val custom = dark.copy(surfacePreset = preset)
        fun paint(colors: NativeColorScheme, checked: Boolean, enabled: Boolean): IntArray =
            GalleryVideoAudioToggle(context, colors) {}.use { mute ->
                mute.bind("Mute audio", checked, enabled)
                layout(mute.view, 0, MeasureSpec.UNSPECIFIED)
                val row = mute.view as NativeToggleRow
                val label = row.getChildAt(0) as TextView
                assertEquals(if (enabled) colors.ink else colors.muted, label.currentTextColor)
                val actual = pixels(renderer(row))
                val reference = NativeMaterialSwitchView(context, colors, checked, enabled)
                layout(reference, 0, MeasureSpec.UNSPECIFIED)
                assertArrayEquals(pixels(reference), actual)
                actual
            }

        assertFalse(paint(light, checked = true, enabled = true)
            .contentEquals(paint(dark, checked = true, enabled = true)))
        val textured = paint(custom, checked = false, enabled = true)
        val smooth = paint(custom.copy(surfacePreset = preset.copy(foregroundGrain = 0f)),
            checked = false, enabled = true)
        assertFalse(textured.contentEquals(smooth))
        assertFalse(smooth.contentEquals(paint(dark, checked = false, enabled = true)))
        paint(custom, checked = true, enabled = false)
    }

    private fun assertNode(view: View, label: String, checked: Boolean, enabled: Boolean) {
        val node = checkNotNull(view.createAccessibilityNodeInfo())
        try {
            assertTrue(node.isCheckable)
            assertEquals(checked, node.isChecked)
            assertEquals(enabled, node.isEnabled)
            assertEquals(label, node.contentDescription.toString())
            assertEquals(0, node.childCount)
            assertEquals(if (enabled) 1 else 0,
                node.actionList.count { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        } finally { node.recycle() }
    }

    private fun touch(view: View, downTime: Long, action: Int): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            view.width / 2f, view.height / 2f, 0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun fontContext(scale: Float): Context {
        val context = instrumentation.context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = scale })
    }

    private fun addRotate(actions: GalleryActionRow) = View(actions.context).also {
        val size = actions.dp(48)
        actions.addView(it, ViewGroup.MarginLayoutParams(size, size))
    }

    private fun renderer(row: ViewGroup) =
        (0 until row.childCount).map(row::getChildAt).filterIsInstance<NativeMaterialSwitchView>().single()

    private fun layout(view: View, width: Int, mode: Int = MeasureSpec.EXACTLY) {
        view.measure(MeasureSpec.makeMeasureSpec(width, mode), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            IntArray(view.width * view.height).also {
                bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height)
            }
        } finally { bitmap.recycle() }
    }

    private fun View.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
