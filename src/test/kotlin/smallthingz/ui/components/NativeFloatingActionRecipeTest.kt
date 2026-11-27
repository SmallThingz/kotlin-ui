package smallthingz.ui.components

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.injector.InjectAppearance
import smallthingz.ui.injector.InjectCompositeHostApplication
import smallthingz.ui.injector.InjectDesign

/** Real shared Add button, native ScrollView and measured rows; no synthetic action height. */
@RunWith(RobolectricTestRunner::class)
@Config(application = InjectCompositeHostApplication::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeFloatingActionRecipeTest {
    @Test fun normalButtonKeepsIts20dpPlacementAnd96dpTailInBothDirections() {
        for (rtl in listOf(false, true)) withFixture(360, 1f, rtl) { f ->
            f.bind()
            f.settleLayout()
            assertEquals(96, f.content.paddingBottom)
            f.assertButtonInsideMargins()
            f.scrollToLastRow()
            f.assertLastRowAboveAction()
            assertTrue(f.action.view.button.performClick())
            assertEquals(1, f.clicks)
        }
    }

    @Test fun actualAddButtonAtNarrowFont2OutgrowsFixedTailAndMeasuredClearanceRevealsLastRow() {
        for (rtl in listOf(false, true)) withFixture(180, 2f, rtl) { f ->
            // Same real Add button and bounded side margins; only the legacy fixed tail differs.
            f.content.setPadding(16, 12, 16, 96)
            f.settleLayout()
            f.assertButtonInsideMargins()
            assertTrue("The actual wrapped Add plugin button must outgrow the old96dp tail",
                f.action.view.height + 20 > 96)
            f.scrollToLastRow()
            assertTrue("Fixed96 leaves the last row behind the wrapped action even at maximum scroll",
                f.bounds(f.lastRow).bottom > f.action.view.top)

            f.bind()
            f.settleLayout()
            assertEquals(f.action.view.height + 20 + NativeScreenSpacing.CONTROL_GAP_DP,
                f.content.paddingBottom)
            f.assertButtonInsideMargins()
            f.scrollToLastRow()
            f.assertLastRowAboveAction()
            assertTrue(f.action.view.button.performClick())
            assertEquals(1, f.clicks)
        }
    }

    @Test fun resizeSettlesWithoutLayoutLoopAndClosingOnlyRetiresThePaddingBinding() =
        withFixture(180, 2f, false) { f ->
            f.bind()
            f.settleLayout()
            assertTrue(f.content.paddingBottom > 96)
            f.width = 360
            f.settleLayout()
            assertEquals("Wider unwrapped action restores the normal tail", 96, f.content.paddingBottom)
            f.scrollToLastRow()
            f.assertLastRowAboveAction()
            val requests = f.content.paddingLayoutRequests
            f.layout()
            assertEquals("Stable geometry never re-requests layout", requests, f.content.paddingLayoutRequests)

            val binding = checkNotNull(f.binding)
            binding.close()
            binding.close()
            assertEquals("Restore the caller's original page padding", 24, f.content.paddingBottom)
            f.width = 180
            f.settleLayout()
            assertEquals("A retired listener cannot publish padding on another size change", 24, f.content.paddingBottom)
            assertSame(f.root, f.action.view.parent)
            assertTrue("Placement retirement does not close the caller's button", f.action.view.button.performClick())
            assertEquals(1, f.clicks)
        }

    private fun withFixture(width: Int, font: Float, rtl: Boolean, block: (Fixture) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val fixture = Fixture(controller.get(), width, font, rtl)
        controller.windowFocusChanged(true)
        try { block(fixture) }
        finally {
            fixture.binding?.close()
            fixture.action.close()
            controller.pause().stop().destroy()
        }
    }

    private class Fixture(activity: Activity, var width: Int, font: Float, rtl: Boolean) {
        private val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            fontScale = font
            densityDpi = 160
        })
        private val direction = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        private val ui = InjectAppearance(context, InjectDesign.theme(context, false))
        private val recipe = NativeFloatingActionRecipe.Default
        val root = FrameLayout(context).apply {
            layoutDirection = direction
            // Already-consumed system/cutout/IME insets belong to the host, not this recipe.
            setPadding(11, 23, 17, 29)
        }
        val content = CountingColumn(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 24)
        }
        val lastRow: View
        val scroll = ScrollView(context).apply { isFillViewport = true; addView(content) }
        var clicks = 0
        val action = ui.addButton("Add plugin") { clicks++ }
        var binding: AutoCloseable? = null
            private set

        init {
            val rows = List(12) { View(context).also { content.addView(it, LinearLayout.LayoutParams(-1, 80)) } }
            lastRow = rows.last()
            val page = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(View(context), LinearLayout.LayoutParams(-1, 58))
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            root.addView(page, FrameLayout.LayoutParams(-1, -1))
            root.addView(action.view, recipe.layoutParams(context))
            activity.setContentView(root)
            assertTrue(action.view.isAttachedToWindow)
        }

        fun bind() { check(binding == null); binding = recipe.bindScrollClearance(action.view, content) }

        fun layout() {
            root.forceLayout()
            root.measure(exact(width), exact(600))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }

        fun settleLayout() {
            // First measure the real action; then consume the listener's padding request.
            layout()
            layout()
            val requests = content.paddingLayoutRequests
            layout()
            assertEquals("A settled binding must not create a requestLayout loop", requests, content.paddingLayoutRequests)
        }

        fun assertButtonInsideMargins() {
            assertEquals(direction, action.view.layoutDirection)
            assertTrue(action.view.left >= root.paddingLeft + 20)
            assertTrue(action.view.right <= root.width - root.paddingRight - 20)
            if (direction == View.LAYOUT_DIRECTION_RTL) assertEquals(root.paddingLeft + 20, action.view.left)
            else assertEquals(root.width - root.paddingRight - 20, action.view.right)
            assertEquals(root.height - root.paddingBottom - 20, action.view.bottom)
            assertSame(action.view, action.view.button.parent)
        }

        fun scrollToLastRow() {
            val maximum = (content.height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
            scroll.scrollTo(0, maximum)
            assertEquals("Check the actual maximum scroll position", maximum, scroll.scrollY)
        }

        fun assertLastRowAboveAction() {
            val row = bounds(lastRow)
            assertTrue("Last row is fully within the viewport", row.top >= bounds(scroll).top)
            assertTrue("Last row clears the action and shared content gap",
                row.bottom <= action.view.top - NativeScreenSpacing.CONTROL_GAP_DP)
        }

        fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
            root.offsetDescendantRectToMyCoords(view, it)
        }

        private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }

    private class CountingColumn(context: android.content.Context) : LinearLayout(context) {
        var paddingLayoutRequests = 0
        override fun requestLayout() {
            paddingLayoutRequests++
            super.requestLayout()
        }
    }
}
