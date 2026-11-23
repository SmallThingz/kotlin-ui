package smallthingz.ui.reverb

import androidx.annotation.StringRes

/** Localized content supplied by the recording host. */
data class ReverbPlayerLabels(
    @StringRes val trimRecording: Int,
    @StringRes val cancel: Int,
    @StringRes val saving: Int,
    @StringRes val saveCopy: Int,
    @StringRes val preparing: Int,
    @StringRes val trimStart: Int,
    @StringRes val trimEnd: Int,
)
