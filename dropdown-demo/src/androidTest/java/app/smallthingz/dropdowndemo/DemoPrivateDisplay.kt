package app.smallthingz.dropdowndemo

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Instrumentation-only private surface. No physical input, system settings, or default-display fallback. */
internal object DemoPrivateDisplay {
    private const val EXPECTED_PACKAGE = "app.smallthingz.dropdowndemo.emailvisual"
    private const val PERMISSION = "android.permission.ADD_TRUSTED_DISPLAY"
    private const val NONCE_EXTRA = "email.visual.ownedDisplayNonce"
    // API36 system/test flag values, verified against android16-release DisplayManager/Display
    // and Control's ProbeRunner. These are NOT ordinary SDK public constants.
    private const val CREATE_FLAGS = (1 shl 8) or (1 shl 10) or (1 shl 14) or (1 shl 16)
    private const val REQUIRED_FLAGS = Display.FLAG_PRIVATE or (1 shl 7) or (1 shl 11) or (1 shl 12)
    private const val FORBIDDEN_FLAGS = Display.FLAG_SECURE or (1 shl 6)
    private var active: Session? = null

    fun <T> runOwned(block: () -> T): T {
        val args = InstrumentationRegistry.getArguments()
        val route = args.getString("visualDisplayIsolation")
        check(route == "private-own-focus" && active == null)
        check(Build.VERSION.SDK_INT == 36) { "This isolated-display contract requires API36." }
        val session = Session(args)
        try {
            session.open()
            active = session
            return block()
        } finally {
            try { session.close() } finally { active = null }
        }
    }

