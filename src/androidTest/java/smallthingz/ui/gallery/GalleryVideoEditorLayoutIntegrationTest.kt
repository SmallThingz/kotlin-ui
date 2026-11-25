package smallthingz.ui.gallery

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import kotlin.math.roundToInt

/** Synthetic retained views only: no media, decoder, global settings or private drafts. */
@RunWith(AndroidJUnit4::class)
class GalleryVideoEditorLayoutIntegrationTest {
    @Test fun boundedResizeRetainsParentsFocusAndRestoresScrollRanges() = withFixture { f ->
        f.layout(360, 700)
        assertEquals(f.dp(250), f.preview.height)
        assertFalse(f.root.canScrollVertically(1))
        assertFalse(f.bodyScroll.canScrollVertically(1))
        assertTrue(f.controlsScroll.canScrollVertically(1))
        assertTrue(f.last.requestFocus())
        val parents = listOf(f.header.parent, f.preview.parent, f.controls.parent, f.last.parent)
        val attachments = f.attachments
        val detachments = f.detachments

        f.layout(360, 128)
        assertEquals(f.dp(48) + f.dp(56), f.preview.height)
        assertEquals(f.dp(56), f.image.height)
        assertEquals(f.controls.measuredHeight, f.controlsScroll.height)
        assertFalse(f.controlsScroll.canScrollVertically(1))
        assertFalse(f.controlsScroll.canScrollVertically(-1))
        assertFalse(f.root.canScrollVertically(1))
        assertTrue(f.bodyScroll.canScrollVertically(1) || f.bodyScroll.canScrollVertically(-1))
        val headerTop = f.header.top
        f.bodyScroll.scrollTo(0, Int.MAX_VALUE)
        assertEquals(headerTop, f.header.top)
        assertLastVisible(f)
        assertTrue(f.last.hasFocus())

        f.layout(360, 700)
        assertEquals(f.dp(250), f.preview.height)
        assertEquals(0, f.bodyScroll.scrollY)
        assertFalse(f.bodyScroll.canScrollVertically(1))
        assertFalse(f.bodyScroll.canScrollVertically(-1))
        assertTrue(f.controlsScroll.canScrollVertically(1) || f.controlsScroll.canScrollVertically(-1))
        assertEquals(parents, listOf(f.header.parent, f.preview.parent, f.controls.parent, f.last.parent))
        assertTrue(f.last.hasFocus())
        assertEquals(attachments, f.attachments)
        assertEquals(detachments, f.detachments)
    }

    @Test fun actualTransportGrowthAtSameViewportTriggersFullControlsOverflow() = withFixture { f ->
        f.layout(360, 260)
        assertFalse(f.bodyScroll.canScrollVertically(1))
        f.transport.minimumHeight = f.dp(120)
        f.layout(360, 260)
        assertEquals(f.dp(120) + f.dp(56), f.preview.height)
        assertEquals(f.dp(56), f.image.height)
        assertEquals(f.controls.measuredHeight, f.controlsScroll.height)
        assertFalse(f.controlsScroll.canScrollVertically(1))
        assertTrue(f.bodyScroll.canScrollVertically(1))
        f.transport.minimumHeight = f.dp(48)
        f.layout(360, 260)
        assertFalse(f.bodyScroll.canScrollVertically(1))
        assertEquals(0, f.bodyScroll.scrollY)
    }

    @Test fun oversizedHeaderUsesRootScrollWithoutReparentingOrTrappedInnerRange() = withFixture { f ->
        f.layout(360, 700)
        val headerParent = f.header.parent
        f.header.minimumHeight = f.dp(160)
        f.layout(360, 96)
        assertSame(headerParent, f.header.parent)
        assertEquals(f.dp(160), f.header.height)
        assertEquals(f.dp(56), f.image.height)
        assertTrue(f.root.canScrollVertically(1))
        assertFalse(f.bodyScroll.canScrollVertically(1))
        assertFalse(f.controlsScroll.canScrollVertically(1))
        f.root.scrollTo(0, Int.MAX_VALUE)
        assertLastVisible(f)
        f.header.minimumHeight = f.dp(48)
        f.layout(360, 700)
        assertEquals(0, f.root.scrollY)
        assertFalse(f.root.canScrollVertically(1))
    }

