package smallthingz.ui.scroll

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.geometry.Rect
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.unit.Density
import kotlin.coroutines.CoroutineContext

/** Attached actual focus/reveal dispatch with a deterministic queued dispatcher, not physical input. */
@RunWith(AndroidJUnit4::class)
class NativeQueuedFocusAdmissionTest {
    private class Queue : CoroutineDispatcher() {
        private val pending = java.util.ArrayDeque<Runnable>()
        var observe: (String) -> Unit = {}
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val origin = Throwable().stackTrace.filter {
                it.className == NativeVerticalViewport::class.java.name
            }.joinToString(" <- ") { "${it.methodName}:${it.lineNumber}" }
            observe("enqueue [$origin]")
            pending.add(block)
        }
        fun drain() {
            var count = 0
            while (pending.isNotEmpty()) {
                check(++count < 10000) { "Dispatcher failed to drain" }
                observe("before queued runnable ($count)")
                pending.removeFirst().run()
                observe("after queued runnable ($count)")
            }
        }
    }
    private class Frames : MonotonicFrameClock {
        val times = Channel<Long>(Channel.RENDEZVOUS)
        var now = 0L
        var observe: (String) -> Unit = {}
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            val time = times.receive()
            observe("before frame $time")
            return onFrame(time).also { observe("after frame $time") }
        }
    }
    private fun assertFullyVisible(view: View) {
        val bounds = android.graphics.Rect()
        assertTrue(view.getGlobalVisibleRect(bounds))
        assertEquals(view.width, bounds.width()); assertEquals(view.height, bounds.height())
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun fixture(
        nestedParent: Boolean = false,
        onContentFocusRectangle: ((NativeVerticalViewport, View) -> Unit)? = null,
        test: (NativeVerticalViewport, View, View, Queue, Frames, CoroutineScope) -> Unit,
    ) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val queue = Queue(); val frames = Frames(); val scope = CoroutineScope(queue + SupervisorJob())
        var viewport: NativeVerticalViewport? = null
        var primary: Throwable? = null
        val trace = java.util.ArrayDeque<String>()
        var lastState: String? = null
        var fixtureState: String? = null
        val admissions = linkedMapOf<String, Int>()
        fun record(stage: String) {
            val v = viewport ?: return
            val content = v.getChildAt(0) as? FrameLayout ?: return
            val a = content.getChildAt(0); val b = content.getChildAt(1)
            val outer = (v.parent as? View)?.parent as? NativeVerticalViewport
            fun geometry(view: View?): String = view?.let { "${it.left},${it.top},${it.right},${it.bottom};translationY=${it.translationY};layout=${it.isLayoutRequested}" } ?: "absent"
            val state = "scroll=${v.state.value}/${v.state.maxValue},running=${v.state.isScrollInProgress},focus=${when(v.findFocus()) { a -> "a"; b -> "b"; else -> "other" }},window=${v.hasWindowFocus()},v=${geometry(v)},content=${geometry(content)},a=${geometry(a)},b=${geometry(b)},outer=${outer?.let { "${it.state.value};running=${it.state.isScrollInProgress};${geometry(it)};content=${geometry(it.getChildAt(0))}" }}"
            if (stage.startsWith("enqueue [") && stage != "enqueue []") {
                admissions[stage] = (admissions[stage] ?: 0) + 1
            }
            if (stage == "fixture arranged") fixtureState = state
            if (state != lastState || stage.startsWith("enqueue") ||
                stage.startsWith("incoming INPUT_FOCUS") || stage == "fixture arranged") {
                if (trace.size == 160) trace.removeFirst()
                trace.addLast("$stage: $state")
                lastState = state
            }
        }
        queue.observe = ::record
        frames.observe = ::record
        try {
            instrumentation.runOnMainSync {
                val nested = object : NativeVerticalNestedHost {
                    override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
                    override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
                    override suspend fun preFling(availableY: Float) = 0f
                    override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
                }
                viewport = NativeVerticalViewport(activity, NativeVerticalScrollState(800), Density(1f), frames, scope, nested)
                val content = object : FrameLayout(activity) {
                    override fun requestChildRectangleOnScreen(
                        child: View, rectangle: android.graphics.Rect, immediate: Boolean, source: Int,
                    ): Boolean {
                        if (source == View.RECTANGLE_ON_SCREEN_REQUEST_SOURCE_INPUT_FOCUS) {
                            record("incoming INPUT_FOCUS childTop=${child.top},childScroll=${child.scrollX},${child.scrollY},rect=$rectangle")
                            onContentFocusRectangle?.invoke(requireNotNull(viewport), child)
                        }
                        return super.requestChildRectangleOnScreen(child, rectangle, immediate, source)
                    }
                }.apply { minimumHeight = 1000 }
                for (top in listOf(0, if (nestedParent) 950 else 850)) content.addView(View(activity).apply {
                    isFocusable = true; isFocusableInTouchMode = true
                }, FrameLayout.LayoutParams(100, 50).apply { topMargin = top })
                viewport!!.addView(content)
                val root = FrameLayout(activity)
                if (nestedParent) {
                    val outer = NativeVerticalViewport(activity, NativeVerticalScrollState(100), Density(1f), frames, scope, nested)
                    outer.addView(FrameLayout(activity).apply {
                        minimumHeight = 1000
                        addView(viewport, FrameLayout.LayoutParams(300, 200))
                    })
                    root.addView(outer, FrameLayout.LayoutParams(300, 100))
                } else root.addView(viewport, FrameLayout.LayoutParams(300, 200))
                activity.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val v = requireNotNull(viewport)
                assertTrue(v.isAttachedToWindow)
                v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, 300, 200)
                val content = v.getChildAt(0) as FrameLayout
                val a = content.getChildAt(0); val b = content.getChildAt(1)
                // Discard any Activity's initial default-focus request before arranging the case.
                assertTrue(b.requestFocus()); queue.drain()
                advance(v, queue, frames)
                v.state.dispatchRawDelta((800 - v.state.value).toFloat())
                v.layout(0, 0, 300, 200)
                assertEquals(800, v.state.value)
                record("fixture arranged")
                test(v, a, b, queue, frames, scope)
            }
        } catch (error: Throwable) {
            primary = error
            try {
                val sdkFull = if (android.os.Build.VERSION.SDK_INT >= 36) android.os.Build.VERSION.SDK_INT_FULL else null
                error.addSuppressed(AssertionError("Focus admission diagnostic SDK=${android.os.Build.VERSION.SDK_INT}, SDK_FULL=$sdkFull, build=${android.os.Build.DISPLAY}:\nAdmissions=$admissions\nFixture=$fixtureState\n" + trace.joinToString("\n")))
            } catch (diagnosticFailure: Throwable) {
                if (diagnosticFailure !== error) error.addSuppressed(diagnosticFailure)
            }
            throw error
        }
        finally {
            try { instrumentation.runOnMainSync {
                try { scope.cancel(); queue.drain() } finally { activity.finish() }
            } } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup
                if (cleanup !== primary) primary!!.addSuppressed(cleanup)
            }
        }
    }
    private fun advance(v: NativeVerticalViewport, queue: Queue, frames: Frames) {
        repeat(240) {
            frames.now += 16_666_667L
            frames.times.trySend(frames.now)
            queue.drain()
            v.layout(0, 0, 300, 200)
            ((v.parent as? View)?.parent as? NativeVerticalViewport)?.layout(0, 0, 300, 100)
        }
        assertFalse(v.state.isScrollInProgress)
    }

    @Test fun obsoleteQueuedFocusCannotScrollAwayFromCurrentVisibleFocus() = fixture { v, a, b, queue, frames, _ ->
        assertTrue(a.requestFocus())
        assertTrue(b.requestFocus())
        assertSame(b, v.findFocus())
        queue.drain()
        advance(v, queue, frames)
        assertSame(b, v.findFocus())
        assertEquals(800, v.state.value)
        assertFullyVisible(b)
    }

    @Test fun alreadyAcceptedFocusCannotHideCurrentVisibleFocus() = fixture { v, a, b, queue, frames, _ ->
        assertTrue(a.requestFocus())
        queue.drain()
        assertTrue(v.state.isScrollInProgress)
        assertTrue(b.requestFocus())
        queue.drain()
        advance(v, queue, frames)
        assertSame(b, v.findFocus())
        assertEquals(800, v.state.value)
        assertFullyVisible(b)
    }

    @Test fun midflightFocusAtVisibleEdgeCannotBeMovedOut() = fixture { v, a, b, queue, frames, _ ->
        assertTrue(a.requestFocus()); queue.drain()
        var framesAdvanced = 0
        while (v.state.value == 800 && framesAdvanced < 240) {
            framesAdvanced++
            frames.now += 16_666_667L
            frames.times.trySend(frames.now); queue.drain(); v.layout(0, 0, 300, 200)
        }
        assertTrue(v.state.isScrollInProgress)
        assertTrue(v.state.value < 800)
        assertTrue(v.state.value > 0)
        (b.layoutParams as FrameLayout.LayoutParams).topMargin = v.state.value + 150
        b.requestLayout()
        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 300, 200)
        val position = v.state.value
        val edge = android.graphics.Rect()
        assertTrue(b.getGlobalVisibleRect(edge)); assertEquals(b.height, edge.height())
        val host = android.graphics.Rect(); assertTrue(v.getGlobalVisibleRect(host))
        assertEquals(host.bottom, edge.bottom)
        assertTrue(b.requestFocus()); queue.drain()
        advance(v, queue, frames)
        assertSame(b, v.findFocus()); assertEquals(position, v.state.value)
        assertFullyVisible(b)
    }

    @Test fun returningFocusGetsANewRequestGeneration() = fixture { v, a, b, queue, frames, _ ->
        assertTrue(a.requestFocus()); queue.drain()
        assertTrue(b.requestFocus()); assertTrue(a.requestFocus()); queue.drain()
        advance(v, queue, frames)
        assertSame(a, v.findFocus()); assertEquals(0, v.state.value)
    }

    @Test fun newerExplicitRevealIsNotUndoneByFocusFallback() = fixture { v, a, b, queue, frames, scope ->
        assertTrue(a.requestFocus()); queue.drain()
        assertTrue(b.requestFocus()); queue.drain()
        val explicit = scope.launch { v.bringDescendantIntoView(a) { Rect(0f, 0f, a.width.toFloat(), a.height.toFloat()) } }
        queue.drain(); advance(v, queue, frames)
        assertTrue(explicit.isCompleted); assertFalse(explicit.isCancelled)
        assertSame(b, v.findFocus()); assertEquals(0, v.state.value)
    }

    @Test fun supersededFocusCannotScrollNestedAncestorAwayFromVisibleFocus() = fixture(nestedParent = true) { v, a, b, queue, frames, _ ->
        val outer = (v.parent as View).parent as NativeVerticalViewport
        assertEquals(100, outer.state.value)
        assertFullyVisible(b)
        assertTrue(a.requestFocus()); queue.drain()
        assertTrue(v.state.isScrollInProgress); assertTrue(outer.state.isScrollInProgress)
        assertTrue(b.requestFocus()); queue.drain()
        advance(v, queue, frames)
        assertSame(b, v.findFocus())
        assertEquals(800, v.state.value); assertEquals(100, outer.state.value)
        assertFalse(outer.state.isScrollInProgress)
        assertFullyVisible(b)
    }

    @Test fun explicitCallerRevealDoesNotRequireFocusedTarget() = fixture { v, a, b, queue, frames, scope ->
        assertSame(b, v.findFocus())
        val request = scope.launch { v.bringDescendantIntoView(a) { Rect(0f, 0f, a.width.toFloat(), a.height.toFloat()) } }
        queue.drain()
        advance(v, queue, frames)
        assertTrue(request.isCompleted)
        assertSame(b, v.findFocus())
        assertEquals(0, v.state.value)
    }

    private fun arrangeRectangleCase(v: NativeVerticalViewport): FrameLayout {
        v.state.dispatchRawDelta((400 - v.state.value).toFloat())
        v.forceLayout()
        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 300, 200)
        val content = v.getChildAt(0) as FrameLayout
        assertEquals(400, v.state.value)
        assertEquals(-400, content.top)
        return content
    }

    @Test fun legacyRectangleCopiesCallerBoundsAndUsesLiveChildScroll() = fixture { v, _, b, queue, frames, _ ->
        val content = arrangeRectangleCase(v)
        content.scrollTo(0, 100)
        val requested = android.graphics.Rect(0, 700, 100, 750)
        assertTrue(v.requestChildRectangleOnScreen(content, requested, false))
        requested.set(0, 0, 100, 50)
        content.scrollTo(0, 150)
        queue.drain(); advance(v, queue, frames)
        // Copied [700,750] - child scroll150 - host position400 = visible [150,200].
        assertEquals(400, v.state.value)
        assertSame(b, v.findFocus())
        assertEquals(150, content.scrollY)
        assertTrue(v.requestChildRectangleOnScreen(content, android.graphics.Rect(0, 800, 100, 850), false))
        queue.drain(); advance(v, queue, frames)
        assertEquals(500, v.state.value)
        assertSame(b, v.findFocus())
    }

    @Test fun typedFocusRectanglePreservesSuppliedBounds() = fixture { v, _, b, queue, frames, _ ->
        val content = arrangeRectangleCase(v)
        content.scrollTo(0, 150)
        assertSame(b, v.findFocus())
        assertTrue(v.requestChildRectangleOnScreen(content, android.graphics.Rect(0, 800, 100, 850),
            false, View.RECTANGLE_ON_SCREEN_REQUEST_SOURCE_INPUT_FOCUS))
        queue.drain(); advance(v, queue, frames)
        // Caller bounds require position500; replacing them with focused b[850,900] requires550.
        assertEquals(500, v.state.value)
        assertSame(b, v.findFocus())
    }

    @Test fun typedFocusRectangleRetiresWhenFocusReturnsWithNewGeneration() = fixture { v, a, b, queue, frames, _ ->
        val content = v.getChildAt(0)
        assertSame(b, v.findFocus())
        assertTrue(v.requestChildRectangleOnScreen(content, android.graphics.Rect(0, 0, 100, 50),
            false, View.RECTANGLE_ON_SCREEN_REQUEST_SOURCE_INPUT_FOCUS))
        assertTrue(a.requestFocus()); assertTrue(b.requestFocus())
        queue.drain(); advance(v, queue, frames)
        assertSame(b, v.findFocus())
        assertEquals(800, v.state.value)
        assertFullyVisible(b)
    }

    private fun arrangeOffscreenFocusCase(v: NativeVerticalViewport, a: View, top: Int = 200) {
        (a.layoutParams as FrameLayout.LayoutParams).topMargin = top
        a.requestLayout()
        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 300, 200)
        assertEquals(top, a.top)
        assertEquals(800, v.state.value)
        assertEquals(-800, v.getChildAt(0).top)
    }

    @Test fun focusedOffscreenChildAtNonzeroTopRevealsItsActualBounds() = fixture { v, a, _, queue, frames, _ ->
        arrangeOffscreenFocusCase(v, a)
        assertTrue(a.requestFocus()); queue.drain()
        advance(v, queue, frames)
        assertSame(a, v.findFocus())
        assertEquals(200, v.state.value)
        assertFullyVisible(a)
    }

    @Test fun explicitMatchingRectangleInsideFocusCallbackRemainsIndependent() = fixture { v, a, _, queue, frames, _ ->
        arrangeOffscreenFocusCase(v, a)
        var requested = false
        a.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused) {
                requested = true
                assertTrue(a.requestRectangleOnScreen(android.graphics.Rect(0, -600, 100, -550),
                    false, View.RECTANGLE_ON_SCREEN_REQUEST_SOURCE_INPUT_FOCUS))
            }
        }
        assertTrue(a.requestFocus()); queue.drain()
        advance(v, queue, frames)
        assertTrue(requested)
        assertSame(a, v.findFocus())
        assertEquals(0, v.state.value)
    }

    @Test fun explicitMatchingRectangleAfterFocusReturnsRemainsIndependent() = fixture { v, a, _, queue, frames, _ ->
        arrangeOffscreenFocusCase(v, a)
        assertTrue(a.requestFocus())
        assertTrue(a.requestRectangleOnScreen(android.graphics.Rect(0, -600, 100, -550),
            false, View.RECTANGLE_ON_SCREEN_REQUEST_SOURCE_INPUT_FOCUS))
        queue.drain(); advance(v, queue, frames)
        assertSame(a, v.findFocus())
        assertEquals(0, v.state.value)
    }

    @Test fun nestedDirectExplicitRectangleInsideAutomaticDispatchRemainsIndependent() {
        var requested = false
        fixture(onContentFocusRectangle = { v, child ->
            if (child.top == 200) {
                requested = true
                // Same malformed rectangle in immediate content-child coordinates; outer auto is on-stack.
                assertTrue(v.requestChildRectangleOnScreen(v.getChildAt(0),
                    android.graphics.Rect(0, -400, 100, -350), false,
                    View.RECTANGLE_ON_SCREEN_REQUEST_SOURCE_INPUT_FOCUS))
            }
        }) { v, a, _, queue, frames, _ ->
            arrangeOffscreenFocusCase(v, a)
            assertTrue(a.requestFocus()); queue.drain()
            advance(v, queue, frames)
            assertTrue(requested)
            assertSame(a, v.findFocus())
            assertEquals(0, v.state.value)
        }
    }

    @Test fun partiallyVisibleFocusRevealsFullBoundsDespiteValidAutomaticRectangle() = fixture { v, a, _, queue, frames, _ ->
        arrangeOffscreenFocusCase(v, a, top = 780)
        assertEquals(50, a.height)
        val visible = android.graphics.Rect()
        assertTrue(a.getLocalVisibleRect(visible))
        assertEquals(android.graphics.Rect(0, 20, 100, 50), visible)
        assertTrue(a.requestFocus()); queue.drain()
        advance(v, queue, frames)
        assertSame(a, v.findFocus())
        assertEquals(780, v.state.value)
        assertFullyVisible(a)
    }
}
