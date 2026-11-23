package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import smallthingz.ui.components.NativeTopBarInsets

/** Localized label, action availability and behavior for a shared header button. */
data class ReverbHeaderAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true) {
    internal fun withIcon(icon: ReverbIcon) = ReverbTopBarActionContent(icon, label, onClick, enabled)
}

/** The visible copy can abbreviate the value. Accessibility gets the supplied full phrase. */
data class ReverbHeaderCount(val label: String, val compactValue: String, val spokenDescription: String)

/** Compact Browser / identity / Settings header with an optional separate count strip. */
class ReverbProductTopBar(context: Context, initialTheme: ReverbTheme, inputRoot: View? = null) :
    LinearLayout(context) {
    private val bar = ReverbTopBar(context, initialTheme, inputRoot)
    private val countsStrip = ReverbHeaderCountsView(context)
    private var identity: ReverbProductIdentity = ReverbProductIdentity.Reverb
    private var about: ReverbAboutDialog? = null
    private var ownerClose: (() -> Unit)? = null
    private var clearFocusReturn: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(countsStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        countsStrip.visibility = GONE
    }

    fun bind(
        theme: ReverbTheme,
        identity: ReverbProductIdentity = ReverbProductIdentity.Reverb,
        aboutLabel: String,
        onAbout: () -> Unit,
        browser: ReverbHeaderAction?,
        settings: ReverbHeaderAction,
        insets: NativeTopBarInsets = NativeTopBarInsets(0, 0, 0),
        counts: List<ReverbHeaderCount> = emptyList(),
    ) {
        this.identity = identity
        bar.bind(theme, browser?.withIcon(ReverbIcon.Browser),
            ReverbTopBarCenterContent.Product(identity, aboutLabel, onAbout),
            settings.withIcon(ReverbIcon.Settings), insets)
        countsStrip.bind(theme, insets, counts)
    }

    /**
     * Show About from the retained identity action. A completed user dismissal closes the panel
     * and returns focus. Window teardown retires it independently. Owner close is silent.
     */
    fun openAbout(
        theme: ReverbTheme,
        details: ReverbAboutDetails,
        paneLabel: String,
        openRepository: () -> Boolean,
        onUserDismiss: () -> Unit = {},
        onWindowDismissed: () -> Unit = {},
    ): Boolean {
        val anchor = bar.centerAnchor ?: return false
        if (about != null || !anchor.isAttachedToWindow || !anchor.isShown || !anchor.hasWindowFocus()) return false
        clearFocusReturn?.invoke()
        val accessibilityFocus = anchor.createAccessibilityNodeInfo()?.isAccessibilityFocused == true
        lateinit var opened: ReverbAboutDialog
        lateinit var detach: OnAttachStateChangeListener
        fun retire(callback: () -> Unit, restoreFocus: Boolean = false) {
            if (about !== opened) return
            about = null
            ownerClose = null
            anchor.removeOnAttachStateChangeListener(detach)
            try {
                opened.dismiss()
                if (restoreFocus) returnFocus(anchor, accessibilityFocus)
            } finally {
                callback()
            }
        }
        opened = ReverbAboutDialog(anchor, theme, details.copy(identity = identity, paneLabel = paneLabel),
            openRepository = openRepository, onDismiss = { retire(onUserDismiss, restoreFocus = true) })
        detach = object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = retire(onWindowDismissed)
        }
        anchor.addOnAttachStateChangeListener(detach)
        opened.window?.decorView?.filterTouchesWhenObscured = true
        opened.onWindowDismissed { retire(onWindowDismissed) }
        about = opened
        ownerClose = { retire({}) }
        return try {
            opened.show()
            true
        } catch (_: WindowManager.BadTokenException) {
            closeAbout()
            false
        }
    }

    fun closeAbout() {
        clearFocusReturn?.invoke()
        ownerClose?.invoke()
    }

    private fun returnFocus(anchor: View, accessibilityFocus: Boolean) {
        if (!anchor.isAttachedToWindow || !anchor.isShown) return
        fun focus() {
            if (!anchor.isShown || !anchor.isEnabled || about != null) return
            if (accessibilityFocus) {
                anchor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            } else anchor.requestFocus()
        }
        if (anchor.hasWindowFocus()) {
            focus()
            return
        }
        val observer = anchor.viewTreeObserver
        val windowFocus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) {
                clearFocusReturn?.invoke()
                focus()
            }
        }
        val attach = object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { clearFocusReturn?.invoke() }
        }
        clearFocusReturn = {
            clearFocusReturn = null
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(windowFocus)
            anchor.removeOnAttachStateChangeListener(attach)
        }
        observer.addOnWindowFocusChangeListener(windowFocus)
        anchor.addOnAttachStateChangeListener(attach)
    }
}
