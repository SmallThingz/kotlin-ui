package smallthingz.ui.reverb

import androidx.annotation.StringRes
import smallthingz.ui.common.PlaybackPositionAnchor
import smallthingz.ui.components.waveform.ProgressiveWaveformData
import smallthingz.ui.components.waveform.ProgressiveWaveformFrame
import smallthingz.ui.geometry.Rect

enum class RangeEditTarget { START, END }

data class RangeMorphSourceGeometry(
    val rootLeftInRootPx: Float,
    val rootTopInRootPx: Float,
    val centerXInRootPx: Float,
    val centerYInRootPx: Float,
    val bodyDiameterPx: Float,
) {
    val centerXInLocalPx: Float get() = centerXInRootPx - rootLeftInRootPx
    val centerYInLocalPx: Float get() = centerYInRootPx - rootTopInRootPx
}

data class NativeRangeExportPresentation(
    val sourceGeometry: RangeMorphSourceGeometry?,
    val waveformTargetBoundsInRoot: Rect?,
    val transitionStarted: Boolean,
    val interactionReady: Boolean,
    val backProgress: Float,
    val visualizerVisible: Boolean,
    val snapshotPresent: Boolean,
    val maximumDurationSeconds: Double,
    val chromeAlpha: Float,
    val waveformData: ProgressiveWaveformData,
    val waveformFrame: ProgressiveWaveformFrame,
    val admissionPending: Boolean = false,
)

const val RANGE_BLOB_MORPH_HANDOFF_PROGRESS = 0.07f

// These are the exact base-radius terms used by AudioBlobView's shader/fallback renderer:
// baseRadius = 0.095 + life * (0.235 + activity * 0.018).
private const val AUDIO_BLOB_BASE_RADIUS_FRACTION = 0.095f
private const val AUDIO_BLOB_LIFE_RADIUS_FRACTION = 0.235f
private const val AUDIO_BLOB_ACTIVITY_RADIUS_FRACTION = 0.018f
private const val AUDIO_BLOB_COLLAPSED_LIFE = 0.38f
private const val AUDIO_BLOB_DISABLED_LIFE = 0.36f

fun rangeBlobBaseRadiusFraction(
    active: Boolean,
    enabled: Boolean,
    activity: Float,
): Float {
    val life = when {
        !enabled -> AUDIO_BLOB_DISABLED_LIFE
        active -> 1f
        else -> AUDIO_BLOB_COLLAPSED_LIFE
    }
    val liveActivity = if (active) activity.coerceIn(0f, 1f) else 0f
    return AUDIO_BLOB_BASE_RADIUS_FRACTION +
        life * (AUDIO_BLOB_LIFE_RADIUS_FRACTION + liveActivity * AUDIO_BLOB_ACTIVITY_RADIUS_FRACTION)
}

fun rangeMorphSourceGeometry(
    rootBoundsInRoot: Rect?,
    blobBoundsInRoot: Rect?,
    active: Boolean,
    enabled: Boolean,
    activity: Float,
    renderedBaseRadiusFraction: Float? = null,
): RangeMorphSourceGeometry? {
    val root = rootBoundsInRoot ?: return null
    val blob = blobBoundsInRoot ?: return null
    val viewSize = minOf(blob.width, blob.height)
    if (viewSize <= 0f || root.width <= 0f || root.height <= 0f) return null
    val baseRadius = renderedBaseRadiusFraction
        ?.takeIf { it.isFinite() && it > 0f }
        ?: rangeBlobBaseRadiusFraction(active, enabled, activity)
    val bodyDiameter = viewSize * 2f * baseRadius
    return RangeMorphSourceGeometry(
        rootLeftInRootPx = root.left,
        rootTopInRootPx = root.top,
        centerXInRootPx = blob.center.x,
        centerYInRootPx = blob.center.y,
        bodyDiameterPx = bodyDiameter,
    )
}

fun rangeBlobMorphStartScaleX(blobDiameterPx: Float, timelineWidthPx: Float): Float =
    (blobDiameterPx / timelineWidthPx.coerceAtLeast(1f)).coerceAtLeast(0f)

fun rangeBlobMorphStartScaleY(blobDiameterPx: Float, timelineHeightPx: Float): Float =
    (blobDiameterPx / timelineHeightPx.coerceAtLeast(1f)).coerceAtLeast(0f)

fun rangeBlobMorphTranslation(progress: Float, sourceCenterPx: Float, targetCenterPx: Float): Float =
    (sourceCenterPx - targetCenterPx) * (1f - progress.coerceIn(0f, 1f))

/** Range data used for display. The editor and audio transport remain with the host. */
data class ReverbRangeEditorSnapshot(
    val startSeconds: Float,
    val endSeconds: Float,
    val durationSeconds: Float,
    val lastTarget: RangeEditTarget,
    val snapshotReady: Boolean,
    val durationActionAvailable: Boolean,
    val selectionDurationEditing: Boolean,
    val isPlaying: Boolean,
    val canExport: Boolean,
    val playbackAnchor: PlaybackPositionAnchor?,
) {
    fun targetSeconds(target: RangeEditTarget): Float =
        if (target == RangeEditTarget.START) startSeconds else endSeconds
}

data class ReverbRangeLabels(
    @StringRes val close: Int,
    @StringRes val oneShot: Int,
    @StringRes val looping: Int,
    @StringRes val preparing: Int,
    @StringRes val export: Int,
    @StringRes val duration: Int,
    @StringRes val play: Int,
    @StringRes val pause: Int,
)
