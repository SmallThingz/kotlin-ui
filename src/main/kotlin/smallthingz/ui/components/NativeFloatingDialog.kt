/*
 * Native adaptation of AndroidX Compose UI/Animation behavior.
 * Copyright 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0; see LICENSE.txt and SOURCE-dialogs.json.
 */

package smallthingz.ui.components

import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import android.os.Build
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Source-derived floating modal window with explicit caller content and semantic lifetime.
 * Retains the source theme/window policy, sizing, animations and cancellation behavior.
 *
 * The owner retains this object for one route entry and calls dismiss() when that route is removed.
 * Recreate on anchor/density changes, matching released Dialog's remember keys.
 * Actual window/frame/input parity remains a fixture obligation.
 */
class NativeFloatingDialog(
    private val anchor: View,
    private val density: Float,
    onDismiss: () -> Unit,
    statusBarProtection: NativeStatusBarProtection?,
    private var shadowDp: Float,
    private val entryEdge: NativeDialogEdge?,
    createContent: (android.content.Context, NativeModalWindowRuntime, () -> Unit) -> NativeFloatingDialogContent,
) : ComponentDialog(ContextThemeWrapper(anchor.context, smallthingz.ui.R.style.FloatingDialogWindowTheme)) {
    constructor(
        anchor: View,
        density: Float,
        onDismiss: () -> Unit,
        statusBarProtection: NativeStatusBarProtection?,
        shadowDp: Float,
        createContent: (android.content.Context, NativeModalWindowRuntime, () -> Unit) -> NativeFloatingDialogContent,
    ) : this(anchor, density, onDismiss, statusBarProtection, shadowDp, null, createContent)

    constructor(
        anchor: View,
        density: Float,
        onDismiss: () -> Unit,
        entryEdge: NativeDialogEdge,
        createContent: (android.content.Context, NativeModalWindowRuntime, () -> Unit) -> NativeFloatingDialogContent,
    ) : this(anchor, density, onDismiss, null, NativeFloatingPanelRecipe.Default.shadowDp, entryEdge, createContent)

    constructor(
        anchor: View,
        density: Float,
        onDismiss: () -> Unit,
        statusBarProtection: NativeStatusBarProtection?,
        createContent: (android.content.Context, NativeModalWindowRuntime, () -> Unit) -> NativeFloatingDialogContent,
    ) : this(anchor, density, onDismiss, statusBarProtection, NativeFloatingPanelRecipe.Default.shadowDp, createContent)

    constructor(
        anchor: View,
        density: Float,
        onDismiss: () -> Unit,
        createContent: (android.content.Context, NativeModalWindowRuntime, () -> Unit) -> NativeFloatingDialogContent,
    ) : this(anchor, density, onDismiss, null, createContent)

    private var currentOnDismiss = onDismiss
    private val visibilityMotion = NativeFloatingVisibilityMotion()
    private val predictiveMotion = NativeFloatingPredictiveMotion(animateCancellation = true)
    private val runtime = NativeModalWindowRuntime(context)
    private var predictiveSession = false
    private val choreographer = Choreographer.getInstance()
    private var callbackPosted = false
    private var disposed = false
    private var exitDelivered = false
    private var pressOutside = false
    private val hostLifecycle = anchor.findViewTreeLifecycleOwner()?.lifecycle
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> { runtime.setStarted(true); requestFrame() }
            Lifecycle.Event.ON_STOP -> {
                runtime.setStarted(false)
                choreographer.removeFrameCallback(frameCallback)
                callbackPosted = false
            }
            Lifecycle.Event.ON_DESTROY -> dismiss()
            else -> Unit
        }
    }
    private val contentOwner = createContent(context, runtime, ::requestDismiss).also { owner ->
        if (entryEdge != null) (owner.predictiveMotionTarget as? NativeFloatingPanelSurface)?.bindDialogEdge(entryEdge)
    }
    private val content = contentOwner.view.apply { alpha = 0f }
    private val root = FloatingWindowRoot(
        context, requireNotNull(window), content, statusBarProtection, entryEdge,
        host = anchor.rootView.takeIf { entryEdge != null },
        exteriorTopPaintSpace = contentOwner.exteriorTopPaintSpace,
        exteriorBottomPaintSpace = contentOwner.exteriorBottomPaintSpace,
    ) {
        visibilityMotion.setHeight(content.measuredHeight)
        applyMotion()
        requestFrame()
    }
    private val frameCallback = Choreographer.FrameCallback { frameNanos ->
        callbackPosted = false
        if (!disposed && root.isAttachedToWindow && hostStarted()) {
            val scale = readCachedAnimatorDurationScale(context)
            visibilityMotion.frame(frameNanos, scale)
            val predictiveCommitted = predictiveMotion.frame(frameNanos, scale)
            applyMotion()
            if (predictiveCommitted) {
                deliverPredictiveCommit()
            } else if (visibilityMotion.exiting && visibilityMotion.finished && !exitDelivered) {
                exitDelivered = true
                content.visibility = View.GONE
                currentOnDismiss()
            }
            requestFrame()
        }
    }
    private val predictiveCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            if (!isEnabled || disposed) return
            predictiveSession = true
            predictiveMotion.started(backEvent.progress)
            applyMotion()
        }
        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (!isEnabled || disposed || !predictiveSession) return
            predictiveMotion.progressed(backEvent.progress)
            applyMotion()
        }
        override fun handleOnBackCancelled() {
            if (!isEnabled || disposed) return
            predictiveSession = false
            predictiveMotion.cancelled()
            applyMotion()
            requestFrame()
        }
        override fun handleOnBackPressed() {
            if (!isEnabled || disposed) return
            val complete = if (predictiveSession) predictiveMotion.committed() else predictiveMotion.ordinaryBack()
            predictiveSession = false
            if (complete) {
                applyMotion()
                deliverPredictiveCommit()
            } else requestFrame()
        }
    }

    init {
        val window = requireNotNull(window)
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setGravity(if (entryEdge == NativeDialogEdge.BOTTOM) Gravity.BOTTOM else Gravity.TOP)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.attributes = window.attributes.apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsSides(0)
                setFitInsetsTypes(0)
            }
        }
        window.setSoftInputMode(if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        setTitle("")
        inheritSecurePolicy()
        root.layoutDirection = anchor.layoutDirection
        contentOwner.bindWindowAccessibility(root)
        root.elevation = shadowDp * density
        root.outlineProvider = NativeWindowShadowOutline
        if (Build.VERSION.SDK_INT >= 29) window.decorView.isForceDarkAllowed = false
        (window.decorView as? ViewGroup)?.disableClippingUntil(root)
        setContentView(root)
        statusBarProtection?.applyWindowAppearance(window)
        root.setViewTreeLifecycleOwner(anchor.findViewTreeLifecycleOwner())
        root.setViewTreeViewModelStoreOwner(anchor.findViewTreeViewModelStoreOwner())
        root.setViewTreeSavedStateRegistryOwner(anchor.findViewTreeSavedStateRegistryOwner())
        // DialogWrapper installs decor/content before DialogLayout.updateProperties sets width.
        // PhoneWindow.generateLayout resets a floating window to WRAP_CONTENT during decor creation.
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setCanceledOnTouchOutside(true)
        // The fallback remains enabled while an explicit exit disables predictive handling.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestDismiss()
        })
        onBackPressedDispatcher.addCallback(this, predictiveCallback)
        hostLifecycle?.addObserver(lifecycleObserver)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                runtime.setStarted(hostStarted())
                ViewCompat.requestApplyInsets(root)
                requestFrame()
            }
            override fun onViewDetachedFromWindow(view: View) {
                runtime.setStarted(false)
                choreographer.removeFrameCallback(frameCallback)
                callbackPosted = false
            }
        })
    }

    /** Rebind the caller callback and inherited window policy without replacing content. */
    fun bind(onDismiss: () -> Unit) = bind(onDismiss, shadowDp)

    fun bind(onDismiss: () -> Unit, shadowDp: Float) {
        currentOnDismiss = onDismiss
        if (this.shadowDp != shadowDp) {
            this.shadowDp = shadowDp
            root.elevation = shadowDp * density
        }
        root.layoutDirection = anchor.layoutDirection
        inheritSecurePolicy()
    }

    /** Close button, outside click and Escape own the source AnimatedVisibility exit. */
    fun requestDismiss() {
        if (disposed || visibilityMotion.exiting) return
        predictiveCallback.isEnabled = false
        predictiveSession = false
        predictiveMotion.disable()
        visibilityMotion.requestExit()
        applyMotion()
        requestFrame()
    }

    private fun deliverPredictiveCommit() {
        try {
            // Successful predictive completion invokes owner directly, without a second exit.
            currentOnDismiss()
        } finally {
            predictiveMotion.finishCommitCallback()
            if (!disposed) applyMotion()
        }
    }

    private fun inheritSecurePolicy() {
        val flags = (anchor.rootView.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0
        requireNotNull(window).setFlags(flags and WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun applyMotion() {
        content.translationX = visibilityMotion.translation.x.toFloat()
        val edge = entryEdge ?: NativeDialogEdge.TOP
        content.translationY = edge.orientTopOffset(visibilityMotion.translation.y.toFloat())
        content.alpha = visibilityMotion.alpha
        val progress = if (predictiveMotion.active) predictiveMotion.progress.coerceIn(0f, 1f) else 0f
        val target = contentOwner.predictiveMotionTarget
        if (target === content) {
            // A single caller View represents both layers; compose instead of overwriting entry/exit.
            content.translationY += edge.orientTopOffset(-target.height * progress)
            content.alpha *= 1f - .22f * progress
        } else {
            target.translationY = edge.orientTopOffset(-target.height * progress)
            target.alpha = 1f - .22f * progress
        }
    }

    private fun requestFrame() {
        if (disposed || callbackPosted || !root.isAttachedToWindow || !hostStarted()) return
        if (!visibilityMotion.needsFrame(predictiveMotion.hasContinuation, exitDelivered)) return
        callbackPosted = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun hostStarted(): Boolean = hostLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isTracking && !event.isCanceled && keyCode == KeyEvent.KEYCODE_ESCAPE) {
            requestDismiss()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var result = super.onTouchEvent(event)
        if (!root.isInsideContent(event)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressOutside = true; result = true }
                MotionEvent.ACTION_UP -> if (pressOutside) {
                    requestDismiss(); result = true; pressOutside = false
                }
                MotionEvent.ACTION_CANCEL -> pressOutside = false
            }
        } else if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressOutside = false
        }
        return result
    }

    /** Released DialogWrapper prevents platform cancellation from removing its own Window. */
    override fun cancel() = Unit

    /** Owner disposal is immediate and must not invoke its own onDismiss callback again. */
    override fun dismiss() {
        if (disposed) return
        disposed = true
        predictiveSession = false
        predictiveMotion.disable()
        choreographer.removeFrameCallback(frameCallback)
        callbackPosted = false
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure, block)
        }
        release { hostLifecycle?.removeObserver(lifecycleObserver) }
        release { predictiveCallback.remove() }
        release { contentOwner.dispose() }
        release { runtime.close() }
        release { super.dismiss() }
        failure?.let { throw it }
    }
}

