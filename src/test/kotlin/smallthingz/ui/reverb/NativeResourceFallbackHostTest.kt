package smallthingz.ui.reverb

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import smallthingz.ui.R

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en")
class NativeResourceFallbackHostTest {
    @Test
    @Config(qualifiers = "ar")
    fun existingReverbTranslationsResolveAlongsideEnglishFallback() {
        val resources = RuntimeEnvironment.getApplication().resources
        assertEquals("مسح", resources.getString(R.string.clear_buffer))
        assertEquals("استئناف التسجيل بعد إعادة تشغيل الجهاز",
            resources.getString(R.string.onboarding_restart_resume_title))
        assertEquals("Show previous option", resources.getString(R.string.native_dropdown_previous_option))
    }

    @Test
    @Config(qualifiers = "lo")
    fun platformSemanticsRemainLocalizedWhenCustomCopyFallsBack() {
        val resources = RuntimeEnvironment.getApplication().resources
        assertEquals("ເລືອກແລ້ວ", resources.getString(R.string.selected))
        assertEquals("Review permissions", resources.getString(R.string.reverb_permission_notices_review))
    }

    @Test fun englishFallbackRetainsPluralBranchesAndFormatArguments() {
        val resources = RuntimeEnvironment.getApplication().resources
        assertEquals("1 permission needs attention",
            resources.getQuantityString(R.plurals.reverb_permission_notices_summary, 1, 1))
        assertEquals("2 permissions need attention",
            resources.getQuantityString(R.plurals.reverb_permission_notices_summary, 2, 2))
        assertEquals("09:15 · 2 min",
            resources.getString(R.string.reverb_incident_time_duration, "09:15", "2 min"))
        assertEquals("Playing · 2 min", resources.getString(R.string.recording_playing_details, "2 min"))
    }
}
