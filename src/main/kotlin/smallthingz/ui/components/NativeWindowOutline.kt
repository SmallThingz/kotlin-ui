// SPDX-License-Identifier: Apache-2.0
// Copyright 2020-2023 The Android Open Source Project
package smallthingz.ui.components

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/** Reserve the window root's shadow insets without painting a native root shadow. */
internal object NativeWindowShadowOutline : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRect(0, 0, view.width, view.height)
        outline.alpha = 0f
    }
}
