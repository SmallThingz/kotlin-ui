package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.roundToInt

/** Real Android layout and scroll transforms with post-layout placement.
 * Forced layouts below evaluate the final viewport. Between layouts, content scrolls naturally.
 * No Activity, settings or media mutation. */
@RunWith(AndroidJUnit4::class)
class GalleryHealReservedDecisionLayoutIntegrationTest {
    @Test fun reservedReadyDecisionUsesOnlyVisibleSlackAndRetainsChildren() =
        onMain {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            for (font in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
                val context = ContextThemeWrapper(target, android.R.style.Theme_Material_NoActionBar).apply {
                    applyOverrideConfiguration(Configuration(target.resources.configuration).apply {
                        fontScale = font
                        setLayoutDirection(if (rtl) Locale.forLanguageTag("ar") else Locale.US)
                    })
                }
                val f = Fixture(context, rtl)
                try {
                    f.selecting()
                    val reserved = f.panel.measuredHeight
                    val canvasBudget = f.dp(1000) - f.scroll.measuredHeight
                    f.panel.reservation = reserved
                    f.ready(false)
                    val natural = f.bounds(f.decisions)
                    val comparison = f.bounds(f.before)
                    val guidance = f.bounds(f.guidance)
                    val retained = listOf(f.before, f.discard, f.apply)
                    assertTrue("The fixture must contain real spare space", natural.bottom < reserved - f.panel.paddingBottom)
                    assertTrue(f.discard.requestFocus())
                    repeat(3) {
                        f.ready(true)
                        assertEquals(reserved, f.panel.measuredHeight)
                        assertEquals("Canvas budget proxy cannot change", canvasBudget, f.dp(1000) - f.scroll.measuredHeight)
                        f.assertAnchoredGroup(comparison, natural, reserved)
                        // Repeated Ready layouts must not accumulate displacement or stretch rows.
                        f.ready(true)
                        f.assertAnchoredGroup(comparison, natural, reserved)
                        assertSame("Placement must not choose Apply", f.discard, f.panel.findFocus())
                        assertSame(retained[0], f.group.getChildAt(0))
                        assertSame(f.guidance, f.panel.reservedReadyGuidance)
                        assertEquals("Guidance text is never rebound by placement", "Check the preview before applying.", f.guidance.text.toString())
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, f.guidance.accessibilityLiveRegion)
                        assertEquals(guidance.width(), f.guidance.width)
                        assertEquals(guidance.height(), f.guidance.height)
                        assertSame(retained[1], f.decisions.getChildAt(0))
                        assertSame(retained[2], f.decisions.getChildAt(1))
                        assertEquals(0, f.clicks)
                        f.selecting()
                        assertEquals(guidance, f.bounds(f.guidance))
                        assertEquals(View.GONE, f.group.visibility)
                        assertEquals(reserved, f.panel.measuredHeight)
                        f.ready(false) // Same retained rows, natural Preparing placement.
                        assertEquals("Natural placement restored", natural, f.bounds(f.decisions))
                        assertEquals(comparison, f.bounds(f.before))
                        assertEquals(guidance, f.bounds(f.guidance))
                        assertTrue(f.discard.requestFocus())
                    }
                    // An intervening warning must not be crossed, even when the decision
                    // group itself still fits and uses its unchanged bottom anchor.
                    f.warning.visibility = View.VISIBLE
                    f.ready(false)
                    val warningComparison = f.bounds(f.before)
                    val warningDecision = f.bounds(f.decisions)
                    f.ready(true)
                    assertEquals(guidance, f.bounds(f.guidance))
                    assertTrue(f.bounds(f.guidance).bottom <= f.bounds(f.warning).top)
                    val warningDelta = reserved - f.panel.paddingBottom - warningDecision.bottom
                    assertEquals(Rect(warningComparison).apply { offset(0, warningDelta) }, f.bounds(f.before))
                    f.warning.visibility = View.GONE
                    // Force a layout at a nonzero scroll offset to validate framework frames.
                    // This does not prove or require scroll-only re-anchoring.
                    f.panel.clearFocus() // Coordinate cases must not trigger focus-driven scrolling.
                    f.scroll.scrollTo(0, f.dp(24))
                    f.ready(true)
                    assertEquals(f.dp(24), f.scroll.scrollY)
                    val rect = Rect(0, 0, f.panel.width, f.panel.height)
                    f.scroll.offsetDescendantRectToMyCoords(f.panel, rect)
                    assertEquals(f.body.top + f.panel.top, rect.top)
                    assertEquals(f.body.left + f.panel.left, rect.left)
                    val drawing = Rect(); f.scroll.getDrawingRect(drawing)
                    assertEquals(f.scroll.scrollY, drawing.top)
                    assertEquals(f.scroll.scrollY + f.scroll.height, drawing.bottom)
                    f.assertAnchoredGroup(comparison, natural, reserved)
                    // At the next forced layout the panel is clipped at the top: natural placement.
                    // Scrolling alone need not relayout or reposition the decision row.
                    f.scroll.scrollTo(0, f.body.top + f.panel.top + f.dp(30))
                    f.ready(true)
                    assertTrue(f.scroll.scrollY + f.scroll.paddingTop > f.body.top + f.panel.top)
                    assertEquals(natural, f.bounds(f.decisions))
                    assertEquals(comparison, f.bounds(f.before))
                    assertEquals(guidance, f.bounds(f.guidance))
                    // A short viewport also refuses anchoring, independently of scroll position.
                    f.scroll.scrollTo(0, 0); f.viewportHeight = f.dp(180); f.ready(true)
                    assertTrue(f.panel.height > f.scroll.height - f.scroll.paddingTop - f.scroll.paddingBottom)
                    assertEquals(natural, f.bounds(f.decisions))
                    assertEquals(comparison, f.bounds(f.before))
                    assertEquals(guidance, f.bounds(f.guidance))
                    // Details after the decision group retains normal document flow.
                    f.viewportHeight = f.dp(600); f.details.visibility = View.VISIBLE; f.ready(true)
                    assertEquals(natural, f.bounds(f.decisions))
                    assertEquals(comparison, f.bounds(f.before))
                    assertEquals(guidance, f.bounds(f.guidance))
                    assertTrue(f.bounds(f.details).top >= f.bounds(f.decisions).bottom)
                    // Real wrapped labels, narrow font1/font2: no compression to force fitting.
                    f.details.visibility = View.GONE
                    f.width = f.dp(240); f.viewportHeight = f.dp(180)
                    f.discard.text = "Discard preview without applying changes"
                    f.apply.text = "Apply this preview to the photo"
                    f.ready(false); val narrow = f.bounds(f.decisions)
                    val narrowComparison = f.bounds(f.before)
                    val narrowGuidance = f.bounds(f.guidance)
                    val naturalHeight = f.panel.measuredHeight
                    f.ready(true)
                    assertEquals(naturalHeight, f.panel.measuredHeight)
                    assertEquals(narrow, f.bounds(f.decisions))
                    assertEquals(narrowComparison, f.bounds(f.before))
                    assertEquals(narrowGuidance, f.bounds(f.guidance))
                    assertTrue(f.discard.height >= f.dp(48) && f.apply.height >= f.dp(48))
                    for (button in listOf(f.discard, f.apply)) {
                        assertTrue(button.width >= f.dp(48))
                        val text = checkNotNull(button.layout)
                        assertTrue(text.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                        repeat(text.lineCount) { assertEquals(0, text.getEllipsisCount(it)) }
                    }
                    assertTrue(f.bounds(f.before).bottom <= narrow.top)
                    assertFalse(Rect.intersects(f.bounds(f.discard), f.bounds(f.apply)))
                    assertEquals(0, f.clicks)
                    f.discard.performClick(); assertEquals(1, f.clicks)
                } finally { f.panel.clearFocus(); f.scroll.removeAllViews() }
            }
        }

