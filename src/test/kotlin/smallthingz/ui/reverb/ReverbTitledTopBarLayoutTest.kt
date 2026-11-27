package smallthingz.ui.reverb

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeThemedToolbarAction
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.nativeTopBarHitIndex
import smallthingz.ui.text.style.TextOverflow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rAU-w199dp-h421dp-464dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReverbTitledTopBarLayoutTest {
    @Test fun defaultCompactTitleEllipsizesWithoutMovingActionsOrLosingItsLabel() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                densityDpi = 464
                fontScale = .9f
            })
            val root = FrameLayout(context)
            activity.setContentView(root)
            controller.windowFocusChanged(true)
            val theme = ReverbDesign.theme(context, dark = true)
            val style = ReverbDesign.appearance.toolbars
            val density = theme.density.density
            assertEquals(2.9f, density, .01f)
            fun dp(value: Float) = (value * density).roundToInt()
            val clicks = mutableListOf<String>()
            fun action(name: String) = NativeThemedToolbarAction(context, density, theme.palette, { root }, name,
                selectionWrapper = false, rolePresent = true, style = style).apply {
                bindToolbarAction(density, theme.palette, true, NativeToolbarActionTone.MUTED) { clicks += name }
            }
            val back = action("Back")
            val done = action("Done")
            val title = theme.paragraph(context, "Settings", style.centeredTitleRole)
            val bar = ReverbTitledTopBarLayout(context, density, theme.palette, back, title, done)
            root.addView(bar)
            fun place(view: View, width: Int) {
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
            }

            place(bar, 576) // Retained native freeform window width at 464 dpi.
            assertEquals(1, checkNotNull(title.currentParagraph).lineCount)
            title.setText("Settings and permissions")
            place(bar, 576)
            val narrow = checkNotNull(title.currentParagraph)
            assertEquals(1, narrow.lineCount)
            assertTrue("A long title must ellipsize in the measured middle slot", narrow.isLineEllipsized(0))
            assertEquals("Settings and permissions", title.createAccessibilityNodeInfo()?.text?.toString())
            assertEquals(dp(style.compactHeightDp), bar.height)
            assertTrue(title.left >= back.right && title.right <= done.left)
            assertEquals(dp(style.compactEdgeInsetDp), back.left)
            assertEquals(dp(style.compactEdgeInsetDp), bar.width - done.right)
            assertEquals(dp(style.action.sizeDp), back.width)
            assertEquals(back.width, done.width)

            val actionBounds = listOf(back.hitBounds(), done.hitBounds())
            val minimumTarget = style.action.minimumTargetDp * density
            assertEquals(48f, style.action.minimumTargetDp, 0f)
            for ((index, bounds) in actionBounds.withIndex()) {
                val x = bounds.left - (minimumTarget - bounds.width) * .25f
                val y = bounds.top + bounds.height * .5f
                assertEquals(index, nativeTopBarHitIndex(actionBounds, x, y, true, minimumTarget))
            }
            assertTrue(back.accessibilityAction.performClick())
            assertTrue(done.accessibilityAction.performClick())
            assertEquals(listOf("Back", "Done"), clicks)

            place(bar, 1152)
            val wide = checkNotNull(title.currentParagraph)
            assertEquals(1, wide.lineCount)
            assertFalse(wide.isLineEllipsized(0))
            assertEquals(wide.height, narrow.height, .01f)

            val explicit = theme.paragraph(context, "Settings\nPermissions", style.centeredTitleRole).apply {
                setTextLayout(maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val explicitBar = ReverbTitledTopBarLayout(context, density, theme.palette,
                View(context), explicit, View(context))
            place(explicitBar, 1152)
            assertEquals("Preserve a caller's explicit text policy", 2, checkNotNull(explicit.currentParagraph).lineCount)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
