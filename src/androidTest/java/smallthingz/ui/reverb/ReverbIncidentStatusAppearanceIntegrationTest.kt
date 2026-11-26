package smallthingz.ui.reverb

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeDarkSurfaces
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

/** Detached binding endpoints; existing attached incident fixtures own action routing. */
@RunWith(AndroidJUnit4::class)
class ReverbIncidentStatusAppearanceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun normalStatusUsesHollowErrorAndCheckedThemeInk() = main {
        val amoled = amoled()
        assertTrue(amoled.surfacePreset is NativeDarkSurfacePreset)
        assertEquals(NativeDarkSurfaces.AMOLED, (amoled.surfacePreset as NativeDarkSurfacePreset).style)
        assertNotEquals("Witness that the ordinary AMOLED ink is not literal white", Color.White.toArgb(), amoled.ink)
        val gray = NativeDarkSurfacePreset.Amoled.copy(style = NativeDarkSurfaces.GRAY).colorScheme()
            .resolve(true, 28) { error("Fallback palette must not read system resources") }
        val cases = listOf(
            NativeColorScheme.fallback(false) to NativeColorScheme.fallback(false).ink,
            amoled to Color.White.toArgb(), gray to Color.White.toArgb(),
            ReverbDesign.theme(context, true).palette to Color.White.toArgb(),
            // A custom palette without a shared dark preset retains its own content ink.
            NativeColorScheme.fallback(true) to NativeColorScheme.fallback(true).ink,
        )
        for ((palette, checkedInk) in cases) {
            val card = card(palette)
            try {
                card.bind(Row("a", false), true, null, actions)
                place(card)
                val glyph = glyph(card)
                assertGlyph(glyph, ReverbIcon.Unchecked, palette.error, texts.markChecked)
                assertEquals(MaterialRoundedIcon.RadioButtonUnchecked, icon(glyph).materialIcon)
                val target = glyph.parent as ViewGroup
                val size = target.width to target.height
                assertEquals((48 * context.resources.displayMetrics.density).roundToInt(), target.width)
                assertTrue(target.height >= (46 * context.resources.displayMetrics.density).roundToInt())
                assertEquals(context.getString(R.string.reverb_incident_unchecked),
                    ViewCompat.getStateDescription(target.parent as View))

                // Same occurrence: color animation snaps while detached, without replacing the glyph view.
                card.bind(Row("a", true), true, null, actions)
                place(card)
                assertSame(glyph, glyph(card))
                assertGlyph(glyph, ReverbIcon.Checked, checkedInk, texts.markUnchecked)
                assertEquals(MaterialRoundedIcon.CheckCircle, icon(glyph).materialIcon)
                assertEquals(size, target.width to target.height)
                assertEquals(context.getString(R.string.reverb_incident_checked),
                    ViewCompat.getStateDescription(target.parent as View))
            } finally { card.unbind() }
        }
    }

    @Test fun selectionThemeAndOccurrenceReuseKeepStatusSeparate() = main {
        val dark = amoled()
        val light = NativeColorScheme.fallback(false)
        val card = card(dark)
        try {
            card.bind(Row("a", false), true, null, actions)
            place(card)
            val glyph = glyph(card)
            val body = (glyph.parent as View).parent as View
            for (acknowledged in listOf(false, true)) {
                card.bind(Row("a", acknowledged), true, false, actions)
                assertGlyph(glyph, ReverbIcon.Unchecked, dark.muted, texts.select)
                assertNull("Selection must not publish acknowledgement state", ViewCompat.getStateDescription(body))
                assertTrue(card.bindSelection("a", true))
                assertGlyph(glyph, ReverbIcon.Checked, dark.onPrimaryContainer, texts.deselect)
                assertNull(ViewCompat.getStateDescription(body))
                assertTrue(card.bindSelection("a", null))
                assertGlyph(glyph, if (acknowledged) ReverbIcon.Checked else ReverbIcon.Unchecked,
                    if (acknowledged) Color.White.toArgb() else dark.error,
                    if (acknowledged) texts.markUnchecked else texts.markChecked)
            }
            // Palette replacement must not retain white on a light background.
            card.setAppearance(appearance(light))
            assertGlyph(glyph, ReverbIcon.Checked, light.ink, texts.markUnchecked)
            card.setAppearance(appearance(dark))
            assertGlyph(glyph, ReverbIcon.Checked, Color.White.toArgb(), texts.markUnchecked)
            val custom = dark.copy(acknowledgedStatusTintOverride = light.primary)
            card.setAppearance(appearance(custom))
            assertGlyph(glyph, ReverbIcon.Checked, light.primary, texts.markUnchecked)
            assertTrue(card.bindSelection("a", false))
            assertGlyph(glyph, ReverbIcon.Unchecked, custom.muted, texts.select)
            assertTrue(card.bindSelection("a", null))
            assertGlyph(glyph, ReverbIcon.Checked, light.primary, texts.markUnchecked)
            card.bind(Row("a", false), true, null, actions)
            assertGlyph(glyph, ReverbIcon.Unchecked, custom.error, texts.markChecked)
            card.bind(Row("a", true), true, null, actions)
            card.setAppearance(appearance(custom.copy(acknowledgedStatusTintOverride = null)))
            assertGlyph(glyph, ReverbIcon.Checked, Color.White.toArgb(), texts.markUnchecked)
            assertFalse(card.bindSelection("stale-other-key", false))
            assertGlyph(glyph, ReverbIcon.Checked, Color.White.toArgb(), texts.markUnchecked)

            card.bind(Row("b", false), false, null, actions)
            assertSame(glyph, glyph(card))
            assertGlyph(glyph, ReverbIcon.Unchecked, dark.error, null)
            card.unbind()
            assertNull(glyph.contentDescription)
            card.bind(Row("c", true), true, null, actions)
            assertSame(glyph, glyph(card))
            assertGlyph(glyph, ReverbIcon.Checked, Color.White.toArgb(), texts.markUnchecked)
        } finally { card.unbind() }
    }

    private fun amoled() = NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 28) {
        error("Fallback palette must not read system resources")
    }

    private fun appearance(palette: NativeColorScheme): IncidentCardAppearance {
        val style = ReverbDesign.appearance
        return IncidentCardAppearance(palette, style.typography[NativeTypographyRole.BODY_MEDIUM],
            style.typography[NativeTypographyRole.BODY_SMALL], Density(context), createFontFamilyResolver(context),
            style.groupSurfaces.recordingRow)
    }

    private fun card(palette: NativeColorScheme) = ReverbIncidentCardView(context, appearance(palette),
        object : IncidentCardMenuHost {
            override fun show(request: IncidentCardMenuRequest) = error("Binding must not open a menu")
            override fun update(request: IncidentCardMenuRequest) = error("No menu was opened")
            override fun release(identity: IncidentCardMenuIdentity) = error("No menu was opened")
        }, texts, object : IncidentCardAuthority {
            override fun activate(row: ReverbIncidentOccurrence, showOptions: () -> Boolean): Boolean =
                error("Binding must not activate a row")
            override fun menuTarget(row: ReverbIncidentOccurrence, requiresMutation: Boolean): ReverbIncidentTarget? =
                error("Binding must not request a menu target")
        })

    private fun place(card: View) {
        val width = (320 * context.resources.displayMetrics.density).roundToInt()
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
    }

    private fun glyph(card: View): ReverbGlyphView = descendants(card).filterIsInstance<ReverbGlyphView>().single()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun icon(view: ReverbGlyphView) = ReverbGlyphView::class.java.getDeclaredField("currentIcon")
        .apply { isAccessible = true }.get(view) as ReverbIcon
    private fun assertGlyph(view: ReverbGlyphView, expectedIcon: ReverbIcon, tint: Int, label: String?) {
        assertEquals(expectedIcon, icon(view))
        assertEquals(tint, ReverbGlyphView::class.java.getDeclaredField("displayedColor")
            .apply { isAccessible = true }.getInt(view))
        assertEquals(label, view.contentDescription?.toString())
    }
    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }

    private data class Row(override val key: String, override val acknowledged: Boolean) :
        ReverbIncidentOccurrence, ReverbIncidentTarget {
        override val target: ReverbIncidentTarget get() = this
        override val timeLabel = "12:00"
        override val processLabel = "PID 42"
        override val liveEnd: ReverbIncidentLiveEnd? = null
        override fun matches(reference: ReverbIncidentTarget) = reference is Row && reference.key == key
    }
    private val actions = ReverbIncidentActions({ error("Unexpected acknowledgement mutation") },
        { error("Unexpected deletion") }, { error("Unexpected batch deletion") }, { error("Unexpected copy") })
    private val texts = ReverbIncidentTexts(
        "Incidents", "Back", "Clear selection", { "$it selected" }, "Multi-select", "Select all", "Deselect all",
        "Delete selected", "Clear all", "Clear incidents?", "Cancel", "No incidents", "Nothing to show",
        "Loading", "Unavailable", "Stale", "Select", "Deselect", "Mark checked", "Mark unchecked", "Options", "Copy", "Delete",
    )
}
