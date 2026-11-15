/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package smallthingz.ui.animation.core

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

// Keep throws out of inline call sites; argument preconditions share ui.util.
internal fun throwIllegalStateException(message: String) {
    throw IllegalStateException(message)
}

internal fun throwIllegalStateExceptionForNullCheck(message: String): Nothing {
    throw IllegalStateException(message)
}

// Like Kotlin's check() but without the .toString() call
@Suppress("BanInlineOptIn") // same opt-in as using Kotlin's check()
@OptIn(ExperimentalContracts::class)
internal inline fun checkPrecondition(value: Boolean, lazyMessage: () -> String) {
    contract { returns() implies value }
    if (!value) {
        throwIllegalStateException(lazyMessage())
    }
}

// Like Kotlin's checkNotNull() but without the .toString() call
@Suppress("BanInlineOptIn") // same opt-in as using Kotlin's check()
@OptIn(ExperimentalContracts::class)
internal inline fun <T : Any> checkPreconditionNotNull(value: T?, lazyMessage: () -> String): T {
    contract { returns() implies (value != null) }

    if (value == null) {
        throwIllegalStateExceptionForNullCheck(lazyMessage())
    }
    return value
}
