package smallthingz.ui.components

import android.content.Context
import android.view.View

/** Domain labels/callbacks and icon identities belong to the caller. Rows retain positional identity. */
data class NativeMenuAction<I>(val label: String, val icon: I, val textColor: Int, val iconColor: Int,
                              val enabled: Boolean = true, val onClick: () -> Unit)
data class NativeMenuChoices<T>(val selected: T, val options: List<T>, val optionLabel: (T) -> String)
data class NativeMenuColors(val raised: Int, val surfaceContainerHigh: Int, val ink: Int,
                            val muted: Int, val primary: Int)

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
