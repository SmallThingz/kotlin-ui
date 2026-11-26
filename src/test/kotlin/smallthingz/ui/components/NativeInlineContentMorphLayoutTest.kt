package smallthingz.ui.components

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.animation.SpringValue
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.view.ViewSpringDriver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeInlineContentMorphLayoutTest {
    @Test fun sameHeightMorphUpdatesAlphaWithoutRequestingAnotherLayout() = withFixture { f ->
        val editor = FixedHeightView(f.activity, 48)
        f.morph.editor(editor, sourceAvailable = true)
        f.layout()
        f.frame(1_000_000_000L)
        assertFalse(f.morph.isLayoutRequested)
        f.frame(1_040_000_000L)
        assertTrue(editor.alpha > 0f && editor.alpha < 1f)
        assertEquals(1f - editor.alpha, f.action.alpha, 0f)
        assertFalse("Opacity-only sample must not remeasure the row", f.morph.isLayoutRequested)
        assertEquals(48, f.morph.measuredHeight)
        f.frame(3_000_000_000L)
        assertEquals(1f, editor.alpha, 0f)
        assertEquals(View.INVISIBLE, f.action.visibility)
        assertFalse(f.morph.isLayoutRequested)
        assertFalse(f.motion().isRunning)
        assertEquals(false, field(f.driver(), "framePosted"))
    }

    @Test fun changingRoundedHeightAndChildRemeasureStillRequestAndDeliverLayout() = withFixture { f ->
        val editor = FixedHeightView(f.activity, 112)
        f.morph.editor(editor, sourceAvailable = true)
        f.layout()
        f.frame(1_000_000_000L)
        assertFalse(f.morph.isLayoutRequested)
        f.frame(1_040_000_000L)
        assertTrue(f.morph.isLayoutRequested)
        val fraction = f.motion().value.coerceIn(0f, 1f)
        assertTrue(fraction > 0f && fraction < 1f)
        f.layout()
        assertEquals(48 + ((112 - 48) * fraction).roundToInt(), f.morph.measuredHeight)
        f.frame(1_040_000_000L)
        assertFalse("Repeated rounded sample must not request a duplicate layout", f.morph.isLayoutRequested)
        editor.naturalHeight = 160
        editor.requestLayout()
        assertTrue(f.morph.isLayoutRequested)
        f.layout()
        assertEquals(48 + ((160 - 48) * fraction).roundToInt(), f.morph.measuredHeight)
        f.morph.editor(editor, sourceAvailable = false)
        assertTrue(f.morph.isLayoutRequested)
        f.layout()
        assertEquals((160 * f.motion().value.coerceIn(0f, 1f)).roundToInt(), f.morph.measuredHeight)
        f.frame(3_000_000_000L)
        f.layout()
        assertEquals(160, f.morph.measuredHeight)
        assertEquals(1f, editor.alpha, 0f)
    }

    @Test fun closingDetachRetiresEditorOnceAndReattachCanOpenAnotherOwner() = withFixture { f ->
        val first = FixedHeightView(f.activity, 112)
        f.morph.editor(first, sourceAvailable = true)
        f.layout()
        f.frame(1_000_000_000L)
        f.frame(1_040_000_000L)
        f.morph.editor(null, sourceAvailable = true)
        val oldDriver = f.driver()
        f.root.removeView(f.morph)
        assertFalse(f.morph.isAttachedToWindow)
        assertNull(field(f.morph, "driver"))
        assertEquals(true, field(oldDriver, "closed"))
        assertEquals(false, field(oldDriver, "framePosted"))
        assertEquals(listOf(first), f.retired)
        assertFalse(f.morph.hasEditor)
        oldDriver.doFrame(3_000_000_000L)
        assertEquals(listOf(first), f.retired)
        f.root.addView(f.morph, FrameLayout.LayoutParams(320, FrameLayout.LayoutParams.WRAP_CONTENT))
        f.layout()
        assertEquals(48, f.morph.measuredHeight)
        val second = FixedHeightView(f.activity, 80)
        f.morph.editor(second, sourceAvailable = true)
        f.layout()
        f.frame(4_000_000_000L)
        f.frame(6_000_000_000L)
        f.layout()
        assertEquals(80, f.morph.measuredHeight)
        assertEquals(1f, second.alpha, 0f)
        f.morph.close()
        f.morph.close()
        assertEquals(listOf(first, second), f.retired)
        assertFalse(f.morph.hasEditor)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val f = Fixture(controller.get())
        try { block(f) } finally {
            f.morph.close()
            controller.pause().stop().destroy()
        }
    }

    private class FixedHeightView(context: Context, var naturalHeight: Int) : View(context) {
        override fun onMeasure(width: Int, height: Int) {
            setMeasuredDimension(MeasureSpec.getSize(width), naturalHeight)
        }
    }

    private class Fixture(val activity: Activity) {
        val root = FrameLayout(activity)
        val action = FixedHeightView(activity, 48)
        val retired = mutableListOf<FixedHeightView>()
        val morph = NativeInlineContentMorph<FixedHeightView>(activity, action) { retired += it }
        init {
            activity.setContentView(root)
            root.addView(morph, FrameLayout.LayoutParams(320, FrameLayout.LayoutParams.WRAP_CONTENT))
            layout()
            assertTrue(morph.isAttachedToWindow)
        }
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        fun driver() = field(morph, "driver") as ViewSpringDriver<*, *>
        @Suppress("UNCHECKED_CAST")
        fun motion() = field(morph, "progress") as SpringValue<Float, AnimationVector1D>
        fun frame(time: Long) {
            val owner = driver()
            // Deliver the real production callback at a controlled timestamp, removing its queued
            // instance first as Choreographer dispatch would. No substitute spring/layout model.
            Choreographer.getInstance().removeFrameCallback(owner)
            owner.doFrame(time)
        }
    }

    companion object {
        private fun field(owner: Any, name: String): Any? = owner.javaClass
            .getDeclaredField(name).apply { isAccessible = true }.get(owner)
    }
}
