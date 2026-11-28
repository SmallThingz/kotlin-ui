package smallthingz.ui.reverb

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeThemedToolbarAction
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.NativeTopBarAction
import smallthingz.ui.components.nativeTopBarHitIndex
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.inject.InjectAppearance
import smallthingz.ui.inject.InjectDesign
import smallthingz.ui.text.style.TextOverflow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rAU-w199dp-h421dp-464dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReverbTitledTopBarLayoutTest {
    /** Same-process mask reuse across separate windows; software raster, not GPU readback. */
    @Test
    @Config(sdk = [35], qualifiers = "en-rAU-w320dp-h640dp-420dpi")
    fun catalogBackThenSettingsBackKeepsItsRenderedGlyphAfterCatalogDetaches() {
        fun renderAndClose(catalog: Boolean): Int {
            val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
            try {
                val activity = controller.get()
                val ui = InjectAppearance(activity, InjectDesign.theme(activity, dark = true))
                val density = activity.resources.displayMetrics.density
                val style = ui.defaults.toolbars
                val root = FrameLayout(activity).apply {
                    background = ui.defaults.backdrops.pageBackground(ui.scheme)
                }
                activity.setContentView(root)
                controller.windowFocusChanged(true)
                var clicks = 0
                val onBack = { clicks++; activity.finish() }
                val back: NativeTopBarAction
                val toolbar: View
                if (catalog) {
                    // AvailableCatalogToolbar's production action/surface composition.
                    fun action(label: String, icon: MaterialRoundedIcon, shared: Boolean,
                        click: () -> Unit): NativeThemedToolbarAction {
                        val tone = if (shared) NativeToolbarActionTone.NEUTRAL else NativeToolbarActionTone.MUTED
                        val tint = style.actionTint(ui.scheme, tone)
                        return NativeThemedToolbarAction(activity, density, ui.scheme, { root }, label,
                            selectionWrapper = true, sharedSurface = shared, rolePresent = true, style = style).apply {
                            setGlyph(ui.icon(icon, tint), tint)
                            bindToolbarAction(density, ui.scheme, true, tone, click)
                        }
                    }
                    back = action("Back", MaterialRoundedIcon.ArrowBack, false, onBack)
                    val endActions = listOf(
                        action("Install plugin from ZIP", MaterialRoundedIcon.Add, true) {},
                        action("Manage catalogs", MaterialRoundedIcon.FormatListBulleted, true) {},
                    )
                    val target = (style.action.minimumTargetDp * density).roundToInt()
                    val group = NativeGroupLayout(activity, style.action.radiusDp, LinearLayout.HORIZONTAL).apply {
                        setPaletteImmediately(style.actionFill(ui.scheme),
                            style.actionOutline(ui.scheme, NativeToolbarActionTone.NEUTRAL))
                        endActions.forEach { addRow(it, NativeGroupLayout.LayoutParams(target, target)) }
                    }
                    toolbar = ReverbSelectionTopBarLayout(activity, back,
                        ui.paragraph("Available plugins", style.selectionTitleRole), group, endActions).also {
                        NativeToolbarBackground(it, style, ui.scheme)
                    }
                } else {
                    // PermissionsActivity uses the inputRoot overload and a fresh appearance.
                    back = ui.semanticIconAction({ root }, "Back", MaterialRoundedIcon.ArrowBack, onBack)
                    toolbar = ui.centeredToolbar(back, "Settings")
                }
                root.addView(toolbar, FrameLayout.LayoutParams(-1, -2))
                val width = (320f * density).roundToInt()
                val height = (640f * density).roundToInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                assertTrue(back.visual.isAttachedToWindow)
                val visual = Rect(0, 0, back.visual.width, back.visual.height)
                root.offsetDescendantRectToMyCoords(back.visual, visual)
                val glyphSize = (style.action.glyphDp * density).roundToInt()
                val x = visual.left + ((visual.width() - glyphSize) / 2f).roundToInt()
                val y = visual.top + ((visual.height() - glyphSize) / 2f).roundToInt()
                val tint = style.actionTint(ui.scheme, NativeToolbarActionTone.MUTED)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val ink = try {
                    fun capture(): IntArray {
                        bitmap.eraseColor(0)
                        // Traverse the attached page, including toolbar and rounded surface clips.
                        root.draw(Canvas(bitmap))
                        val pixels = IntArray(glyphSize * glyphSize)
                        bitmap.getPixels(pixels, 0, glyphSize, x, y, glyphSize, glyphSize)
                        return IntArray(pixels.size) { if (pixels[it] == tint) 1 else 0 }
                    }
                    val first = capture()
                    assertTrue("${if (catalog) "Catalog" else "Settings"}: actual opaque arrow ink",
                        first.sum() > glyphSize)
                    assertArrayEquals("Warm drawable cache retains its arrow", first, capture())
                    first
                } finally { bitmap.recycle() }
                assertTrue(back.accessibilityAction.performClick())
                assertEquals(1, clicks)
                assertTrue(activity.isFinishing)
                root.removeAllViews()
                assertFalse("Retire the previous toolbar before creating the next window", back.isAttachedToWindow)
                return ink.sum()
            } finally {
                controller.pause().stop().destroy()
            }
        }

        val catalog = renderAndClose(catalog = true)
        val settings = renderAndClose(catalog = false)
        val reopenedSettings = renderAndClose(catalog = false)
        // Grain behind antialiased edge pixels may affect rounding; opaque arrow coverage must remain.
        for (ink in listOf(settings, reopenedSettings)) {
            assertEquals("Both Settings instances retain Catalog's visible ArrowBack coverage",
                catalog.toDouble(), ink.toDouble(), catalog * .05)
        }
    }

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
