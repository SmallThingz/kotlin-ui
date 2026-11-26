package smallthingz.ui.reverb

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import smallthingz.ui.components.CachedPathIconDrawable
import smallthingz.ui.components.NativePathIconMask

/**
 * A single rounded tab outline and its count, in the shared 24 x 24 glyph viewport.
 *
 * The count is immutable; the chrome replaces this drawable when the count changes and retains
 * it for tint/state changes. The host owns its 48dp action, localized count description and noisy
 * foreground surface. This uses the same ALPHA_8 mask/SrcIn tint renderer as ReverbIconDrawable;
 * it adds neither an opaque background nor a second button outline. Numbers do not mirror in RTL.
 *
 * Brave's Android tab counter uses a bounded two-digit count (and a face above 99). Here the
 * explicit 99+ overflow keeps that bound while describing its meaning without an easter egg.
 * Original counter geometry; no upstream artwork is copied.
 */
class ReverbBrowserTabCountDrawable(count: Int, density: Float, tint: Int) :
    CachedPathIconDrawable(browserTabCountMask(count), density, tint) {
    val count: Int = count.coerceAtLeast(0)
}

internal fun browserTabCountLabel(count: Int): String =
    if (count > 99) "99+" else count.coerceAtLeast(0).toString()

private fun browserTabCountMask(count: Int): () -> NativePathIconMask {
    val outline = Path().apply {
        // Opposite windings cut out the interior of this one outline.
        addRoundRect(2f, 2f, 22f, 22f, 4f, 4f, Path.Direction.CW)
        addRoundRect(4f, 4f, 20f, 20f, 2f, 2f, Path.Direction.CCW)
    }
    val label = browserTabCountLabel(count)
    val lettering = Path()
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textSize = 12f
        getTextPath(label, 0, label.length, 0f, 0f, lettering)
    }
    val ink = RectF()
    lettering.computeBounds(ink, true)
    if (!ink.isEmpty) {
        // Fit the actual glyph ink, including the plus, with clearance from the outline.
        val scale = minOf(1f, 14f / ink.width(), 12f / ink.height())
        lettering.transform(Matrix().apply {
            setScale(scale, scale)
            postTranslate(12f - ink.centerX() * scale, 12f - ink.centerY() * scale)
        })
    }
    val mask = NativePathIconMask(outline, lettering)
    return { mask }
}
