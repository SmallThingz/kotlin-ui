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

/** Real Android layout-time scroll transforms; no scroll-only re-anchoring contract.
 * Forced layouts below evaluate the current viewport. Between layouts, content scrolls naturally.
 * No Activity, settings or media mutation. */
@RunWith(AndroidJUnit4::class)
class GalleryHealReservedDecisionLayoutIntegrationTest {
    @Test fun reservedReadyDecisionUsesOnlyVisibleSlackAndRetainsChildren() =
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
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
                    val retained = listOf(f.before, f.discard, f.apply)
                    assertTrue("The fixture must contain real spare space", natural.bottom < reserved - f.panel.paddingBottom)
                    assertTrue(f.discard.requestFocus())
                    repeat(3) {
                        f.ready(true)
                        assertEquals(reserved, f.panel.measuredHeight)
                        assertEquals("Canvas budget proxy cannot change", canvasBudget, f.dp(1000) - f.scroll.measuredHeight)
                        assertEquals(comparison, f.bounds(f.before))
                        assertEquals(reserved - f.panel.paddingBottom, f.bounds(f.decisions).bottom)
                        assertTrue(f.bounds(f.decisions).top >= f.bounds(f.before).bottom)
                        assertSame("Placement must not choose Apply", f.discard, f.panel.findFocus())
                        assertSame(retained[0], f.group.getChildAt(0))
                        assertSame(retained[1], f.decisions.getChildAt(0))
                        assertSame(retained[2], f.decisions.getChildAt(1))
                        assertEquals(0, f.clicks)
                        f.selecting()
                        assertEquals(View.GONE, f.group.visibility)
                        assertEquals(reserved, f.panel.measuredHeight)
                        f.ready(false) // Same retained rows, natural Preparing placement.
                        assertEquals("No accumulated extension", natural, f.bounds(f.decisions))
                        assertEquals(comparison, f.bounds(f.before))
                        assertTrue(f.discard.requestFocus())
                    }
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
                    assertEquals(reserved - f.panel.paddingBottom, f.bounds(f.decisions).bottom)
                    // At the next forced layout the panel is clipped at the top: natural placement.
                    // Scrolling alone need not relayout or reposition the decision row.
                    f.scroll.scrollTo(0, f.body.top + f.panel.top + f.dp(30))
                    f.ready(true)
                    assertTrue(f.scroll.scrollY + f.scroll.paddingTop > f.body.top + f.panel.top)
                    assertEquals(natural, f.bounds(f.decisions))
                    // A short viewport also refuses anchoring, independently of scroll position.
                    f.scroll.scrollTo(0, 0); f.viewportHeight = f.dp(180); f.ready(true)
                    assertTrue(f.panel.height > f.scroll.height - f.scroll.paddingTop - f.scroll.paddingBottom)
                    assertEquals(natural, f.bounds(f.decisions))
                    // Details after the decision group retains normal document flow.
                    f.viewportHeight = f.dp(600); f.details.visibility = View.VISIBLE; f.ready(true)
                    assertEquals(natural, f.bounds(f.decisions))
                    assertTrue(f.bounds(f.details).top >= f.bounds(f.decisions).bottom)
                    // Real wrapped labels, narrow font1/font2: no compression to force fitting.
                    f.details.visibility = View.GONE
                    f.width = f.dp(240); f.viewportHeight = f.dp(180)
                    f.discard.text = "Discard preview without applying changes"
                    f.apply.text = "Apply this preview to the photo"
                    f.ready(false); val narrow = f.bounds(f.decisions)
                    val naturalHeight = f.panel.measuredHeight
                    f.ready(true)
                    assertEquals(naturalHeight, f.panel.measuredHeight)
                    assertEquals(narrow, f.bounds(f.decisions))
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

    /** Mirrors only the existing app's reserved-height contract, not its Heal state machine. */
    private class ReservedPanel(context: Context) : GalleryHealControlsLayout(context) {
        var reservation = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (reservation > measuredHeight) setMeasuredDimension(measuredWidthAndState,
                resolveSizeAndState(reservation, heightMeasureSpec, measuredState shl MEASURED_HEIGHT_STATE_SHIFT))
        }
    }

    private class Fixture(val context: Context, rtl: Boolean) {
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
        val selection = View(context)
        val panel = ReservedPanel(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(9), dp(11), dp(13))
            addView(TextView(context).apply { text = "Check the preview before applying." }, LinearLayout.LayoutParams(-1, -2))
            addView(selection, LinearLayout.LayoutParams(-1, dp(320)))
            addView(group, LinearLayout.LayoutParams(-1, -2))
            addView(details, LinearLayout.LayoutParams(-1, -2))
            reservedDecisionGroup = group
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(7), dp(9), dp(11))
            addView(View(context), LinearLayout.LayoutParams(-1, dp(96)))
            addView(panel, LinearLayout.LayoutParams(-1, -2))
            addView(View(context), LinearLayout.LayoutParams(-1, dp(600)))
        }
        val scroll = ScrollView(context).apply {
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setPadding(dp(13), dp(17), dp(19), dp(23))
            isSmoothScrollingEnabled = false
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        fun layout() {
            // Force descendant layout: fit/fallback is evaluated here, not on scroll events.
            panel.forceLayout(); group.forceLayout(); body.forceLayout(); scroll.forceLayout()
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, width, viewportHeight)
        }
        fun selecting() {
            panel.anchorReadyDecision = false; group.visibility = View.GONE; selection.visibility = View.VISIBLE
            layout()
        }
        fun ready(anchor: Boolean) {
            selection.visibility = View.GONE; group.visibility = View.VISIBLE; panel.anchorReadyDecision = anchor
            layout()
        }
        fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
            panel.offsetDescendantRectToMyCoords(view, it)
        }
    }
}
