package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbPermissionNotices
import smallthingz.ui.reverb.ReverbPermissionOnboardingKind
import smallthingz.ui.reverb.ReverbPermissionOnboardingState
import smallthingz.ui.reverb.settings.ReverbSettingsPermissionReviewRow
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.util.closePreservingPrimaryFailure
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Ordinary attached native host: no source-geometry binder or real permission request. */
@RunWith(AndroidJUnit4::class)
class ReverbPermissionNoticesAccessibilityIntegrationTest {
    @Test fun settingsReviewRowDefaultRetainsSourceHostOwnership() = withNotice { f ->
        var row: ReverbSettingsPermissionReviewRow? = null
        var requests = 0
        try {
            main {
                val environment = ReverbEnvironment(f.activity, false).forSettings()
                // Existing trailing-lambda constructor must remain source compatible.
                row = ReverbSettingsPermissionReviewRow(f.activity, environment, { null },
                    ReverbIcon.BackgroundWarning, R.string.reverb_settings_background_use_restricted,
                    R.string.reverb_settings_background_review, R.string.reverb_settings_background_review) { requests++ }
                f.root.removeAllViews()
                f.root.addView(checkNotNull(row), FrameLayout.LayoutParams(-1, -2))
                checkNotNull(row).bind(environment, true)
            }
            await("Default settings row attached", { viewState(checkNotNull(row)) }) { ready(checkNotNull(row)) }
            main {
                val action = button(checkNotNull(row))
                assertNull(action.createAccessibilityNodeInfo())
                assertFalse(click(action))
                assertEquals(0, requests)
                val lease = action.mergedRowAccessibility.bindGeometry(NativePlainTextViewGeometry(f.root,
                    NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })) { true }
                try {
                    val parent = checkNotNull(action.createAccessibilityNodeInfo())
                    assertTrue(parent.isEnabled && parent.isVisibleToUser)
                    assertEquals("android.view.View", parent.className.toString())
                    assertEquals(f.activity.getString(R.string.reverb_settings_background_review),
                        parent.contentDescription.toString())
                    assertEquals(1, parent.actionList.count { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                    val children = arrayListOf<View>()
                    action.addChildrenForAccessibility(children)
                    assertEquals(listOf(action.label, action.mergedRowAccessibility.roleView), children)
                    val role = checkNotNull(action.mergedRowAccessibility.roleView.createAccessibilityNodeInfo())
                    assertEquals("android.widget.Button", role.className.toString())
                    assertFalse(role.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                    assertTrue(click(action))
                    assertEquals(1, requests)
                } finally { lease.close() }
                assertNull(action.createAccessibilityNodeInfo())
            }
        } finally { main { row?.close() } }
    }

    @Test fun settingsReviewRowOptInExposesOnlyLiveEnabledActionAndRetiresAfterHostTakeover() = withNotice { f ->
        var row: ReverbSettingsPermissionReviewRow? = null
        var requests = 0
        lateinit var environment: ReverbEnvironment
        try {
            main {
                environment = ReverbEnvironment(f.activity, false).forSettings()
                row = ReverbSettingsPermissionReviewRow(f.activity, environment, { null },
                    ReverbIcon.BackgroundWarning, R.string.reverb_settings_background_use_restricted,
                    R.string.reverb_settings_background_review, R.string.reverb_settings_background_review,
                    request = { requests++ }, standaloneAccessibility = true)
                f.root.removeAllViews()
                f.root.addView(checkNotNull(row), FrameLayout.LayoutParams(-1, -2))
                checkNotNull(row).bind(environment, true)
            }
            await("Standalone settings row attached", { viewState(checkNotNull(row)) }) { ready(checkNotNull(row)) }
            main {
                val current = checkNotNull(row)
                val action = button(current)
                assertAction(action, f.activity.getString(R.string.reverb_settings_background_review))
                assertEquals(1, exposedActions(current))
                assertTrue(click(action)); assertEquals(1, requests)
                current.bind(environment, false)
                assertFalse(checkNotNull(action.createAccessibilityNodeInfo()).isEnabled)
                assertFalse(click(action)); assertEquals(1, requests)
                current.bind(environment, true)
                current.visibility = View.GONE
                assertNull(action.createAccessibilityNodeInfo())
                assertFalse(click(action)); assertEquals(1, requests)
                current.visibility = View.VISIBLE
                val lease = action.mergedRowAccessibility.bindGeometry(NativePlainTextViewGeometry(f.root,
                    NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })) { true }
                try { assertTrue(checkNotNull(action.createAccessibilityNodeInfo()).isVisibleToUser) }
                finally { lease.close() }
                assertNull(action.createAccessibilityNodeInfo())
                assertFalse(click(action)); assertEquals(1, requests)
                current.close()
                assertNull(action.createAccessibilityNodeInfo())
            }
        } finally { main { row?.close() } }
    }

    @Test fun standaloneActionsRequestEachKindAndRetireOnDisableGrantAndRevocation() = withNotice { f ->
        for (kind in ReverbPermissionOnboardingKind.entries) {
            val state = state(kind)
            main { f.notice.bind(listOf(state)) }
            awaitNotice(f)
            main {
                assertTrue(f.notice.view.hasWindowFocus())
                assertFalse(f.notice.isExpanded)
                val summary = button(f.notice.summaryView)
                assertAction(summary, f.activity.getString(R.string.reverb_permission_notices_review))
                assertEquals(1, exposedActions(f.notice.view))
                assertTrue(click(summary))
            }
            awaitNotice(f, kind)
            main {
                assertTrue(f.notice.isExpanded)
                val request = button(checkNotNull(f.notice.noticeView(kind)))
                assertAction(request, state.requestLabel)
                assertEquals(2, exposedActions(f.notice.view))
                assertTrue(click(request))
                assertEquals(kind, f.requests.last())
                val accepted = f.requests.size

                f.notice.bind(listOf(state.copy(canRequest = false)))
                val disabled = checkNotNull(request.createAccessibilityNodeInfo())
                assertFalse(disabled.isEnabled)
                assertFalse(disabled.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                assertFalse(click(request))
                assertEquals(accepted, f.requests.size)

                f.notice.bind(listOf(state.copy(allowed = true)))
                assertNull(f.notice.noticeView(kind))
                assertNull(request.createAccessibilityNodeInfo())
                assertFalse(click(request))
                assertNull(button(f.notice.summaryView).createAccessibilityNodeInfo())
            }
            // Revocation creates a new current action, without reviving the removed one.
            main { f.notice.bind(listOf(state)) }
            awaitNotice(f)
            main {
                assertFalse(f.notice.isExpanded)
                assertEquals(1, exposedActions(f.notice.view))
            }
        }
        main { assertEquals(ReverbPermissionOnboardingKind.entries.toList(), f.requests) }
    }

    @Test fun collapseHiddenHostAndCloseRemovePreviouslyExposedActions() = withNotice { f ->
        val kind = ReverbPermissionOnboardingKind.NOTIFICATIONS
        main { f.notice.bind(listOf(state(kind))) }
        awaitNotice(f)
        main { assertTrue(click(button(f.notice.summaryView))) }
        awaitNotice(f, kind)
        main {
            val summary = button(f.notice.summaryView)
            val request = button(checkNotNull(f.notice.noticeView(kind)))
            assertAction(summary, f.activity.getString(R.string.reverb_permission_notices_hide))
            assertTrue(click(summary))
            assertFalse(f.notice.isExpanded)
            assertNull(request.createAccessibilityNodeInfo())
            assertFalse(click(request))

            f.notice.bind(listOf(state(kind)), shown = false)
            assertNull(summary.createAccessibilityNodeInfo())
            assertFalse(click(summary))
        }
        main { f.notice.bind(listOf(state(kind))) }
        awaitNotice(f)
        main {
            val summary = button(f.notice.summaryView)
            assertAction(summary, f.activity.getString(R.string.reverb_permission_notices_review))
            f.notice.close()
            assertNull(summary.createAccessibilityNodeInfo())
            assertFalse(click(summary))
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun collapsedAndExpandedRowsKeepLightAndCustomWarningFill() {
        val light = NativeColorScheme.fallback(darkTheme = false)
        for (palette in listOf(light, light.copy(surfaceContainerLow = 0xffc9e3d2.toInt()))) {
            withNotice(palette) { f ->
                val kind = ReverbPermissionOnboardingKind.NOTIFICATIONS
                main { f.notice.bind(listOf(state(kind))) }
                awaitNotice(f)
                fun assertRowFill(row: View) {
                    assertTrue(row.width > 4 && row.height > 0)
                    // Blank start inset, clear of the outer outline, glyph and action.
                    val sample = Rect(4, row.height / 2, 5, row.height / 2 + 1)
                    f.notice.view.offsetDescendantRectToMyCoords(row, sample)
                    val bitmap = Bitmap.createBitmap(f.notice.view.width, f.notice.view.height, Bitmap.Config.ARGB_8888)
                    try {
                        f.notice.view.draw(Canvas(bitmap))
                        assertEquals(ReverbDesign.appearance.groupSurfaces.settingsWarning.fillColor(palette),
                            bitmap.getPixel(sample.left, sample.top))
                    } finally { bitmap.recycle() }
                }
                main {
                    assertFalse(f.notice.isExpanded)
                    assertRowFill(f.notice.summaryView)
                    assertTrue(click(button(f.notice.summaryView)))
                }
                awaitNotice(f, kind)
                main {
                    assertTrue(f.notice.isExpanded)
                    assertRowFill(f.notice.summaryView)
                    assertRowFill(checkNotNull(f.notice.noticeView(kind)))
                    assertTrue(f.requests.isEmpty())
                }
            }
        }
    }

    @Test fun sourceGeometryTakeoverDoesNotFallBackToNativeBoundsAfterRelease() = withNotice { f ->
        main { f.notice.bind(listOf(state(ReverbPermissionOnboardingKind.NOTIFICATIONS))) }
        awaitNotice(f)
        main {
            val summary = button(f.notice.summaryView)
            assertAction(summary, f.activity.getString(R.string.reverb_permission_notices_review))
            val geometry = NativePlainTextViewGeometry(f.root,
                NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
            val lease = summary.mergedRowAccessibility.bindGeometry(geometry) { true }
            try { assertTrue(checkNotNull(summary.createAccessibilityNodeInfo()).isVisibleToUser) }
            finally { lease.close() }
            assertNull(summary.createAccessibilityNodeInfo())
            assertFalse(click(summary))
            assertFalse(f.notice.isExpanded)
        }
    }

    private class Fixture(val activity: Activity, palette: NativeColorScheme? = null) {
        val requests = mutableListOf<ReverbPermissionOnboardingKind>()
        private val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            densityDpi = 160
            fontScale = 1f
        })
        private val baseTheme = ReverbDesign.theme(context, dark = false)
        private val theme = if (palette == null) baseTheme else
            ReverbDesign.windowTheme(baseTheme.density, baseTheme.fontResolver, palette)
        val notice = ReverbPermissionNotices(context, theme) { requests.add(it) }
        val root = FrameLayout(activity).apply {
            addView(notice.view, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun withNotice(palette: NativeColorScheme? = null, assertions: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        var failure: Throwable? = null
        try {
            main {
                fixture = Fixture(activity, palette)
                activity.setContentView(checkNotNull(fixture).root)
            }
            val mounted = checkNotNull(fixture)
            await("Exact fixture root attached, laid out and focused", { viewState(mounted.root) }) {
                !activity.isFinishing && !activity.isDestroyed &&
                    mounted.root.rootView === activity.window.peekDecorView() && ready(mounted.root)
            }
            assertions(mounted)
        } catch (error: Throwable) {
            failure = error
        } finally {
            failure = closePreservingPrimaryFailure(failure) {
                main { try { fixture?.notice?.close() } finally { activity.finish() } }
            }
            failure = closePreservingPrimaryFailure(failure) {
                // Observe THIS returned Activity, not any other test Activity reaching destruction.
                await("Exact fixture Activity destroyed and detached", {
                    "destroyed=${activity.isDestroyed}, finishing=${activity.isFinishing}, " +
                        "decorAttached=${activity.window.peekDecorView()?.isAttachedToWindow}, " +
                        "rootAttached=${fixture?.root?.isAttachedToWindow}"
                }) {
                    activity.isDestroyed && activity.window.peekDecorView()?.isAttachedToWindow != true &&
                        fixture?.root?.isAttachedToWindow != true
                }
            }
        }
        failure?.let { throw it }
    }

    private fun state(kind: ReverbPermissionOnboardingKind) = ReverbPermissionOnboardingState(
        kind, "Permission", "Needed", "Manage ${kind.name}", false, "Off", true)

    private fun assertAction(button: NativeMaterialButtonView, description: String) {
        val node = checkNotNull(button.createAccessibilityNodeInfo())
        assertTrue(node.isEnabled && node.isVisibleToUser)
        assertEquals("android.widget.Button", node.className.toString())
        assertEquals(description, node.contentDescription.toString())
        assertEquals(1, node.actionList.count { it.id == AccessibilityNodeInfo.ACTION_CLICK })
    }

    private fun exposedActions(view: View) = descendants(view).filterIsInstance<NativeMaterialButtonView>()
        .count { it.createAccessibilityNodeInfo()?.actionList?.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } == true }
    private fun button(view: View) = descendants(view).filterIsInstance<NativeMaterialButtonView>().single()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun click(view: View) = view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)

    private fun awaitNotice(f: Fixture, kind: ReverbPermissionOnboardingKind? = null) {
        fun row() = if (kind == null) f.notice.summaryView else f.notice.noticeView(kind)
        await("Notice action attached, laid out and focused: $kind", {
            "root=${viewState(f.root)}; notice=${viewState(f.notice.view)}; row=${row()?.let(::viewState)}"
        }) {
            val target = row()
            !f.activity.isFinishing && !f.activity.isDestroyed && ready(f.root) && ready(f.notice.view) &&
                target != null && ready(target) && ready(button(target))
        }
    }

    private fun ready(view: View) = view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
        view.isLaidOut && !view.isLayoutRequested && view.width > 0 && view.height > 0 && view.hasWindowFocus()

    private fun viewState(view: View) = "attached=${view.isAttachedToWindow}, shown=${view.isShown}, " +
        "laidOut=${view.isLaidOut}, layoutRequested=${view.isLayoutRequested}, " +
        "size=${view.width}x${view.height}, focus=${view.hasWindowFocus()}"

    private fun await(description: String, state: () -> String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var lastState = "not sampled"
        while (true) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) throw AssertionError("$description: $lastState")
            var admitted = false
            onMain(remaining) { admitted = condition(); lastState = state() }
            if (admitted) return
            val pause = minOf(16L, deadline - SystemClock.uptimeMillis())
            if (pause > 0) SystemClock.sleep(pause)
        }
    }

    private fun main(action: () -> Unit) = onMain(5_000L, action)
    private fun onMain(timeoutMillis: Long, action: () -> Unit) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Fixture waits must run off the main thread" }
        val handler = Handler(Looper.getMainLooper())
        val task = FutureTask<Unit> { action() }
        check(handler.post(task)) { "Main dispatcher rejected fixture work" }
        try { task.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (error: ExecutionException) { throw (error.cause ?: error) }
        finally {
            if (!task.isDone) {
                handler.removeCallbacks(task)
                task.cancel(false)
            }
        }
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
}
