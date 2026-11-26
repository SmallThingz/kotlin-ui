package smallthingz.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(AndroidJUnit4::class)
class NativeAnchoredMenuPanelIntegrationTest {
    @Test fun arbitraryChildKeepsItsInputAndMatchesTheExistingPopupSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var anchor: View
        var window: PopupWindow? = null
        lateinit var panel: NativeAnchoredMenuPanel
        lateinit var child: View
        var legacy: NativePopupMenu<Int>? = null
        var runtime: NativeModalWindowRuntime? = null
        val density = Density(1f)
        val palette = NativeColorScheme.fallback(false)
        var clicks = 0
        var keys = 0
        var hovers = 0
        var retired = 0
        var zeroScaleRetired = 0
        var stale = 0
        var scale = 0f
        val naturallyCollapsed = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                scale = readCachedAnimatorDurationScale(activity)
                assertTrue("Animator duration scale $scale is outside the bounded fixture range", scale in 0f..10f)
                val root = FrameLayout(activity)
                anchor = View(activity)
                root.addView(anchor, FrameLayout.LayoutParams(68, 42).apply { topMargin = 60 })
                activity.setContentView(root)
            }
            awaitLayout(anchor)
            instrumentation.runOnMainSync {
                child = View(activity).apply {
                    isFocusableInTouchMode = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = "Audio details"
                    setOnClickListener { clicks++ }
                    setOnKeyListener { _, code, _ ->
                        (code == KeyEvent.KEYCODE_ENTER).also { if (it) keys++ }
                    }
                    setOnHoverListener { _, event ->
                        (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER).also { if (it) hovers++ }
                    }
                }
                panel = NativeUiAppearance.Default.modals.anchoredPanel.createPanel(child, density, palette)
                panel.setAnimationPivot(.25f, 0f)
                val popup = PopupWindow(panel, 180, 108, true).apply { isOutsideTouchable = true }
                window = popup
                panel.applyWindowStyle(popup)
                assertEquals(8f, popup.elevation, 0f)
                assertTrue(popup.isFocusable)
                assertTrue(popup.isOutsideTouchable)
                panel.expanded(true)
                popup.showAsDropDown(anchor)
            }
            awaitLayout(panel)
            awaitExpanded(panel, scale)
            instrumentation.runOnMainSync {
                assertEquals(1f, panel.alpha, 0f)
                assertEquals(45f, panel.pivotX, 0f)
                assertEquals(180, panel.width)
                assertEquals(108, panel.height)
                assertSame(child, surfaceBody(panel))
                assertEquals("Audio details", child.createAccessibilityNodeInfo().contentDescription.toString())
                assertTrue(child.requestFocus())
                assertSame(child, panel.findFocus())
                assertTrue(panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                assertEquals(1, keys)
                val hover = event(panel, MotionEvent.ACTION_HOVER_ENTER)
                try {
                    hover.source = InputDevice.SOURCE_MOUSE
                    assertTrue(panel.dispatchGenericMotionEvent(hover))
                    assertEquals(1, hovers)
                } finally { hover.recycle() }
                tap(panel)
            }
            instrumentation.runOnMainSync {
                assertEquals(1, clicks)
                assertDefaultSurface(panel, palette)

                panel.expanded(false) { stale++ }
                panel.expanded(true)
                panel.animateFrame(System.nanoTime(), durationScale = 0f)
                assertEquals(0, stale)
                assertEquals(1f, panel.alpha, 0f)

                val popup = requireNotNull(window)
                panel.expanded(false) { zeroScaleRetired++ }
                panel.animateFrame(System.nanoTime(), durationScale = 0f)
                assertEquals(1, zeroScaleRetired)
                assertEquals(0f, panel.alpha, 0f)
                assertTrue(popup.isShowing)

                panel.expanded(true)
                panel.animateFrame(System.nanoTime(), durationScale = 0f)
                panel.expanded(false) {
                    retired++
                    popup.dismiss()
                    naturallyCollapsed.countDown()
                }
            }
            assertTrue("The popup did not finish a natural collapse at scale $scale",
                naturallyCollapsed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val popup = requireNotNull(window)
                assertEquals(1, retired)
                assertFalse(popup.isShowing)

                panel.expanded(true)
                popup.showAsDropDown(anchor)
            }
            awaitLayout(panel)
            instrumentation.runOnMainSync {
                panel.animateFrame(System.nanoTime(), durationScale = 0f)
                assertSame(child, surfaceBody(panel))
                panel.expanded(false) { stale++ }
                val outside = event(panel, MotionEvent.ACTION_OUTSIDE)
                try { assertTrue(panel.rootView.dispatchTouchEvent(outside)) } finally { outside.recycle() }
                assertFalse(requireNotNull(window).isShowing)

                val clock = NativeModalWindowRuntime(activity).also { runtime = it; it.setStarted(true) }
                val menu = NativePopupMenu(anchor, density, NativeTypography.Default, createFontFamilyResolver(activity),
                    palette, NativeVerticalScrollState(), clock.frames, clock.scope, glyphs,
                    onDismissRequest = {}, onTerminal = {})
                legacy = menu
                menu.bindActions(listOf(NativeMenuAction("Audio details", 0) {}))
                menu.show()
            }
            val menu = requireNotNull(legacy)
            awaitLayout(menu)
            instrumentation.runOnMainSync {
                assertEquals(8f, menu.elevation, 0f)
                assertDefaultSurface(menu.getChildAt(0) as NativeAnchoredMenuPanel, palette)
                assertEquals(0, stale)
            }
        } finally {
            instrumentation.runOnMainSync {
                window?.dismiss()
                legacy?.close()
                runtime?.close()
                activity.finish()
            }
        }
    }

    @Test fun interruptedEntryRetainsAttachedPanelReversesAndRetiresOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var anchor: View
        var window: PopupWindow? = null
        try {
            instrumentation.runOnMainSync {
                anchor = View(activity)
                activity.setContentView(FrameLayout(activity).apply {
                    addView(anchor, FrameLayout.LayoutParams(68, 42))
                })
            }
            awaitLayout(anchor)
            for (exitScale in listOf(1f, 0f)) {
                lateinit var panel: NativeAnchoredMenuPanel
                var retired = 0
                var stale = 0
                instrumentation.runOnMainSync {
                    panel = NativeAnchoredMenuRecipe.Default.createPanel(View(activity), Density(1f),
                        NativeColorScheme.fallback(false))
                    window = PopupWindow(panel, 180, 108, true).also {
                        panel.applyWindowStyle(it)
                        it.showAsDropDown(anchor)
                    }
                }
                awaitLayout(panel)
                // Synthetic timestamps in one main-thread turn prevent real Choreographer frames
                // interleaving with the retained-pose/callback assertions. This is not timing QA.
                instrumentation.runOnMainSync {
                    val popup = requireNotNull(window)
                    panel.expanded(true)
                    panel.animateFrame(0L, 1f)
                    panel.animateFrame(40_000_000L, 1f)
                    val alpha = panel.alpha
                    val scale = panel.scaleX
                    assertTrue(alpha > 0f && alpha < 1f)
                    panel.expanded(false) { stale++ }
                    assertTrue(popup.isShowing)
                    assertEquals(0, stale)
                    assertEquals(alpha, panel.alpha, 0f)
                    assertEquals(scale, panel.scaleX, 0f)
                    panel.animateFrame(56_000_000L, 1f)
                    assertTrue(panel.motionShouldExist)
                    panel.expanded(true) // Revokes the old terminal callback without replacing the window.
                    panel.animateFrame(72_000_000L, 1f)
                    assertEquals(0, stale)
                    panel.expanded(false) { retired++; popup.dismiss() }
                    assertEquals(0, retired)
                    if (exitScale == 0f) panel.animateFrame(72_000_000L, 0f)
                    else {
                        panel.animateFrame(88_000_000L, 1f)
                        assertEquals(0, retired)
                        assertTrue(popup.isShowing)
                        var time = 104_000_000L
                        repeat(320) {
                            if (retired == 0) { panel.animateFrame(time, 1f); time += 16_000_000L }
                        }
                    }
                    assertEquals(1, retired)
                    assertEquals(0, stale)
                    assertFalse(popup.isShowing)
                    panel.animateFrame(10_000_000_000L, 0f)
                    assertEquals(1, retired)
                    assertFalse(panel.motionShouldExist)
                }
                // Teardown is immediate and discards a pending animated-close callback.
                instrumentation.runOnMainSync { requireNotNull(window).showAsDropDown(anchor) }
                awaitLayout(panel)
                instrumentation.runOnMainSync {
                    panel.expanded(true)
                    panel.animateFrame(0L, 1f)
                    panel.animateFrame(40_000_000L, 1f)
                    panel.expanded(false) { stale++ }
                    requireNotNull(window).dismiss()
                    panel.animateFrame(56_000_000L, 0f)
                    assertEquals(0, stale)
                    assertEquals(1, retired)
                    assertFalse(panel.motionShouldExist)
                }
            }
        } finally {
            instrumentation.runOnMainSync { window?.dismiss(); activity.finish() }
        }
    }

    private fun surfaceBody(panel: NativeAnchoredMenuPanel): View =
        ((panel.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0)

    private fun assertDefaultSurface(panel: NativeAnchoredMenuPanel, palette: NativeColorScheme) {
        val surface = panel.getChildAt(0) as ViewGroup
        val outline = Outline().also { surface.outlineProvider.getOutline(surface, it) }
        assertEquals(18f, outline.radius, 0f)
        assertEquals(3f, surface.elevation, 0f)
        assertEquals(8, surfaceBody(panel).top)
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        try {
            surface.draw(Canvas(bitmap))
            val fill = NativeAnchoredMenuRecipe.Default.surfaceColor(palette)
            assertEquals(fill, bitmap.getPixel(surface.width / 2, 1))
            assertEquals(0, bitmap.getPixel(0, 0))
        } finally {
            bitmap.recycle()
        }
    }

    private fun tap(panel: NativeAnchoredMenuPanel) {
        val downTime = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = event(panel, action, downTime)
            try { assertTrue(panel.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    private fun event(panel: NativeAnchoredMenuPanel, action: Int, downTime: Long = SystemClock.uptimeMillis()) =
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, panel.width / 2f, panel.height / 2f, 0)

    private fun awaitExpanded(panel: NativeAnchoredMenuPanel, scale: Float) {
        val ready = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (panel.alpha == 1f && panel.scaleX == 1f) ready.countDown()
            true
        }
        instrumentation.runOnMainSync {
            if (panel.alpha == 1f && panel.scaleX == 1f) ready.countDown()
            else panel.viewTreeObserver.addOnPreDrawListener(listener)
        }
        try {
            assertTrue("The popup did not finish a natural expansion at scale $scale", ready.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { panel.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
    }

    private fun awaitLayout(view: View) {
        val ready = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (view.isAttachedToWindow && view.width > 0 && view.height > 0) ready.countDown()
            else view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                    oldL: Int, oldT: Int, oldR: Int, oldB: Int,
                ) {
                    if (r <= l || b <= t) return
                    v.removeOnLayoutChangeListener(this)
                    ready.countDown()
                }
            })
        }
        assertTrue("The popup view did not lay out", ready.await(5, TimeUnit.SECONDS))
    }

    private val glyphs = object : NativeMenuIcons<Int> {
        override val check = 0
        override fun create(context: android.content.Context, icon: Int, color: Int) = View(context)
        override fun bind(view: View, icon: Int, color: Int) = Unit
    }
}
