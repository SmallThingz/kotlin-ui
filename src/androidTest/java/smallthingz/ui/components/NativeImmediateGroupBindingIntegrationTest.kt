package smallthingz.ui.components

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.textSurfaceRange
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeImmediateGroupBindingIntegrationTest {
    @Test fun immediateBindingAppliesAndClearsPresetWithoutReplacingContentOrRepeatingInkInvalidation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val recipe = NativeGroupSurfaceRecipes.Default.settingsGroup
            var notifications = 0
            val group = recipe.create(context) { notifications++ }
            val child = View(context)
            group.addRow(child, NativeGroupLayout.LayoutParams(-1, 48))
            val parent = child.parent
            val palette = NativeColorScheme.fallback(false).copy(surfaceContainerHigh = Color.BLACK)
            recipe.bindImmediately(group, palette)
            notifications = 0

            val textured = palette.copy(surfacePreset = NativeGrainPreset(0f, .5f))
            recipe.bindImmediately(group, textured)
            assertEquals(Color.BLACK, group.paintedFillColor)
            assertEquals(textSurfaceRange(Color.BLACK, 0xff090909.toInt()), group.paintedTextSurface)
            assertEquals(1, notifications)
            recipe.bindImmediately(group, textured)
            assertEquals(1, notifications)

            recipe.bindImmediately(group, palette)
            assertEquals(textSurfaceRange(Color.BLACK), group.paintedTextSurface)
            assertEquals(2, notifications)
            assertSame(parent, child.parent)
            assertSame(child, group.pointerContent.getChildAt(0))
        }
    }

    @Test fun presetOutlineWidthRebindsLaidOutGroupsAndRestoresTheirDefaultStroke() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val config = Configuration(target.resources.configuration).apply { densityDpi = 420 }
            val context = target.createConfigurationContext(config)
            assertEquals(2.625f, context.resources.displayMetrics.density, 0f)
            val recipe = NativeGroupSurfaceRecipes.Default.settingsGroup
            val palette = NativeColorScheme.fallback(false).copy(
                surfaceContainerHigh = Color.BLACK,
                onSurface = Color.WHITE,
            )
            val preset = NativeDarkSurfacePreset.Amoled.copy(
                backgroundGrain = 0f,
                foregroundGrain = 0f,
                borderLightness = 1f,
            )
            val width = 160
            val height = 128
            val middle = height / 2
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                for (edges in listOf(null, NativeGroupEdges(false, false),
                    NativeGroupEdges(false, false, squareTop = true, squareBottom = true))) {
                    val group = recipe.create(context)
                    group.setJoinedEdges(edges)
                    group.setJoinedSeparatorColor(Color.RED)
                    group.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    group.layout(0, 0, width, height)
                    val cornerWasInside = group.containsVisualPoint(0f, 0f)

                    fun draw() {
                        bitmap.eraseColor(Color.TRANSPARENT)
                        group.draw(Canvas(bitmap))
                        assertEquals(cornerWasInside, group.containsVisualPoint(0f, 0f))
                        if (edges != null && !edges.squareTop) {
                            assertEquals(Color.RED, bitmap.getPixel(width / 2, 0))
                            assertEquals(Color.BLACK, bitmap.getPixel(width / 2, 1))
                        }
                    }

                    recipe.bindImmediately(group, palette)
                    draw()
                    val defaultEdge = bitmap.getPixel(2, middle)
                    assertTrue("Missing default 1 dp stroke for $edges", Color.red(defaultEdge) > 0)
                    assertEquals(Color.BLACK, bitmap.getPixel(3, middle))

                    recipe.bind(group, palette.copy(surfacePreset = preset.copy(borderWidthDp = 2f)))
                    draw()
                    assertEquals(Color.WHITE, bitmap.getPixel(4, middle))
                    assertEquals(Color.BLACK, bitmap.getPixel(6, middle))

                    recipe.bindImmediately(group, palette.copy(surfacePreset = preset.copy(borderWidthDp = 3f)))
                    draw()
                    assertEquals(Color.WHITE, bitmap.getPixel(6, middle))

                    recipe.bindImmediately(group, palette.copy(surfacePreset = preset.copy(borderWidthDp = 0f)))
                    draw()
                    assertEquals(Color.BLACK, bitmap.getPixel(0, middle))

                    recipe.bind(group, palette.copy(surfacePreset = NativeGrainPreset(0f, 0f)))
                    draw()
                    assertEquals(defaultEdge, bitmap.getPixel(2, middle))
                    assertEquals(Color.BLACK, bitmap.getPixel(3, middle))

                    recipe.bindImmediately(group, palette.copy(surfacePreset = preset.copy(borderWidthDp = 3f)))
                    draw()
                    assertEquals(Color.WHITE, bitmap.getPixel(6, middle))
                    recipe.bindImmediately(group, palette)
                    draw()
                    assertEquals(defaultEdge, bitmap.getPixel(2, middle))
                    assertEquals(Color.BLACK, bitmap.getPixel(3, middle))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun immediateBindingRetiresAnAttachedGroupsPreviousPaletteTransition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var group: NativeGroupLayout
        val recipe = NativeGroupSurfaceRecipes.Default.settingsGroup
        val palette = NativeColorScheme.fallback(false).copy(surfaceContainerHigh = Color.BLACK)
        withLaidOutPaintActivity(mount = { activity ->
            group = recipe.create(activity)
            recipe.bind(group, palette)
            listOf(group, group.pointerContent)
        }) {
            instrumentation.runOnMainSync {
                recipe.bind(group, palette.copy(surfaceContainerHigh = Color.WHITE))
                recipe.bindImmediately(group, palette.copy(surfaceContainerHigh = Color.RED))
                assertEquals(Color.RED, group.paintedFillColor)
                assertEquals(textSurfaceRange(Color.RED), group.paintedTextSurface)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(Color.RED, group.paintedFillColor)
                assertEquals(textSurfaceRange(Color.RED), group.paintedTextSurface)
            }
        }
    }
}

