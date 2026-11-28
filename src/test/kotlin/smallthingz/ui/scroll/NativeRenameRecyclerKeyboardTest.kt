package smallthingz.ui.scroll

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import smallthingz.ui.common.NativeSurfaceInputAdmission
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbInlineRecordingRename
import smallthingz.ui.reverb.settings.ReverbRetentionInputView
import smallthingz.ui.reverb.settings.ReverbRetentionMode

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

    @Test fun renameShiftHomeEndPairsKeepNativeSelectionAndViewport() = withHost { f ->
        assertModifierBoundaryPairs(f, shift = true)
    }

    @Test fun renameCtrlHomeEndPairsKeepNativeCaretAndViewport() = withHost { f ->
        assertModifierBoundaryPairs(f, shift = false)
    }

    @Test fun retentionShiftHomeEndPairsKeepNativeSelectionAndViewport() = withHost(retention = true) { f ->
        assertModifierBoundaryPairs(f, shift = true)
    }

    @Test fun retentionCtrlHomeEndPairsKeepNativeCaretAndViewport() = withHost(retention = true) { f ->
        assertModifierBoundaryPairs(f, shift = false)
    }

    private fun assertModifierBoundaryPairs(f: Fixture, shift: Boolean) {
        f.focusField()
        val before = f.anchor()
        val pivot = 5
        val modifier = if (shift) KeyEvent.KEYCODE_SHIFT_LEFT else KeyEvent.KEYCODE_CTRL_LEFT
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            else KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        for ((key, end) in listOf(KeyEvent.KEYCODE_MOVE_HOME to 0, KeyEvent.KEYCODE_MOVE_END to SEED.length)) {
            f.fieldEditor.setSelection(pivot)
            val start = if (shift) pivot else end
            f.modifiedPair(key, modifier, meta) { phase ->
                f.assertField(before, SEED, start, end, "modifier=$modifier key=$key $phase")
            }
        }
        // Releasing the modifiers must leave ordinary typing unmodified and publish to this owner.
        f.typeX()
        val expected = (if (shift) SEED.take(pivot) else SEED) + "x"
        f.assertField(before, expected, expected.length, expected.length, "typing after modifier release")
        assertTrue(f.rebinds > 0)
    }

    @Test fun boundaryUpAfterRenameRetirementReachesCurrentSibling() {
        for (retirement in Retirement.entries) withHost { f ->
            f.focusRename()
            val editor = f.rename.editor
            editor.setSelection(5)
            val beforeDown = f.anchor()
            val time = SystemClock.uptimeMillis()
            assertTrue(f.dispatch(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END, 0)))
            f.settle()
            f.assertEditor(beforeDown, SEED, SEED.length, "$retirement: DOWN reached native editor")

            // Real visibility/enabled/attachment/admission changes, not overrides of View state.
            when (retirement) {
                Retirement.HIDDEN -> editor.visibility = View.INVISIBLE
                Retirement.DISABLED -> editor.isEnabled = false
                Retirement.DETACHED -> (editor.parent as ViewGroup).removeView(editor)
                Retirement.ANCESTOR_BLOCKED -> f.inputAllowed = false
            }
            if (retirement == Retirement.HIDDEN) assertFalse(editor.isShown)
            if (retirement == Retirement.DISABLED) assertFalse(editor.isEnabled)
            if (retirement == Retirement.DETACHED) assertFalse(editor.isAttachedToWindow)
            if (retirement == Retirement.ANCESTOR_BLOCKED) assertFalse(editor.onCheckIsTextEditor())

            // A late direct delivery to the retired leaf must not become a new editor-owned UP.
            assertFalse("$retirement: retired leaf rejects late UP",
                editor.dispatchKeyEvent(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MOVE_END, 0)))
            val sibling = checkNotNull(f.list.findViewHolderForAdapterPosition(1)).itemView
            assertTrue(sibling.requestFocusFromTouch())
            assertSame(sibling, f.windowRoot.findFocus())
            assertFalse(editor.isFocused)
            var siblingUps = 0
            sibling.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_MOVE_END && event.action == KeyEvent.ACTION_UP) siblingUps++
                false // Preserve RecyclerView's normal non-editor endpoint fallback.
            }
            val before = f.anchor()
            try {
                assertTrue(f.dispatch(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MOVE_END, 0)))
                assertEquals("$retirement: current sibling receives the UP", 1, siblingUps)
                f.awaitFrames("$retirement: ordinary parent End reaches last row") {
                    f.list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                        !f.list.canScrollVertically(1) && f.layout.findLastVisibleItemPosition() == ROWS - 1
                }
                assertNotEquals(before, f.anchor())
                assertFalse("$retirement: old editor cannot reacquire focus", editor.isFocused)
                assertEquals(SEED, editor.text.toString())
                assertEquals(SEED, f.draft)
                assertEquals(0, f.rebinds)
            } finally { sibling.setOnKeyListener(null) }
        }
    }

    private enum class Retirement { HIDDEN, DISABLED, DETACHED, ANCESTOR_BLOCKED }

    private fun withHost(retention: Boolean = false, block: (Fixture) -> Unit) {
        // Reuse the Activity + NativeModalWindowRuntime pattern from ReverbRetainedGlyphCacheHostTest.
        val controller = Robolectric.buildActivity(Activity::class.java)
        try {
            controller.setup().visible()
            NativeModalWindowRuntime(controller.get()).use { runtime ->
                runtime.setStarted(true)
                val fixture = Fixture(controller.get(), runtime, retention) { focused ->
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
        useRetention: Boolean,
        private val windowFocusChanged: (Boolean) -> Unit,
    ) : AutoCloseable {
        var inputAllowed = true
        private val root = object : FrameLayout(activity), NativeSurfaceInputAdmission {
            override fun admitsSurfaceInput(descendant: View) = inputAllowed
        }
        val windowRoot: View get() = activity.window.decorView
        val list = NativeSourceRecyclerView(activity)
        val layout = LinearLayoutManager(activity)
        private val environment = ReverbEnvironment(activity, false)
        private val retention = if (useRetention) ReverbRetentionInputView(activity, environment) else null
        private val retentionRow = retention?.let { editor ->
            FrameLayout(activity).apply { addView(editor, FrameLayout.LayoutParams(-1, -2)) }
        }
        val fieldEditor: EditText get() = retention ?: rename.editor
        private val fieldRow: View get() = retentionRow ?: rename
        var draft = SEED
            private set
        var rebinds = 0
            private set
        private var closed = false
        private val rebind: Runnable = Runnable {
            if (!closed) {
                rebinds++
                if (retention == null) rename.bind(environment, draft, null, false) else bindRetention()
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
            if (retention != null) bindRetention()
            list.layoutManager = layout
            list.itemAnimator = null
            list.adapter = object : RecyclerView.Adapter<Holder>() {
                init { setHasStableIds(true) }
                override fun getItemCount() = ROWS
                override fun getItemId(position: Int) = position.toLong()
                override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
                    val view = if (viewType == 0) fieldRow else TextView(activity).apply {
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

        private fun bindRetention() {
            checkNotNull(retention).bind(draft, ReverbRetentionMode.TIME, "Synthetic retention", environment,
                requestFocus = false, readCurrentRaw = { draft }, onRawChanged = { value ->
                    draft = value
                    root.removeCallbacks(rebind)
                    root.post(rebind)
                })
        }

        fun focusField() {
            if (retention == null) return focusRename()
            assertTrue(retention.requestFocusFromTouch())
            awaitFrames("actual retention editor focused and laid out") {
                retention.isFocused && retention.hasWindowFocus() && !list.isLayoutRequested
            }
            retention.setSelection(SEED.length)
            assertTrue(list.canScrollVertically(1))
            assertField(anchor(), SEED, SEED.length, SEED.length, "initial retention")
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

        fun modifiedPair(code: Int, modifier: Int, meta: Int, check: (String) -> Unit) {
            val time = SystemClock.uptimeMillis()
            dispatch(KeyEvent(time, time, KeyEvent.ACTION_DOWN, modifier, 0, meta))
            try {
                assertTrue("Modified boundary DOWN reaches native editor",
                    dispatch(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 0, meta)))
                check("DOWN immediately")
                settle()
                check("DOWN after frames")
                dispatch(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta))
                check("UP immediately")
                settle()
                check("UP after frames")
            } finally {
                dispatch(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, modifier, 0, 0))
            }
            settle()
            check("modifier released")
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

        fun assertField(before: Anchor, expected: String, start: Int, end: Int, phase: String) {
            assertEquals("$phase: no list scroll may start", RecyclerView.SCROLL_STATE_IDLE, list.scrollState)
            assertEquals("$phase: viewport anchor", before, anchor())
            assertSame("$phase: original row stays mounted", fieldRow,
                list.findViewHolderForAdapterPosition(0)?.itemView)
            assertTrue("$phase: editor attached", fieldEditor.isAttachedToWindow)
            assertSame("$phase: editor retains focus", fieldEditor, windowRoot.findFocus())
            assertEquals("$phase: native text", expected, fieldEditor.text.toString())
            assertEquals("$phase: owner draft", expected, draft)
            assertEquals("$phase: selection anchor", start, fieldEditor.selectionStart)
            assertEquals("$phase: selection extent", end, fieldEditor.selectionEnd)
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
                try { retention?.releaseCallbacks() } finally {
                    try { list.adapter = null } finally { root.removeAllViews() }
                }
            }
        }

        private class Holder(view: View) : RecyclerView.ViewHolder(view)
    }

    private companion object {
        const val SEED = "Synthetic recording"
        const val ROWS = 40
    }
}