    private fun optionsFor(intent: Intent): Bundle? = active?.let { session ->
        session.checkDisplay()
        session.prepareIntent(intent)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.getSystemService(ActivityManager::class.java)
            .isActivityStartAllowedOnDisplay(context, session.displayId, intent))
        ActivityOptions.makeBasic().setLaunchDisplayId(session.displayId).toBundle()
    }

    fun startActivity(intent: Intent): Activity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(intent, optionsFor(intent))
        instrumentation.runOnMainSync { active?.checkActivity(activity) }
        return activity
    }

    fun snapshot(activity: Activity, decor: View): String? = active?.snapshot(activity, decor)

    fun beforeLocalInput(activity: Activity, decor: View) {
        active?.let { it.snapshot(activity, decor); it.localInputs.incrementAndGet() }
    }

    private class Session(args: Bundle) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val manager = context.getSystemService(DisplayManager::class.java)
        private val nonce = checkNotNull(args.getString("visualRouteNonce")).also {
            check(it.matches(Regex("[a-f0-9]{16}")))
        }
        private val width = dimension(args, "visualDisplayWidthPx", 320..2048)
        private val height = dimension(args, "visualDisplayHeightPx", 320..2048)
        private val density = dimension(args, "visualDisplayDensityDpi", 160..640)
        private var reader: ImageReader? = null
        private var virtual: VirtualDisplay? = null
        private val frames = AtomicInteger()
        val localInputs = AtomicInteger()
        private val baseline = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
        private val baselineTasks = mutableSetOf<Int>()
        private val launched = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
        private val plannedComponents = Collections.synchronizedSet(mutableSetOf<String>())
        private val lifecycle = ActivityLifecycleCallback { activity, _ -> track(activity) }
        private var lifecycleRegistered = false
        private var unresolvedEscapes = 0
        private var lingeringOwnedActivities = 0
        var displayId = Display.INVALID_DISPLAY
            private set

        fun open() {
            check(context.packageName == EXPECTED_PACKAGE && context.applicationInfo.uid == Process.myUid())
            check(width.toLong() * height <= 4_194_304L)
            check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
            check(context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                baseline.addAll(liveActivities())
                baselineTasks.addAll(baseline.map { it.taskId })
                baselineTasks.addAll(context.getSystemService(ActivityManager::class.java).appTasks.map { task ->
                    checkNotNull(task.taskInfo) { "Task information unavailable before isolated launch." }.taskId
                })
                monitor.addLifecycleCallback(lifecycle)
                lifecycleRegistered = true
            }
            val ui = checkNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { sink ->
                sink.setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.use { frames.incrementAndGet() }
                }, Handler(Looper.getMainLooper()))
            }
            try {
                ui.adoptShellPermissionIdentity(PERMISSION)
                check(context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED)
                virtual = manager.createVirtualDisplay("Dropdown demo $nonce", width, height, density,
                    checkNotNull(reader).surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or CREATE_FLAGS)
            } finally {
                ui.dropShellPermissionIdentity()
            }
            check(context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
            displayId = checkNotNull(virtual).display.displayId
            check(displayId > Display.DEFAULT_DISPLAY)
            val end = SystemClock.uptimeMillis() + 4_000
            while (checkNotNull(virtual).display.state != Display.STATE_ON) {
                check(SystemClock.uptimeMillis() < end) { "Owned display did not turn on." }
                SystemClock.sleep(20)
            }
            checkDisplay()
            report("allocated", false)
        }

        fun checkDisplay() {
            val display = checkNotNull(virtual).display
            check(display.isValid && display.displayId == displayId && displayId > Display.DEFAULT_DISPLAY)
            check(display.flags and REQUIRED_FLAGS == REQUIRED_FLAGS && display.flags and FORBIDDEN_FLAGS == 0)
            check(display.state == Display.STATE_ON)
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            check(metrics.widthPixels == width && metrics.heightPixels == height && metrics.densityDpi == density)
        }

        fun prepareIntent(intent: Intent) {
            val component = checkNotNull(intent.component)
            check(component.packageName == EXPECTED_PACKAGE && !intent.hasExtra(NONCE_EXTRA))
            plannedComponents.add(component.className)
            intent.putExtra(NONCE_EXTRA, nonce)
        }

        private fun track(activity: Activity) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (activity !in baseline && activity.packageName == EXPECTED_PACKAGE &&
                activity.applicationInfo.uid == Process.myUid() &&
                activity.intent?.getStringExtra(NONCE_EXTRA) == nonce &&
                activity.intent?.component?.className?.let { it in plannedComponents } == true) launched.add(activity)
        }

        private fun liveActivities(): List<Activity> {
            check(Looper.myLooper() == Looper.getMainLooper())
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            return Stage.values().filter { it != Stage.DESTROYED }.flatMap { monitor.getActivitiesInStage(it) }
                .distinct().filter { it.packageName == EXPECTED_PACKAGE && it.applicationInfo.uid == Process.myUid() }
        }

        fun checkActivity(activity: Activity) {
            check(Looper.myLooper() == Looper.getMainLooper())
            track(activity)
            check(activity in launched) { "Activity is not a fresh session-owned launch." }
            checkDisplay()
            check(activity.packageName == EXPECTED_PACKAGE && activity.applicationInfo.uid == Process.myUid())
            check(!activity.isFinishing && !activity.isDestroyed && activity.taskId >= 0)
            check(activity.display?.displayId == displayId)
        }

        fun snapshot(activity: Activity, decor: View): String {
            checkActivity(activity)
            check(decor.display?.displayId == displayId && decor.context.packageName == EXPECTED_PACKAGE)
            check(decor.isAttachedToWindow && decor.isShown && decor.hasWindowFocus())
            check(decor.windowVisibility == View.VISIBLE && decor.windowToken != null)
            check(decor.width > 0 && decor.height > 0)
            val xy = IntArray(2).also(decor::getLocationOnScreen)
            check(xy[0] >= 0 && xy[1] >= 0 && xy[0] + decor.width <= width && xy[1] + decor.height <= height)
            return JSONObject().put("route", "private-own-focus").put("nonce", nonce)
                .put("displayId", displayId).put("displayFlags", checkNotNull(virtual).display.flags)
                .put("taskId", activity.taskId).put("package", EXPECTED_PACKAGE).put("uid", Process.myUid())
                .put("activityClass", activity.javaClass.name).put("windowToken", decor.windowToken.toString())
                .put("windowId", decor.createAccessibilityNodeInfo()?.windowId ?: -1)
                .put("widthPx", width).put("heightPx", height).put("densityDpi", density)
                .put("rotation", checkNotNull(virtual).display.rotation)
                .put("decorX", xy[0]).put("decorY", xy[1]).put("decorWidth", decor.width).put("decorHeight", decor.height)
                .put("ownedWindowFocused", true).put("physicalInputInjected", false)
                .put("primaryFocusProof", "external-coordinator-required").toString()
        }

        fun close() {
            var failure: Throwable? = null
            var closingOwned = emptyList<Activity>()
            fun attempt(block: () -> Unit) {
                try { block() } catch (error: Throwable) { failure?.addSuppressed(error) ?: run { failure = error } }
            }
            if (lifecycleRegistered) attempt {
                instrumentation.runOnMainSync {
                    val current = liveActivities()
                    current.forEach(::track)
                    unresolvedEscapes = current.count { it !in baseline && it !in launched &&
                        (displayId <= 0 || it.display?.displayId != displayId) }
                    val owned = (launched.toList() + current.filter {
                        it !in baseline && displayId > 0 && it.display?.displayId == displayId
                    }).distinct()
                    closingOwned = owned
                    owned.filter { !it.isDestroyed }.forEach { activity ->
                        attempt {
                            // Includes dormant appTasks: never remove any pre-session task.
                            if (activity.taskId in baselineTasks) activity.finish() else activity.finishAndRemoveTask()
                        }
                    }
                }
            }
            attempt {
                if (lifecycleRegistered) instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
                    lifecycleRegistered = false
                }
            }
            attempt { check(unresolvedEscapes == 0) { "Untracked new QA Activity escape requires coordinator reconciliation." } }
            attempt { virtual?.release() }
            attempt {
                instrumentation.runOnMainSync {
                    reader?.setOnImageAvailableListener(null, null)
                    reader?.close()
                }
            }
            attempt {
                if (displayId > 0) {
                    val end = SystemClock.uptimeMillis() + 2_000
                    while (manager.getDisplay(displayId)?.isValid == true) {
                        check(SystemClock.uptimeMillis() < end) { "Owned display was not released." }
                        SystemClock.sleep(20)
                    }
                }
            }
            attempt {
                val end = SystemClock.uptimeMillis() + 2_000
                do {
                    instrumentation.runOnMainSync {
                        lingeringOwnedActivities = closingOwned.count { !it.isDestroyed }
                    }
                    if (lingeringOwnedActivities == 0) break
                    check(SystemClock.uptimeMillis() < end) { "Owned Activity destruction is unresolved after cleanup." }
                    SystemClock.sleep(20)
                } while (true)
            }
            report("closed", failure == null)
            failure?.let { throw it }
        }

        private fun report(phase: String, released: Boolean) {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("ownedDisplayLifecycle", JSONObject().put("phase", phase).put("nonce", nonce)
                    .put("displayId", displayId).put("uid", Process.myUid()).put("package", EXPECTED_PACKAGE)
                    .put("widthPx", width).put("heightPx", height).put("densityDpi", density)
                    .put("drainedFrames", frames.get()).put("localInputEvents", localInputs.get())
                    .put("unresolvedEscapes", unresolvedEscapes)
                    .put("lingeringOwnedActivities", lingeringOwnedActivities)
                    .put("released", released).put("physicalInputInjected", false)
                    .put("primaryFocusProof", "external-coordinator-required").toString())
            })
        }

        private fun dimension(args: Bundle, key: String, bounds: IntRange): Int =
            checkNotNull(args.getString(key)).toInt().also { check(it in bounds) }
    }
}
