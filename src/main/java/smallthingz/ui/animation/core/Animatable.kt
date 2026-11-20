/*
 * Copyright 2019 The Android Open Source Project
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

import smallthingz.ui.runtime.State
import smallthingz.ui.foundation.MutatorMutex

@Suppress("NotCloseable")
public class Animatable<T, V : AnimationVector>
constructor(
    initialValue: T,
    public val typeConverter: TwoWayConverter<T, V>,
    private val visibilityThreshold: T? = null,
    public val label: String = "Animatable",
) {

    @Deprecated(
        "Maintained for binary compatibility",
        replaceWith =
            ReplaceWith(
                "Animatable(initialValue, typeConverter, visibilityThreshold, \"Animatable\")"
            ),
        DeprecationLevel.HIDDEN,
    )
    public constructor(
        initialValue: T,
        typeConverter: TwoWayConverter<T, V>,
        visibilityThreshold: T? = null,
    ) : this(initialValue, typeConverter, visibilityThreshold, "Animatable")

    internal val internalState =
        AnimationState(typeConverter = typeConverter, initialValue = initialValue)

    
    public val value: T
        get() = internalState.value

    
    public val velocityVector: V
        get() = internalState.velocityVector

    
    public val velocity: T
        get() = typeConverter.convertFromVector(velocityVector)

    
    public var isRunning: Boolean = false
        private set

    
    public var targetValue: T = initialValue
        private set(next) {
            if (field != next) field = next
        }

    
    private val mutatorMutex = MutatorMutex()
    internal val defaultSpringSpec: SpringSpec<T> =
        SpringSpec(visibilityThreshold = visibilityThreshold)

    public suspend fun animateTo(
        targetValue: T,
        animationSpec: AnimationSpec<T> = defaultSpringSpec,
        initialVelocity: T = velocity,
        block: (Animatable<T, V>.() -> Unit)? = null,
    ) {
        val anim =
            TargetBasedAnimation(
                animationSpec = animationSpec,
                initialValue = value,
                targetValue = targetValue,
                typeConverter = typeConverter,
                initialVelocity = initialVelocity,
            )
        runAnimation(anim, initialVelocity, block)
    }

    private suspend fun runAnimation(
        animation: Animation<T, V>,
        initialVelocity: T,
        block: (Animatable<T, V>.() -> Unit)?,
    ) {

        // Store the start time before it's reset during job cancellation.
        val startTime = internalState.lastFrameTimeNanos
        mutatorMutex.mutate {
            try {
                internalState.velocityVector = typeConverter.convertToVector(initialVelocity)
                targetValue = animation.targetValue
                isRunning = true

                internalState.animate(animation, startTime) {
                    block?.invoke(this@Animatable)
                }
            } finally {
                endAnimation()
            }
        }
    }

    private fun endAnimation() {
        // Reset velocity
        internalState.apply {
            velocityVector.reset()
            lastFrameTimeNanos = AnimationConstants.UnspecifiedTime
            isRunning = false
            activeScope = null
        }
        isRunning = false
    }

    
    public suspend fun snapTo(targetValue: T) {
        mutatorMutex.mutate {
            endAnimation()
            internalState.value = targetValue
            this.targetValue = targetValue
        }
    }

    
    public suspend fun stop() {
        mutatorMutex.mutate { endAnimation() }
    }

    
    public fun asState(): State<T> = internalState
}

public fun Animatable(
    initialValue: Float,
    visibilityThreshold: Float = Spring.DefaultDisplacementThreshold,
): Animatable<Float, AnimationVector1D> =
    Animatable(initialValue, Float.VectorConverter, visibilityThreshold)

// TODO: Consider some version of @Composable fun<T, V: AnimationVector> Animatable<T, V>.animateTo
