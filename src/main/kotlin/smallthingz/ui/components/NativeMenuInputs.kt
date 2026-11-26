package smallthingz.ui.components

import android.content.Context
import android.view.View

/** Domain labels/callbacks and icon identities belong to the caller. Rows retain positional identity. */
enum class NativeMenuActionRole { NORMAL, MUTED_ICON, DESTRUCTIVE }

data class NativeMenuAction<I>(
    val label: String,
    val icon: I,
    val role: NativeMenuActionRole = NativeMenuActionRole.NORMAL,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

internal fun <I> NativeMenuAction<I>.resolve(palette: NativeColorScheme): ResolvedMenuAction<I> {
    val text = if (role == NativeMenuActionRole.DESTRUCTIVE) palette.error else palette.onSurface
    val glyph = if (role == NativeMenuActionRole.MUTED_ICON) palette.onSurfaceVariant else text
    return ResolvedMenuAction(label, icon, text, glyph, enabled, onClick)
}

internal data class ResolvedMenuAction<I>(val label: String, val icon: I, val textColor: Int, val iconColor: Int,
                              val enabled: Boolean = true, val onClick: () -> Unit)
data class NativeMenuChoices<T>(val selected: T, val options: List<T>, val optionLabel: (T) -> String)
internal data class NativeMenuColors(val raised: Int, val surfaceContainerHigh: Int, val ink: Int,
                            val muted: Int, val primary: Int, val grainIntensity: Float = 0f)

internal fun NativeAnchoredMenuRecipe.colors(palette: NativeColorScheme): NativeMenuColors {
    val fill = surfaceColor(palette)
    return NativeMenuColors(fill, fill, palette.ink, palette.muted, palette.primary,
        palette.popupGrain)
}

/** One retained icon view per row; bind must accept every icon produced by this renderer. */
interface NativeMenuIcons<I> {
    val check: I
    fun create(context: Context, icon: I, color: Int): View
    fun bind(view: View, icon: I, color: Int)
}

/** A checkable menu item can be independent or belong to an exclusive group. */
data class NativeMenuCheckState(val checked: Boolean, val exclusive: Boolean = false)

/** Optional checked state for action rows. Null retains the action's ordinary leading icon. */
interface NativeCheckableMenuIcons<I> : NativeMenuIcons<I> {
    fun checkState(icon: I): NativeMenuCheckState?
}

internal fun exact(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
internal fun atMost(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
internal fun unspecified(): Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
