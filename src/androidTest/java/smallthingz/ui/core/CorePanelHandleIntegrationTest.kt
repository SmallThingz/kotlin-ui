package smallthingz.ui.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeActionSheetStyle
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Detached actual-view fixtures only: no activity, modal runtime, model or host bootstrap. */
@RunWith(AndroidJUnit4::class)
class CorePanelHandleIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun exactAndAtMostParentsKeepMarkInsideTightBounds() = instrumentation.runOnMainSync {
        val handle = CorePanelHandle(context, style())
        measureNatural(handle)
        assertTrue("Roomy allocation must retain a usable target", handle.measuredHeight >= 48)
        val mark = mark(handle)
        val roomyMarkWidth = mark.width
        assertTrue(roomyMarkWidth > 0 && roomyMarkWidth < handle.width)
        assertTrue(mark.height > 0 && mark.height < handle.height)
        assertTrue(kotlin.math.abs(mark.left - (handle.width - mark.right)) <= 1)
        assertTrue(kotlin.math.abs(mark.top - (handle.height - mark.bottom)) <= 1)

        // MATCH_PARENT supplies EXACTLY; WRAP_CONTENT supplies AT_MOST to the actual handle.
        // Both allocations are smaller than its normal mark and preferred touch target.
        for (childDimension in listOf(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) {
            val parent = FrameLayout(context)
            val bounded = CorePanelHandle(context, style()).apply { setPadding(2, 3, 2, 3) }
            parent.addView(bounded, FrameLayout.LayoutParams(childDimension, childDimension))
            for ((width, height) in listOf(19 to 11, 0 to 0)) {
                parent.measure(exact(width), exact(height))
                parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
                assertEquals(width, parent.width)
                assertEquals(height, parent.height)
                assertTrue(bounded.measuredWidth in 0..width)
                assertTrue(bounded.measuredHeight in 0..height)
                if (childDimension == ViewGroup.LayoutParams.MATCH_PARENT) {
                    assertEquals(width, bounded.measuredWidth)
                    assertEquals(height, bounded.measuredHeight)
                }
                assertContained(bounded, mark(bounded))
                if (width > 0 && height > 0) {
                    assertTrue(mark(bounded).width > 0)
                    assertTrue(mark(bounded).height > 0)
                    assertTrue(mark(bounded).width < roomyMarkWidth)
                }
            }
            parent.removeAllViews()
        }
    }

    @Test fun detachedStyleRebindRetainsIdentityAndMatchesFreshSharedPaint() = instrumentation.runOnMainSync {
        val initial = style().let { it.copy(palette = it.palette.copy(onSurfaceVariant = Color.RED)) }
        val next = initial.copy(palette = initial.palette.copy(onSurfaceVariant = Color.BLUE))
        val handle = CorePanelHandle(context, initial)
        val retainedMark = mark(handle)
        var clicks = 0
        handle.contentDescription = "Host-owned dismiss"
        handle.isFocusable = true
        handle.setOnClickListener { clicks++ }
        measureNatural(handle)
        val before = raster(handle)
        try {
            handle.updateStyle(next)
            measureNatural(handle)
            val reference = CorePanelHandle(context, next)
            measureNatural(reference)
            val actual = raster(handle)
            val expected = raster(reference)
            try {
                assertFalse("Detached palette rebind must change rendered ink", before.sameAs(actual))
                assertTrue("Rebound paint must match a fresh component with the same style", expected.sameAs(actual))
            } finally { actual.recycle(); expected.recycle() }
            assertSame(retainedMark, mark(handle))
            assertEquals("Host-owned dismiss", handle.contentDescription)
            assertTrue(handle.isFocusable)
            assertEquals(0, clicks)
            assertTrue(handle.performClick())
            assertEquals(1, clicks)

            val larger = next.copy(textDensity = Density(2f, 2f),
                recipe = next.recipe.copy(handleHeightDp = 6f, handleVerticalPaddingDp = 30f))
            val oldHeight = handle.measuredHeight
            val oldMarkWidth = retainedMark.measuredWidth
            val oldMarkHeight = retainedMark.measuredHeight
            handle.updateStyle(larger)
            measureNatural(handle)
            val largerReference = CorePanelHandle(context, larger)
            measureNatural(largerReference)
            assertSame(retainedMark, mark(handle))
            assertTrue(handle.measuredHeight > oldHeight)
            assertTrue(retainedMark.measuredWidth > oldMarkWidth)
            assertTrue(retainedMark.measuredHeight > oldMarkHeight)
            assertEquals(largerReference.measuredHeight, handle.measuredHeight)
            assertContained(handle, retainedMark)
            assertEquals(1, clicks)
        } finally { before.recycle() }
    }

