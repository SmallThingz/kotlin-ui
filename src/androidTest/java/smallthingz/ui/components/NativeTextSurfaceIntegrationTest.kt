package smallthingz.ui.components

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.ToggleRow
import smallthingz.ui.common.ToggleState
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.text.view.ParagraphView

/** Scoped current-paint and ownership contracts; not performance or hardware-pixel qualification. */
@RunWith(AndroidJUnit4::class)
class NativeTextSurfaceIntegrationTest {
    @Test fun samplingRequiresScopeAndRejectsUnknownOwnOrInterveningPaint() = onMain { context ->
        val scope = Scope(context).apply { background = NoiseDrawable(Color.WHITE, null) }
        val wrapper = FrameLayout(context)
        val label = View(context)
        scope.addView(wrapper)
        wrapper.addView(label)
        assertEquals(textSurfaceRange(Color.WHITE), NativeTextSurface.sample(label))
        label.background = ColorDrawable(Color.BLACK)
        assertEquals(0L, NativeTextSurface.sample(label))
        label.background.alpha = 0
        assertEquals(textSurfaceRange(Color.WHITE), NativeTextSurface.sample(label))
        wrapper.background = ColorDrawable(Color.WHITE)
        assertEquals("Unknown paint must not sample through to the page", 0L, NativeTextSurface.sample(label))
        wrapper.background = null
        scope.removeView(wrapper)
        val unscoped = FrameLayout(context).apply { background = NoiseDrawable(Color.WHITE, null) }
        unscoped.addView(wrapper)
        assertEquals("A known underlay alone is not opt-in", 0L, NativeTextSurface.sample(label))
    }

    @Test fun nearestGroupWinsWithoutFallingThroughUnknownOrProgressPaint() = onMain { context ->
        val scope = Scope(context).apply { background = NoiseDrawable(Color.WHITE, null) }
        val group = NativeGroupLayout(context)
        val label = View(context)
        group.addRow(label, NativeGroupLayout.LayoutParams(100, 40))
        scope.addView(group)
        group.setPaletteImmediately(Color.BLACK, Color.TRANSPARENT)
        assertEquals(textSurfaceRange(Color.BLACK), NativeTextSurface.sample(label))
        group.setSurfacePreset(NativeDarkSurfacePreset.Amoled)
        assertEquals(textSurfaceRange(Color.BLACK, 0xff090909.toInt()), NativeTextSurface.sample(label))
        group.setBackgroundProgress(.5f, Color.WHITE)
        assertEquals(0L, NativeTextSurface.sample(label))
        group.setBackgroundProgress(0f, Color.WHITE)
        group.setSurfacePreset(null)
        group.setPaletteImmediately(0x80ffffff.toInt(), Color.TRANSPARENT)
        assertEquals("Translucent group is unknown, not the opaque page above it", 0L, NativeTextSurface.sample(label))
    }

    @Test fun onlyExplicitOpaqueOwnedChildrenPruneAndTheChangedOwnerAlwaysTraverses() = mounted { root ->
        val direct = Target(root.context).also(root::addView)
        fun group(owned: Boolean = true, fill: Int = Color.WHITE,
                  alpha: Float = 1f, progress: Boolean = false): Pair<NativeGroupLayout, Target> {
            val group = NativeGroupLayout(root.context, onPaintedSurfaceChanged = {})
            group.setPaletteImmediately(fill, Color.TRANSPARENT)
            group.alpha = alpha
            if (progress) group.setBackgroundProgress(.5f, Color.BLACK)
            if (owned) assertSame(group, NativeTextSurface.ownGroupInk(group))
            val target = Target(root.context)
            group.addRow(target, NativeGroupLayout.LayoutParams(100, 40))
            root.addView(group)
            return group to target
        }
        val opaque = group()
        val ordinary = group(owned = false)
        val translucent = group(fill = 0x80ffffff.toInt())
        val unknown = group(fill = Color.TRANSPARENT)
        val progress = group(progress = true)
        val faded = group(alpha = .5f)
        val nested = group()
        root.removeView(nested.first)
        opaque.first.addRow(nested.first, NativeGroupLayout.LayoutParams(100, 40))
        val all = listOf(direct, opaque.second, ordinary.second, translucent.second,
            unknown.second, progress.second, faded.second, nested.second)
        all.forEach { assertTrue(it.isAttachedToWindow); it.calls = 0 }
        NativeTextSurface.invalidate(root)
        all.forEach { assertEquals(if (it === opaque.second || it === nested.second) 0 else 1, it.calls) }
        all.forEach { it.calls = 0 }
        opaque.first.setPaletteImmediately(Color.BLACK, Color.TRANSPARENT)
        assertEquals("Origin is never pruned by its own opaque ownership", 1, opaque.second.calls)
        assertEquals("Independent opaque nested owner remains pruned", 0, nested.second.calls)
        assertEquals(1, all.sumOf { it.calls })
        opaque.first.setTextSurfaceInkInvalidator(null)
        all.forEach { it.calls = 0 }
        NativeTextSurface.invalidate(root)
        assertEquals("Revoking ownership restores conservative traversal", 1, opaque.second.calls)
        assertEquals(0, nested.second.calls)
    }

