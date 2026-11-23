package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.common.PairedDropdownLayout
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.util.closePreservingPrimaryFailure

/** Application-provided choice data. Labels are display text and each value is a typed identity. */
data class ReverbAudioOptions<Channels, Format, Source, Route>(
    val channels: DropdownContent<Channels>,
    val format: DropdownContent<Format>,
    val rate: DropdownContent<Int>,
    val source: DropdownContent<Source>,
    val route: DropdownContent<Route>,
)

data class ReverbAudioActions<Channels, Format, Source, Route>(
    val channels: (Channels) -> Unit,
    val format: (Format) -> Unit,
    val rate: (Int) -> Unit,
    val source: (Source) -> Unit,
    val route: (Route) -> Unit,
)

/** Five typed fields in the Recording settings group. Adjacent fields share the group's dividers. */
class ReverbAudioChoices<Channels, Format, Source, Route>(
    context: Context,
    theme: ReverbTheme,
    initial: ReverbAudioOptions<Channels, Format, Source, Route>,
    actions: ReverbAudioActions<Channels, Format, Source, Route>,
    inputRoot: () -> View?,
    inputAllowed: () -> Boolean,
) : AutoCloseable {
    private val dropdowns = DropdownGroup(theme.controls, {
        inputRoot()?.rootView?.findViewById<View>(android.R.id.content) as? FrameLayout
    }, inputAllowed)
    private val fields = ReverbDesign.appearance.groupSurfaces.settingsChoices
    internal val group = fields.create(context, ReverbTextSurface::invalidate)
    val view: View get() = group
    private val channels = dropdowns.field(context, initial.channels, inputRoot, actions.channels)
    private val format = dropdowns.field(context, initial.format, inputRoot, actions.format)
    private val rate = dropdowns.field(context, initial.rate, inputRoot, actions.rate)
    private val source = dropdowns.field(context, initial.source, inputRoot, actions.source)
    private val route = dropdowns.field(context, initial.route, inputRoot, actions.route)
    private val sample = PairedDropdownLayout(context, format.native, rate.native)
    private val input = PairedDropdownLayout(context, source.native, route.native)
    private var closed = false

    init {
        group.addRow(channels.view, NativeGroupLayout.LayoutParams(-1, -2))
        group.addRow(sample, NativeGroupLayout.LayoutParams(-1, -2))
        group.addRow(input, NativeGroupLayout.LayoutParams(-1, -2))
        bindLayout(theme)
    }

    fun bind(next: ReverbAudioOptions<Channels, Format, Source, Route>, theme: ReverbTheme) {
        if (closed) return
        dropdowns.bindTheme(theme.controls)
        channels.bind(next.channels)
        format.bind(next.format)
        rate.bind(next.rate)
        source.bind(next.source)
        route.bind(next.route)
        bindLayout(theme)
    }

    private fun bindLayout(theme: ReverbTheme) {
        val showChannels = channels.view.visibility != View.GONE
        val showSample = format.view.visibility != View.GONE || rate.view.visibility != View.GONE
        val showInput = source.view.visibility != View.GONE || route.view.visibility != View.GONE
        sample.visibility = if (showSample) View.VISIBLE else View.GONE
        input.visibility = if (showInput) View.VISIBLE else View.GONE
        rate.native.dividerAtStart = format.view.visibility != View.GONE
        route.native.dividerAtStart = source.view.visibility != View.GONE
        topDivider(sample, showChannels)
        topDivider(input, showChannels || showSample)
        fields.bind(group, theme.palette)
    }

    private fun topDivider(row: View, enabled: Boolean) {
        val params = row.layoutParams as NativeGroupLayout.LayoutParams
        if (params.dividerTop == enabled) return
        params.dividerTop = enabled
        row.requestLayout()
    }

    fun dismissDropdown() = dropdowns.dismiss()

    override fun close() {
        if (closed) return
        closed = true
        var failure = closePreservingPrimaryFailure(null, dropdowns::close)
        for (field in listOf(channels, format, rate, source, route)) {
            failure = closePreservingPrimaryFailure(failure, field::close)
        }
        failure?.let { throw it }
    }
}
