package smallthingz.ui.inject

import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.reverb.ReverbDesign
import kotlin.math.abs
import kotlin.math.roundToInt

/** Source-only geometry coverage; existing app toolbar tests cover attached action semantics. */
@RunWith(AndroidJUnit4::class)
class InjectPluginToolbarIntegrationTest {
    @Test fun longSecondaryTitleStaysCenteredAndClearOfToggleOrStatusAtLargeFontInRtl() = main {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (fontScale in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                this.fontScale = fontScale
            })
            val theme = InjectDesign.theme(context, false)
            val ui = InjectAppearance(context, theme)
            val navigation = ui.semanticIconAction({ null }, "Back", MaterialRoundedIcon.ArrowBack) {}
            val toggle = View(context)
            val progress = View(context)
            val bar = smallthingz.ui.injector.InjectPluginDetailToolbar(context, theme, navigation, toggle, progress)
            bar.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            for (showToggle in listOf(false, true)) {
                bar.bindPresentation("Long plugin files and diagnostic logs", false, showToggle,
                    !showToggle, true, "Working".takeUnless { showToggle })
                val width = (320f * theme.density.density).roundToInt()
                bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                bar.layout(0, 0, width, bar.measuredHeight)
                val title = bounds(bar, bar.titleView)
                val end = bounds(bar, toggle.parent as View)
                val start = bounds(bar, navigation)
                assertTrue("Title stays physically centered", abs(title.exactCenterX() - width / 2f) <= .5f)
                assertFalse(Rect.intersects(title, start))
                assertFalse(Rect.intersects(title, end))
                assertTrue(title.top >= 0 && title.bottom <= bar.height)
                assertEquals((InjectPluginToolbarRecipe.toggleWidthDp * theme.density.density).roundToInt(), end.width())
                assertEquals((ReverbDesign.appearance.toolbars.action.minimumTargetDp * theme.density.density).roundToInt(), end.height())
                assertSame(ReverbDesign.appearance.toolbars, InjectPluginToolbarRecipe.toolbar)
                assertEquals(ReverbDesign.appearance.toolbars.centeredTitleRole, InjectPluginToolbarRecipe.titleRole)
                if (!showToggle) assertEquals("Working", progress.contentDescription)
            }
        }
    }

    @Test fun identityTitleAndStatusVisibilityRetainTheSuppliedOwners() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val theme = InjectDesign.theme(context, true)
        val ui = InjectAppearance(context, theme)
        val navigation = ui.semanticIconAction("Back", MaterialRoundedIcon.ArrowBack) {}
        val identity = ui.semanticIconAction("Plugin menu", MaterialRoundedIcon.Info) {}
        val toggle = View(context)
        val status = View(context)
        val bar = InjectPluginDetailToolbar(context, theme, navigation, toggle, status)
        bar.setIdentity(identity)
        bar.bindPresentation("Plugin", true, true, false, true, null)
        assertEquals(View.VISIBLE, identity.visibility)
        assertEquals(View.GONE, bar.titleView.visibility)
        val retainedParent = toggle.parent
        bar.bindPresentation("Files", false, false, false, false, null)
        assertEquals(View.GONE, identity.visibility)
        assertEquals(View.VISIBLE, bar.titleView.visibility)
        assertEquals("Files", bar.titleView.getText())
        assertSame(retainedParent, toggle.parent)
        assertSame(bar, identity.parent)
        assertSame(bar, navigation.parent)
    }

    private fun bounds(parent: ViewGroup, child: View) = Rect(0, 0, child.width, child.height).also {
        parent.offsetDescendantRectToMyCoords(child, it)
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