    @Test fun detachedAndUnscopedTargetsDoNotReceiveCallbacksAndReattachUsesLiveAncestry() = mounted { root ->
        val child = FrameLayout(root.context)
        val target = Target(root.context)
        child.addView(target)
        root.addView(child)
        NativeTextSurface.invalidate(child)
        assertEquals(1, target.calls)
        root.removeView(child)
        assertFalse(target.isAttachedToWindow)
        NativeTextSurface.invalidate(child)
        NativeTextSurface.invalidate(root)
        assertEquals(1, target.calls)
        val outside = FrameLayout(root.context)
        (root.parent as ViewGroup).addView(outside)
        try {
            outside.addView(child)
            assertTrue(target.isAttachedToWindow)
            NativeTextSurface.invalidate(outside)
            NativeTextSurface.invalidate(child)
            assertEquals("Attached without a scope must remain unchanged", 1, target.calls)
            outside.removeView(child)
            root.addView(child)
            NativeTextSurface.invalidate(child)
            assertEquals(2, target.calls)
        } finally { (outside.parent as? ViewGroup)?.removeView(outside) }
    }

    @Test fun publicCommonToggleUsesScopeSamplerRetainsParagraphsAndHonorsExplicitOptOut() = mounted { root ->
        val theme = ControlTheme.resolve(root.context, false).withColorScheme(
            NativeColorScheme.fallback(false).copy(onSurface = Color.BLACK, onSurfaceVariant = Color.BLACK))
        val state = ToggleState("Output compression", false, true, "Preserve this summary")
        var requests = 0
        val toggle = ToggleRow(root.context, state, theme) { requests++ }
        val optOut = ToggleRow(root.context, state, theme, null, true, null) { requests++ }
        val group = NativeTextSurface.ownGroupInk(NativeGroupLayout(root.context))
        group.setPaletteImmediately(Color.BLACK, Color.TRANSPARENT)
        group.addRow(toggle.view, NativeGroupLayout.LayoutParams(300, 100))
        group.addRow(optOut.view, NativeGroupLayout.LayoutParams(300, 100))
        root.addView(group)
        try {
            val labels = descendants(toggle.view).filterIsInstance<ParagraphView>()
            assertEquals(2, labels.size)
            labels.forEach {
                assertNotNull(it.paintedTextSurface)
                assertEquals(textSurfaceRange(Color.BLACK), it.paintedTextSurface!!.asLong)
                assertInk(it, Color.WHITE)
            }
            descendants(optOut.view).filterIsInstance<ParagraphView>().forEach { assertNull(it.paintedTextSurface) }
            group.setPaletteImmediately(Color.WHITE, Color.TRANSPARENT)
            labels.forEach { assertEquals(textSurfaceRange(Color.WHITE), it.paintedTextSurface!!.asLong) }
            toggle.bind(state.copy(checked = true), theme)
            val retained = descendants(toggle.view).filterIsInstance<ParagraphView>()
            labels.indices.forEach { assertSame(labels[it], retained[it]) }
            root.removeView(group)
            val unscoped = FrameLayout(root.context).apply { addView(group) }
            labels.forEach { assertEquals(0L, it.paintedTextSurface!!.asLong) }
            unscoped.removeView(group)
            root.addView(group)
            labels.forEach { assertEquals(textSurfaceRange(Color.WHITE), it.paintedTextSurface!!.asLong) }
            assertEquals(0, requests)
        } finally { toggle.close(); optOut.close() }
    }

