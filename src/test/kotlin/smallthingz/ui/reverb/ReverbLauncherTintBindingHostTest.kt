package smallthingz.ui.reverb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Causal stable-bind regression plus resource/state transitions; no private cache or device timing. */
@SuppressLint("ResourceType") // Framework color IDs are fixture-only keys for adaptive drawables.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbLauncherTintBindingHostTest {
    @Test fun unchangedLauncherAndPaletteBindsKeepWarmRasterAndOriginalPixels() = withMark { mark, image, source ->
        val identity = ReverbProductIdentity.Launcher(FIRST, circularOutline = true)
        val palette = ReverbDesign.theme(mark.context, false).palette
        mark.bind(identity, palette)
        place(mark, 64)
        val artwork = source.created.single()
        val first = render(mark)
        val warm = artwork.draws
        val tintCalls = artwork.tintCalls
        assertTrue(warm > 0)
        assertTrue("First load retains original tint clearing", tintCalls > 0)
        assertMaskAndColor(first, artwork.color)
        assertPixels(first, render(mark))
        assertEquals(warm, artwork.draws)
        repeat(2) {
            mark.bind(identity, palette)
            assertSame(artwork.icon, image.drawable)
            assertEquals(1, source.created.size)
            assertNull(image.imageTintList)
            assertPixels(first, render(mark))
            assertEquals("Unchanged Launcher bind must not retraverse child tints", tintCalls, artwork.tintCalls)
            assertEquals("Unchanged Launcher bind keeps the warmed child raster", warm, artwork.draws)
        }
        mark.bind(identity, ReverbDesign.theme(mark.context, true).palette)
        assertSame(artwork.icon, image.drawable)
        assertPixels(first, render(mark))
        assertEquals(tintCalls, artwork.tintCalls)
        assertEquals(warm, artwork.draws)

        mark.visibility = View.GONE
        mark.visibility = View.VISIBLE
        assertSame(artwork.icon, image.drawable)
        assertPixels(first, render(mark))
        val afterVisibility = artwork.draws
        assertPixels(first, render(mark))
        assertEquals(afterVisibility, artwork.draws)
        println("LauncherTint warm=$warm afterStableBinds=$warm afterVisibility=$afterVisibility " +
            "tintCalls=$tintCalls->${artwork.tintCalls}")
    }

    @Test fun stateChangesAndResourceConfigurationMonochromeTransitionsStillRepaint() = withMark { mark, image, source ->
        val identity = ReverbProductIdentity.Launcher(FIRST, circularOutline = true)
        val palette = ReverbDesign.theme(mark.context, false).palette
        mark.bind(identity, palette)
        place(mark, 64)
        val initial = source.created.single()
        val first = render(mark)
        val firstCount = initial.draws
        image.isPressed = true
        val pressed = render(mark)
        assertTrue("Stateful resource updates are still delivered", initial.draws > firstCount)
        assertFalse(first.sameAs(pressed))
        image.isPressed = false
        assertPixels(first, render(mark))
        initial.foreground.alpha = 96
        assertFalse(first.sameAs(render(mark)))
        initial.foreground.alpha = 255
        assertPixels(first, render(mark))

        mark.bind(ReverbProductIdentity.Monochrome(android.R.color.holo_red_dark), palette)
        assertNotNull("Monochrome keeps its palette filter", image.colorFilter)
        assertNotSame(initial.icon, image.drawable)
        val beforeReturn = source.created.size
        mark.bind(identity, palette)
        val returned = source.created.last()
        assertEquals(beforeReturn + 1, source.created.size)
        assertNotSame(initial.icon, image.drawable)
        assertSame(returned.icon, image.drawable)
        assertNull(image.colorFilter)
        assertNull(image.imageTintList)
        assertTrue("Returning Launcher still clears tint", returned.tintCalls > 0)
        assertPixels(first, render(mark))

        val beforeReplacement = source.created.size
        val secondIdentity = identity.copy(drawable = SECOND)
        mark.bind(secondIdentity, palette)
        val replacement = source.created.last()
        assertEquals(beforeReplacement + 1, source.created.size)
        assertSame(replacement.icon, image.drawable)
        assertNotSame(returned.icon, image.drawable)
        assertTrue(replacement.tintCalls > 0)
        val replaced = render(mark)
        assertMaskAndColor(replaced, replacement.color)
        assertFalse(first.sameAs(replaced))

        val beforeConfiguration = source.created.size
        val night = Configuration(source.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        @Suppress("DEPRECATION")
        source.updateConfiguration(night, source.displayMetrics)
        mark.dispatchConfigurationChanged(night)
        val configured = source.created.last()
        assertEquals(beforeConfiguration + 1, source.created.size)
        assertSame(configured.icon, image.drawable)
        assertNotSame(replacement.icon, configured.icon)
        assertTrue("ImageView applies the established null tint to replacement resources", configured.tintCalls > 0)
        val nightPixels = render(mark)
        assertMaskAndColor(nightPixels, configured.color)
        assertFalse(replaced.sameAs(nightPixels))
        val configDraws = configured.draws
        val configTints = configured.tintCalls
        mark.bind(secondIdentity, ReverbDesign.theme(mark.context, true).palette)
        assertEquals(beforeConfiguration + 1, source.created.size)
        assertSame(configured.icon, image.drawable)
        assertPixels(nightPixels, render(mark))
        assertEquals(configDraws, configured.draws)
        assertEquals(configTints, configured.tintCalls)
    }

    private fun withMark(block: (ReverbIdentityMarkView, ImageView, LauncherResources) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val base = activity.get()
        val source = LauncherResources(base.resources)
        val context = object : ContextWrapper(base) {
            override fun getResources(): Resources = source
            override fun getApplicationContext(): Context = this
        }
        val root = FrameLayout(base)
        val mark = ReverbIdentityMarkView(context)
        root.addView(mark, FrameLayout.LayoutParams(64, 64))
        base.setContentView(root)
        activity.visible().windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        try {
            assertTrue(mark.isAttachedToWindow)
            block(mark, mark.getChildAt(1) as ImageView, source)
        } finally {
            activity.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun place(mark: View, size: Int) {
        val exact = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        mark.measure(exact, exact)
        mark.layout(0, 0, size, size)
    }

    private fun render(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height,
        Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun assertPixels(expected: Bitmap, actual: Bitmap) =
        assertTrue("Mask, original colors and grain stay byte-identical", expected.sameAs(actual))

    private fun assertMaskAndColor(bitmap: Bitmap, color: Int) {
        assertEquals("The original adaptive/outer circular mask remains transparent", Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertEquals("Launcher foreground keeps its original resource color", color,
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
    }

    /** Only two fixture resources differ; ordinary shared resources resolve through the real assets. */
    @Suppress("DEPRECATION")
    private class LauncherResources(base: Resources) : Resources(base.assets, base.displayMetrics,
        Configuration(base.configuration)) {
        val created = mutableListOf<Artwork>()
        override fun getDrawable(id: Int, theme: Theme?): Drawable {
            if (id != FIRST && id != SECOND) return super.getDrawable(id, theme)
            val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val color = if (night) Color.YELLOW else if (id == FIRST) Color.CYAN else Color.MAGENTA
            return Artwork(this, color).also(created::add).icon
        }
    }

    private class Artwork(resources: Resources, val color: Int) {
        private val tile = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            setPixels(intArrayOf(0x40303030, 0x60202020, 0x60202020, 0x40303030), 0, 2, 0, 0, 2, 2)
        }
        private val bitmap = BitmapDrawable(resources, tile).apply {
            tileModeX = Shader.TileMode.REPEAT
            tileModeY = Shader.TileMode.REPEAT
            isFilterBitmap = true
        }
        private val background = CountedDrawable(LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(Color.DKGRAY) }, bitmap)))
        private val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.WHITE))
            addState(intArrayOf(), ColorDrawable(color))
        }
        val foreground = CountedDrawable(InsetDrawable(states, .30f))
        val icon = AdaptiveIconDrawable(background, foreground)
        val draws: Int get() = background.draws + foreground.draws
        val tintCalls: Int get() = background.tintCalls + foreground.tintCalls
    }

    /** Forward public drawable state/callbacks; count layer visits without replacing raster work. */
    private class CountedDrawable(private val child: Drawable) : Drawable(), Drawable.Callback {
        var draws = 0
            private set
        var tintCalls = 0
            private set
        init { child.callback = this }
        override fun draw(canvas: Canvas) { draws++; child.draw(canvas) }
        override fun onBoundsChange(bounds: Rect) { child.bounds = bounds }
        override fun getIntrinsicWidth() = 108
        override fun getIntrinsicHeight() = 108
        override fun setAlpha(alpha: Int) { child.alpha = alpha }
        override fun getAlpha(): Int = child.alpha
        override fun setColorFilter(filter: ColorFilter?) { child.colorFilter = filter }
        override fun setTintList(tint: ColorStateList?) { tintCalls++; child.setTintList(tint) }
        override fun isStateful(): Boolean = child.isStateful
        override fun onStateChange(state: IntArray): Boolean = child.setState(state)
        override fun onLevelChange(level: Int): Boolean = child.setLevel(level)
        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            val changed = super.setVisible(visible, restart)
            return child.setVisible(visible, restart) || changed
        }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = child.opacity
        override fun invalidateDrawable(who: Drawable) = invalidateSelf()
        override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) = scheduleSelf(what, whenMillis)
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
    }

    private companion object {
        // Public framework resource IDs are intercepted only by this fixture resource provider.
        const val FIRST = android.R.color.holo_blue_dark
        const val SECOND = android.R.color.holo_orange_dark
    }
}
