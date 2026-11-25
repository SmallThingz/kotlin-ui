package smallthingz.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Mounted View queries/layout/input checks, not frame timing or pixel equivalence to a baseline. */
@RunWith(AndroidJUnit4::class)
class NativeButtonOutlineLayoutIntegrationTest {
    @Test fun ownedQueriesRepeatOnlyForChangedGeometryOrDensity() = withButton { f ->
        f.layout(320, 96)
        assertTrue(f.meter.queries > 0)
        assertSame(f.owned, f.content.outlineProvider)
        // Calibration counts actual synchronous Android getOutline calls without replacing shape.
        f.meter.queries = 0
        f.button.invalidateOutline()
        f.content.invalidateOutline()
        assertEquals(2, f.meter.queries)
        f.meter.queries = 0
        f.layout()
        assertEquals("external queries dirty both layout keys", 2, f.meter.queries)
        f.expectStable()

        for ((w, h) in listOf(336 to 96, 336 to 112)) {
            f.meter.queries = 0
            f.layout(w, h)
            assertTrue("changed size must query owned outlines", f.meter.queries >= 2)
            f.expectStable()
            f.assertOutline(f.button, 8f)
            f.assertOutline(f.content, 8f)
        }
        f.button.bindTypography(TextStyle.Default, QueryDensity(2f, 1f, f.meter), f.resolver)
        f.meter.queries = 0
        f.layout()
        assertTrue("equal bounds/new density", f.meter.queries >= 2)
        f.assertOutline(f.button, 16f)
        f.assertOutline(f.content, 16f)
        f.expectStable()

        // Density is a key even when the computed radius is clamped to the same half-height.
        f.layout(336, 12)
        f.button.bindTypography(TextStyle.Default, QueryDensity(3f, 1f, f.meter), f.resolver)
        f.meter.queries = 0
        f.layout()
        assertTrue("clamped radius does not hide density changes", f.meter.queries >= 2)
        f.assertOutline(f.button, 6f)
        f.expectStable()
    }

