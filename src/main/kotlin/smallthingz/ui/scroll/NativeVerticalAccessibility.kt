/*
 * Copyright 2024 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * Source-derived from BringIntoViewSpec.android.kt and AndroidComposeViewAccessibilityDelegate.
 */
package smallthingz.ui.scroll

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.view.View
import smallthingz.ui.foundation.gestures.BringIntoViewSpec
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import kotlin.math.abs

internal enum class NativeVerticalAccessibilityDirection { FORWARD, BACKWARD }

/** Source delegate admits an action only when its signed viewport amount can change the range. */
internal fun nativeVerticalAccessibilityDelta(
    direction: NativeVerticalAccessibilityDirection,
    viewportHeight: Float,
    position: Int,
    maximum: Int,
    enabled: Boolean,
): Float? {
    if (!enabled) return null
    val delta = if (direction == NativeVerticalAccessibilityDirection.FORWARD) viewportHeight else -viewportHeight
    return delta.takeIf { (it < 0f && position > 0) || (it > 0f && position < maximum) }
}

fun nativeBringIntoViewSpec(context: Context): BringIntoViewSpec =
    if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val itemSize = abs((offset + size) - offset)
                val initialTarget = .3f * containerSize - 0f * itemSize
                val space = containerSize - initialTarget
                val target = if (itemSize <= containerSize && space < itemSize) containerSize - itemSize else initialTarget
                return offset - target
            }
        }
    } else {
        object : BringIntoViewSpec {} // Uses the pinned public default minimum-distance arithmetic.
    }

/** An ordinary View ancestor participates in the same concurrent responder chain. */
interface NativeBringIntoViewParent {
    val bringIntoViewView: View
    suspend fun bringDescendantIntoView(child: View, bounds: () -> Rect?)
}

/**
 * Source BringIntoViewResponder.localRectOf translates a requested rect by the unclipped FULL
 * child bounding box's top-left. Preserve that rule, rather than clipping the request or rounding
 * every ancestor offset. Coordinates are re-read for every animation frame.
 */
fun nativeBringIntoViewLocalRect(ancestor: View, child: View, requested: Rect): Rect? {
    if (!ancestor.isAttachedToWindow || !child.isAttachedToWindow) return null
    val fullBounds = RectF(0f, 0f, child.width.toFloat(), child.height.toFloat())
    var current = child
    while (current !== ancestor) {
        val parent = current.parent as? View ?: return null
        current.matrix.mapRect(fullBounds)
        fullBounds.offset((current.left - parent.scrollX).toFloat(), (current.top - parent.scrollY).toFloat())
        current = parent
    }
    return requested.translate(Offset(fullBounds.left, fullBounds.top))
}

/** Copies Android child-content coordinates and converts them using the child's live scroll. */
fun nativeChildRectangleBounds(child: View, rectangle: AndroidRect): () -> Rect {
    val requested = Rect(rectangle.left.toFloat(), rectangle.top.toFloat(),
        rectangle.right.toFloat(), rectangle.bottom.toFloat())
    return {
        val scrollX = child.scrollX
        val scrollY = child.scrollY
        if (scrollX == 0 && scrollY == 0) requested
        else Rect(requested.left - scrollX, requested.top - scrollY,
            requested.right - scrollX, requested.bottom - scrollY)
    }
}

/** Only Android's direct automatic focus callback duplicates our requestChildFocus reveal. */
fun nativeIsAutomaticFocusRectangle(host: View): Boolean {
    val stack = Throwable().stackTrace
    val hostClass = host.javaClass.name
    val index = stack.indexOfFirst {
        it.className == hostClass && it.methodName == "requestChildRectangleOnScreen"
    }
    if (index < 0 || index + 2 >= stack.size) return false
    // Anchor the nearest host entry: an outer automatic request may contain a nested explicit call.
    val request = stack[index + 1]
    val caller = stack[index + 2]
    return request.className == "android.view.View" && request.methodName == "requestRectangleOnScreen" &&
        caller.className == "android.view.View" && caller.methodName == "onFocusChanged"
}

/** Copies an INPUT_FOCUS child-content rectangle and retires it with its focus generation. */
fun nativeFocusRectangleBounds(
    host: View,
    child: View,
    rectangle: AndroidRect,
    isCurrentGeneration: () -> Boolean,
): (() -> Rect?)? {
    val focused = host.findFocus()?.takeIf { it !== host } ?: return null
    val bounds = nativeChildRectangleBounds(child, rectangle)
    fun currentBounds(): Rect? {
        if (!isCurrentGeneration() || host.findFocus() !== focused ||
            !focused.isAttachedToWindow || child.parent !== host) return null
        var descendant: View = focused
        while (descendant !== child) {
            descendant = descendant.parent as? View ?: return null
        }
        return bounds()
    }
    return if (currentBounds() != null) ::currentBounds else null
}

fun Rect.toNativeBringIntoViewRect(): AndroidRect =
    // AndroidComposeView's final native boundary uses toAndroidRect's Float.toInt conversion.
    AndroidRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
