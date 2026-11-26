package smallthingz.ui.reverb

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Real shared header/About bindings in a modeled host window; not device/render evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReverbProductHeaderBrandTest {
    @SuppressLint("ResourceType") // A color drawable makes unchanged artwork paint directly observable.
    @Test fun homePresentationReusesArtworkAndAnchorWhileDefaultBindingRemainsUnoutlined() {
        withHeader { activity, header, root ->
            val identity = ReverbProductIdentity.Launcher(android.R.color.holo_blue_dark)
            var clicks = 0
            fun bind(outlined: Boolean, dark: Boolean = false) {
                header.bind(ReverbDesign.theme(activity, dark), identity,
                    "About fixture", { clicks++ }, null, ReverbHeaderAction("Settings", {}),
                    circularLauncherOutline = outlined)
                layout(root)
            }
            bind(false)
            val mark = nodes(header).filterIsInstance<ReverbIdentityMarkView>().single()
            val image = mark.getChildAt(1) as ImageView
            val artwork = image.drawable
            val color = (artwork as ColorDrawable).color
            val anchor = checkNotNull((header.getChildAt(0) as ReverbTopBar).centerAnchor)
            val bounds = boundsIn(root, mark)
            assertFalse(mark.clipToOutline)
            assertFalse(identity.circularOutline)

            for (dark in listOf(false, true, false)) {
                bind(true, dark)
                assertSame(anchor, (header.getChildAt(0) as ReverbTopBar).centerAnchor)
                assertSame(mark, nodes(header).filterIsInstance<ReverbIdentityMarkView>().single())
                assertSame(artwork, image.drawable)
                assertEquals(color, (image.drawable as ColorDrawable).color)
                assertEquals(bounds, boundsIn(root, mark))
                assertTrue(mark.clipToOutline)
                assertNull(image.imageTintList)
                assertNull(image.colorFilter)
                assertNull(mark.background)
                assertEquals(ImageView.ScaleType.FIT_CENTER, image.scaleType)
                assertEquals("About fixture", anchor.contentDescription)
                assertTrue(mark.width > 0 && mark.height > 0)
                val outline = Outline()
                mark.outlineProvider.getOutline(mark, outline)
                assertEquals(minOf(mark.width, mark.height) / 2f, outline.radius, .01f)
                assertFalse("Presentation must not mutate the retained product identity", identity.circularOutline)
            }
            assertEquals(0, clicks)
            assertTrue(anchor.performClick())
            assertEquals(1, clicks)

            // Omit the new option: existing callers keep their original presentation.
            header.bind(ReverbDesign.theme(activity, false), identity,
                "About fixture", {}, null, ReverbHeaderAction("Settings", {}))
            layout(root)
            assertFalse(mark.clipToOutline)
            assertSame(artwork, image.drawable)
            assertSame(anchor, (header.getChildAt(0) as ReverbTopBar).centerAnchor)
        }
    }

    @SuppressLint("ResourceType")
    @Test fun actualAboutOpenedFromOutlinedHomeKeepsOriginalUnoutlinedLauncherArtwork() {
        withHeader { activity, header, root ->
            val identity = ReverbProductIdentity.Launcher(android.R.color.holo_blue_dark)
            val theme = ReverbDesign.theme(activity, true)
            val details = ReverbAboutDetails("Fixture", "0.1.0", "Source", "Repository", "Unavailable")
            var opened = false
            header.bind(theme, identity, "About fixture", {
                opened = header.openAbout(theme, details, "About fixture", openRepository = { true })
            }, null, ReverbHeaderAction("Settings", {}), circularLauncherOutline = true)
            layout(root)
            val home = nodes(header).filterIsInstance<ReverbIdentityMarkView>().single()
            val homeImage = home.getChildAt(1) as ImageView
            val homeColor = (homeImage.drawable as ColorDrawable).color
            val anchor = checkNotNull((header.getChildAt(0) as ReverbTopBar).centerAnchor)
            assertTrue(home.clipToOutline)
            assertTrue(anchor.isAttachedToWindow && anchor.isShown && anchor.hasWindowFocus())
            assertTrue(anchor.performClick())
            assertTrue("Use the actual openAbout path, not a copied test identity", opened)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = checkNotNull(ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            val about = nodes(checkNotNull(dialog.window).decorView)
                .filterIsInstance<ReverbIdentityMarkView>().single()
            val aboutImage = about.getChildAt(1) as ImageView
            assertFalse("Home outline must not leak into About", about.clipToOutline)
            assertEquals(homeColor, (aboutImage.drawable as ColorDrawable).color)
            assertNull(aboutImage.imageTintList)
            assertNull(aboutImage.colorFilter)
            assertNull(about.background)
            assertEquals(ImageView.ScaleType.FIT_CENTER, aboutImage.scaleType)
            assertFalse(identity.circularOutline)
            assertTrue(home.clipToOutline)
            header.closeAbout()
            assertFalse(dialog.isShowing)
        }
    }

    private fun withHeader(action: (Activity, ReverbProductTopBar, FrameLayout) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val root = FrameLayout(activity)
        val header = ReverbProductTopBar(activity, ReverbDesign.theme(activity, false))
        root.addView(header, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(root)
        controller.windowFocusChanged(true)
        try { action(activity, header, root) }
        finally {
            header.closeAbout()
            controller.pause().stop().destroy()
        }
    }

    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun nodes(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(nodes(view.getChildAt(index)))
    }

    private fun boundsIn(root: ViewGroup, view: View): Rect = Rect(0, 0, view.width, view.height).also {
        root.offsetDescendantRectToMyCoords(view, it)
    }
}
