// Reverb application presentation source, retained under the Reverb project GPL-3.0 license.

package smallthingz.ui.reverb

/** An occurrence's opaque action identity, independent from its group or displayed message. */
interface ReverbIncidentTarget {
    fun matches(reference: ReverbIncidentTarget): Boolean
}

interface ReverbIncidentLiveEnd {
    val ongoing: Boolean
    fun labelAt(elapsedMillis: Long): String
}

interface ReverbIncidentOccurrence {
    val key: String
    val target: ReverbIncidentTarget
    val acknowledged: Boolean
    val startLabel: String
    val endLabel: String
    val causeLabel: String
    val diagnostics: String
    val liveEnd: ReverbIncidentLiveEnd?
}

data class ReverbIncidentGroup(val key: String, val message: String, val occurrences: List<ReverbIncidentOccurrence>)

enum class ReverbIncidentLoadStatus { LOADING, READY, UNAVAILABLE }

data class ReverbIncidentHistory(
    val groups: List<ReverbIncidentGroup> = emptyList(),
    val status: ReverbIncidentLoadStatus = ReverbIncidentLoadStatus.LOADING,
    val rows: List<ReverbIncidentOccurrence> = emptyList(),
) {
    val canMutate get() = status == ReverbIncidentLoadStatus.READY
    val empty get() = canMutate && rows.isEmpty()
}

data class ReverbIncidentActions(
    val toggleAcknowledged: (ReverbIncidentTarget) -> Unit,
    val delete: (ReverbIncidentTarget) -> Unit,
    val deleteMany: (List<ReverbIncidentTarget>) -> Unit,
    val copy: (ReverbIncidentTarget) -> Unit,
)

data class ReverbIncidentTexts(
    val title: String,
    val back: String,
    val clearSelection: String,
    val selectionCount: (Int) -> String,
    val multiSelect: String,
    val selectAll: String,
    val deselectAll: String,
    val deleteSelected: String,
    val clearAll: String,
    val clearConfirmation: String,
    val cancel: String,
    val empty: String,
    val emptyDetail: String,
    val loading: String,
    val unavailable: String,
    val stale: String,
    val select: String,
    val deselect: String,
    val markChecked: String,
    val markUnchecked: String,
    val openOptions: String,
    val copy: String,
    val delete: String,
)
