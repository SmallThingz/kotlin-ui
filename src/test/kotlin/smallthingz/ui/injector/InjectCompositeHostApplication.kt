package smallthingz.ui.injector

import android.app.Application
import android.content.pm.ApplicationInfo

/** Matches the app/instrumentation host's RTL capability without changing the library manifest. */
class InjectCompositeHostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        applicationInfo.flags = applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
    }
}
