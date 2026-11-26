package smallthingz.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.reverb.ReverbDesign
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Attached Android traversals plus software pixel controls; no compositor/performance claim. */
@RunWith(AndroidJUnit4::class)
class NativeSelectionForegroundVisibilityIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { action() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    @Test fun hiddenAncestorSkipsAutomaticSamplingAndFirstVisibleDrawUsesCurrentState() = withFixture { f ->
        var previousPixels = IntArray(0)
        main {
            assertTrue(f.control.isAttachedToWindow && f.control.isShown)
            previousPixels = pixels(f.control)
            f.presentation.reads = 0
        }
        awaitDraw(f.root)
        main { assertTrue("Visible automatic preparation is the positive control", f.presentation.reads > 0) }

        for (visibility in listOf(View.GONE, View.INVISIBLE)) {
            main { f.ancestor.visibility = visibility }
            awaitDraw(f.root)
            main {
                assertTrue("Hiding the ancestor must retain attachment", f.control.isAttachedToWindow)
                assertFalse(f.control.isShown)
                f.dark = !f.dark
                f.palette = ReverbDesign.theme(f.activity, f.dark).palette.settingsControls
                f.selected = if (f.selected == "one") "two" else "one"
                f.options = f.options.map { it.copy(label = "${it.id} updated $visibility") }
                f.control.bind(f.options, f.selected, f.palette)
                f.presentation.reads = 0
            }
            // Real unrelated window draws, not calls to onPreDraw or fabricated attachment flags.
            repeat(3) { awaitDraw(f.root) }
            main {
                assertEquals("Hidden automatic supplier calls: visibility=$visibility", 0, f.presentation.reads)
            }

            var firstVisible: Snapshot? = null
            awaitDraw(f.root, change = { f.ancestor.visibility = View.VISIBLE }) {
                assertTrue(f.control.isAttachedToWindow && f.control.isShown)
                // OnDraw notification follows window pre-draw, before child dispatchDraw. Check this
                // before the explicit pixel capture so its prepare() cannot repair a missed bind.
                assertTrue("First visible pre-draw must sample the current surface", f.presentation.reads > 0)
                firstVisible = snapshot(f.control)
            }
            main {
                val first = requireNotNull(firstVisible)
                assertEquals(f.selected, first.checked)
                assertEquals(f.options.map { it.label.toString() }, first.labels)
                assertEquals(0, f.requests)
                withReference(f) { reference ->
                    val expected = snapshot(reference, prepareFirst = true)
                    assertEquals("Ink before explicit drawing must already match", expected.inks, first.inks)
                    assertEquals(expected.checked, first.checked)
                    assertArrayEquals("First-visible software pixels preserve grain/contrast", expected.pixels, first.pixels)
                }
                assertFalse("Hidden palette/selection/text changes must change pixels", previousPixels.contentEquals(first.pixels))
                withReference(f, f.palette.copy(surfacePreset = NativeGrainPreset(0f, 0f))) { flat ->
                    assertFalse("Pixel control must exercise actual shared grain", pixels(flat).contentEquals(first.pixels))
                }
                previousPixels = first.pixels
            }
        }
    }

    @Test fun detachedExplicitDrawingStillPreparesAndRetirementRemovesAutomaticWork() = withFixture { f ->
        var retainedWidth = 0
        main {
            retainedWidth = f.control.width
            f.ancestor.removeView(f.control)
            assertFalse(f.control.isAttachedToWindow)
            f.presentation.reads = 0
        }
        repeat(2) { awaitDraw(f.root) }
        main {
            assertEquals("Detached owner must leave the window observer", 0, f.presentation.reads)
            f.dark = !f.dark
            f.palette = ReverbDesign.theme(f.activity, f.dark).palette.settingsControls
            f.selected = "two"
            f.control.bind(f.options, f.selected, f.palette)
            layoutDetached(f.control, retainedWidth)
            f.presentation.reads = 0
            val explicit = snapshot(f.control, prepareFirst = true)
            assertTrue("Detached draw must still prepare through dispatchDraw", f.presentation.reads > 0)
            withReference(f) { reference ->
                val expected = snapshot(reference, prepareFirst = true)
                assertArrayEquals(expected.pixels, explicit.pixels)
                assertEquals(expected.inks, explicit.inks)
                assertEquals("two", explicit.checked)
            }
            f.presentation.reads = 0
            f.ancestor.addView(f.control, FrameLayout.LayoutParams(-1, -2))
        }
        awaitDraw(f.root)
        main {
            assertTrue(f.control.isAttachedToWindow && f.control.isShown)
            assertTrue("Reattachment must restore automatic preparation", f.presentation.reads > 0)
            f.control.close()
            f.presentation.reads = 0
        }
        repeat(2) { awaitDraw(f.root) }
        main {
            assertEquals("Closed attached owner must not sample", 0, f.presentation.reads)
            pixels(f.control)
            assertEquals("Explicit drawing after retirement must not revive preparation", 0, f.presentation.reads)
            assertEquals(0, f.requests)
        }
    }

