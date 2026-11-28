package smallthingz.ui.components

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbIconDrawable

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeLeadingDiscGlyphCacheHostTest {
    @Test fun directGlyphReleasesOnHideAndDetachAndRedrawsExactPixels() {
        verifyLifecycle(wrapped = false)
    }

    @Test fun wrappedGlyphReleasesOnHideAndDetachAndRedrawsExactPixels() {
        verifyLifecycle(wrapped = true)
    }

    @Test fun contentTakeoverReleasesGlyphUntilItIsDrawnAgain() = withDisc(wrapped = true) {
        val initial = render()
        val original = requireMask(glyph)
        disc.bindContent(View(disc.context))
        assertTrue(disc.isAttachedToWindow && disc.isShown)
        assertReleased(glyph, original)
        assertFalse(glyph.isVisible)
        layout()
        assertFalse(render().any { it == Color.RED })
        assertReleased(glyph, original)

        disc.bindContent(null)
        layout()
        assertReleased(glyph, original)
        assertTrue(glyph.isVisible)
        assertArrayEquals(initial, render())
        assertNotSame(original, requireMask(glyph))
    }

    @Test fun replacingVisibleGlyphReleasesOutgoingWrapper() = withDisc(wrapped = true) {
        render()
        val original = requireMask(glyph)
        val replacement = ReverbIconDrawable(ReverbIcon.Play, 1f, Color.BLUE)
        bindGlyph(replacement)
        assertReleased(glyph, original)
        assertFalse(glyph.isVisible)
        assertNull(mask.get(replacement))
        assertTrue(replacement.isVisible)
        assertTrue(render().any { it == Color.BLUE })
        requireMask(replacement)
    }

    @Test fun bindingWarmWrapperWhileHiddenReleasesItsMaskUntilShown() = withDisc(wrapped = false) {
        val replacement = ReverbIconDrawable(ReverbIcon.Play, 1f, Color.BLUE)
        replacement.setBounds(0, 0, 24, 24)
        pixels(24, 24, replacement::draw)
        val warmed = requireMask(replacement)
        parent.visibility = View.INVISIBLE
        assertTrue(disc.isAttachedToWindow)
        assertFalse(disc.isShown)

        bindGlyph(wrap(replacement))
        assertReleased(replacement, warmed)
        assertFalse(replacement.isVisible)
        parent.visibility = View.VISIBLE
        assertReleased(replacement, warmed)
        assertTrue(replacement.isVisible)
        assertTrue(render().any { it == Color.BLUE })
        assertNotSame(warmed, requireMask(replacement))
    }

    private fun verifyLifecycle(wrapped: Boolean) = withDisc(wrapped) {
        val initial = render()
        assertTrue(initial.any { it == Color.RED })
        val original = requireMask(glyph)

        parent.visibility = View.INVISIBLE
        assertTrue(disc.isAttachedToWindow)
        assertFalse(disc.isShown)
        assertReleased(glyph, original)
        assertFalse(glyph.isVisible)

        parent.visibility = View.VISIBLE
        assertReleased(glyph, original)
        assertTrue(glyph.isVisible)
        assertArrayEquals(initial, render())
        val rebuilt = requireMask(glyph)
        assertNotSame(original, rebuilt)

        parent.removeView(disc)
        assertFalse(disc.isAttachedToWindow)
        assertReleased(glyph, rebuilt)
        assertFalse(glyph.isVisible)
        attach()
        assertReleased(glyph, rebuilt)
        assertTrue(glyph.isVisible)
        assertArrayEquals(initial, render())
        assertNotSame(rebuilt, requireMask(glyph))
    }

    private fun withDisc(wrapped: Boolean, test: Fixture.() -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            Fixture(controller.get(), wrapped).apply {
                attach()
                test()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private class Fixture(activity: Activity, wrapped: Boolean) {
        val parent = FrameLayout(activity)
        val glyph = ReverbIconDrawable(ReverbIcon.AudioFile, 1f, Color.RED)
        private val style = NativeLeadingDiscStyle()
        val disc = NativeLeadingDisc(activity, 1f, style, if (wrapped) wrap(glyph) else glyph, Color.WHITE)

        init { activity.setContentView(parent) }

        fun attach() {
            parent.addView(disc, FrameLayout.LayoutParams(44, 44))
            layout()
            assertTrue(disc.isAttachedToWindow && disc.isShown)
        }

        fun bindGlyph(value: Drawable) {
            disc.bindStyle(1f, style, value)
            layout()
        }

        fun layout() {
            val exact = View.MeasureSpec.makeMeasureSpec(44, View.MeasureSpec.EXACTLY)
            disc.measure(exact, exact)
            disc.layout(0, 0, 44, 44)
        }

        fun render(): IntArray = pixels(disc.width, disc.height, disc::draw)
    }

    private companion object {
        val mask = CachedPathIconDrawable::class.java.getDeclaredField("maskBitmap").apply { isAccessible = true }
        val maskCanvas = CachedPathIconDrawable::class.java.getDeclaredField("maskCanvas").apply { isAccessible = true }

        fun wrap(glyph: Drawable): Drawable = object : DrawableWrapper(glyph) {}

        fun requireMask(glyph: Drawable): Bitmap {
            assertNotNull(maskCanvas.get(glyph))
            return checkNotNull(mask.get(glyph) as Bitmap?)
        }

        fun assertReleased(glyph: Drawable, previous: Bitmap) {
            assertNull(mask.get(glyph))
            assertNull(maskCanvas.get(glyph))
            assertFalse(previous.isRecycled)
        }

        fun pixels(width: Int, height: Int, draw: (Canvas) -> Unit): IntArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                draw(Canvas(bitmap))
                return IntArray(width * height).also {
                    bitmap.getPixels(it, 0, width, 0, 0, width, height)
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
