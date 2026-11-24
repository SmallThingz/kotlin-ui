// Reverb application presentation source, retained under the Reverb project GPL-3.0 license.

package smallthingz.ui.reverb

import smallthingz.ui.components.NativeColorScheme

import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope

/** IncidentsScreen446–467: Copy always; Delete only for a currently mutable history snapshot. */
internal class ReverbIncidentMenuHost(
    theme: ReverbTheme,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    private val copyText: String,
    private val deleteText: String,
) : IncidentCardMenuHost, AutoCloseable {
    private val host = ReverbActionMenuHost(theme, ReverbActionMenuKind.INCIDENTS, frames, scope)
    private val requests = java.util.IdentityHashMap<IncidentCardMenuIdentity, IncidentCardMenuRequest>()
    private fun actions(request: IncidentCardMenuRequest): List<ReverbMenuAction> = buildList {
        add(ReverbMenuAction(copyText, ReverbIcon.Copy, onClick = request.copy))
        if (request.canDelete) add(ReverbMenuAction(deleteText, ReverbIcon.Delete,
            isDestructive = true, onClick = request.delete))
    }
    override fun show(request: IncidentCardMenuRequest) {
        requests[request.identity] = request
        host.show(request.identity, request.anchor, actions(request), origin = request.origin)
    }
    override fun update(request: IncidentCardMenuRequest) {
        requests[request.identity] = request
        host.update(request.identity, request.anchor, actions(request))
    }
    fun bindPalette(palette: NativeColorScheme) {
        host.bindPalette(palette)
        requests.values.toList().forEach(::update)
    }
    override fun release(identity: IncidentCardMenuIdentity) { requests.remove(identity); host.release(identity) }
    override fun close() { requests.clear(); host.close() }
}
