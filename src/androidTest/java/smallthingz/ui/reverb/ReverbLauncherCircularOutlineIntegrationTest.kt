package smallthingz.ui.reverb

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.withRetiringTestActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/** Source-only addition until the combined library is compiled and these Android cases run.
 * Software Canvas results and actual window PixelCopy results are deliberately separate.
 * The existing identity fixture is bound through ProductIdentity; no private method, painter,
 * reflection or fixed child-index selection is used. Resources below own the synthetic drawable fixtures.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
@SuppressLint("ResourceType") // FIXTURE_ICON is owned by FixtureResources, not an app R symbol.
class ReverbLauncherCircularOutlineIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun defaultIsUnchangedAndOptInClipsOnlyTheOuterCircleAcrossSizesAndDensities() = main {
        for (dpi in intArrayOf(160, 213, 421)) {
            val context = fixtureContext(dpi, transparentBrand = false) { ColorDrawable(ART) }
            val palette = ReverbDesign.theme(context, dark = false).palette
            val mark = ReverbIdentityMarkView(context)
            val parent = FrameLayout(context).apply { addView(mark) }
            assertFalse(ReverbProductIdentity.Launcher(FIXTURE_ICON).circularOutline)
            for (size in intArrayOf(48, 73, 131)) {
                mark.layoutParams = FrameLayout.LayoutParams(size, size)
                layout(parent, size, size)
                mark.bind(ReverbProductIdentity.Launcher(FIXTURE_ICON), palette)
                layout(parent, size, size)
                val reference = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(context.resources.getDrawable(FIXTURE_ICON, null))
                }
                layout(reference, size, size)
                withBitmaps(software(parent), software(reference)) { actual, expected ->
                    assertTrue("Default must remain the original ImageView pixels", actual.sameAs(expected))
                    assertEquals(ART, actual.getPixel(0, 0))
                }
                mark.bind(ReverbProductIdentity.Launcher(FIXTURE_ICON, circularOutline = true), palette)
                layout(parent, size, size)
                // Draw through a backgroundless parent, exercising ViewGroup's draw-skip path too.
                assertNull(mark.background)
                withBitmap(software(parent)) { image ->
                    assertCircleOutside(image, Color.TRANSPARENT)
                    assertEquals("Artwork center is not recolored", ART, image.getPixel(size / 2, size / 2))
                }
            }
        }
    }

    @Test fun fullAdaptiveResourceStaysUntintedAndItsLayersAreNotReplacedByDecoration() = main {
        val context = fixtureContext(213, transparentBrand = false) {
            AdaptiveIconDrawable(ColorDrawable(ART), GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(Color.RED, Color.YELLOW, Color.BLUE)))
        }
        val mark = ReverbIdentityMarkView(context)
        layout(mark, 97, 97)
        for (dark in listOf(false, true)) for (outline in listOf(false, true)) {
            mark.bind(ReverbProductIdentity.Launcher(FIXTURE_ICON, circularOutline = outline),
                ReverbDesign.theme(context, dark).palette)
            layout(mark, 97, 97)
            val image = launcherImage(mark)
            val supplied = context.fixtureResources.lastDrawable
            assertSame("Use the original resource Drawable as the ImageView content", supplied, image.drawable)
            assertTrue(image.drawable is AdaptiveIconDrawable)
            assertNull(image.imageTintList)
            assertNull(image.colorFilter)
            assertEquals(255, image.imageAlpha)
            assertEquals(ImageView.ScaleType.FIT_CENTER, image.scaleType)
            val reference = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(context.resources.getDrawable(FIXTURE_ICON, null))
            }
            layout(reference, image.width, image.height)
            val before = software(image)
            try {
                withBitmap(software(reference)) { assertTrue("Full adaptive pixels stay resource-owned", before.sameAs(it)) }
                withBitmap(software(mark)) { /* Parent decoration must not mutate the retained drawable. */ }
                withBitmap(software(image)) { assertTrue("Decoration cannot alter either adaptive layer", before.sameAs(it)) }
                assertSame(supplied, image.drawable)
            } finally { before.recycle() }
        }
    }

    @Test fun outlineMatchesPublicReverbOutlineAndFixedAmoledTokenAtFractionalDensity() = main {
        var opaqueTokenPixels = 0
        for (dpi in intArrayOf(160, 213, 421)) {
            // Transparent brand colors suppress its unrelated glyph/plate. The reference outline
            // still comes only from the public Reverb API, never from a copied stroke algorithm.
            val context = fixtureContext(dpi, transparentBrand = true) { ColorDrawable(Color.TRANSPARENT) }
            val mark = ReverbIdentityMarkView(context)
            val reference = ReverbBrandMarkView(context)
            val opaqueContext = fixtureContext(dpi, transparentBrand = false) { ColorDrawable(ART) }
            val opaqueMark = ReverbIdentityMarkView(opaqueContext)
            for (size in intArrayOf(48, 73, 131)) {
                layout(mark, size, size)
                layout(reference, size, size)
                reference.setReverbBrandOutline(false)
                withBitmap(software(reference)) { image -> assertAllTransparent(image) }
                reference.setReverbBrandOutline(true)
                val expected = software(reference)
                try {
                    var visibleOutlinePixels = 0
                    for (y in 0 until size) for (x in 0 until size) {
                        val pixel = expected.getPixel(x, y)
                        if (Color.alpha(pixel) > 0) visibleOutlinePixels++
                        if (Color.alpha(pixel) == 255) {
                            assertEquals(NativeDarkSurfacePreset.Amoled.borderColor, pixel)
                            opaqueTokenPixels++
                        }
                    }
                    assertTrue("Every size must have a visible outline", visibleOutlinePixels > 0)
                    for (dark in listOf(false, true)) {
                        mark.bind(ReverbProductIdentity.Launcher(FIXTURE_ICON, circularOutline = true),
                            ReverbDesign.theme(context, dark).palette)
                        layout(mark, size, size)
                        withBitmap(software(mark)) { actual ->
                            assertTrue("Launcher and Reverb outline geometry/color differ at dpi=$dpi size=$size dark=$dark",
                                actual.sameAs(expected))
                        }
                        opaqueMark.bind(ReverbProductIdentity.Launcher(FIXTURE_ICON, circularOutline = true),
                            ReverbDesign.theme(opaqueContext, dark).palette)
                        layout(opaqueMark, size, size)
                        withBitmap(software(opaqueMark)) { actual ->
                            assertEquals(ART, actual.getPixel(size / 2, size / 2))
                            for (y in 0 until size) for (x in 0 until size)
                                if (Color.alpha(expected.getPixel(x, y)) == 255)
                                    assertEquals("Outline must be above opaque artwork", expected.getPixel(x, y), actual.getPixel(x, y))
                        }
                    }
                } finally { expected.recycle() }
            }
        }
        // Thin antialiased strokes need not have an opaque pixel at every size; the higher
        // density cases provide interior samples. Exact whole-raster parity applies to all cases.
        assertTrue("Outline must visibly paint the fixed AMOLED token", opaqueTokenPixels > 0)
    }

    @Test fun rebindToDefaultMonochromeOrReverbRemovesAllAddedDecoration() = main {
        val context = fixtureContext(421, transparentBrand = false) { ColorDrawable(ART) }
        for (dark in listOf(false, true)) {
            val palette = ReverbDesign.theme(context, dark).palette
            val retained = ReverbIdentityMarkView(context)
            layout(retained, 101, 101)
            for (identity in listOf(ReverbProductIdentity.Launcher(FIXTURE_ICON),
                ReverbProductIdentity.Monochrome(FIXTURE_ICON), ReverbProductIdentity.Reverb)) {
                retained.bind(ReverbProductIdentity.Launcher(FIXTURE_ICON, circularOutline = true), palette)
                layout(retained, 101, 101)
                withBitmap(software(retained)) { assertCircleOutside(it, Color.TRANSPARENT) }
                retained.bind(identity, palette)
                layout(retained, 101, 101)
                val fresh = ReverbIdentityMarkView(context)
                fresh.bind(identity, palette)
                layout(fresh, 101, 101)
                withBitmaps(software(retained), software(fresh)) { actual, expected ->
                    assertTrue("Rebinding must equal a fresh undecorated $identity", actual.sameAs(expected))
                }
                if (identity != ReverbProductIdentity.Reverb) {
                    withBitmap(software(retained)) { assertEquals("No stale circular clipping", 255, Color.alpha(it.getPixel(0, 0))) }
                }
            }
        }
    }

    @Test fun publicProductHeaderKeepsTheAboutActionAndUsesTheOriginalImageView() = main {
        val context = fixtureContext(213, transparentBrand = false) { ColorDrawable(ART) }
        val theme = ReverbDesign.theme(context, dark = false)
        val header = ReverbProductTopBar(context, theme)
        var aboutCalls = 0
        fun bind(outline: Boolean) = header.bind(theme,
            identity = ReverbProductIdentity.Launcher(FIXTURE_ICON, circularOutline = outline),
            aboutLabel = "Outline fixture About", onAbout = { aboutCalls++ }, browser = null,
            settings = ReverbHeaderAction("Outline fixture Settings", {}))
        bind(false)
        layoutWidth(header, 480)
        val image = launcherImage(header)
        bind(true)
        layoutWidth(header, 480)
        assertSame("Opt-in must retain the ImageView renderer", image, launcherImage(header))
        assertNull(image.imageTintList)
        assertNull(image.colorFilter)
        val action = visibleDescendants(header).single {
            it.isClickable && it.contentDescription?.toString() == "Outline fixture About"
        }
        assertFalse(action.isAttachedToWindow)
        assertFalse("Detached About action must reject invocation", action.performClick())
        assertEquals(0, aboutCalls)
    }

    @Test fun attachedPublicProductHeaderInvokesAboutWithAndWithoutTheOutline() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: FrameLayout? = null
        withRetiringTestActivity(activity, root = { host }) {
            lateinit var header: ReverbProductTopBar
            lateinit var context: FixtureContext
            var aboutCalls = 0
            var expectedCalls = 0
            main {
                context = fixtureContext(213, transparentBrand = false, base = activity) { ColorDrawable(ART) }
                header = ReverbProductTopBar(context, ReverbDesign.theme(context, dark = false))
                host = FrameLayout(activity).apply {
                    addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                activity.setContentView(requireNotNull(host), ViewGroup.LayoutParams(480, 160))
            }
            for (outlined in listOf(false, true)) {
                main {
                    header.bind(ReverbDesign.theme(context, dark = false),
                        identity = ReverbProductIdentity.Launcher(FIXTURE_ICON, circularOutline = outlined),
                        aboutLabel = "Attached outline fixture About", onAbout = { aboutCalls++ }, browser = null,
                        settings = ReverbHeaderAction("Attached outline fixture Settings", {}))
                }
                val deadline = SystemClock.uptimeMillis() + 5_000
                var invoked = false
                while (!invoked && SystemClock.uptimeMillis() < deadline) {
                    main {
                        if (header.isAttachedToWindow && header.isShown && header.isLaidOut &&
                            !header.isLayoutRequested) {
                            val action = visibleDescendants(header).single {
                                it.isClickable && it.contentDescription?.toString() == "Attached outline fixture About"
                            }
                            assertTrue(action.isAttachedToWindow && action.isShown)
                            assertTrue(action.width > 0 && action.height > 0)
                            assertEquals(expectedCalls, aboutCalls)
                            assertTrue("Attached About action must accept invocation", action.performClick())
                            expectedCalls++
                            assertEquals(expectedCalls, aboutCalls)
                            invoked = true
                        }
                    }
                    if (!invoked) SystemClock.sleep(16)
                }
                assertTrue("Header did not become attached, shown and laid out", invoked)
            }
        }
    }

    @Test fun actualHardwareWindowAndDetachedHardwareCanvasClipAndRemoveTheOptIn() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: LinearLayout? = null
        withRetiringTestActivity(activity, root = { host }) {
            lateinit var attached: ReverbIdentityMarkView
            lateinit var plain: ReverbIdentityMarkView
            lateinit var detached: ReverbIdentityMarkView
            lateinit var drawer: DetachedDrawer
            lateinit var context: FixtureContext
            main {
                context = fixtureContext(213, transparentBrand = false, base = activity) { ColorDrawable(ART) }
                attached = ReverbIdentityMarkView(context)
                plain = ReverbIdentityMarkView(context)
                detached = ReverbIdentityMarkView(context)
                layout(detached, 64, 64)
                drawer = DetachedDrawer(context, detached)
                host = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(8, 8, 8, 8)
                    if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
                    for (view in listOf(attached, plain, drawer)) addView(view, LinearLayout.LayoutParams(64, 64))
                }
                activity.setContentView(requireNotNull(host), ViewGroup.LayoutParams(208, 80))
            }
            for ((outlined, marker) in listOf(true to Color.MAGENTA, false to Color.CYAN)) {
                main {
                    val palette = ReverbDesign.theme(context, dark = outlined).palette
                    attached.bind(ReverbProductIdentity.Launcher(FIXTURE_ICON, circularOutline = outlined), palette)
                    detached.bind(ReverbProductIdentity.Launcher(FIXTURE_ICON, circularOutline = outlined), palette)
                    layout(detached, 64, 64)
                    plain.bind(ReverbProductIdentity.Launcher(FIXTURE_ICON), palette)
                    requireNotNull(host).setBackgroundColor(marker)
                    drawer.invalidate()
                }
                val root = requireNotNull(host)
                withBitmap(windowPixels(activity.window, root, marker)) { image ->
                    val origins = ArrayList<Triple<Int, Int, Boolean>>()
                    main {
                        assertTrue("This case requires actual attached hardware drawing", root.isHardwareAccelerated)
                        assertTrue(attached.isAttachedToWindow && attached.isHardwareAccelerated)
                        assertFalse(detached.isAttachedToWindow)
                        assertTrue("Detached mark must have been drawn on a real hardware Canvas", drawer.sawHardwareCanvas)
                        for (view in listOf(attached, plain, drawer))
                            origins += Triple(view.left, view.top, view !== plain)
                    }
                    for ((left, top, optInFixture) in origins) {
                        assertEquals(ART, image.getPixel(left + 32, top + 32))
                        val expectedCorner = if (outlined && optInFixture) marker else ART
                        for ((x, y) in listOf(0 to 0, 63 to 0, 0 to 63, 63 to 63))
                            assertEquals("Hardware corner: outline=$outlined optInFixture=$optInFixture", expectedCorner,
                                image.getPixel(left + x, top + y))
                    }
                }
            }
        }
    }

    private class DetachedDrawer(context: Context, private val mark: View) : View(context) {
        var sawHardwareCanvas = false
            private set
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            sawHardwareCanvas = sawHardwareCanvas || canvas.isHardwareAccelerated
            mark.draw(canvas)
        }
    }

    private class FixtureContext(base: Context, val fixtureResources: FixtureResources) : ContextWrapper(base) {
        override fun getResources(): Resources = fixtureResources
        override fun getApplicationContext(): Context = this
    }

    @Suppress("DEPRECATION")
    private class FixtureResources(base: Resources, private val transparentBrand: Boolean,
        private val createIcon: () -> Drawable) : Resources(base.assets, base.displayMetrics, base.configuration) {
        var lastDrawable: Drawable? = null
            private set
        override fun getDrawable(id: Int, theme: Theme?): Drawable =
            if (id == FIXTURE_ICON) createIcon().also { lastDrawable = it } else super.getDrawable(id, theme)
        override fun getColor(id: Int, theme: Theme?): Int =
            if (transparentBrand && id in intArrayOf(R.color.reverb_brand_background,
                    R.color.reverb_brand_accent, R.color.reverb_brand_main)) Color.TRANSPARENT
            else super.getColor(id, theme)
    }

    private fun fixtureContext(dpi: Int, transparentBrand: Boolean,
        base: Context = instrumentation.targetContext, createIcon: () -> Drawable): FixtureContext {
        val configured = base.createConfigurationContext(Configuration(base.resources.configuration).apply { densityDpi = dpi })
        return FixtureContext(configured, FixtureResources(configured.resources, transparentBrand, createIcon))
    }

    private fun visibleDescendants(view: View): List<View> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val found = ArrayList<View>(); found += view
        if (view is ViewGroup) for (i in 0 until view.childCount) found += visibleDescendants(view.getChildAt(i))
        return found
    }

    private fun launcherImage(root: View): ImageView = visibleDescendants(root)
        .filterIsInstance<ImageView>().single { it.drawable != null }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun layoutWidth(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun software(view: View): Bitmap {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            assertFalse("Bitmap-backed fixture is software only", canvas.isHardwareAccelerated)
            view.draw(canvas)
            return image
        } catch (failure: Throwable) {
            image.recycle()
            throw failure
        }
    }

    private fun assertAllTransparent(image: Bitmap) {
        for (y in 0 until image.height) for (x in 0 until image.width) assertEquals(0, Color.alpha(image.getPixel(x, y)))
    }

    private fun assertCircleOutside(image: Bitmap, outside: Int) {
        assertEquals(image.width, image.height)
        val radius = image.width / 2.0
        for (y in 0 until image.height) for (x in 0 until image.width)
            if (hypot(x + .5 - radius, y + .5 - radius) >= radius + 1.0)
                assertEquals("Outside circle at ($x,$y)", outside, image.getPixel(x, y))
    }

    private fun windowPixels(window: Window, root: View, marker: Int): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var source: Rect? = null
            main {
                if (root.isLaidOut && root.width > 0 && root.height > 0) {
                    val location = IntArray(2).also(root::getLocationInWindow)
                    source = Rect(location[0], location[1], location[0] + root.width, location[1] + root.height)
                }
            }
            val bounds = source
            if (bounds == null) { SystemClock.sleep(16); continue }
            val image = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            val ownership = Any()
            var abandoned = false
            var completed = false
            var result = PixelCopy.ERROR_UNKNOWN
            try {
                main {
                    PixelCopy.request(window, bounds, image, { status ->
                        synchronized(ownership) {
                            result = status
                            completed = true
                            if (abandoned) image.recycle()
                        }
                        done.countDown()
                    }, Handler(Looper.getMainLooper()))
                }
            } catch (failure: Throwable) {
                image.recycle()
                throw failure
            }
            if (!done.await((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
                synchronized(ownership) {
                    abandoned = true // Never recycle while PixelCopy may still write.
                    if (completed) image.recycle()
                }
                throw AssertionError("PixelCopy callback did not complete")
            }
            if (result == PixelCopy.SUCCESS && image.getPixel(2, 2) == marker) return image
            image.recycle()
            assertTrue("Unexpected PixelCopy error $result", result == PixelCopy.SUCCESS || result == PixelCopy.ERROR_SOURCE_NO_DATA)
            SystemClock.sleep(16)
        }
        throw AssertionError("No actual window frame with the current phase marker")
    }

    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private inline fun withBitmap(image: Bitmap, action: (Bitmap) -> Unit) {
        try { action(image) } finally { image.recycle() }
    }

    private inline fun withBitmaps(first: Bitmap, second: Bitmap, action: (Bitmap, Bitmap) -> Unit) {
        try { action(first, second) } finally { first.recycle(); second.recycle() }
    }

    companion object {
        private const val FIXTURE_ICON = 0x6f010001
        private const val ART = 0xff1395d8.toInt()
    }
}