    /** Same retained grouped surface as the shared Settings presentation; only its supplier is counted. */
    private class CountingPresentation(context: Context) : NativeSegmentedPresentation {
        private val recipe = ReverbDesign.appearance.settingsSelectors.segmented
        override val surface = NativeGroupLayout(context, recipe.surfaceRadiusDp)
        var reads = 0
        override fun setContent(content: View) = surface.addRow(content, NativeGroupLayout.LayoutParams(-1, -2))
        override fun bindSurface(palette: ControlPalette) {
            surface.setSurfacePreset(palette.surfacePreset)
            surface.setPalette(palette.surface, palette.outline)
        }
        override val paintedSurfaceColor: Int get() { reads++; return surface.paintedFillColor }
        override val paintedTextSurface: Long get() = surface.paintedTextSurface
    }

    private class Fixture(val activity: Activity) {
        var dark = true
        var palette = ReverbDesign.theme(activity, dark).palette.settingsControls
        var options = listOf(NativeChoice("one", "One"), NativeChoice("two", "Two"))
        var selected = "one"
        var requests = 0
        val presentation = CountingPresentation(activity)
        val control = NativeSegmentedControl(activity, palette,
            ReverbDesign.appearance.settingsSelectors.segmented,
            activity.resources.displayMetrics.density, { true }, presentation).apply {
            bind(options, selected, palette)
            onSelectionRequest = { requests++; false }
        }
        val ancestor = FrameLayout(activity).apply { addView(control, FrameLayout.LayoutParams(-1, -2)) }
        val root = FrameLayout(activity).apply {
            // Keep keyboard focus on the fixture root, so pixel references compare idle controls.
            isFocusableInTouchMode = true
            addView(ancestor, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private data class Snapshot(val pixels: IntArray, val inks: List<Pair<Int, Int>>,
                                val checked: String, val labels: List<String>)

    private fun snapshot(control: NativeSegmentedControl, prepareFirst: Boolean = false): Snapshot {
        // References and detached subjects deliberately use the unchanged explicit drawing path.
        val image = if (prepareFirst) pixels(control) else null
        val choices = descendants(control).filterIsInstance<NativeSegmentedChoice>().single()
        val chips = (0 until choices.childCount).map { choices.getChildAt(it) as NativeChip }
        val inks = chips.map { requireNotNull(it.uniformSelectionInk).let { ink -> ink.trackInk to ink.pillInk } }
        for (chip in chips) {
            val node = requireNotNull(chip.createAccessibilityNodeInfo())
            assertTrue(node.isCheckable)
            assertEquals(chip.isChecked, node.isChecked)
        }
        assertEquals(1, chips.count { it.isChecked })
        return Snapshot(image ?: pixels(control), inks, requireNotNull(choices.selectedId), chips.map { it.text.toString() })
    }

    private fun withReference(f: Fixture, palette: ControlPalette = f.palette, block: (NativeSegmentedControl) -> Unit) {
        val reference = ReverbDesign.appearance.settingsSelectors.createSegmentedControl(f.activity, palette)
        try {
            reference.bind(f.options, f.selected, palette)
            reference.layoutDirection = f.control.layoutDirection
            layoutDetached(reference, f.control.width)
            block(reference)
        } finally { reference.close() }
    }

    private fun layoutDetached(view: View, width: Int) {
        assertFalse("Only detached fixtures may receive manual layout", view.isAttachedToWindow)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun pixels(view: View): IntArray {
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            IntArray(view.width * view.height).also { bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height) }
        } finally { bitmap.recycle() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        awaitGrain()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        withRetiringTestActivity(activity, root = { fixture?.root }, cleanup = { fixture?.control?.close() }) {
            main {
                fixture = Fixture(activity).also {
                    activity.setContentView(it.root)
                    assertTrue(it.root.requestFocus())
                }
            }
            val f = requireNotNull(fixture)
            repeat(2) { awaitDraw(f.root) }
            block(f)
        }
    }

    private fun awaitDraw(view: View, change: () -> Unit = {}, observe: () -> Unit = {}) {
        val done = CountDownLatch(1)
        var posted = false
        var failure: Throwable? = null
        val listener = ViewTreeObserver.OnDrawListener {
            if (!posted) {
                posted = true
                try { observe() } catch (error: Throwable) { failure = error }
                view.post { done.countDown() }
            }
        }
        try {
            main { view.viewTreeObserver.addOnDrawListener(listener); change(); view.postInvalidateOnAnimation() }
            assertTrue("Real attached window must draw", done.await(5, TimeUnit.SECONDS))
        } finally { main { view.viewTreeObserver.removeOnDrawListener(listener) } }
        failure?.let { throw it }
    }

    private fun awaitGrain() {
        val ready = CountDownLatch(1)
        lateinit var observer: NoiseDrawable
        val readinessCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                if (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null) ready.countDown()
            }
            override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        main {
            observer = NoiseDrawable().apply { callback = readinessCallback }
            if (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null) ready.countDown()
        }
        try { assertTrue("Shared grain must be ready for pixel controls", ready.await(5, TimeUnit.SECONDS)) }
        finally { main { observer.callback = null } }
    }
}
