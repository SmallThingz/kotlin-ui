package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ListView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.components.nativeTextStyle
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/**
 * Visual list defaults only. The adapter, stable IDs, recycling, scroll/input arbitration and
 * saved reader position belong to the host. Layout notifications let that owner restore its
 * anchors without implementing a second visual layout or moving its data model here.
 */
open class ReverbCommandTranscriptList(
    context: Context,
    private val beforeViewportLayout: () -> Unit = {},
    private val afterViewportLayout: () -> Unit = {},
) : ListView(context) {
    init {
        divider = null
        dividerHeight = 0
        cacheColorHint = Color.TRANSPARENT
        selector = ColorDrawable(Color.TRANSPARENT)
        itemsCanFocus = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        beforeViewportLayout()
        super.onLayout(changed, left, top, right, bottom)
        afterViewportLayout()
    }
}

/** One retained transcript viewport and one lazily-created, lifecycle-gated expansion clock. */
open class ReverbCommandTranscriptFrame(
    context: Context,
    theme: ReverbTheme,
    list: ReverbCommandTranscriptList,
    emptyLabel: String,
) : FrameLayout(context) {
    private val empty = ReverbCommandNotice(context, theme).apply { bind(emptyLabel) }
    private var runtime: NativeModalWindowRuntime? = null
    private var lifecycle: Lifecycle? = null
    private val observer = LifecycleEventObserver { _, _ -> syncMotion() }

    init {
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun showEmpty(value: Boolean) { empty.visibility = if (value) VISIBLE else GONE }

    internal fun expansionScope(): CoroutineScope =
        (runtime ?: NativeModalWindowRuntime(context).also { runtime = it; syncMotion() }).scope

    private fun syncMotion() {
        runtime?.setStarted(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false &&
            isAttachedToWindow && isShown && windowVisibility == VISIBLE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycle = findViewTreeLifecycleOwner()?.lifecycle
        lifecycle?.addObserver(observer)
        syncMotion()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncMotion()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncMotion()
    }

    override fun onDetachedFromWindow() {
        lifecycle?.removeObserver(observer)
        lifecycle = null
        runtime?.close()
        runtime = null
        super.onDetachedFromWindow()
    }
}

/** The caller supplies an empty/omission message, including any localized counts. */
class ReverbCommandNotice(context: Context, theme: ReverbTheme, omission: Boolean = false) : NativeText(
    context, theme.palette.pageControls,
    theme.appearance.typography.nativeTextStyle(NativeTypographyRole.BODY_SMALL), NativeTextRole.MUTED,
) {
    init {
        if (omission) setPaddingRelative(0, 0, 0,
            (theme.appearance.settingsSelectors.retentionSection.stackedGapDp * theme.density.density).roundToInt())
    }

    fun bind(label: String) { if (!label.contentEquals(text)) text = label }
}