    @Test fun customButtonAndContentProvidersRemainIndependentAndReturnToOwnedRefreshes() = withButton { f ->
        f.layout(320, 96)
        f.expectStable()
        for (target in listOf<View>(f.button, f.content)) {
            val custom = object : ViewOutlineProvider() {
                var queries = 0
                override fun getOutline(view: View, outline: Outline) {
                    queries++
                    // Deliberately includes a nongeometry dependency, outside the owned contract.
                    outline.setRoundRect(0, 0, view.width, view.height, if (view.isPressed) 3f else 7f)
                }
            }
            target.outlineProvider = custom
            repeat(3) { index ->
                target.isPressed = index % 2 == 0
                val before = custom.queries
                f.meter.queries = 0
                f.layout()
                assertEquals("custom provider queried every layout", before + 1, custom.queries)
                assertEquals("other owned outline retains its separate key", 0, f.meter.queries)
            }
            target.isPressed = false
            target.outlineProvider = f.owned
            // The platform setter already invalidates; the next layout must also clear fallback state.
            f.meter.queries = 0
            f.layout()
            assertEquals("only the returning target invalidates", 1, f.meter.queries)
            f.expectStable()
            target.outlineProvider = null
            f.layout()
            target.outlineProvider = f.owned
            f.meter.queries = 0
            f.layout()
            assertEquals("null provider also resets only its own key", 1, f.meter.queries)
            f.expectStable()
        }
        // Replacement and return without an intervening layout still gets the platform invalidation.
        val replacement = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setEmpty()
        }
        f.button.outlineProvider = replacement
        f.meter.queries = 0
        f.button.outlineProvider = f.owned
        assertEquals(1, f.meter.queries)
        f.assertOutline(f.button, 8f)
    }

    @Test fun intermediateExternalOutlineRefreshCannotReuseAnOlderLayoutKey() = withButton { f ->
        f.layout(320, 96)
        f.expectStable()
        for (target in listOf<View>(f.button, f.content)) {
            f.button.bindTypography(TextStyle.Default, QueryDensity(2f, 1f, f.meter), f.resolver)
            f.meter.queries = 0
            target.invalidateOutline() // Android receives radius16 between the two layouts.
            assertEquals(1, f.meter.queries)
            f.button.bindTypography(TextStyle.Default, QueryDensity(1f, 1f, f.meter), f.resolver)
            f.meter.queries = 0
            f.layout()
            assertEquals("returning to density1 must replace the intermediate outline", 1, f.meter.queries)
            f.assertOutline(target, 8f)
            f.expectStable()
        }
    }

    @Test fun failedReturnToOwnedProviderCannotReuseAPreviousLayoutKey() = withButton { f ->
        f.layout(320, 96)
        f.expectStable()
        for (target in listOf<View>(f.button, f.content)) {
            target.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, 3f)
            }
            f.meter.failOwnedQuery = true
            try {
                target.outlineProvider = f.owned
                throw AssertionError("owned callback must fail during provider replacement")
            } catch (expected: IllegalStateException) {
                assertEquals("owned outline query", expected.message)
            } finally {
                f.meter.failOwnedQuery = false
            }
            assertSame(f.owned, target.outlineProvider)
            f.meter.queries = 0
            f.layout()
            assertEquals("recovering owned provider must replace the retained custom outline", 1, f.meter.queries)
            f.assertOutline(target, 8f)
            f.expectStable()
        }
    }

    @Test fun privateFontAndRtlContextsKeepLayoutClippingActionsAndRole() {
        for (fontScale in listOf(1f, 2f)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            withButton(fontScale, direction) { f ->
                val glyph = ColorDrawable(Color.WHITE)
                f.button.setLeadingIcon(glyph)
                f.layout(420, 140)
                f.expectStable()
                assertEquals(2, f.button.childCount)
                assertTrue(f.content.clipToOutline)
                assertTrue(f.content.clipChildren && f.content.clipToPadding)
                assertEquals(Rect(0, 0, 420, 140), bounds(f.content))
                assertEquals(Rect(0, 0, 420, 140), bounds(f.button.getChildAt(1)))
                assertTrue(f.button.label.width > 0 && f.button.label.height > 0)
                assertTrue(f.button.label.left >= 0 && f.button.label.right <= f.content.width)
                if (direction == View.LAYOUT_DIRECTION_RTL) {
                    assertTrue(f.button.label.right < glyph.bounds.left)
                } else {
                    assertTrue(glyph.bounds.right < f.button.label.left)
                }
                // Standalone accessibility publishes the role on the public button node.
                val role = checkNotNull(f.button.createAccessibilityNodeInfo())
                try { assertEquals("android.widget.Button", role.className.toString()) }
                finally { role.recycle() }
                f.button.isFocusableInTouchMode = true
                assertTrue(f.button.requestFocus())
                f.expectStable()
                assertTrue(f.button.hasFocus())
                assertEquals(0f, f.button.elevation, 0f)
                val down = SystemClock.uptimeMillis()
                val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 210f, 70f, 0)
                val release = MotionEvent.obtain(down, down + 1, MotionEvent.ACTION_UP, 210f, 70f, 0)
                try {
                    assertTrue(f.button.onTouchEvent(press))
                    f.expectStable() // Layout still forwards input.measured during an active press.
                    assertTrue(f.button.onTouchEvent(release))
                } finally { press.recycle(); release.recycle() }
                assertEquals(1, f.clicks)
                f.button.bind("Outline test", false, Color.BLUE, Color.WHITE)
                f.layout()
                assertFalse(f.button.isEnabled)
                assertFalse(f.button.isFocusable)
                assertFalse(f.button.performClick())
                assertEquals(1, f.clicks)
                f.button.bind("Outline test", true, Color.BLUE, Color.WHITE)
                f.layout()
                assertTrue(f.button.isEnabled && f.button.isFocusable)
                assertTrue(f.button.performClick())
                assertEquals(2, f.clicks)
                assertEquals(0f, f.button.elevation, 0f)
                // Exercise unchanged software draw/clip, without treating these pixels as device fit.
                val bitmap = Bitmap.createBitmap(420, 140, Bitmap.Config.ARGB_8888)
                try {
                    f.button.draw(Canvas(bitmap))
                    assertTrue(Color.alpha(bitmap.getPixel(210, 70)) > 0)
                } finally { bitmap.recycle() }
            }
        }
    }

    private class QueryMeter {
        var providerClass = ""
        var queries = 0
        var failOwnedQuery = false
        fun sampleDensity(value: Float): Float {
            // Test-only observation, no production hooks or substitute provider identity.
            if (Thread.currentThread().stackTrace.any { it.className == providerClass && it.methodName == "getOutline" }) {
                queries++
                check(!failOwnedQuery) { "owned outline query" }
            }
            return value
        }
    }

    private class QueryDensity(private val value: Float, override val fontScale: Float, private val meter: QueryMeter) : Density {
        override val density: Float get() = meter.sampleDensity(value)
    }

    private class Fixture(context: Context, fontScale: Float, direction: Int) {
        val meter = QueryMeter()
        val resolver = createFontFamilyResolver(context)
        var clicks = 0
        val button = NativeMaterialButtonView(context, false, TextStyle.Default,
            QueryDensity(1f, fontScale, meter), resolver,
            NativeMaterialButtonStyle(horizontalPaddingDp = 12, radiusDp = 8f, alignContentStart = true)) { clicks++ }
        val content = button.getChildAt(0) as ViewGroup
        val owned = checkNotNull(button.outlineProvider)
        init {
            meter.providerClass = owned.javaClass.name
            button.layoutDirection = direction
            button.bind("Outline test", true, Color.BLUE, Color.WHITE)
        }
        fun layout(w: Int = button.width, h: Int = button.height) {
            button.forceLayout()
            button.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            button.layout(0, 0, w, h)
        }
        fun expectStable() {
            meter.queries = 0
            layout()
            assertEquals("same owned outline geometry", 0, meter.queries)
        }
        fun assertOutline(view: View, expectedRadius: Float) {
            val outline = Outline()
            checkNotNull(view.outlineProvider).getOutline(view, outline)
            val rect = Rect()
            assertTrue(outline.getRect(rect))
            assertEquals(Rect(0, 0, view.width, view.height), rect)
            assertEquals(expectedRadius, outline.radius, 0f)
            // Direct provider sampling is also external to layout, so restore its key explicitly.
            meter.queries = 0
            layout()
            assertEquals("sampling dirties only this target's layout key", 1, meter.queries)
        }
    }

    private fun withButton(fontScale: Float = 1f, direction: Int = View.LAYOUT_DIRECTION_LTR, assertions: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        var fixture: Fixture? = null
        try {
            instrumentation.runOnMainSync {
                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                    densityDpi = 160
                    this.fontScale = fontScale
                })
                fixture = Fixture(context, fontScale, direction)
                activity.setContentView(FrameLayout(activity).apply {
                    addView(fixture!!.button, FrameLayout.LayoutParams(420, 140))
                })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val f = checkNotNull(fixture)
                assertTrue(f.button.isAttachedToWindow && f.content.isAttachedToWindow)
                assertions(f)
            }
        } finally {
            instrumentation.runOnMainSync {
                try { fixture?.button?.close() } finally { activity.finish() }
            }
        }
    }

    private companion object {
        fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)
    }
}