/** Shared only by these paint tests; admission and teardown follow the outline fixture's lifecycle contract. */
internal fun withLaidOutPaintActivity(mount: (Activity) -> List<View>, assertions: () -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
        NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val layoutReady = CountDownLatch(1)
    val destroyed = CountDownLatch(1)
    var root: FrameLayout? = null
    var required = emptyList<View>()
    var listener: View.OnLayoutChangeListener? = null
    var registered = false
    var phase = "mount"
    var primaryFailure: Throwable? = null
    fun admitted() = !activity.isFinishing && !activity.isDestroyed && required.isNotEmpty() &&
        required.all { it.isAttachedToWindow && it.isShown && it.isLaidOut && it.width > 0 && it.height > 0 }
    fun snapshot(): String {
        var state = "phase=$phase"
        try {
            instrumentation.runOnMainSync {
                state += " activity=${System.identityHashCode(activity)} finishing=${activity.isFinishing}" +
                    " destroyed=${activity.isDestroyed} windowFocus=${activity.hasWindowFocus()} views=" +
                    required.joinToString { "${it.javaClass.simpleName}(attached=${it.isAttachedToWindow}," +
                        "shown=${it.isShown},laidOut=${it.isLaidOut},size=${it.width}x${it.height})" }
            }
        } catch (failure: Throwable) { state += " diagnosticFailure=${failure.javaClass.name}" }
        return state
    }
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(value: Activity) { if (value === activity) destroyed.countDown() }
        override fun onActivityCreated(value: Activity, saved: Bundle?) = Unit
        override fun onActivityStarted(value: Activity) = Unit
        override fun onActivityResumed(value: Activity) = Unit
        override fun onActivityPaused(value: Activity) = Unit
        override fun onActivityStopped(value: Activity) = Unit
        override fun onActivitySaveInstanceState(value: Activity, saved: Bundle) = Unit
    }
    try {
        instrumentation.runOnMainSync {
            activity.application.registerActivityLifecycleCallbacks(lifecycle)
            registered = true
            check(!activity.isFinishing && !activity.isDestroyed) { "Host already retired" }
            required = mount(activity)
            check(required.isNotEmpty())
            val container = FrameLayout(activity).also { root = it }
            container.addView(required.first(), FrameLayout.LayoutParams(-1, -1))
            listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (admitted()) layoutReady.countDown()
            }.also(container::addOnLayoutChangeListener)
            activity.setContentView(container)
        }
        phase = "await-layout"
        assertTrue("Attached visible fixture layout did not arrive", layoutReady.await(5, TimeUnit.SECONDS))
        phase = "assertions"
        instrumentation.runOnMainSync { assertTrue("Fixture retired after layout", admitted()) }
        assertions()
    } catch (failure: Throwable) {
        val contextual = AssertionError(snapshot(), failure)
        primaryFailure = contextual
        throw contextual
    } finally {
        phase = "cleanup"
        var cleanupFailure: Throwable? = null
        fun retain(failure: Throwable) {
            val previous = cleanupFailure
            if (previous == null) cleanupFailure = failure else if (previous !== failure) previous.addSuppressed(failure)
        }
        try {
            instrumentation.runOnMainSync {
                try { listener?.let { root?.removeOnLayoutChangeListener(it) } }
                catch (failure: Throwable) { retain(failure) }
                try {
                    if (activity.isDestroyed) destroyed.countDown()
                    else if (!activity.isFinishing) activity.finish()
                } catch (failure: Throwable) { retain(failure) }
            }
            assertTrue("Owned Activity destruction did not complete", destroyed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertTrue("Activity not destroyed after callback", activity.isDestroyed) }
        } catch (failure: Throwable) { retain(failure) }
        finally {
            try {
                instrumentation.runOnMainSync {
                    if (registered) activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
                }
            } catch (failure: Throwable) { retain(failure) }
        }
        cleanupFailure?.let { failure ->
            val primary = primaryFailure
            if (primary != null) primary.addSuppressed(failure) else throw AssertionError(snapshot(), failure)
        }
    }
}
