package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    @Test fun compactDecisionRetainsLegacyHeightAndTargetsAcrossResizeAndFallback() = onMain {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        for (font in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            val context = ContextThemeWrapper(target, android.R.style.Theme_Material_NoActionBar).apply {
                applyOverrideConfiguration(Configuration(target.resources.configuration).apply {
                    fontScale = font
                    setLayoutDirection(if (rtl) Locale.forLanguageTag("ar") else Locale.US)
                })
            }
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
            fun button() = Button(context).apply {
                minWidth = dp(48); minimumWidth = dp(48)
                minHeight = dp(48); minimumHeight = dp(48)
                setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
                isFocusableInTouchMode = true
            }
            val cancel = button(); val apply = button()
            val row = GalleryHealDecisionRow(context, cancel, apply)
            val oldCancel = button(); val oldApply = button()
            // Independent Android framework reference: the original equal-half row.
            val oldSlot = FrameLayout(context).apply {
                addView(oldApply, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
            }
            val legacy = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(oldCancel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                addView(oldSlot, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            }
            val slot = apply.parent as FrameLayout
            for (root in listOf(row, legacy)) {
                root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                root.setPadding(dp(7), dp(3), dp(11), dp(5))
            }
            fun layout(width: Int) {
                fun dirty(view: View) {
                    view.forceLayout()
                    if (view is ViewGroup) repeat(view.childCount) { dirty(view.getChildAt(it)) }
                }
                for (root in listOf(row, legacy)) {
                    dirty(root)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    root.layout(0, 0, width, root.measuredHeight)
                }
            }
            fun bounds(root: ViewGroup, view: View) = Rect(0, 0, view.width, view.height).also {
                root.offsetDescendantRectToMyCoords(view, it)
            }
            var cancelClicks = 0; var applyClicks = 0
            cancel.setOnClickListener { cancelClicks++ }; apply.setOnClickListener { applyClicks++ }
            // Detached hit-routing coverage: consume the routed gesture and activate its
            // actual recipient, without relying on an unattached View's posted click queue.
            val cancelEvents = mutableListOf<Int>(); val applyEvents = mutableListOf<Int>()
            for ((view, events) in listOf(cancel to cancelEvents, apply to applyEvents)) {
                view.setOnTouchListener { recipient, event ->
                    events += event.actionMasked
                    if (event.actionMasked == MotionEvent.ACTION_UP) recipient.performClick()
                    true
                }
            }
            fun touch(x: Float, y: Float) {
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time, action, x, y, 0)
                    try { assertTrue(row.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
            }
            try {
                // Re-enter compact after wrapping, too-small targets and GONE; no sticky mode.
                for ((index, state) in listOf("compact", "invisible", "wrapped", "compactOdd", "tooSmall", "gone", "compact").withIndex()) {
                    val wrapped = state == "wrapped"
                    val tiny = state == "tooSmall"
                    cancel.text = if (wrapped) "Discard preview without applying changes" else if (tiny) "X" else "Discard"
                    apply.text = if (wrapped) "Apply this preview to the photo" else if (tiny) "Y" else "Apply"
                    oldCancel.text = cancel.text; oldApply.text = apply.text
                    apply.visibility = when (state) { "invisible" -> View.INVISIBLE; "gone" -> View.GONE; else -> View.VISIBLE }
                    oldApply.visibility = apply.visibility
                    val width = if (wrapped) dp(240) or 1 else if (tiny) dp(96) or 1
                        else if (state == "compactOdd") dp(640) or 1 else dp(640)
                    layout(width)
                    assertSame(cancel, row.getChildAt(0)); assertSame(slot, row.getChildAt(1))
                    assertSame(apply, slot.getChildAt(0))
                    assertEquals("Never spend photo height: $state/$font/$rtl", legacy.height, row.height)
                    assertEquals(oldCancel.height, cancel.height)
                    assertEquals("Original Apply half-slot", bounds(legacy, oldSlot), bounds(row, slot))
                    if (state != "gone") assertEquals(bounds(legacy, oldApply), bounds(row, apply))
                    if (wrapped || tiny || state == "gone") {
                        assertEquals("Exact legacy fallback", bounds(legacy, oldCancel), bounds(row, cancel))
                        if (wrapped) assertTrue(oldCancel.lineCount > 1 || oldApply.lineCount > 1)
                        if (tiny) assertTrue("Exercise a sub-48dp half", oldCancel.width < dp(48))
                    } else {
                        val c = bounds(row, cancel); val a = bounds(row, apply)
                        assertEquals("Existing two rounded half-gaps", 2 * dp(4), if (rtl) c.left - a.right else a.left - c.right)
                        assertFalse(Rect.intersects(c, a))
                        val inside = Rect(row.paddingLeft, row.paddingTop, row.width - row.paddingRight, row.height - row.paddingBottom)
                        assertTrue(inside.contains(c)); assertTrue(inside.contains(a))
                        for (view in listOf(cancel, apply)) {
                            assertTrue(view.width >= dp(48) && view.height >= dp(48))
                            val text = checkNotNull(view.layout)
                            assertEquals(1, text.lineCount); assertEquals(0, text.getEllipsisCount(0))
                            assertEquals(view.text.length, text.getLineEnd(0))
                        }
                        // This coordinate lies in Apply's empty slot AND Cancel's live target.
                        val overlap = Rect(c)
                        assertTrue(overlap.intersect(bounds(row, slot)))
                        assertFalse(Rect.intersects(overlap, a))
                        assertFalse(slot.isClickable)
                        cancelEvents.clear(); applyEvents.clear()
                        val beforeCancel = cancelClicks; val beforeApply = applyClicks
                        touch(overlap.exactCenterX(), overlap.exactCenterY())
                        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), cancelEvents)
                        assertTrue(applyEvents.isEmpty())
                        assertEquals(beforeCancel + 1, cancelClicks); assertEquals(beforeApply, applyClicks)
                        if (apply.visibility == View.VISIBLE) {
                            touch(a.exactCenterX(), a.exactCenterY())
                            assertEquals(beforeApply + 1, applyClicks)
                        }
                    }
                    if (index == 0) assertTrue(cancel.requestFocus())
                    assertSame("Resizing and visibility retain the focused button", cancel, row.findFocus())
                }
            } finally {
                cancel.setOnTouchListener(null); apply.setOnTouchListener(null)
                row.clearFocus(); row.removeAllViews(); legacy.removeAllViews()
            }
        }
    }

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
                    f.assertDecisionSlots()
                    val applySlot = f.apply.parent as View
                    assertTrue("Short Apply uses less than its reserved half-row", f.apply.width < applySlot.width)
                    val cancelBounds = f.bounds(f.discard)
                    val applyBounds = f.bounds(f.apply)
                    // Working keeps the same slots and row height without exposing Apply.
                    f.apply.visibility = View.INVISIBLE
                    f.apply.isEnabled = false
                    f.ready(false)
                    assertEquals(natural, f.bounds(f.decisions))
                    assertEquals(cancelBounds, f.bounds(f.discard))
                    assertEquals(applyBounds, f.bounds(f.apply))
                    assertFalse(f.apply.isShown || f.apply.isEnabled)
                    f.apply.visibility = View.VISIBLE
                    f.apply.isEnabled = true
                    f.ready(false)
                    val retained = listOf(f.before, f.discard, f.apply)
                    assertTrue("The fixture must contain real spare space", natural.bottom < reserved - f.panel.paddingBottom)
                    assertTrue(f.discard.requestFocus())
                    repeat(3) {
                        f.ready(true)
                        assertEquals(reserved, f.panel.measuredHeight)
                        assertEquals("Canvas budget proxy cannot change", canvasBudget, f.dp(1000) - f.scroll.measuredHeight)
                        f.assertAnchoredGroup(comparison, natural, reserved)
                        f.assertDecisionSlots()
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
                        assertSame(retained[2], (f.decisions.getChildAt(1) as FrameLayout).getChildAt(0))
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
                    f.assertDecisionSlots()
                    assertTrue(f.discard.height >= f.dp(48) && f.apply.height >= f.dp(48))
                    for (button in listOf(f.discard, f.apply)) {
                        assertTrue(button.width >= f.dp(48))
                        val text = checkNotNull(button.layout)
                        assertTrue(text.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                        assertEquals(button.text.length, text.getLineEnd(text.lineCount - 1))
                        repeat(text.lineCount) {
                            assertEquals(0, text.getEllipsisCount(it))
                            assertTrue(text.getLineLeft(it) >= -1f && text.getLineRight(it) <= text.width + 1f)
                        }
                    }
                    assertTrue(f.bounds(f.before).bottom <= narrow.top)
                    assertFalse(Rect.intersects(f.bounds(f.discard), f.bounds(f.apply)))
                    assertEquals(0, f.clicks)
                    f.discard.performClick(); assertEquals(1, f.clicks)
                    f.apply.performClick(); assertEquals(2, f.clicks)
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
                assertSame(retained[2], (f.decisions.getChildAt(1) as FrameLayout).getChildAt(0))
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
        val decisions = GalleryHealDecisionRow(context, discard, apply)
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
        fun assertDecisionSlots() {
            val cancelBounds = bounds(discard)
            val applyBounds = bounds(apply)
            val slot = bounds(apply.parent as View)
            assertTrue("Apply stays inside its reserved slot", slot.contains(applyBounds))
            assertFalse("Cancel/Discard can never overlap actual Apply", Rect.intersects(cancelBounds, applyBounds))
            val compact = discard.layout?.lineCount == 1 && apply.layout?.lineCount == 1 &&
                discard.width >= dp(48) && apply.width >= dp(48)
            if (compact) assertEquals("Short actions share the original 8dp gap", 2 * dp(4),
                if (decisions.layoutDirection == View.LAYOUT_DIRECTION_RTL) cancelBounds.left - applyBounds.right
                else applyBounds.left - cancelBounds.right)
            else {
                assertTrue("Wrapped fallback retains equal halves", kotlin.math.abs(cancelBounds.width() - slot.width()) <= 1)
                assertFalse(Rect.intersects(cancelBounds, slot))
            }
            if (decisions.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                assertEquals("Apply aligns to logical end", slot.left, applyBounds.left)
                assertTrue(applyBounds.right <= cancelBounds.left)
            } else {
                assertEquals("Apply aligns to logical end", slot.right, applyBounds.right)
                assertTrue(cancelBounds.right <= applyBounds.left)
            }
            for (button in listOf(discard, apply)) {
                assertTrue(button.width >= dp(48) && button.height >= dp(48))
            }
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
