package smallthingz.ui.reverb

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeTopBarInsets
import smallthingz.ui.components.NativeTopBarLayout
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeThemedToolbarAction
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.text.style.TextOverflow
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ReverbTopBarIntegrationTest {
    @Test fun multiActionToolbarMatchesTitledBackInBothDirectionsWithoutShrinkingEndCells() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        onMainThread {
            val context = instrumentation.targetContext
            val theme = ReverbDesign.theme(context, dark = false)
            val style = ReverbDesign.appearance.toolbars
            val density = theme.density.density
            fun dp(value: Float) = (value * density).roundToInt()
            val clicks = IntArray(3)
            fun action(index: Int, shared: Boolean = false, selection: Boolean = true) =
                NativeThemedToolbarAction(context, density, theme.palette, { null }, "Action $index",
                    selectionWrapper = selection, sharedSurface = shared, rolePresent = true, style = style).apply {
                    bindToolbarAction(density, theme.palette, true, NativeToolbarActionTone.MUTED) { clicks[index]++ }
                }
            val back = action(0)
            val endActions = listOf(action(1, shared = true), action(2, shared = true))
            val target = dp(style.action.minimumTargetDp)
            val group = NativeGroupLayout(context, style.action.radiusDp, LinearLayout.HORIZONTAL).apply {
                endActions.forEach { addRow(it, NativeGroupLayout.LayoutParams(target, target)) }
            }
            val title = theme.paragraph(context, "Available plugins", style.selectionTitleRole).apply {
                setTextLayout(softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val bar = ReverbSelectionTopBarLayout(context, back, title, group, endActions)
            val referenceBack = action(0, selection = false)
            val referenceEnd = action(2, selection = false)
            val reference = ReverbTitledTopBarLayout(context, density, theme.palette,
                referenceBack, View(context), referenceEnd)
            fun bounds(owner: ViewGroup, child: View): Rect = Rect(0, 0, child.width, child.height).also {
                owner.offsetDescendantRectToMyCoords(child, it)
            }
            fun glyphOrigin(owner: ViewGroup, action: NativeThemedToolbarAction): Pair<Int, Int> {
                val visual = action.visual
                val box = bounds(owner, visual)
                val glyph = dp(style.action.glyphDp)
                return box.left + ((visual.width - glyph) / 2f).roundToInt() to
                    box.top + ((visual.height - glyph) / 2f).roundToInt()
            }
            for (widthDp in listOf(320f, 220f)) {
                val width = dp(widthDp)
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    for (view in listOf(bar, reference)) {
                        view.layoutDirection = direction
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        view.layout(0, 0, width, view.measuredHeight)
                    }
                    assertEquals(dp(58f), bar.measuredHeight)
                    assertEquals(bounds(reference, referenceBack.visual), bounds(bar, back.visual))
                    assertEquals(glyphOrigin(reference, referenceEnd), glyphOrigin(bar, endActions.last()))
                    assertEquals(2 * target, group.measuredWidth)
                    endActions.forEach { assertEquals(target, it.measuredWidth); assertEquals(target, it.measuredHeight) }
                    assertTrue(!Rect.intersects(bounds(bar, endActions[0]), bounds(bar, endActions[1])))
                    assertTrue(!Rect.intersects(bounds(bar, title), bounds(bar, back.visual)))
                    assertTrue(!Rect.intersects(bounds(bar, title), bounds(bar, group)))
                    if (widthDp == 220f) assertTrue(title.measuredWidth < title.maxIntrinsicTextWidth())
                }
            }
            listOf(back, *endActions.toTypedArray()).forEach { assertTrue(it.accessibilityAction.performClick()) }
            assertEquals(listOf(1, 1, 1), clicks.toList())
        }
    }

    private fun onMainThread(action: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { action() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    @Test fun defaultsToReverbMarkAndMirrorsOnlyTheOuterSlots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val theme = ReverbDesign.theme(context, dark = false)
            val bar = ReverbTopBar(context, theme)
            val slots = bar.getChildAt(0) as ViewGroup
            val mark = slots.getChildAt(0) as ReverbBrandMarkView
            val insets = NativeTopBarInsets(13, 25, 7)
            bar.bind(theme,
                start = ReverbTopBarActionContent(ReverbIcon.Back, "Back", {}),
                end = ReverbTopBarActionContent(ReverbIcon.Settings, "Settings", {}),
                insets = insets)
            val start = slots.getChildAt(1)
            val end = slots.getChildAt(2)
            assertEquals(NativeTopBarLayout.Slot.START, (start.layoutParams as NativeTopBarLayout.ActionLayoutParams).slot)
            assertEquals(NativeTopBarLayout.Slot.END, (end.layoutParams as NativeTopBarLayout.ActionLayoutParams).slot)
            val width = (600 * theme.density.density).toInt()
            fun layout(direction: Int) {
                bar.layoutDirection = direction
                slots.layoutDirection = direction
                bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                bar.layout(0, 0, width, bar.measuredHeight)
            }
            layout(View.LAYOUT_DIRECTION_LTR)
            val centerLeft = mark.left
            val startLeft = start.left
            val endLeft = end.left
            assertTrue(startLeft >= insets.left)
            assertTrue(startLeft < centerLeft && centerLeft < endLeft)
            assertTrue(end.right <= width - insets.right)
            layout(View.LAYOUT_DIRECTION_RTL)
            assertEquals(endLeft, start.left)
            assertEquals(startLeft, end.left)
            assertEquals(centerLeft, mark.left)
            assertSame(mark, slots.getChildAt(0))
        }
    }
}
