package smallthingz.ui.components

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default-display night observation independent of this package's explicit night override.
 * API32+ source boundary; delivery/resume behavior still depends on the actual platform.
 * Own exactly one instance in Application for the process lifetime. No Activity is retained.
 * Public APIs cannot deterministically release its token; do not create per-window instances.
 */
class NativeDisplayNightObserver private constructor(private val windowContext: Context) : ComponentCallbacks {
    private val mutableDark = MutableStateFlow(requireNotNull(night(windowContext.resources.configuration)) {
        "Default display night mode unresolved"
    })
    val dark: StateFlow<Boolean> = mutableDark.asStateFlow()

    init {
        windowContext.registerComponentCallbacks(this)
    }

    /** Reads the delivered token configuration; does NOT synchronously refresh it from WMS. */
    @MainThread
    fun resample() {
        publish(windowContext.resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        publish(newConfig)
    }

    override fun onLowMemory() = Unit

    private fun publish(config: Configuration) {
        // Keep the last independent value if a transient callback omits the night mask.
        night(config)?.let { mutableDark.value = it }
    }

    private fun night(config: Configuration): Boolean? = when (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_NO -> false
        Configuration.UI_MODE_NIGHT_YES -> true
        else -> null
    }

    companion object {
        /** No window, token substitution, permission request, package override or global write. */
        @MainThread
        fun create(application: Application): NativeDisplayNightObserver? {
            // AOSP12L checks the token override itself; Android12 can skip initialization.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return null
            val display = requireNotNull(application.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)) { "Default display unavailable" }
            return NativeDisplayNightObserver(application.createWindowContext(display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null))
        }
    }
}
