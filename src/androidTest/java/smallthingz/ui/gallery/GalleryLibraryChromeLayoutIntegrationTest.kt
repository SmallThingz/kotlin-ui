package smallthingz.ui.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class GalleryLibraryChromeLayoutIntegrationTest {
    @Test fun goneAccessReflowsFilterAndTraversalWithoutReplacingChildren() = withActivity { activity ->
        val chrome = TestChrome(activity).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        chrome.bind()
        activity.setContentView(chrome)
        assertTrue(chrome.isAttachedToWindow)

        val topRowHeight = dp(activity, 48) + 2 * dp(activity, 2)
        val accessRowHeight = dp(activity, 4) + dp(activity, 36)
        val filterHeight = dp(activity, 48)
        val originalChildren = List(chrome.childCount) { chrome.getChildAt(it) }

        fun assertReservedRows() {
            place(chrome, dp(activity, 360))
            assertEquals(topRowHeight + accessRowHeight + filterHeight, chrome.height)
            assertEquals(topRowHeight + accessRowHeight, chrome.filter.top)
            assertTraversal(chrome.leading, chrome.heading, chrome.action, chrome.access, chrome.filter)
        }

        assertReservedRows()
        chrome.access.visibility = View.GONE
        place(chrome, dp(activity, 360))
        assertEquals(topRowHeight, chrome.height)
        assertEquals(chrome.leading.top, chrome.filter.top)
        assertEquals(chrome.leading.right + dp(activity, 4), chrome.filter.left)
        assertTraversal(chrome.leading, chrome.filter, chrome.heading, chrome.action)

        // At this width the filter still needs a row, but its traversal must skip the access ID.
        place(chrome, dp(activity, 240))
        assertEquals(topRowHeight + filterHeight, chrome.height)
        assertEquals(topRowHeight, chrome.filter.top)
        assertTraversal(chrome.leading, chrome.heading, chrome.action, chrome.filter)

        chrome.access.visibility = View.VISIBLE
        assertReservedRows()
        chrome.access.visibility = View.INVISIBLE
        assertReservedRows()
        assertEquals(originalChildren.size, chrome.childCount)
        originalChildren.forEachIndexed { index, child -> assertSame(child, chrome.getChildAt(index)) }
        assertSame(chrome, chrome.access.parent)
    }

    @Test fun emptyChromeMeasuresAndDeferredBindingPopulatesTheSameInstance() = withActivity { activity ->
        val empty = GalleryLibraryChromeLayout(activity).apply {
            minimumWidth = dp(activity, 80)
            minimumHeight = dp(activity, 28)
        }
        val widthLimit = dp(activity, 60)
        empty.measure(View.MeasureSpec.makeMeasureSpec(widthLimit, View.MeasureSpec.AT_MOST), unspecified())
        empty.layout(0, 0, empty.measuredWidth, empty.measuredHeight)
        assertEquals(widthLimit, empty.width)
        assertEquals(dp(activity, 28), empty.height)
        assertEquals(0, empty.childCount)

        val chrome = TestChrome(activity)
        activity.setContentView(chrome)
        assertTrue(chrome.isAttachedToWindow)
        val width = dp(activity, 360)
        place(chrome, width)
        assertEquals(width, chrome.width)
        assertEquals(0, chrome.height)
        assertEquals(0, chrome.childCount)

        val deferredMinimum = dp(activity, 28)
        chrome.minimumHeight = deferredMinimum
        chrome.dispatchConfigurationChanged(Configuration(activity.resources.configuration))
        place(chrome, width)
        assertEquals(deferredMinimum, chrome.height)
        assertEquals(0, chrome.childCount)

        chrome.bind()
        place(chrome, width)
        assertEquals(dp(activity, 52), chrome.minimumHeight)
        assertTrue(chrome.heading.width > 0 && chrome.heading.height > 0)
        assertEquals(dp(activity, 48), chrome.action.width)
        assertEquals(dp(activity, 48), chrome.action.height)
        assertSame(chrome, chrome.heading.parent)
        assertSame(chrome, chrome.action.parent)
    }

    private class TestChrome(context: Context) : GalleryLibraryChromeLayout(context) {
        val leading = slot(context, 48, 48)
        val heading = slot(context, 80, 24)
        val action = slot(context, 48, 48)
        val access = slot(context, 240, 36)
        val filter = slot(context, 48, 48)

        fun bind() = bindChromeViews(leading, heading, listOf(action), access, filter, brandedRoot = true)
    }

    private fun assertTraversal(vararg views: View) {
        for (index in 1 until views.size) {
            assertEquals(views[index - 1].id, views[index].accessibilityTraversalAfter)
        }
    }

    private fun place(chrome: GalleryLibraryChromeLayout, width: Int) {
        chrome.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), unspecified())
        chrome.layout(0, 0, chrome.measuredWidth, chrome.measuredHeight)
    }

    private fun withActivity(action: (Activity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            main { action(activity) }
        } finally {
            main { activity.finish() }
        }
    }

    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private companion object {
        fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

        fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        fun slot(context: Context, width: Int, height: Int) = object : View(context) {
            init { id = generateViewId() }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(
                    resolveSize(dp(context, width), widthMeasureSpec),
                    resolveSize(dp(context, height), heightMeasureSpec),
                )
            }
        }
    }
}
