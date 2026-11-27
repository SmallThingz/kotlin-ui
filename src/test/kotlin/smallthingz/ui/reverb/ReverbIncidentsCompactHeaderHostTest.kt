package smallthingz.ui.reverb

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rAU-w199dp-h421dp-464dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbIncidentsCompactHeaderHostTest {
    @Test fun captionInsetsUseMaximumWithoutReservingNavigationBarSpace() {
        val mixed = WindowInsetsCompat.Builder()
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.captionBar(), Insets.of(0, 107, 0, 0))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 80, 0, 0))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout(), Insets.of(7, 120, 9, 0))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 99))
            .build()
        assertEquals(Insets.of(7, 120, 9, 0), reverbIncidentSafeInsets(mixed))
        assertEquals(Insets.of(0, 107, 0, 0), reverbIncidentSafeInsets(captionOnly()))
    }

    @Test fun emptyHeaderReclaimsActionSpaceAndRealActionsReturnAtCompactWidth() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                densityDpi = 464
                fontScale = .9f
            })
            val host = FrameLayout(context)
            activity.setContentView(host)
            controller.windowFocusChanged(true)
            val theme = ReverbDesign.theme(context, false)
            val style = ReverbDesign.appearance.toolbars
            assertEquals(2.9f, theme.density.density, .01f)
            val slot = (style.action.selectionSizeDp * theme.density.density).roundToInt()
            var backs = 0
            NativeModalWindowRuntime(context).use { runtime ->
                val panel = ReverbIncidentsContentView(context, theme,
                    ReverbIncidentTexts("Incidents", "Back", "Clear selection", { "$it selected" }, "Multi-select",
                        "Select all", "Deselect all", "Delete selected", "Clear all", "Clear incidents?", "Cancel",
                        "No incidents", "Nothing to show", "Loading", "Unavailable", "Stale", "Select", "Deselect",
                        "Mark checked", "Mark unchecked", "Options", "Copy", "Delete"),
                    ReverbIncidentActions({}, {}, {}, {}), { backs++ }, runtime.frames, runtime.scope, { 0f }, host)
                host.addView(panel, FrameLayout.LayoutParams(576, 1220))
                try {
                    val header = panel.getChildAt(0) as ViewGroup
                    val bar = header.getChildAt(0) as ReverbSelectionTopBarLayout
                    val back = bar.getChildAt(0) as ReverbToolbarActionView
                    val title = bar.getChildAt(1) as ParagraphView
                    val group = bar.getChildAt(2) as NativeGroupLayout
                    val destructive = group.pointerContent.getChildAt(0) as ReverbToolbarActionView
                    val selection = group.pointerContent.getChildAt(1) as ReverbToolbarActionView
                    fun place() {
                        host.measure(View.MeasureSpec.makeMeasureSpec(576, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(1220, View.MeasureSpec.EXACTLY))
                        host.layout(0, 0, 576, 1220)
                    }

                    val empty = ReverbIncidentHistory(status = ReverbIncidentLoadStatus.READY)
                    panel.bind(empty)
                    place()
                    val originalBackTop = bar.top + back.top
                    panel.setStatusBarInsets(reverbIncidentSafeInsets(captionOnly()))
                    place()
                    assertEquals(originalBackTop + 107, bar.top + back.top)
                    assertEquals(107 + style.compactHeightPx(theme.density.density), header.height)
                    assertEquals(View.GONE, group.visibility)
                    val emptyTitleWidth = title.width
                    assertTrue(emptyTitleWidth >= title.maxIntrinsicTextWidth())
                    assertFalse(checkNotNull(title.currentParagraph).isLineEllipsized(0))
                    assertTrue(title.left >= back.right)
                    assertNull(destructive.accessibilityAction.createAccessibilityNodeInfo())
                    assertNull(selection.accessibilityAction.createAccessibilityNodeInfo())
                    assertAction(back, "Back", enabled = true)
                    assertTrue(back.accessibilityAction.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertEquals(1, backs)

                    panel.bind(history())
                    panel.pointerList?.itemAnimator = null
                    place()
                    assertEquals(View.VISIBLE, group.visibility)
                    assertEquals(2 * slot, group.width)
                    assertEquals(slot, destructive.width)
                    assertEquals(slot, selection.width)
                    assertEquals(emptyTitleWidth - group.width, title.width)
                    assertTrue(title.left >= back.right && title.right <= group.left)
                    assertTrue(destructive.right <= selection.left)
                    assertAction(destructive, "Clear all", enabled = true)
                    assertAction(selection, "Multi-select", enabled = true)
                    assertTrue(selection.accessibilityAction.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    place()
                    assertTrue(panel.selectionActive)
                    assertEquals("0 selected", title.getText())
                    assertEquals(2 * slot, group.width)
                    assertAction(destructive, "Delete selected", enabled = false)
                    assertAction(selection, "Select all", enabled = true)

                    // The common layout still reserves space for an intentionally invisible group.
                    val visibleWidth = title.width
                    group.visibility = View.INVISIBLE
                    place()
                    assertEquals(visibleWidth, title.width)
                    assertEquals(2 * slot, group.width)

                    panel.bind(empty)
                    place()
                    assertFalse(panel.selectionActive)
                    assertEquals(View.GONE, group.visibility)
                    assertEquals(emptyTitleWidth, title.width)
                    assertEquals("Incidents", title.getText())
                    assertFalse(checkNotNull(title.currentParagraph).isLineEllipsized(0))
                    assertNull(selection.accessibilityAction.createAccessibilityNodeInfo())
                } finally {
                    host.removeView(panel)
                    panel.dispose()
                }
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun captionOnly(): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.captionBar(), Insets.of(0, 107, 0, 0))
        .setVisible(WindowInsetsCompat.Type.captionBar(), false)
        .build()

    @Suppress("DEPRECATION")
    private fun assertAction(action: ReverbToolbarActionView, label: String, enabled: Boolean) {
        val info = checkNotNull(action.accessibilityAction.createAccessibilityNodeInfo())
        try {
            assertEquals(label, info.contentDescription.toString())
            assertEquals(enabled, info.isEnabled)
            assertEquals(enabled, info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        } finally {
            info.recycle()
        }
    }

    private fun history(): ReverbIncidentHistory {
        val row = object : ReverbIncidentOccurrence, ReverbIncidentTarget {
            override val key = "row"
            override val target: ReverbIncidentTarget get() = this
            override val acknowledged = false
            override val timeLabel = "10:00"
            override val processLabel = "PID 42"
            override val liveEnd: ReverbIncidentLiveEnd? = null
            override fun matches(reference: ReverbIncidentTarget) = reference === this
        }
        val day = ReverbIncidentDay(1, "Today", listOf(ReverbIncidentGroup("topic", "Error", listOf(row))))
        return ReverbIncidentHistory(listOf(day), ReverbIncidentLoadStatus.READY, listOf(row))
    }
}
