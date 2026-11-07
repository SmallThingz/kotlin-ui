/*
 * Adapted from AndroidX UI 1.11.2 PointerInteropFilter/AndroidViewHolder.android.kt.
 * Copyright 2020-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package smallthingz.ui.input

/** One retained parent forwards a native child's interception request into its pointer owner. */
class NativeAndroidInteropRequests {
    private var registration: Registration? = null
    private inner class Registration(val onRequest: (Boolean) -> Unit) : AutoCloseable {
        override fun close() { if (registration === this) registration = null }
    }
    fun request(disallow: Boolean) { registration?.onRequest?.invoke(disallow) }
    fun bind(onRequest: (Boolean) -> Unit): AutoCloseable =
        Registration(onRequest).also { registration = it }
}

interface NativeAndroidInteropHost {
    val nativeInteropRequests: NativeAndroidInteropRequests
}
