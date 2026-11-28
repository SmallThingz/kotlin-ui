package smallthingz.ui.reverb

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
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
import smallthingz.ui.components.CachedPathIconDrawable
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeTopBarAction
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReverbBrowserToolbarGlyphCacheHostTest {
    @Test fun offsetTabCountReleasesOnAncestorHideAndRebuildsExactPixels() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val parent = FrameLayout(activity)
            activity.setContentView(parent)
            val theme = ReverbDesign.windowTheme(Density(1f), createFontFamilyResolver(activity),
                NativeColorScheme.fallback(false))
            val tabs = ReverbBrowserChromeAction("tabs", "Tabs",
                ReverbBrowserChromeIcon.Shared(ReverbIcon.Save))
            ReverbBrowserChromeActionView(activity, theme, tabs, {}).use { action ->
                action.bindGlyphOffset(6f)
                action.bindTabCount(7)
                parent.addView(action, FrameLayout.LayoutParams(48, 48))
                val exact = View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY)
                action.measure(exact, exact)
                action.layout(0, 0, 48, 48)

                val wrapper = NativeTopBarAction::class.java.getDeclaredField("glyph")
                    .apply { isAccessible = true }.get(action) as DrawableWrapper
                val glyph = wrapper.drawable as ReverbBrowserTabCountDrawable
                val mask = CachedPathIconDrawable::class.java.getDeclaredField("maskBitmap")
                    .apply { isAccessible = true }
                fun render(): IntArray {
                    val leaf = action.visual
                    val bitmap = Bitmap.createBitmap(leaf.width, leaf.height, Bitmap.Config.ARGB_8888)
                    leaf.draw(Canvas(bitmap))
                    return IntArray(leaf.width * leaf.height).also {
                        bitmap.getPixels(it, 0, leaf.width, 0, 0, leaf.width, leaf.height)
                    }
                }

                assertTrue(action.isAttachedToWindow && action.isShown)
                val pixels = render()
                assertTrue(pixels.any { it ushr 24 != 0 })
                val original = mask.get(glyph) as Bitmap
                parent.visibility = View.INVISIBLE
                assertTrue(action.isAttachedToWindow)
                assertFalse(action.isShown)
                assertNull(mask.get(glyph))
                assertFalse(original.isRecycled)

                parent.visibility = View.VISIBLE
                assertNull(mask.get(glyph))
                assertArrayEquals(pixels, render())
                val rebuilt = checkNotNull(mask.get(glyph))
                assertNotSame(original, rebuilt)
                parent.removeView(action)
                assertFalse(action.isAttachedToWindow)
                assertNull(mask.get(glyph))
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
