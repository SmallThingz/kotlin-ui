/*
 * Source-derived from AndroidX UI 1.11.2 AndroidPopup.android.kt and Material3 1.4.0
 * AndroidMenu.android.kt. Copyright 2020-2023 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package smallthingz.ui.components

import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.unit.Density
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import smallthingz.ui.input.phase.PhasePointerRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Actual focusable sub-panel with the released Android Popup flags and geometry. */
class NativePopupMenu<I> private constructor(
    private val anchor: View,
    private val menu: NativeMenuContent<I>,
    private val density: Density,
    private val recipe: NativeAnchoredMenuRecipe,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val input: NativeMenuPointerBinding<I>,
    private val onDismissRequest: () -> Unit,
    private val onTerminal: () -> Unit,
) : PhasePointerRoot(
    anchor.context,
    rootToScreen = input.geometry::rootToScreen,
    coordinates = input.geometry::coordinates,
    hitLayout = input.layout,
    prepareCoordinates = input.geometry::begin,
), AutoCloseable {
    private constructor(anchor: View, density: Density, frames: MonotonicFrameClock,
                        scope: CoroutineScope, parts: Parts<I>, onDismissRequest: () -> Unit,
                        onTerminal: () -> Unit, recipe: NativeAnchoredMenuRecipe) :
        this(anchor, parts.content, density, recipe, frames, scope, parts.input, onDismissRequest, onTerminal)

    constructor(anchor: View, density: Density, textStyle: TextStyle, resolver: FontFamily.Resolver,
                colors: NativeMenuColors, state: NativeVerticalScrollState,
                frames: MonotonicFrameClock, scope: CoroutineScope, icons: NativeMenuIcons<I>,
                onDismissRequest: () -> Unit, onTerminal: () -> Unit) :
        this(anchor, density, textStyle, resolver, colors, state, frames, scope, icons,
            NativeAnchoredMenuRecipe.Default, onDismissRequest, onTerminal)

    constructor(anchor: View, density: Density, textStyle: TextStyle, resolver: FontFamily.Resolver,
                colors: NativeMenuColors, state: NativeVerticalScrollState,
                frames: MonotonicFrameClock, scope: CoroutineScope, icons: NativeMenuIcons<I>,
                recipe: NativeAnchoredMenuRecipe, onDismissRequest: () -> Unit, onTerminal: () -> Unit) :
        this(anchor, density, frames, scope,
            Parts(anchor, density, textStyle, resolver, colors, state, frames, scope, icons, recipe),
            onDismissRequest, onTerminal, recipe)

    fun <T> bindChoices(choices: NativeMenuChoices<T>, colors: NativeMenuColors, select: (T) -> Unit) {
        menu.bind(choices, colors, select)
        input.refreshRows()
    }

    fun bindActions(actions: List<NativeMenuAction<I>>, colors: NativeMenuColors,
                    background: Int = colors.surfaceContainerHigh) {
        menu.bindActions(actions, colors, background)
        input.refreshRows()
    }

    private class Parts<I>(anchor: View, density: Density, style: TextStyle, resolver: FontFamily.Resolver,
                           colors: NativeMenuColors, state: NativeVerticalScrollState,
                           frames: MonotonicFrameClock, scope: CoroutineScope, icons: NativeMenuIcons<I>,
                           recipe: NativeAnchoredMenuRecipe) {
        val content: NativeMenuContent<I>
        val input: NativeMenuPointerBinding<I>
        init {
            lateinit var binding: NativeMenuPointerBinding<I>
            content = NativeMenuContent(anchor.context, density, style, resolver, colors, recipe, icons,
                state, frames, scope) { event -> binding.interestedParent(event) }
            binding = NativeMenuPointerBinding(content, density)
            input = binding
        }
    }

    private val manager = context.getSystemService(WindowManager::class.java)
    private val params = WindowManager.LayoutParams().apply {
        gravity = Gravity.START or Gravity.TOP
        flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            (((anchor.rootView.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0) and
                WindowManager.LayoutParams.FLAG_SECURE)
        type = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
        token = anchor.applicationWindowToken
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        format = PixelFormat.TRANSLUCENT
        title = context.getString(smallthingz.ui.R.string.default_popup_window_title)
    }
    private val visibleFrame = Rect()
    private val location = IntArray(2)
    private var origin: NativeMenuOrigin? = null
    private var originMapper: NativeMenuOriginMapper? = null

    /** Null restores the ordinary full-anchor placement used by keyboard/accessibility menus. */
    fun setOrigin(value: NativeMenuOrigin?) {
        val next = value?.takeIf { it.x.isFinite() && it.y.isFinite() }
        if (origin == next) return
        origin = next
        if (next != null && originMapper == null) originMapper = NativeMenuOriginMapper()
        updatePosition(force = true)
    }
    private val animationLayer = NativeAnchoredMenuPanel(menu, density, recipe, drivesMotion = false)
    private var frameJob: Job? = null
    private var back: Any? = null
    private var added = false
    private var closed = false
    private var actionInputRetired = false
    private var inputGeneration = 0L
    private var admittedFlags = 0
    private var admittedAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var positioned = false
    private var menuMeasured = false
    private var previousAnchor: NativeMenuBounds? = null
    private var previousMenuWidth = -1
    private var previousMenuHeight = -1
    private var previousWindowWidth = -1
    private var previousWindowHeight = -1
    private var anchorObserver: ViewTreeObserver? = null
    private val anchorLayout = ViewTreeObserver.OnGlobalLayoutListener {
        // Insets/window changes can change the usable frame without moving this anchor. This
        // callback is layout-driven; the steady frame loop below only polls cheap coordinates.
        updatePosition(force = true)
    }

    init {
        input.bindRoot(this)
        id = android.R.id.content
        setViewTreeLifecycleOwner(anchor.findViewTreeLifecycleOwner())
        setViewTreeViewModelStoreOwner(anchor.findViewTreeViewModelStoreOwner())
        setViewTreeSavedStateRegistryOwner(anchor.findViewTreeSavedStateRegistryOwner())
        isSaveEnabled = false // The screen owner saves each retained ScrollState exactly once.
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        // Compose enters an eligible menu item in keyboard mode; touch mode keeps root focus.
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        super.setLayoutDirection(anchor.layoutDirection)
        elevation = recipe.windowShadowInsetDp * density.density
        outlineProvider = NativeWindowShadowOutline
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(animationLayer)
    }

    fun show() {
        check(!added && !closed)
        animationLayer.retargetMotion(true)
        // Mark before addView, because its synchronous attach callback starts the frame owner.
        added = true
        try { manager.addView(this,params) }
        catch (failure: Throwable) {
            added = isAttachedToWindow || parent != null
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun expanded(expanded: Boolean) {
        if (closed) return
        if (expanded && actionInputRetired) {
            // Explicit readmission is a new input lifetime, even while the old exit is painted.
            inputGeneration++
            actionInputRetired = false
            params.flags = admittedFlags
            importantForAccessibility = admittedAccessibility
            val generation = inputGeneration
            if (added) manager.updateViewLayout(this, params)
            if (closed || inputGeneration != generation) return
            if (isAttachedToWindow) registerBack()
        }
        animationLayer.retargetMotion(expanded)
        // An interrupted entry retains this same window until the exit settles. Explicit
        // owner teardown still uses close() and removes the window immediately.
        if (!animationLayer.motionShouldExist) onTerminal()
    }

    /**
     * Accept once, requesting window focus release before the caller runs its action. Android
     * delivers the focus callback asynchronously; callers still observe actual focus readiness.
     * The exit keeps only
     * paint; rows remain alive until dispatch unwinds. True survives a synchronous terminal close.
     * No work is deferred until after the caller, which may close or explicitly reopen this menu.
     */
    fun acceptAction(): Boolean {
        if (closed || actionInputRetired) return false
        actionInputRetired = true
        val generation = ++inputGeneration
        admittedFlags = params.flags
        admittedAccessibility = importantForAccessibility
        // This tracking is separate from row.input; a stale BACK/ESC UP must not dismiss a
        // same-window lifetime explicitly reopened by the accepted action.
        keyDispatcherState?.reset(this)
        fun stillRetiring() = !closed && actionInputRetired && inputGeneration == generation
        try {
            cancelPointerInput()
            // Key/accessibility activation need not have a retained pointer path to cancel.
            for (row in menu.column.rows) if (stillRetiring()) row.input.cancel()
            if (stillRetiring()) {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                if (Build.VERSION.SDK_INT >= 33) MenuBackApi33.unregister(this, back)
                back = null
                params.flags = admittedFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                if (added) manager.updateViewLayout(this, params)
            }
            if (stillRetiring()) expanded(false)
        } catch (failure: Throwable) {
            // Do not leave an accepted window owning focus after a failed retirement. A new
            // lifetime admitted by a reentrant callback belongs to its new owner instead.
            if (stillRetiring()) {
                try { onTerminal() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                if (stillRetiring()) {
                    try { close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                }
            }
            throw failure
        }
        return true
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT < 33 || closed || actionInputRetired || back != null) return
        val generation = inputGeneration
        back = MenuBackApi33.register(this) {
            if (!closed && !actionInputRetired && inputGeneration == generation) onDismissRequest()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anchorObserver = anchor.viewTreeObserver.also { it.addOnGlobalLayoutListener(anchorLayout) }
        registerBack()
        frameJob = scope.launch {
            while (isActive && !closed) {
                frames.withFrameNanos { time ->
                    if (!closed) {
                        updatePosition()
                        animationLayer.advanceMotion(time, visible = positioned && anchor.isAttachedToWindow)
                        if (!animationLayer.motionShouldExist) onTerminal()
                    }
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        frameJob?.cancel()
        frameJob = null
        anchorObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(anchorLayout)
        anchorObserver = null
        if (Build.VERSION.SDK_INT >= 33) MenuBackApi33.unregister(this,back)
        back = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        animationLayer.measure(atMost(visibleFrame.width()),atMost(visibleFrame.height()))
        setMeasuredDimension(animationLayer.measuredWidth,animationLayer.measuredHeight)
        menuMeasured = true
        // Compose's onSizeChanged publishes placement during measure, before ViewRoot's initial
        // relayout and renderer light-center setup. Waiting for onLayout leaves the first setup
        // at the default window origin even though the popup is drawn at its final placement.
        updatePosition()
    }

    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        animationLayer.layout(0,0,animationLayer.measuredWidth,animationLayer.measuredHeight)
        params.width = menu.measuredWidth
        params.height = menu.measuredHeight
        if (added && !closed) {
            updatePosition(force=true)
        }
        invalidateOutline()
    }

    private fun updatePosition(force: Boolean = false) {
        if (!added || closed || !anchor.isAttachedToWindow || !menuMeasured) return
        val menuWidth = menu.measuredWidth
        val menuHeight = menu.measuredHeight
        val pointOrigin = origin
        if (pointOrigin == null) anchor.getLocationInWindow(location)
        else {
            val mapper = checkNotNull(originMapper)
            mapper.map(anchor, pointOrigin)
            location[0] = mapper.x
            location[1] = mapper.y
        }
        val anchorWidth = if (pointOrigin == null) anchor.width else 0
        val anchorHeight = if (pointOrigin == null) anchor.height else 0
        val previous = previousAnchor
        val sameAnchor = previous != null && previous.left == location[0] && previous.top == location[1] &&
            previous.width == anchorWidth && previous.height == anchorHeight
        if (!force && sameAnchor && menuWidth == previousMenuWidth && menuHeight == previousMenuHeight) return
        val bounds = if (sameAnchor) requireNotNull(previous) else
            NativeMenuBounds(location[0],location[1],location[0]+anchorWidth,location[1]+anchorHeight)
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        val windowWidth = visibleFrame.width()
        val windowHeight = visibleFrame.height()
        if (!force && bounds == previousAnchor && menuWidth == previousMenuWidth &&
            menuHeight == previousMenuHeight && windowWidth == previousWindowWidth &&
            windowHeight == previousWindowHeight) return
        if (windowWidth != previousWindowWidth || windowHeight != previousWindowHeight) requestLayout()
        previousAnchor = bounds
        previousMenuWidth = menuWidth
        previousMenuHeight = menuHeight
        previousWindowWidth = windowWidth
        previousWindowHeight = windowHeight
        val placement = nativeMenuPosition(bounds,windowWidth,windowHeight,menuWidth,menuHeight,
            anchor.layoutDirection == LAYOUT_DIRECTION_RTL,(48f*density.density).roundToInt())
        params.x = placement.x
        params.y = placement.y
        animationLayer.pivotX = placement.pivotX * menuWidth
        animationLayer.pivotY = placement.pivotY * menuHeight
        if (Build.VERSION.SDK_INT >= 29)
            systemGestureExclusionRects = listOf(Rect(0,0,windowWidth,windowHeight))
        manager.updateViewLayout(this,params)
        positioned = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (closed || actionInputRetired) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            val state = keyDispatcherState ?: return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                state.startTracking(event,this)
                return true
            } else if (event.action == KeyEvent.ACTION_UP && state.isTracking(event) && !event.isCanceled) {
                onDismissRequest()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (closed || actionInputRetired) return true
        if ((event.action == MotionEvent.ACTION_DOWN &&
                (event.x < 0 || event.x >= width || event.y < 0 || event.y >= height)) ||
            event.action == MotionEvent.ACTION_OUTSIDE) {
            onDismissRequest()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (closed || actionInputRetired) true else super.dispatchGenericMotionEvent(event)

    // Source Popup ignores ViewRootImpl's locale override and retains its parent's direction.
    override fun setLayoutDirection(layoutDirection: Int) = Unit

    override fun close() {
        if (closed) return
        closed = true
        frameJob?.cancel()
        frameJob = null
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try { block() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        cleanup { input.close() }
        setViewTreeLifecycleOwner(null)
        if (added) {
            added = false
            cleanup { manager.removeViewImmediate(this) }
        }
        cleanup { menu.close() }
        failure?.let { throw it }
    }
}

@RequiresApi(33)
private object MenuBackApi33 {
    fun register(view: View,dismiss: () -> Unit): Any {
        val callback = OnBackInvokedCallback { dismiss() }
        view.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,callback,
        )
        return callback
    }
    fun unregister(view: View,callback: Any?) {
        if (callback is OnBackInvokedCallback)
            view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
    }
}