    @Test fun readyDecisionAnchorsAfterViewportResizeClampsScrollOffset() =
        onMain {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val f = Fixture(target, rtl = false, trailingHeightDp = 48)
            try {
                f.viewportHeight = f.dp(240)
                f.selecting()
                val reserved = f.panel.measuredHeight
                f.panel.reservation = reserved
                f.ready(false)
                val natural = f.bounds(f.decisions)
                val comparison = f.bounds(f.before)
                val retained = listOf(f.before, f.discard, f.apply)
                assertTrue(natural.bottom < reserved - f.panel.paddingBottom)

                f.panel.clearFocus()
                f.scroll.scrollTo(0, f.body.height)
                f.ready(true)
                val panelRect = Rect(0, 0, f.panel.width, f.panel.height)
                f.scroll.offsetDescendantRectToMyCoords(f.panel, panelRect)
                val oldOffset = f.scroll.scrollY
                assertTrue(oldOffset + f.scroll.paddingTop > panelRect.top)
                assertEquals(natural, f.bounds(f.decisions))
                assertEquals(comparison, f.bounds(f.before))

                // Keep the same content but shrink the scroll range to a nonzero offset.
                val finalOffset = f.dp(24)
                assertTrue(oldOffset > finalOffset)
                f.viewportHeight = f.body.height + f.scroll.paddingTop + f.scroll.paddingBottom - finalOffset
                f.ready(true)
                assertEquals(finalOffset, f.scroll.scrollY)
                panelRect.set(0, 0, f.panel.width, f.panel.height)
                f.scroll.offsetDescendantRectToMyCoords(f.panel, panelRect)
                assertTrue(panelRect.top >= finalOffset + f.scroll.paddingTop)
                assertTrue(panelRect.bottom <= finalOffset + f.scroll.height - f.scroll.paddingBottom)
                assertEquals(reserved, f.panel.measuredHeight)
                f.assertAnchoredGroup(comparison, natural, reserved)
                assertSame(retained[0], f.group.getChildAt(0))
                assertSame(retained[1], f.decisions.getChildAt(0))
                assertSame(retained[2], f.decisions.getChildAt(1))
            } finally { f.panel.clearFocus(); f.scroll.removeAllViews() }
        }

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }

    /** Mirrors only the existing app's reserved-height contract, not its Heal state machine. */
    private class ReservedPanel(context: Context) : GalleryHealControlsLayout(context) {
        var reservation = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (reservation > measuredHeight) setMeasuredDimension(measuredWidthAndState,
                resolveSizeAndState(reservation, heightMeasureSpec, measuredState shl MEASURED_HEIGHT_STATE_SHIFT))
        }
    }

    private class Fixture(val context: Context, rtl: Boolean, trailingHeightDp: Int = 600) {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        var clicks = 0
        var width = dp(640)
        var viewportHeight = dp(600)
        private fun button(label: String) = Button(context).apply {
            text = label; setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
            minHeight = dp(48); minimumHeight = dp(48)
            isFocusableInTouchMode = true
            setOnClickListener { clicks++ }
        }
        val before = button("Before heal")
        val discard = button("Discard preview")
        val apply = button("Apply")
        val details = button("Details").apply { visibility = View.GONE }
        val decisions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(discard, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            addView(apply, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        }
        val group = GalleryHealDecisionGroup(context, before, decisions)
        val guidance = TextView(context).apply {
            text = "Check the preview before applying."
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val warning = TextView(context).apply { text = "Cleanup required"; visibility = View.GONE }
        val selection = View(context)
        val panel = ReservedPanel(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(9), dp(11), dp(13))
            addView(guidance, LinearLayout.LayoutParams(-1, -2))
            addView(selection, LinearLayout.LayoutParams(-1, dp(320)))
            addView(warning, LinearLayout.LayoutParams(-1, -2))
            addView(group, LinearLayout.LayoutParams(-1, -2))
            addView(details, LinearLayout.LayoutParams(-1, -2))
            reservedDecisionGroup = group
            reservedReadyGuidance = guidance
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(7), dp(9), dp(11))
            addView(View(context), LinearLayout.LayoutParams(-1, dp(96)))
            addView(panel, LinearLayout.LayoutParams(-1, -2))
            addView(View(context), LinearLayout.LayoutParams(-1, dp(trailingHeightDp)))
        }
        val scroll = ScrollView(context).apply {
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setPadding(dp(13), dp(17), dp(19), dp(23))
            isSmoothScrollingEnabled = false
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        fun layout() {
            // Force descendant layout; the detached fixture delivers pre-draw after ScrollView clamps.
            panel.forceLayout(); group.forceLayout(); body.forceLayout(); scroll.forceLayout()
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, width, viewportHeight)
            panel.viewTreeObserver.dispatchOnPreDraw()
        }
        fun selecting() {
            panel.anchorReadyDecision = false; group.visibility = View.GONE; selection.visibility = View.VISIBLE
            layout()
        }
        fun ready(anchor: Boolean) {
            selection.visibility = View.GONE; group.visibility = View.VISIBLE; panel.anchorReadyDecision = anchor
            layout()
        }
        fun assertAnchoredGroup(comparison: Rect, decision: Rect, reserved: Int) {
            val delta = reserved - panel.paddingBottom - decision.bottom
            assertTrue("The fixture must exercise positive reserved slack", delta > 0)
            val expectedComparison = Rect(comparison).apply { offset(0, delta) }
            val expectedDecision = Rect(decision).apply { offset(0, delta) }
            assertEquals("Comparison and decisions move together", expectedComparison, bounds(before))
            assertEquals("Decision slots retain their bottom anchor and dimensions", expectedDecision, bounds(decisions))
            assertEquals("No stretched container or internal spacer", group.measuredHeight, group.height)
            assertEquals("Natural row spacing is retained", decision.top - comparison.bottom,
                bounds(decisions).top - bounds(before).bottom)
            assertEquals("Guidance belongs immediately above comparison, without moving its slots",
                dp(6), bounds(before).top - bounds(guidance).bottom)
            assertTrue(panel.indexOfChild(guidance) < panel.indexOfChild(group))
            assertEquals(reserved, panel.measuredHeight)
        }
        fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
            panel.offsetDescendantRectToMyCoords(view, it)
        }
    }
}
