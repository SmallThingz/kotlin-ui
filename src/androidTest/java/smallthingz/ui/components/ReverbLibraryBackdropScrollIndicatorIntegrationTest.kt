package smallthingz.ui.components

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.animation.ColorTransition
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbLibraryRevealLayer
import smallthingz.ui.scroll.NativeSourceRecyclerView

@RunWith(AndroidJUnit4::class)
class ReverbLibraryBackdropScrollIndicatorIntegrationTest {
    @Test fun retainedTransitionInkTracksSameColorGrainAndSettlesToMuted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var root: ReverbLibraryRevealLayer? = null
        withRetiringTestActivity(activity, root = { root }) {
            var animationsEnabled = false
            instrumentation.runOnMainSync {
                animationsEnabled = readCachedAnimatorDurationScale(activity) > 0f
            }
            assumeTrue("Requires a positive animation scale", animationsEnabled)

            lateinit var initial: ReverbEnvironment
            lateinit var list: NativeSourceRecyclerView
            instrumentation.runOnMainSync {
                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                    densityDpi = 160
                })
                val palette = NativeColorScheme.fallback(false).copy(
                    surface = 0xffaaaaaa.toInt(), onSurfaceVariant = Color.BLACK,
                    surfacePreset = NativeGrainPreset(backgroundGrain = 0f, foregroundGrain = 0f),
                )
                initial = ReverbEnvironment(context, false).withPalette(palette)
                list = NativeSourceRecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    itemAnimator = null
                    defaultFocusHighlightEnabled = false
                    adapter = Rows()
                    bindScrollIndicator(initial.appearance.scrollIndicator, palette.muted)
                }
                root = ReverbLibraryRevealLayer(context, initial, list).also {
                    it.bindScrollIndicatorViewport(list)
                    activity.setContentView(it)
                }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val layer = checkNotNull(root)
                assertTrue(layer.isAttachedToWindow && layer.isShown)
                layer.bind(initial, statusTop = 0, progress = 1f, visible = true)
                layer.measure(exact(120), exact(96))
                layer.layout(0, 0, 120, 96)
                assertTrue(list.canScrollVertically(1))
                val page = (layer.getChildAt(1) as ViewGroup).getChildAt(0)
                val drawable = page.background as NoiseDrawable
                val flatOld = textSurfaceRange(initial.palette.surface)
                assertEquals(flatOld, drawable.paintedTextSurface)

                val preferred = 0xff3a3a3a.toInt()
                val flat = initial.withPalette(initial.palette.copy(surface = Color.WHITE, onSurfaceVariant = preferred))
                val grained = flat.withPalette(flat.palette.copy(
                    surfacePreset = NativeGrainPreset(backgroundGrain = 1f, foregroundGrain = 0f)))
                val motion = paletteMotion(layer)
                try {
                    val scrim = initial.appearance.backdrops.panelScrimColor(flat.palette)
                    // Freeze elapsed time at the first retained paint while exercising real bind callbacks.
                    val state = ViewColorTransition::class.java.getDeclaredField("state")
                        .apply { isAccessible = true }.get(motion) as ColorTransition
                    assertTrue(state.retarget(intArrayOf(flat.palette.surface, scrim), Long.MAX_VALUE))
                    list.bindScrollIndicator(flat.appearance.scrollIndicator, flat.palette.muted)
                    layer.bind(flat, statusTop = 0, progress = 1f, visible = true)
                    assertEquals(flatOld, drawable.paintedTextSurface)
                    assertEquals("Preferred ink clears the flat old fill", preferred, thumbPixel(list))

                    layer.bind(grained, statusTop = 0, progress = 1f, visible = true)
                    val texturedOld = initial.appearance.backdrops.pageBackground(
                        initial.palette.copy(surfacePreset = grained.palette.surfacePreset)).paintedTextSurface
                    assertNotEquals(flatOld, texturedOld)
                    assertSame(drawable, page.background)
                    assertEquals("Preset-only update changes the still-displayed fill", texturedOld, drawable.paintedTextSurface)
                    assertEquals("Current grain changes the transition ink", Color.BLACK, thumbPixel(list))

                    motion.snap(grained.palette.surface, scrim)
                    assertEquals("Settled thumb restores its semantic color", preferred, thumbPixel(list))
                    layer.bind(flat, statusTop = 0, progress = 1f, visible = true)
                    assertSame(drawable, page.background)
                    assertEquals("A settled, unchanged color still accepts new grain",
                        textSurfaceRange(flat.palette.surface), drawable.paintedTextSurface)
                    assertEquals(preferred, thumbPixel(list))
                } finally {
                    motion.close()
                }
            }
        }
    }

    private fun paletteMotion(layer: ReverbLibraryRevealLayer): ViewColorTransition {
        val field = ReverbLibraryRevealLayer::class.java.getDeclaredField("paletteMotion\$delegate")
        return (field.apply { isAccessible = true }.get(layer) as Lazy<*>).value as ViewColorTransition
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun thumbPixel(list: NativeSourceRecyclerView): Int {
        val bitmap = Bitmap.createBitmap(list.width, list.height, Bitmap.Config.ARGB_8888)
        return try {
            list.draw(Canvas(bitmap))
            bitmap.getPixel(list.width - 3, 12)
        } finally {
            bitmap.recycle()
        }
    }

    private class Rows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 12

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24)
            }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }
}