private fun ViewGroup.disableClippingUntil(stop: ViewGroup) {
    clipChildren = false
    if (this === stop) return
    for (index in 0 until childCount) {
        (getChildAt(index) as? ViewGroup)?.disableClippingUntil(stop)
    }
}

/** Source AndroidDialog.DialogLayout measure/inset/outside-hit policy for the non-fitting variant. */
private class FloatingWindowRoot(
    context: android.content.Context,
    private val dialogWindow: Window,
    private val content: View,
    private val statusBarProtection: NativeStatusBarProtection?,
    private val entryEdge: NativeDialogEdge?,
    private val host: View?,
    private val exteriorTopPaintSpace: (() -> Int)?,
    private val exteriorBottomPaintSpace: (() -> Int)?,
    private val onContentMeasured: () -> Unit,
) : ViewGroup(context) {
    private var topPaintSpace = 0
    private var bottomPaintSpace = 0

    init {
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Released AndroidComposeView owns initial native window focus before child controls.
        configureNativeFocusParking()
        addView(content)
        foreground = statusBarProtection
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            statusBarProtection?.updateInsets(insets)
            insetForChild(insets) { l, t, r, b -> insets.inset(l, t, r, b) }
        }
        ViewCompat.setWindowInsetsAnimationCallback(this,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onStart(animation: WindowInsetsAnimationCompat, bounds: WindowInsetsAnimationCompat.BoundsCompat) =
                    insetForChild(bounds) { l, t, r, b -> bounds.inset(Insets.of(l, t, r, b)) }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    statusBarProtection?.updateInsets(insets)
                    return insetForChild(insets) { l, t, r, b -> insets.inset(l, t, r, b) }
                }
            })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val targetHeight = if (heightMode == MeasureSpec.AT_MOST &&
            dialogWindow.attributes.height == LayoutParams.WRAP_CONTENT) height + 1 else height
        val horizontalPadding = paddingLeft + paddingRight
        val paintSpaceLimit = if (heightMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else height
        topPaintSpace = (exteriorTopPaintSpace?.invoke() ?: 0).coerceIn(0, paintSpaceLimit)
        bottomPaintSpace = (exteriorBottomPaintSpace?.invoke() ?: 0).coerceIn(0, paintSpaceLimit - topPaintSpace)
        val verticalPadding = paddingTop + paddingBottom + topPaintSpace + bottomPaintSpace
        val hostWidth = host?.width ?: 0
        val hostHeight = host?.height ?: 0
        val childWidth = if (widthMode == MeasureSpec.UNSPECIFIED && hostWidth <= 0) widthMeasureSpec
            else MeasureSpec.makeMeasureSpec(max(0, nativeDialogBoundedExtent(
                if (widthMode == MeasureSpec.UNSPECIFIED) hostWidth else width, hostWidth) - horizontalPadding),
                if (entryEdge == null) MeasureSpec.AT_MOST else MeasureSpec.EXACTLY)
        val childHeight = if (heightMode == MeasureSpec.UNSPECIFIED && hostHeight <= 0) heightMeasureSpec
            else MeasureSpec.makeMeasureSpec(max(0, nativeDialogBoundedExtent(
                if (heightMode == MeasureSpec.UNSPECIFIED) hostHeight else targetHeight, hostHeight) - verticalPadding),
                MeasureSpec.AT_MOST)
        if (content.visibility == GONE) {
            setMeasuredDimension(if (widthMode == MeasureSpec.EXACTLY) width else horizontalPadding,
                if (heightMode == MeasureSpec.EXACTLY) height else verticalPadding)
            return
        }
        content.measure(childWidth, childHeight)
        setMeasuredDimension(
            when (widthMode) {
                MeasureSpec.EXACTLY -> width
                MeasureSpec.AT_MOST -> minOf(width, content.measuredWidth + horizontalPadding)
                else -> content.measuredWidth + horizontalPadding
            },
            when (heightMode) {
                MeasureSpec.EXACTLY -> height
                MeasureSpec.AT_MOST -> minOf(height, content.measuredHeight + verticalPadding)
                else -> content.measuredHeight + verticalPadding
            })
        if (content.measuredHeight + verticalPadding > height && dialogWindow.attributes.height == LayoutParams.WRAP_CONTENT) {
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            dialogWindow.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        onContentMeasured()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (content.visibility == GONE) return
        val l = paddingLeft + (width - content.measuredWidth - paddingLeft - paddingRight) / 2
        val t = when (entryEdge) {
            NativeDialogEdge.TOP -> paddingTop + topPaintSpace
            NativeDialogEdge.BOTTOM -> height - paddingBottom - bottomPaintSpace - content.measuredHeight
            null -> paddingTop + topPaintSpace +
                (height - content.measuredHeight - paddingTop - paddingBottom - topPaintSpace - bottomPaintSpace) / 2
        }
        content.layout(l, t, l + content.measuredWidth, t + content.measuredHeight)
    }

    private inline fun <T> insetForChild(unchanged: T, block: (Int, Int, Int, Int) -> T): T {
        val l = max(0, content.left); val t = max(0, content.top)
        val r = max(0, width - content.right); val b = max(0, height - content.bottom)
        return if (l == 0 && t == 0 && r == 0 && b == 0) unchanged else block(l, t, r, b)
    }

    fun isInsideContent(event: MotionEvent): Boolean {
        if (!event.x.isFinite() || !event.y.isFinite() || content.visibility == GONE) return false
        val l = left + content.left; val t = top + content.top
        val bottom = t + content.height
        val insideY = if (topPaintSpace > 0 || bottomPaintSpace > 0) event.y >= t && event.y < bottom
            else event.y.roundToInt() in t..bottom
        return event.x.roundToInt() in l..(l + content.width) && insideY
    }
}
