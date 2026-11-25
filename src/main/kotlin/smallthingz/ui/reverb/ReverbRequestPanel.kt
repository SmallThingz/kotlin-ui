package smallthingz.ui.reverb

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import smallthingz.ui.components.*
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

/** View-only request presentation. Its host owns overlay admission, expiry and authorization.
 * Unlike a dialog this can live in a NOT_FOCUSABLE window; attached/visible input is required,
 * but window focus is deliberately not required. No window is created by this component.
 */
class ReverbRequestPanel(
    private val context: Context,
    private val theme: ReverbTheme,
    appName: String,
    title: String,
    message: String,
    hint: String,
    dismissLabel: String,
    confirmLabel: String?,
    private val onDismiss: () -> Unit,
    private val onConfirm: () -> Unit,
) : AutoCloseable {
    private val recipe=ReverbDesign.appearance.modals.floating
    private var closed=false
    private var answered=false
    private var blockedGesture=true
    private fun dp(value:Float)=(value*theme.density.density).roundToInt()
    private fun text(value:String,role:NativeTypographyRole,muted:Boolean=false)=NativeText(
        context,theme.palette.pageControls,ReverbDesign.appearance.typography.nativeTextStyle(role),if(muted)NativeTextRole.MUTED else NativeTextRole.CONTENT).apply {text=value}
    private fun button(label:String,primary:Boolean,action:()->Unit)=NativeMaterialButtonView(
        context,(if(primary)ReverbDesign.appearance.buttons.filled else ReverbDesign.appearance.buttons.text).forMergedActions(),
        theme.density,theme.fontResolver,onClick=action).apply {
        bind(label,true,theme.palette.buttonPalette,onClick=action)
        filterTouchesWhenObscured=true
    }
    private val dismiss=button(dismissLabel,false) {respond(false)}
    private val confirm=confirmLabel?.let {button(it,true) {respond(true)}}
    private val body=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL
        setPaddingRelative(dp(recipe.horizontalPaddingDp),dp(recipe.topPaddingDp),
            dp(recipe.horizontalPaddingDp),if (confirm == null) dp(recipe.bottomPaddingDp) else 0)
        addView(text(appName,recipe.titleRole,true))
        addView(text(title,recipe.headlineRole).apply {isAccessibilityHeading=true},
            LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(recipe.headingGapDp)})
        addView(text(message,recipe.summaryRole),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(recipe.headingGapDp)})
        addView(text(hint,recipe.detailRole,true),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(recipe.summaryGapDp)})
        addView(NativeMergedActionLayout(context, listOfNotNull(dismiss, confirm), theme.palette).apply {
            if (confirm != null) asActionFooter(density = theme.density.density)
        },
            LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(recipe.headingGapDp)})
    }
    val view:View=object:NativeFloatingPanelSurface(context,theme.density.density,recipe,theme.palette) {
        init {setPanelViewport(ScrollView(context).apply {addView(body)})}
        override fun dispatchTouchEvent(event:MotionEvent):Boolean {
            if(closed||answered||!isAttachedToWindow||!isShown)return true
            if(event.actionMasked==MotionEvent.ACTION_DOWN)blockedGesture=false
            val obscured=event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED!=0 ||
                (Build.VERSION.SDK_INT>=29&&event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED!=0)
            if(obscured) {
                blockedGesture=true
                dismiss.cancelInput();confirm?.cancelInput()
                val cancel=MotionEvent.obtain(event).apply {action=MotionEvent.ACTION_CANCEL}
                try {super.dispatchTouchEvent(cancel)} finally {cancel.recycle()}
            }
            return if(blockedGesture)true else super.dispatchTouchEvent(event)
        }
        override fun onDetachedFromWindow() {
            blockedGesture=true
            dismiss.cancelInput();confirm?.cancelInput()
            super.onDetachedFromWindow()
        }
    }.apply {
        ViewCompat.setAccessibilityPaneTitle(this,appName)
        ViewCompat.setOnApplyWindowInsetsListener(this) {_,insets->
            val edges=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            setContentInsets(edges.left,edges.top,edges.right,edges.bottom)
            insets
        }
    }
    private fun respond(accept:Boolean) {
        if(closed||answered||!view.isAttachedToWindow||!view.isShown)return
        answered=true
        dismiss.cancelInput();confirm?.cancelInput()
        if(accept)onConfirm() else onDismiss()
    }
    override fun close() {
        if(closed)return
        closed=true
        var failure=closePreservingPrimaryFailure(null,dismiss::close)
        confirm?.let {failure=closePreservingPrimaryFailure(failure,it::close)}
        failure?.let {throw it}
    }
}
