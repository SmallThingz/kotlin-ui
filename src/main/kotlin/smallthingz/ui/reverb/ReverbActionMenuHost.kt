package smallthingz.ui.reverb

import smallthingz.ui.components.NativePopupMenu
import smallthingz.ui.components.NativeMenuOrigin
import smallthingz.ui.components.NativeMenuAction
import smallthingz.ui.components.NativeMenuActionRole
import smallthingz.ui.scroll.NativeVerticalScrollState

import android.view.View
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import java.util.IdentityHashMap

/**
 * Concrete owner for the released leading-icon DropdownMenus. It reuses the Settings popup's
 * source window/placement/transition/scroll/input implementation; no platform PopupMenu is used.
 * An identity is one source menuCreated lifetime, not a recording ID or callback identity.
 */
enum class ReverbActionMenuKind { RECORDINGS, INCIDENTS }

data class ReverbMenuAction(
    val label: String,
    val icon: ReverbIcon,
    val isDestructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

class ReverbActionMenuHost(
    private var theme: ReverbTheme,
    private val kind: ReverbActionMenuKind,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private class Entry(val scroll: NativeVerticalScrollState = NativeVerticalScrollState()) {
        var session: Session? = null
    }
    private class Session(
        val anchor: View,
        val window: NativePopupMenu<ReverbIcon>,
    ) : AutoCloseable {
        var onDismissRequest: () -> Unit = {}
        var actionGeneration = 0L
        override fun close() = window.close()
    }
    private val entries = IdentityHashMap<Any, Entry>()
    private var closed = false
    fun bindTheme(theme: ReverbTheme) {
        this.theme = theme
        entries.values.mapNotNull { it.session }.forEach { it.window.bindTheme(theme.palette) }
    }

    fun show(identity: Any, anchor: View, actions: List<ReverbMenuAction>,
             onDismissRequest: () -> Unit = {}) =
        show(identity, anchor, actions, origin = null, onDismissRequest = onDismissRequest)

    fun show(identity: Any, anchor: View, actions: List<ReverbMenuAction>,
             origin: NativeMenuOrigin?, onDismissRequest: () -> Unit = {}) {
        if (closed) return
        val entry = entries.getOrPut(identity) { Entry() }
        val prior = entry.session
        if (prior != null && prior.anchor === anchor) {
            prior.onDismissRequest = onDismissRequest
            prior.window.setOrigin(origin)
            bind(identity, entry, prior, actions)
            prior.window.expanded(true)
            return
        }
        if (prior != null) retire(entry, prior)
        lateinit var session: Session
        val window = NativePopupMenu(anchor, theme.density,
            theme.appearance.typography,
            theme.fontResolver, theme.palette, entry.scroll, frames, scope, ReverbMenuIcons,
            recipe = theme.appearance.modals.anchoredPanel,
            onDismissRequest = {
                if (entries[identity] === entry && entry.session === session) {
                    session.onDismissRequest()
                    if (entries[identity] === entry && entry.session === session) session.window.expanded(false)
                }
            },
            onTerminal = { if (entry.session === session) retire(entry, session) })
        session = Session(anchor, window)
        session.onDismissRequest = onDismissRequest
        entry.session = session
        try {
            bind(identity, entry, session, actions)
            window.setOrigin(origin)
            window.show()
        } catch (failure: Throwable) {
            if (entry.session === session) entry.session = null
            try { session.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun update(identity: Any, anchor: View, actions: List<ReverbMenuAction>,
               onDismissRequest: () -> Unit = {}) {
        if (closed) return
        val entry = entries[identity] ?: return
        val session = entry.session ?: return
        session.onDismissRequest = onDismissRequest
        if (session.anchor !== anchor) retire(entry, session)
        else bind(identity, entry, session, actions)
    }

    private fun bind(identity: Any, entry: Entry, session: Session,
                     actions: List<ReverbMenuAction>) {
        val generation = ++session.actionGeneration
        session.window.bindActions(actions.map { action ->
            val role = when {
                action.isDestructive -> NativeMenuActionRole.DESTRUCTIVE
                kind == ReverbActionMenuKind.INCIDENTS -> NativeMenuActionRole.MUTED_ICON
                else -> NativeMenuActionRole.NORMAL
            }
            NativeMenuAction(action.label, action.icon, role, action.enabled, onClick = {
                if (action.enabled && !closed && entries[identity] === entry && entry.session === session &&
                    session.actionGeneration == generation && session.window.acceptAction()) {
                    // Acceptance owns this action even if a zero-duration exit already retired
                    // the session. No later cleanup can mask failure or close a reopened menu.
                    action.onClick()
                }
            })
        })
    }

    /** Source expanded=false keeps ScrollState/menuCreated until its row is actually disposed. */
    fun hide(identity: Any) { entries[identity]?.session?.window?.expanded(false) }

    fun release(identity: Any) {
        val entry = entries.remove(identity) ?: return
        entry.session?.let { retire(entry, it) }
    }

    private fun retire(entry: Entry, session: Session) {
        if (entry.session === session) entry.session = null
        session.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        val sessions = entries.values.mapNotNull { it.session }
        entries.clear()
        var failure: Throwable? = null
        for (session in sessions) {
            try { session.close() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }
}
