package smallthingz.ui.scroll

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbInlineRecordingRename

/** Real rename field, real RecyclerView dispatch and an attached window; no IME/device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeRenameRecyclerKeyboardTest {
    private lateinit var oldFrameDelay: Duration
    private var oldPaused = false

    @Before fun finitePlatformFrames() {
        oldFrameDelay = ShadowChoreographer.getFrameDelay()
        oldPaused = ShadowChoreographer.isPaused()
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ShadowChoreographer.setPaused(true)
    }

    @After fun restorePlatformFrames() {
        ShadowChoreographer.setFrameDelay(oldFrameDelay)
        ShadowChoreographer.setPaused(oldPaused)
    }

    @Test fun endDownMovesCaretAndEndUpCannotScrollRenameOutOfView() = withHost { f ->
        f.focusRename()
        val before = f.anchor()
        f.rename.editor.setSelection(1)
        val downTime = SystemClock.uptimeMillis()
        assertTrue(f.dispatch(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END, 0)))
        f.assertEditor(before, SEED, SEED.length, "End DOWN immediately")
        f.settle()
        f.assertEditor(before, SEED, SEED.length, "End DOWN after frames")

        f.dispatch(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END, 0))
        // RecyclerView 1.4 fallback starts a smooth scroll for an unhandled UP too. Observe that
        // boundary before any frame can finish it, then verify the actual placement after frames.
        f.assertEditor(before, SEED, SEED.length, "End UP immediately")
        f.settle()
        f.assertEditor(before, SEED, SEED.length, "End UP after frames")
        f.typeX()
        f.assertEditor(before, SEED + "x", SEED.length + 1, "text after End pair and owner rebind")
    }

    @Test fun homePairStaysWithRenameEditorAndKeepsListPlacement() = withHost { f ->
        f.focusRename()
        val before = f.anchor()
        f.pair(KeyEvent.KEYCODE_MOVE_HOME) { phase -> f.assertEditor(before, SEED, 0, phase) }
        f.typeX()
        f.assertEditor(before, "x" + SEED, 1, "text after Home pair")
    }

    @Test fun directTextWithoutEndKeepsEditorIdentityFocusAndPlacementAfterRebind() = withHost { f ->
        f.focusRename()
        val before = f.anchor()
        f.typeX()
        f.assertEditor(before, SEED + "x", SEED.length + 1, "direct text and owner rebind")
        assertTrue("The real edit callback must publish and rebind the retained header", f.rebinds > 0)
    }

    @Test fun endStillNavigatesToListBottomWhenNonEditorRowOwnsFocus() = withHost { f ->
        val row = checkNotNull(f.list.findViewHolderForAdapterPosition(1)).itemView
        assertTrue(row.requestFocusFromTouch())
        assertSame(row, f.windowRoot.findFocus())
        assertFalse(row.onCheckIsTextEditor())
        assertTrue(f.list.canScrollVertically(1))
        val before = f.anchor()
        f.pair(KeyEvent.KEYCODE_MOVE_END) {}
        f.awaitFrames("End reached the final list row") {
            !f.list.canScrollVertically(1) && f.layout.findLastVisibleItemPosition() == ROWS - 1
        }
        assertNotEquals(before, f.anchor())
        assertEquals(SEED, f.draft)
    }

    @Test fun focusedEditorEndUpRequiresWindowFocusAndActiveInput() = withHost { f ->
        f.focusRename()
        val editor = f.rename.editor
        val before = f.anchor()
        fun endUp() = editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END))
        assertTrue(editor.isAttachedToWindow)
        assertTrue(editor.isShown)
        assertTrue(editor.hasWindowFocus())
        assertTrue("Focused live editor owns End UP", endUp())

        // Use the real ActivityController window callback, then process queued platform frames.
        // Do not override hasWindowFocus or clear the editor's retained view-focus flag.
        f.changeWindowFocus(false)
        assertTrue("This must exercise retained editor focus", editor.isFocused)
        assertTrue(editor.isAttachedToWindow)
        assertTrue(editor.isShown)
        assertFalse(editor.hasWindowFocus())
        assertFalse("Lost-window editor must not newly consume End UP", endUp())

        f.changeWindowFocus(true)
        assertTrue(editor.isFocused)
        assertTrue(editor.hasWindowFocus())
        assertTrue("Actual window restoration restores the guarded fallback", endUp())
        f.assertEditor(before, SEED, SEED.length, "window focus restored")

        // Exercise the existing leaf admission too; disabling its input session keeps view focus.
        editor.setInputActive(false)
        try {
            assertTrue(editor.isFocused)
            assertTrue(editor.hasWindowFocus())
            assertFalse(editor.onCheckIsTextEditor())
            assertFalse("Inactive leaf must not newly consume End UP", endUp())
        } finally { editor.setInputActive(true) }
        assertTrue(editor.onCheckIsTextEditor())
        assertTrue(endUp())
        f.assertEditor(before, SEED, SEED.length, "input restored")
    }

    private fun withHost(block: (Fixture) -> Unit) {
        // Reuse the Activity + NativeModalWindowRuntime pattern from ReverbRetainedGlyphCacheHostTest.
        val controller = Robolectric.buildActivity(Activity::class.java)
        try {
            controller.setup().visible()
            NativeModalWindowRuntime(controller.get()).use { runtime ->
                runtime.setStarted(true)
                val fixture = Fixture(controller.get(), runtime) { focused ->
                    controller.windowFocusChanged(focused)
                }
                try {
                    fixture.mount()
                    // Match the passing attached-window switch fixture: setContentView after the
                    // initial visible() needs a second visible() before ViewRoot focus dispatch.
                    controller.visible()
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                    assertTrue("Mounted RecyclerView must attach before window focus", fixture.list.isAttachedToWindow)
                    controller.windowFocusChanged(true)
                    fixture.awaitFrames("attached, laid-out, focused window") {
                        fixture.list.isAttachedToWindow && fixture.list.hasWindowFocus() &&
                            fixture.list.height > 0 && !fixture.list.isLayoutRequested &&
                            fixture.list.findViewHolderForAdapterPosition(1) != null
                    }
                    block(fixture)
                } finally { fixture.close() }
            }
        } finally { controller.close() }
    }

    private data class Anchor(val position: Int, val top: Int, val offset: Int)

    private class Fixture(
        private val activity: Activity,
        runtime: NativeModalWindowRuntime,
        private val windowFocusChanged: (Boolean) -> Unit,
    ) : AutoCloseable {
        private val root = FrameLayout(activity)
        val windowRoot: View get() = activity.window.decorView
        val list = NativeSourceRecyclerView(activity)
        val layout = LinearLayoutManager(activity)
        private val environment = ReverbEnvironment(activity, false)
        var draft = SEED
            private set
        var rebinds = 0
            private set
        private var closed = false
        private val rebind: Runnable = Runnable {
            if (!closed) {
                rebinds++
                rename.bind(environment, draft, null, false)
            }
        }
        val rename: ReverbInlineRecordingRename = ReverbInlineRecordingRename(activity, environment, runtime.scope, root,
            "Rename", "Cancel", "Done", { draft }, { value ->
                draft = value
                // Match the production owner's posted state delivery; no adapter invalidation or
                // editor replacement is involved in publishing an ordinary rename draft edit.
                root.removeCallbacks(rebind)
                root.post(rebind)
            }, { error("No Cancel requested") }, { error("No rename submission requested") })

        fun mount() {
            list.layoutManager = layout
            list.itemAnimator = null
            list.adapter = object : RecyclerView.Adapter<Holder>() {
                init { setHasStableIds(true) }
                override fun getItemCount() = ROWS
                override fun getItemId(position: Int) = position.toLong()
                override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
                    val view = if (viewType == 0) rename else TextView(activity).apply {
                        isFocusableInTouchMode = true
                    }
                    view.layoutParams = RecyclerView.LayoutParams(-1, if (viewType == 0) 220 else 60)
                    return Holder(view)
                }
                override fun onBindViewHolder(holder: Holder, position: Int) {
                    (holder.itemView as? TextView)?.text = "Synthetic recording $position"
                }
            }
            root.addView(list, FrameLayout.LayoutParams(-1, 320))
            activity.setContentView(root)
        }

        fun focusRename() {
            rename.startEditing()
            awaitFrames("real rename editor acquired window focus") {
                rename.editor.isFocused && rename.editor.hasWindowFocus() && !list.isLayoutRequested
            }
            assertTrue(list.canScrollVertically(1))
            assertEditor(anchor(), SEED, SEED.length, "initial rename")
        }

        fun changeWindowFocus(focused: Boolean) {
            windowFocusChanged(focused)
            settle()
        }

        fun dispatch(event: KeyEvent) = windowRoot.dispatchKeyEvent(event)

        fun pair(code: Int, check: (String) -> Unit) {
            val time = SystemClock.uptimeMillis()
            dispatch(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
            check("DOWN immediately")
            settle()
            check("DOWN after frames")
            dispatch(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            check("UP immediately")
            settle()
            check("UP after frames")
        }

        fun typeX() {
            val events = checkNotNull(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(charArrayOf('x')))
            assertTrue(events.isNotEmpty())
            events.forEach { event ->
                val consumed = dispatch(event)
                if (event.action == KeyEvent.ACTION_DOWN) assertTrue("Character DOWN reached editor", consumed)
            }
            settle()
        }

        fun anchor(): Anchor {
            val position = layout.findFirstVisibleItemPosition()
            return Anchor(position, checkNotNull(layout.findViewByPosition(position)).top,
                list.computeVerticalScrollOffset())
        }

        fun assertEditor(before: Anchor, expected: String, caret: Int, phase: String) {
            assertEquals("$phase: no list scroll may start", RecyclerView.SCROLL_STATE_IDLE, list.scrollState)
            assertEquals("$phase: viewport anchor", before, anchor())
            assertSame("$phase: original header stays mounted", rename,
                list.findViewHolderForAdapterPosition(0)?.itemView)
            assertTrue("$phase: editor attached", rename.editor.isAttachedToWindow)
            assertSame("$phase: original editor owns focus", rename.editor, windowRoot.findFocus())
            assertEquals("$phase: native text", expected, rename.editor.text.toString())
            assertEquals("$phase: owner draft", expected, draft)
            assertEquals("$phase: selection start", caret, rename.editor.selectionStart)
            assertEquals("$phase: selection end", caret, rename.editor.selectionEnd)
        }

        fun settle() {
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            awaitFrames("layout and recycler scroll reached terminal") {
                !list.isLayoutRequested && list.scrollState == RecyclerView.SCROLL_STATE_IDLE
            }
        }

        fun awaitFrames(reason: String, ready: () -> Boolean) {
            repeat(120) {
                if (ready()) return
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            }
            assertTrue(reason, ready())
        }

        override fun close() {
            if (closed) return
            closed = true
            root.removeCallbacks(rebind)
            list.stopScroll()
            try { rename.close() } finally {
                try { list.adapter = null } finally { root.removeAllViews() }
            }
        }

        private class Holder(view: View) : RecyclerView.ViewHolder(view)
    }

    private companion object {
        const val SEED = "Synthetic recording"
        const val ROWS = 40
    }
}
