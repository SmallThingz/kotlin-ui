package smallthingz.ui.zfm

import android.content.res.Configuration
import android.view.View
import android.widget.LinearLayout
import android.widget.Space
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZfmHomeLayoutIntegrationTest {
    @Test fun bottomDirectionUsesPaddedViewportAndRelayoutPreservesTheUsersEdge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 160
                fontScale = 1f
            })
            val home = ZfmHomeLayout(context, ZfmTheme.resolve(context, dark = false),
                Space(context), settingsIcon = null, settingsLabel = "Settings", onSettings = {})
            val scroll = home.scroll
            scroll.setPadding(0, 8, 0, 16)

            fun growContent(height: Int) {
                home.content.addView(Space(context),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
            }
            fun layoutHome() {
                val spec = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
                home.measure(spec, spec)
                home.layout(0, 0, home.measuredWidth, home.measuredHeight)
            }

            growContent(600)
            home.bindDirection(bottomUp = false)
            layoutHome()
            scroll.viewTreeObserver.dispatchOnPreDraw()
            val initialBottom = home.content.height - scroll.height + 24
            assertTrue(scroll.height > 0 && initialBottom > 24)

            home.bindDirection(bottomUp = true)
            layoutHome()
            assertEquals("Direction change is still waiting for pre-draw", 0, scroll.scrollY)
            scroll.viewTreeObserver.dispatchOnPreDraw()
            assertEquals("Pre-draw includes both vertical padding edges", initialBottom, scroll.scrollY)

            growContent(60)
            layoutHome()
            assertEquals("Late content keeps the bottom pinned", initialBottom + 60, scroll.scrollY)

            val manualOffset = initialBottom / 2
            scroll.scrollTo(0, manualOffset)
            growContent(50)
            layoutHome()
            assertEquals("Late content leaves a user who scrolled up in place", manualOffset, scroll.scrollY)
        }
    }

    @Test fun sameCountCategoryReplacementKeepsOrderWeightsAndManualScroll() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 160
                fontScale = 1f
            })
            for (bottomUp in listOf(true, false)) {
                val home = ZfmHomeLayout(context, ZfmTheme.resolve(context, dark = false),
                    Space(context), settingsIcon = null, settingsLabel = "Settings", onSettings = {})
                val grid = ZfmHomeCategoryGrid(context).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
                grid.bindDirection(bottomUp)
                home.content.addView(grid, LinearLayout.LayoutParams(-1, -2))
                home.content.addView(Space(context), LinearLayout.LayoutParams(-1, 600))
                home.scroll.setPadding(0, 8, 0, 16)
                home.bindDirection(bottomUp)
                fun addCategories() = List(4) { index ->
                    Space(context).apply {
                        minimumWidth = 32 + index * 8
                        minimumHeight = 48
                        grid.addView(this)
                    }
                }
                fun layoutHome() {
                    val spec = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
                    home.measure(spec, spec)
                    home.layout(0, 0, home.measuredWidth, home.measuredHeight)
                }
                fun assertGrid(cells: List<Space>) {
                    assertEquals(2, grid.columnCount)
                    assertTrue(grid.width > 0)
                    cells.forEach { cell ->
                        assertSame(grid, cell.parent)
                        assertEquals(grid.width / 2, cell.width)
                        assertTrue(cell.height >= 48)
                    }
                    assertEquals(0, cells[0].left)
                    assertEquals(cells[0].right, cells[1].left)
                    assertEquals(grid.width, cells[1].right)
                    assertEquals(cells[0].top, cells[1].top)
                    assertEquals(cells[2].top, cells[3].top)
                    if (bottomUp) assertTrue(cells[0].top >= cells[2].bottom)
                    else assertTrue(cells[2].top >= cells[0].bottom)
                }

                val old = addCategories()
                layoutHome()
                home.scroll.viewTreeObserver.dispatchOnPreDraw()
                assertGrid(old)
                val bottom = home.content.height - home.scroll.height + 24
                assertTrue(bottom > 48)
                val manualOffset = bottom / 2
                home.scroll.scrollTo(0, manualOffset)
                assertEquals(manualOffset, home.scroll.scrollY)

                // Equal cardinality and width: membership, not count, must dirty assignment.
                grid.removeAllViews()
                val replacement = addCategories()
                assertEquals(old.size, grid.childCount)
                layoutHome()
                old.forEach { assertNull(it.parent) }
                assertGrid(replacement)
                assertEquals(manualOffset, home.scroll.scrollY)

                replacement.forEach { it.minimumHeight = 80 } // Late metadata growth after replacement.
                layoutHome()
                assertGrid(replacement)
                assertEquals(manualOffset, home.scroll.scrollY)
                val parameters = replacement.map { it.layoutParams }
                grid.requestLayout()
                layoutHome()
                replacement.forEachIndexed { index, cell -> assertSame(parameters[index], cell.layoutParams) }
                assertEquals(manualOffset, home.scroll.scrollY)
            }
        }
    }
}
