// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.graphics

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.R
import smallthingz.ui.components.CachedPathIconDrawable
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.reverb.LibraryBodyMode
import smallthingz.ui.reverb.PlayerTrimHeading
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbIncidentActions
import smallthingz.ui.reverb.ReverbIncidentHistory
import smallthingz.ui.reverb.ReverbIncidentLoadStatus
import smallthingz.ui.reverb.ReverbIncidentTexts
import smallthingz.ui.reverb.ReverbIncidentsContentView
import smallthingz.ui.reverb.ReverbLibraryStatusLabels
import smallthingz.ui.reverb.ReverbLibraryStatusView
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReverbRetainedGlyphCacheHostTest {
    @Test fun incidentStatusGlyphReleasesAndRebuilds() = withHost { activity, root, runtime ->
        val panel = ReverbIncidentsContentView(activity, ReverbEnvironment(activity, false).theme, incidentTexts,
            ReverbIncidentActions({ error("Unexpected toggle") }, { error("Unexpected delete") },
                { error("Unexpected group delete") }, { error("Unexpected copy") }),
            {}, runtime.frames, runtime.scope, { 0f }, root)
        try {
            val ready = ReverbIncidentHistory(status = ReverbIncidentLoadStatus.READY)
            panel.bind(ready)
            val glyph = descendants(panel).single { it.javaClass.simpleName == "IncidentStatusIcon" }
            verify(root, panel, glyph, "icon",
                hide = { panel.bind(ReverbIncidentHistory()) },
                show = { panel.bind(ready) })
        } finally {
            panel.dispose()
        }
    }

    @Test fun trimHeadingGlyphReleasesAndRebuilds() = withHost { activity, root, _ ->
        val heading = PlayerTrimHeading(activity, ReverbEnvironment(activity, false), R.string.reverb_library_empty)
        val glyph = descendants(heading).single { it.javaClass.simpleName == "PlayerGlyph" }
        verify(root, heading, glyph, "drawable")
    }

    @Test fun libraryEmptyGlyphReleasesAndRebuilds() = withHost { activity, root, runtime ->
        val nested = object : NativeVerticalNestedHost {
            override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
            override suspend fun preFling(availableY: Float) = 0f
            override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
        }
        val status = ReverbLibraryStatusView(activity, ReverbEnvironment(activity, false),
            runtime.frames, runtime.scope, nested, root, ReverbLibraryStatusLabels({ "Failure" }, { "Retry" })) { 0f }
        status.bind(LibraryBodyMode.EMPTY) { error("Empty Library has no Retry") }
        val symbol = descendants(status).single { it.javaClass.simpleName == "LibraryEmptySymbol" }
        val glyph = field(symbol, "glyph") as View
        verify(root, status, glyph, "icon")
    }

    private fun withHost(test: (Activity, FrameLayout, NativeModalWindowRuntime) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            NativeModalWindowRuntime(activity).use { runtime ->
                try {
                    test(activity, root, runtime)
                } finally {
                    root.removeAllViews()
                }
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun verify(
        root: FrameLayout, branch: View, glyph: View, drawableField: String,
        hide: () -> Unit = { branch.visibility = View.GONE },
        show: () -> Unit = { branch.visibility = View.VISIBLE },
    ) {
        val icon = field(glyph, drawableField) as CachedPathIconDrawable
        fun cacheField(name: String): Any? = CachedPathIconDrawable::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(icon)
        fun mask() = cacheField("maskBitmap") as Bitmap?
        fun assertReleased(previous: Bitmap) {
            assertNull(mask())
            assertNull(cacheField("maskCanvas"))
            assertFalse(previous.isRecycled)
            assertFalse(icon.isVisible)
        }
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 480)
        }

        root.addView(branch, FrameLayout.LayoutParams(360, 480))
        layout()
        assertTrue(glyph.isAttachedToWindow && glyph.isShown)
        val before = render(glyph)
        assertTrue(before.any { Color.alpha(it) != 0 })
        val original = checkNotNull(mask())
        assertNotNull(cacheField("maskCanvas"))

        hide()
        assertTrue(glyph.isAttachedToWindow)
        assertFalse(glyph.isShown)
        assertReleased(original)

        show()
        layout()
        assertTrue(glyph.isShown)
        assertTrue(icon.isVisible)
        assertArrayEquals(before, render(glyph))
        val replacement = checkNotNull(mask())
        assertNotSame(original, replacement)
        assertNotNull(cacheField("maskCanvas"))

        root.removeView(branch)
        assertFalse(glyph.isAttachedToWindow)
        assertReleased(replacement)
        root.addView(branch, FrameLayout.LayoutParams(360, 480))
        layout()
        assertTrue(glyph.isAttachedToWindow && glyph.isShown)
        assertTrue(icon.isVisible)
        assertArrayEquals(before, render(glyph))
        assertNotSame(replacement, checkNotNull(mask()))
    }

    private fun render(view: View): IntArray {
        val width = view.width
        val height = view.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private companion object {
        val incidentTexts = ReverbIncidentTexts(
            "Incidents", "Back", "Clear selection", { "$it selected" }, "Multi-select", "Select all", "Deselect all",
            "Delete selected", "Clear all", "Clear incidents?", "Cancel", "No incidents", "Nothing to show",
            "Loading", "Unavailable", "Stale", "Select", "Deselect", "Mark checked", "Mark unchecked", "Options", "Copy", "Delete",
        )
    }
}