    @Test fun goneDirectProbeSeesNewRecipeBeforeParentRestoresChild() = instrumentation.runOnMainSync {
        val initial = style()
        val handle = CorePanelHandle(context, initial)
        val parent = FrameLayout(context)
        parent.addView(handle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.measure(exact(240), atMost(400))
        val previousHeight = handle.measuredHeight
        assertTrue(previousHeight > 0)
        handle.visibility = View.GONE
        val expanded = initial.copy(recipe = initial.recipe.copy(handleHeightDp = 6f, handleVerticalPaddingDp = 30f))
        handle.updateStyle(expanded)
        parent.measure(exact(240), atMost(400))
        assertEquals("Ordinary parent measurement skips a GONE child", 0, parent.measuredHeight)
        // This is the direct probe required by an IME-budgeting host, before deciding visibility.
        handle.measure(ViewGroup.getChildMeasureSpec(exact(240), 0, handle.layoutParams.width), unspecified())
        val reference = CorePanelHandle(context, expanded)
        measureNatural(reference)
        assertEquals(View.GONE, handle.visibility)
        assertTrue("A probe must not reuse the old allocation", handle.measuredHeight > previousHeight)
        assertEquals(reference.measuredHeight, handle.measuredHeight)
        val probedHeight = handle.measuredHeight
        handle.visibility = View.VISIBLE
        parent.measure(exact(240), atMost(400))
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
        assertEquals(probedHeight, handle.height)
        assertEquals(probedHeight, parent.height)
        assertContained(handle, mark(handle))
        parent.removeAllViews()
    }

    @Test fun decorativeMarkNeverAcquiresChildFocusOrSemanticOwnership() = instrumentation.runOnMainSync {
        val initial = style()
        val handle = CorePanelHandle(context, initial)
        val retainedMark = mark(handle)
        handle.contentDescription = "Dismiss fixture panel"
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, handle.importantForAccessibility)
        assertFalse(handle.isFocusable)
        assertFalse(handle.isClickable)
        for (next in listOf(initial, initial.copy(palette = initial.palette.copy(onSurfaceVariant = Color.GREEN)))) {
            handle.updateStyle(next)
            measureNatural(handle)
            assertSame(retainedMark, mark(handle))
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, handle.descendantFocusability)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, retainedMark.importantForAccessibility)
            assertNull(retainedMark.contentDescription)
            assertFalse(retainedMark.isScreenReaderFocusable)
            assertFalse(retainedMark.isFocusable)
            assertFalse(retainedMark.isFocusableInTouchMode)
            assertFalse(retainedMark.isClickable)
            assertFalse(retainedMark.isLongClickable)
            assertFalse(retainedMark.requestFocus())
            assertFalse(retainedMark.performClick())
            val focusables = ArrayList<View>()
            handle.addFocusables(focusables, View.FOCUS_FORWARD)
            assertFalse(focusables.contains(retainedMark))
            assertEquals("Dismiss fixture panel", handle.contentDescription)
        }
    }

    private fun style(): NativeActionSheetStyle = ReverbDesign.appearance.modals.actionSheetStyle(
        Density(1f, 2f), createFontFamilyResolver(context),
        NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 30) { error("No resources") })

    private fun mark(handle: CorePanelHandle): View {
        assertEquals("One decorative mark; no extra input or semantic child", 1, handle.childCount)
        return handle.getChildAt(0)
    }

    private fun measureNatural(view: View) {
        view.measure(exact(240), unspecified())
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun assertContained(host: ViewGroup, child: View) {
        assertTrue(child.left >= 0 && child.top >= 0)
        assertTrue(child.right <= host.width && child.bottom <= host.height)
        assertTrue(child.measuredWidth >= 0 && child.measuredHeight >= 0)
        assertEquals(child.measuredWidth, child.width)
        assertEquals(child.measuredHeight, child.height)
    }

    private fun raster(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun atMost(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)
    private fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
}
