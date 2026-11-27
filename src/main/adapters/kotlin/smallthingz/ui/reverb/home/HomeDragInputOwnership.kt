package smallthingz.ui.reverb.home

import smallthingz.ui.common.NativeHorizontalDrag

/** Retire both halves of Home's input lifetime; detector restart alone has no cancel callback. */
fun <S : Any> restartHomeDragInput(
    motion: ReverbHomeBufferMotion<S>,
    detector: NativeHorizontalDrag,
    durationScale: Float,
) {
    // This only returns an active drag. Already accepted navigation/settling keeps its owner.
    motion.dragCancelled(durationScale)
    detector.restart()
}
