package smallthingz.ui.components

import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.NativeMergedRowAccessibilityState
import smallthingz.ui.unit.Density

/** A permission's current status and optional request action. The host supplies platform state. */
open class NativePermissionRow(
    context: Context,
    titleStyle: TextStyle,
    bodyStyle: TextStyle,
    density: Density,
    resolver: FontFamily.Resolver,
    marker: NativeDetailCardMarker,
    trailing: View,
    private val independentAccessory: Boolean = false,
    trailingWidthDp: Int = if (independentAccessory) 48 else 24,
    trailingHeightDp: Int = if (independentAccessory) 48 else 24,
    inputRoot: () -> View? = { null },
    style: NativeDetailCardStyle = NativeDetailCardStyle(),
    standaloneAccessibility: Boolean = true,
) : NativeDetailCardRow(context, titleStyle, bodyStyle, density, resolver, marker, trailing,
    trailingWidthDp, trailingHeightDp, inputRoot, style) {
    private var titleText = ""
    private var bodyText: String? = null
    private var status: CharSequence? = null
    private var requestLabel: CharSequence? = null
    private val accessoryChildren = if (independentAccessory) listOf(trailing) else emptyList()
    val canRequestNow: Boolean get() = rowInputEnabled && hasRowAction

    override val includeTrailingAccessibility: Boolean
        get() = independentAccessory || (status == null && super.includeTrailingAccessibility)

    init {
        isClickable = false
        if (independentAccessory) descendantFocusability = FOCUS_AFTER_DESCENDANTS
        if (standaloneAccessibility) mergedRowAccessibility.enableStandaloneAccessibility({ isShown }) {
            val detail = bodyText?.takeIf { it.isNotEmpty() }
            if (detail == null) titleText else "$titleText, $detail"
        }
    }

    /** A granted permission is descriptive even if a request callback is still supplied. */
    fun bindPermission(
        title: String,
        body: String?,
        granted: Boolean,
        statusDescription: CharSequence?,
        palette: NativeColorScheme,
        requestEnabled: Boolean = true,
        requestLabel: CharSequence? = null,
        onRequest: (() -> Unit)? = null,
    ) {
        titleText = title
        bodyText = body
        status = statusDescription
        this.requestLabel = requestLabel
        onActivate = onRequest.takeIf { !granted && requestEnabled }
        val enabled = onActivate != null
        bindCard(title, body, enabled, palette)
        isClickable = enabled
        ViewCompat.setStateDescription(this, statusDescription)
    }

    override fun accessibilityState(): NativeMergedRowAccessibilityState {
        val actionable = canRequestNow
        return NativeMergedRowAccessibilityState(
            enabled = actionable || status != null || independentAccessory,
            checked = null,
            clickLabel = if (actionable) requestLabel else null,
            orderedChildren = accessibilityChildren(),
            clickable = actionable,
            rolePresent = actionable,
            stateDescription = status,
            readOnlyScreenReaderFocusable = status != null,
            standaloneChildren = if (trailing.visibility == VISIBLE) accessoryChildren else emptyList(),
        )
    }
}
