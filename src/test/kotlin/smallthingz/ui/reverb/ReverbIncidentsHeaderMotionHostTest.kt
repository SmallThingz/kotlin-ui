package smallthingz.ui.reverb

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.components.NativePageTransition
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope

/** Retained real header actions, including the source-bound path used by an owning Activity. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi",
    instrumentedPackages = ["smallthingz.ui.animation.view"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbIncidentsHeaderMotionHostTest {
    private var previousFramePaused = false
    private lateinit var previousFrameDelay: Duration

    @Before fun useControlledFrames() {
        previousFramePaused = ShadowChoreographer.isPaused()
        previousFrameDelay = ShadowChoreographer.getFrameDelay()
        // Keep retarget's instrumented nanoTime and vsync on the same clock, with finite steps.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After fun restoreFramePolicy() {
        ShadowChoreographer.setFrameDelay(previousFrameDelay)
        ShadowChoreographer.setPaused(previousFramePaused)
    }

    @Test fun standaloneHeaderDefersNonBackEffectsUntilMotionSettles() = verifyAdmission(false)
    @Test fun sourceBoundHeaderDefersNonBackEffectsUntilMotionSettles() = verifyAdmission(true)

    @Test fun occurrenceEffectsWaitForMotionButBackRemainsAvailable() =
        verifyAdmission(sourceBound = true, occurrences = true)

    private fun verifyAdmission(sourceBound: Boolean, occurrences: Boolean = false) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = activity.get()
        val host = NativePageTransition.InputRoot(context)
        val background = FrameLayout(context)
        host.addView(background, FrameLayout.LayoutParams(-1, -1))
        context.setContentView(host)
        // Paused vsync defers root attachment until the first scheduled traversal.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        assertTrue("Real incident host must attach before window focus", host.isAttachedToWindow)
        activity.windowFocusChanged(true)
        // SDK35 focus is a queued ViewRoot message; let the next traversal release it.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        assertTrue("Real incident window must own focus", host.hasWindowFocus())
        val oldScale = ValueAnimator.getDurationScale()
        setScale(1f)
        try {
            NativeModalWindowRuntime(context).use { runtime ->
                val panels = NativePageTransition.Panels(host)
                var lease: AutoCloseable? = null
                var backs = 0
                val deleted = mutableListOf<List<ReverbIncidentTarget>>()
                val acknowledged = mutableListOf<ReverbIncidentTarget>()
                val rowDeletes = mutableListOf<ReverbIncidentTarget>()
                val history = history()
                val modes = mutableListOf<Boolean>()
                val panel = ReverbIncidentsContentView(context, ReverbDesign.theme(context, false),
                    texts(), ReverbIncidentActions(
                        { if (occurrences) acknowledged += it else fail("No row acknowledgement expected") },
                        { if (occurrences) rowDeletes += it else fail("No row deletion expected") },
                        { deleted += it },
                        { fail("No copy expected") }),
                    {
                        backs++
                        panels.cancelPreview(onFinished = { lease?.close(); lease = null })
                    }, runtime.frames, runtime.scope, { 0f }, host)
                host.addView(panel, FrameLayout.LayoutParams(-1, -1))
                var headerLease: AutoCloseable? = null
                val observer = panel.observeSelectionMode { modes += it }
                try {
                    panel.bind(history)
                    panel.pointerList?.itemAnimator = null
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                    fun place() {
                        host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                        host.layout(0, 0, 360, 600)
                    }
                    place()
                    if (sourceBound) headerLease = panel.bindHeaderAccessibility(
                        PlainTextAccessibilityWindowScope(host),
                        NativePlainTextViewGeometry(host, clips = panel::semanticsClip),
                        { NativeSourceAccessibilityNode.fromView(it) })
                    val header = panel.getChildAt(0) as ViewGroup
                    val bar = header.getChildAt(0) as ReverbSelectionTopBarLayout
                    val back = bar.getChildAt(0) as ReverbToolbarActionView
                    val title = bar.getChildAt(1) as ParagraphView
                    val group = bar.getChildAt(2) as NativeGroupLayout
                    val destructive = group.pointerContent.getChildAt(0) as ReverbToolbarActionView
                    val select = group.pointerContent.getChildAt(1) as ReverbToolbarActionView
                    fun click(action: ReverbToolbarActionView) {
                        val view = action.accessibilityAction
                        assertTrue("Retained action remains visible", view.getGlobalVisibleRect(Rect()))
                        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                        place()
                    }
                    fun preview() {
                        lease = checkNotNull(NativePageTransition.InputRoot.blockFrom(panel))
                        assertTrue(panels.preview(background, panel, NativePageTransition.Direction.BACK))
                        panels.seekPreview(.25f)
                        assertTrue("Exercise a partly presented page", panel.translationY > 0f)
                    }
                    fun settleCancellation() {
                        val animator: ValueAnimator = ReflectionHelpers.getField(panels, "animator")
                        animator.end()
                        assertNull(lease)
                    }
                    fun clearSheet(): Any? = ReflectionHelpers.getField(panel, "clearSheet")

                    if (occurrences) {
                        val occurrence = history.rows.single().target
                        val card = descendants(panel).filterIsInstance<ReverbIncidentCardView>().single()
                        val body: View = ReflectionHelpers.getField(card, "body")
                        val check: View = ReflectionHelpers.getField(body, "checkTarget")
                        val heading = descendants(panel).filterIsInstance<ReverbIncidentGroupHeading>().single()
                        assertTrue(check.getGlobalVisibleRect(Rect()))
                        assertTrue(check.performClick())
                        assertEquals(listOf(occurrence), acknowledged)

                        assertTrue(body.performLongClick())
                        val binding: Any = ReflectionHelpers.getField(card, "binding")
                        val identity: IncidentCardMenuIdentity = ReflectionHelpers.getField(binding, "menuIdentity")
                        // Keep the real row callback created for the already-open popup. The popup
                        // has its own window, so a Main-only View admission check cannot gate it.
                        val menuRequest = ReverbIncidentCardView::class.java.getDeclaredMethod("menuRequest",
                            IncidentCardMenuIdentity::class.java, smallthingz.ui.components.NativeMenuOrigin::class.java)
                            .apply { isAccessible = true }
                            .invoke(card, identity, null) as IncidentCardMenuRequest
                        assertTrue(menuRequest.canDelete)
                        preview()
                        menuRequest.delete()
                        assertTrue("Already-open Delete must recheck admission", rowDeletes.isEmpty())
                        assertTrue(check.getGlobalVisibleRect(Rect()))
                        check.performClick()
                        assertEquals(listOf(occurrence), acknowledged)
                        assertFalse(body.performLongClick())
                        assertFalse(heading.performLongClick())
                        assertFalse(panel.selectionActive)
                        click(back)
                        assertEquals(1, backs)
                        assertNotNull(lease)
                        check.performClick()
                        assertEquals(listOf(occurrence), acknowledged)
                        settleCancellation()

                        assertTrue(check.performClick())
                        assertEquals(listOf(occurrence, occurrence), acknowledged)
                        menuRequest.delete()
                        assertEquals(listOf(occurrence), rowDeletes)
                        assertTrue(heading.performLongClick())
                        assertTrue(panel.selectionActive)
                        return
                    }

                    preview()
                    click(select)
                    click(destructive)
                    assertEquals(listOf(false), modes)
                    assertFalse(panel.selectionActive)
                    assertNull(clearSheet())
                    assertTrue(deleted.isEmpty())
                    click(back) // Back still reverses the live preview, without bypassing settlement.
                    assertEquals(1, backs)
                    assertNotNull(lease)
                    click(select)
                    click(destructive)
                    assertFalse(panel.selectionActive)
                    assertNull(clearSheet())
                    settleCancellation()

                    click(select)
                    assertTrue(panel.selectionActive)
                    click(select)
                    assertEquals("1 selected", title.getText())
                    preview()
                    click(destructive)
                    click(select) // Must not silently deselect the existing selection.
                    assertEquals("1 selected", title.getText())
                    assertTrue(deleted.isEmpty())
                    click(back) // Clearing selection remains available while the page is moving.
                    assertFalse(panel.selectionActive)
                    assertEquals(1, backs)
                    assertNotNull(lease)
                    click(select)
                    click(destructive)
                    assertFalse(panel.selectionActive)
                    assertNull(clearSheet())
                    click(back)
                    assertEquals(2, backs)
                    settleCancellation()

                    click(select)
                    click(select)
                    click(destructive)
                    assertEquals(listOf(listOf(history.rows.single().target)), deleted)
                    assertFalse(panel.selectionActive)
                    assertEquals(listOf(false, true, false, true, false), modes)
                    click(destructive) // Ordinary settled Clear opens its existing confirmation only.
                    assertNotNull(clearSheet())
                    assertEquals(1, deleted.size) // Never confirm a clear or touch a backend.
                } finally {
                    panels.close()
                    lease?.close()
                    observer.close()
                    headerLease?.close()
                    panel.dispose()
                    host.removeView(panel)
                }
            }
        } finally {
            activity.pause().stop().destroy()
            setScale(oldScale)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun setScale(value: Float) = ValueAnimator::class.java
        .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(null, value)

    private fun texts() = ReverbIncidentTexts("Incidents", "Back", "Clear selection", { "$it selected" },
        "Multi-select", "Select all", "Deselect all", "Delete selected", "Clear all", "Clear incidents?",
        "Cancel", "No incidents", "Nothing to show", "Loading", "Unavailable", "Stale", "Select",
        "Deselect", "Mark checked", "Mark unchecked", "Options", "Copy", "Delete")

    private fun history(): ReverbIncidentHistory {
        val row = object : ReverbIncidentOccurrence, ReverbIncidentTarget {
            override val key = "fixture-row"
            override val target: ReverbIncidentTarget get() = this
            override val acknowledged = false
            override val timeLabel = "10:00"
            override val processLabel = "PID 42"
            override val liveEnd: ReverbIncidentLiveEnd? = null
            override fun matches(reference: ReverbIncidentTarget) = reference === this
        }
        return ReverbIncidentHistory(listOf(ReverbIncidentDay(1, "Today",
            listOf(ReverbIncidentGroup("fixture-topic", "Error", listOf(row))))),
            ReverbIncidentLoadStatus.READY, listOf(row))
    }
}