    @Test fun commonToolbarPreviewKeepsCallbackNameAndRetainedSurfaceThroughCancelAndRebind() = mounted { root ->
        val palette = NativeDefaultColorScheme.resolve(true, 30) { error("No resources for SDK30") }
        val action = NativeThemedToolbarAction(root.context, 1f, palette, { root }, "Open settings")
        root.addView(action)
        var clicks = 0
        action.bindToolbarAction(1f, palette, true, NativeToolbarActionTone.MUTED) { clicks++ }
        layout(action, 64, 64)
        val surface = descendants(action).filterIsInstance<NativeGroupLayout>().single()
        val leaf = action.accessibilityAction
        val tint = action.sampledIconTint
        val range = surface.paintedTextSurface
        assertEquals(textSurfaceRange(0xff090909.toInt(), 0xff131313.toInt()), range)
        val morph = NativePathMorph(Path().apply { addRect(2f, 2f, 22f, 22f, Path.Direction.CW) },
            Path().apply { addCircle(12f, 12f, 10f, Path.Direction.CW) }, ColorDrawable(Color.WHITE))
        for (progress in listOf(.5f, 1f, 0f)) {
            action.setToolbarActionPreview(morph, progress, NativeToolbarActionTone.ALERT)
            assertSame(surface, descendants(action).filterIsInstance<NativeGroupLayout>().single())
            assertSame(leaf, action.accessibilityAction)
            assertEquals(range, surface.paintedTextSurface)
            assertEquals(tint, action.sampledIconTint)
            assertEquals("Open settings", checkNotNull(leaf.createAccessibilityNodeInfo()).contentDescription.toString())
            assertTrue(leaf.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        }
        assertEquals(3, clicks)
        val target: NativeTextSurfaceTarget = action // Inherited base method satisfies the public marker.
        target.invalidateTextSurfaceInk()
        action.bindToolbarAction(1f, palette.copy(surfacePreset = null), true,
            NativeToolbarActionTone.NEUTRAL) { clicks += 10 }
        assertSame(surface, descendants(action).filterIsInstance<NativeGroupLayout>().single())
        assertEquals(textSurfaceRange(0xff0e0e0e.toInt()), surface.paintedTextSurface)
        assertTrue(leaf.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(13, clicks)
        action.cancelInput()
    }

    @Test fun sharedToolbarSurfaceAddsNoNestedGroupAndSamplesItsExistingOwner() = mounted { root ->
        val palette = NativeDefaultColorScheme.resolve(true, 30) { error("No resources for SDK30") }
        val group = NativeTextSurface.ownGroupInk(NativeGroupLayout(root.context))
        group.setPaletteImmediately(Color.WHITE, Color.TRANSPARENT)
        val action = NativeThemedToolbarAction(root.context, 1f, palette, { root }, "Select all", sharedSurface = true)
        group.addRow(action, NativeGroupLayout.LayoutParams(64, 64))
        root.addView(group)
        action.bindToolbarAction(1f, palette, true, NativeToolbarActionTone.NEUTRAL) {}
        assertTrue(descendants(action).none { it is NativeGroupLayout })
        assertSame(action, action.visual.parent)
        assertEquals(textSurfaceRange(Color.WHITE), NativeTextSurface.sample(action.visual))
        group.setPaletteImmediately(Color.BLACK, Color.TRANSPARENT)
        assertEquals(textSurfaceRange(Color.BLACK), NativeTextSurface.sample(action.visual))
        assertTrue(descendants(action).none { it is NativeGroupLayout })
        action.cancelInput()
    }

    private class Scope(context: Context) : FrameLayout(context), NativeTextSurfaceScope
    private class Target(context: Context) : View(context), NativeTextSurfaceTarget {
        var calls = 0
        override fun invalidateTextSurfaceInk() { calls++ }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun assertInk(label: ParagraphView, ink: Int) {
        layout(label, 300, 100)
        val bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
        try {
            label.draw(Canvas(bitmap))
            val pixels = IntArray(300 * 100)
            bitmap.getPixels(pixels, 0, 300, 0, 0, 300, 100)
            assertTrue("Expected visible glyph ink ${Integer.toHexString(ink)}",
                pixels.any { Color.alpha(it) != 0 && (it and 0xffffff) == (ink and 0xffffff) })
        } finally { bitmap.recycle() }
    }

    private fun onMain(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { block(instrumentation.targetContext) }
    }

    /** Await actual visible layout/attachment and exact Activity destruction; no sleeps or input. */
    private fun mounted(assertions: (FrameLayout) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val layoutReady = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        var root: Scope? = null
        var registered = false
        var failure: Throwable? = null
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(value: Activity) { if (value === activity) destroyed.countDown() }
            override fun onActivityCreated(value: Activity, saved: Bundle?) = Unit
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityResumed(value: Activity) = Unit
            override fun onActivityPaused(value: Activity) = Unit
            override fun onActivityStopped(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, saved: Bundle) = Unit
        }
        val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (!activity.isFinishing && !activity.isDestroyed && view.isAttachedToWindow &&
                view.isShown && view.isLaidOut && view.width > 0 && view.height > 0) layoutReady.countDown()
        }
        fun retain(error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
        }
        try {
            instrumentation.runOnMainSync {
                activity.application.registerActivityLifecycleCallbacks(lifecycle)
                registered = true
                root = Scope(activity).also { it.addOnLayoutChangeListener(listener); activity.setContentView(it) }
            }
            assertTrue("Owned scope did not reach an attached visible layout", layoutReady.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val scope = checkNotNull(root)
                assertTrue(scope.isAttachedToWindow && scope.isShown && scope.isLaidOut && scope.width > 0 && scope.height > 0)
                assertFalse(activity.isFinishing || activity.isDestroyed)
                assertions(scope)
            }
        } catch (error: Throwable) { retain(error) }
        finally {
            try {
                instrumentation.runOnMainSync {
                    root?.removeOnLayoutChangeListener(listener)
                    root?.removeAllViews()
                    if (activity.isDestroyed) destroyed.countDown() else if (!activity.isFinishing) activity.finish()
                }
                assertTrue("Owned Activity did not destroy", destroyed.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { assertTrue(activity.isDestroyed) }
            } catch (error: Throwable) { retain(error) }
            finally {
                try {
                    instrumentation.runOnMainSync {
                        if (registered) activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
                    }
                } catch (error: Throwable) { retain(error) }
            }
        }
        failure?.let { throw it }
    }
}