    @Test fun headerLeavingPositiveButUnusableStripScrollsWholeColumn() = withFixture { f ->
        f.last.minimumHeight = f.dp(80)
        for ((headerHeight, viewportHeight) in listOf(80 to 96, 48 to 112)) {
            f.header.minimumHeight = f.dp(headerHeight)
            f.layout(360, viewportHeight)
            assertTrue(f.root.canScrollVertically(1) || f.root.canScrollVertically(-1))
            assertFalse(f.bodyScroll.canScrollVertically(1))
            assertFalse(f.bodyScroll.canScrollVertically(-1))
            assertFalse(f.controlsScroll.canScrollVertically(1))
            assertFalse(f.controlsScroll.canScrollVertically(-1))
            f.root.scrollTo(0, Int.MAX_VALUE)
            assertLastVisible(f)
        }
        f.header.minimumHeight = f.dp(48)
        f.layout(360, 700)
        assertEquals(0, f.root.scrollY)
        assertFalse(f.root.canScrollVertically(1))
    }

    private fun assertLastVisible(f: Fixture) {
        val bounds = Rect()
        f.last.getDrawingRect(bounds)
        f.root.offsetDescendantRectToMyCoords(f.last, bounds)
        bounds.offset(0, -f.root.scrollY)
        val visible = Rect(0, 0, f.root.width, f.root.height)
        for (ancestor in listOf(f.bodyScroll, f.controlsScroll)) {
            val clip = Rect(ancestor.scrollX, ancestor.scrollY,
                ancestor.scrollX + ancestor.width, ancestor.scrollY + ancestor.height)
            f.root.offsetDescendantRectToMyCoords(ancestor, clip)
            clip.offset(0, -f.root.scrollY)
            assertTrue(visible.intersect(clip))
        }
        assertTrue(bounds.width() > 0 && bounds.height() > 0)
        assertTrue("Full action $bounds must fit visible viewport $visible", visible.contains(bounds))
    }

    private class Fixture(context: Context) {
        private val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val header = View(context).apply { minimumHeight = dp(48) }
        val image = View(context)
        val transport = LinearLayout(context).apply { minimumHeight = dp(48) }
        val preview = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(transport, LinearLayout.LayoutParams(-1, -2))
        }
        val last = TextView(context).apply {
            text = "Last editing action"
            isFocusableInTouchMode = true
            minimumHeight = dp(48)
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(context), LinearLayout.LayoutParams(-1, dp(852)))
            addView(last, LinearLayout.LayoutParams(-1, -2))
        }
        var attachments = 0
        var detachments = 0
        val root = GalleryVideoEditorLayout(context, header, preview, controls,
            measureTransportHeight = { width ->
                transport.measure(exact(width), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                transport.measuredHeight
            }, preferredPreviewHeight = { _, _, _ -> dp(250) })
        val column get() = root.getChildAt(0) as ViewGroup
        val bodyScroll get() = column.getChildAt(1) as ScrollView
        val body get() = bodyScroll.getChildAt(0) as ViewGroup
        val controlsScroll get() = body.getChildAt(1) as ScrollView

        init {
            preview.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { attachments++ }
                override fun onViewDetachedFromWindow(v: View) { detachments++ }
            })
        }

        fun layout(width: Int, height: Int) {
            root.measure(exact(dp(width)), exact(dp(height)))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }

        private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        try {
            instrumentation.runOnMainSync {
                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                    fontScale = 2f
                })
                fixture = Fixture(context).also { activity.setContentView(it.root) }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val current = checkNotNull(fixture)
                assertTrue(current.root.isAttachedToWindow)
                block(current)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
