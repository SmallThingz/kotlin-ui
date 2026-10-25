# smallthingz.ui

Reusable Kotlin primitives and an inline construction DSL for ordinary Android
Views. The library supplies geometry, text/font layout, animation math and
coordination, and native View construction helpers. It has no Reverb recorder or
storage dependency and no Compose or Material3 UI runtime.

The DSL constructs actual Views directly. It does not retain a second UI tree or
run a reconciler. This is an implementation boundary, not a measured zero-cost
claim: text layout, animation, allocation and View operations still have costs.

## Build

Use JDK 21 and an Android SDK with platform 37 installed. Set `ANDROID_HOME` or an
untracked `local.properties` containing `sdk.dir`. Java and Kotlin target JVM 17;
the minimum Android API is 28.

```sh
./gradlew assembleRelease testDebugUnitTest
```

The wrapper pins Gradle 9.7.1; the standalone build pins Android Gradle Plugin
9.4.0 and Kotlin 2.3.0. Reverb includes this checkout as `:native-ui` and shares
its matching toolchain classloader. The Android namespace and Kotlin package root
are `smallthingz.ui` in either build. The standalone project is `kotlin-ui`, with
Gradle group `smallthingz`.
Consumers import the direct View DSL from `smallthingz.ui.dsl`.

## Animation owners

`smallthingz.ui.animation` supplies `SpringValue`, `AnimatedValue`,
`TransitionValue`, and `UpdatableScrollAnimation`. The first two preserve typed
velocity on interruption, including Color and IntSize conversion; transition
channels retain raw vector momentum. Callers supply their own tween durations,
visibility policies, and final paint/property updates.

The main-thread owners in `smallthingz.ui.animation.view` connect those samplers
to frames. `ViewFrameLoop` uses an attached View and the caller's window clock;
`AttachedFrameLoop` has explicit attach/detach/close boundaries on that clock.
Both inherit its pause and duration-scale behavior. `ViewSpringDriver` is the
optional direct Choreographer driver; close it when its logical owner retires.
Use one driver per animation and serialize sampler updates on its owner.
`SOURCE-view-animation.json` records the extracted sources and lifecycle fixes.

Sampler and clock-owner tests run with the unit suite. The separate
`scripts/test_view_animation_host.py` runner accepts compiler/test classpaths and
uses `src/testViewHost` to check View owner cancellation against a narrow host
fixture. This verifies production control flow, not Android rendering or timing.

## Procedural background grain

```kotlin
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture

NoiseTexture.prepare() // Optional asynchronous warmup; construction also starts it.
view.background = NoiseDrawable(
    backgroundColor = surfaceColor, // ARGB Int; null draws grain alone.
    seed = NoiseDrawable.SEED_BACKGROUND, // SEED_TOP_BAR uses the other standard phase.
)
```

Grain uses a fixed deterministic algorithm and a 1024 × 1024 repeating tile,
generated once per process. Its ARGB bitmap has a 4 MiB pixel payload; generation
temporarily uses another 4 MiB pixel array, excluding allocator/object overhead.
There is no bundled raster or disk cache. One background worker generates the tile;
drawables initially show their supplied fill and invalidate when generation finishes.
Only the two standard phase shaders are cached. Ordinary drawing reuses each
drawable's paints and shader; optional alpha/filter composition still has a layer cost.
Callers using `NoiseTexture.shader()` must not mutate the shared standard-phase
shader matrices. Disable platform force-dark on the owning View when exact supplied
colors must be preserved.

## Integration and provenance

This is an independent Git repository, extracted with its module-only history
from Reverb. `ORIGIN.json` records the exact source snapshot and split commit.
Reverb pins a Git submodule commit and supplies a compact offline Git bundle plus
bootstrap script; an offline checkout does not require network access.

`SOURCE-foundation.json`, `SOURCE-motion.json`, `SOURCE-motion.md`,
`SOURCE-text.json`, and `src/main/resource-provenance/` retain the original
upstream attribution and historical qualification records. Their `native-ui/`
target paths are relative to the original Reverb checkout. The package migration
from `app.smallthingz.nativeui` to `smallthingz.ui` also moved the corresponding
source directories from `app/smallthingz/nativeui/` to `smallthingz/ui/`; the
historical manifests retain their original paths and hashes. Old temporary source
paths are historical provenance, not current build inputs. Historical validation
statements are not a claim that a new checkout has passed current tests.

First-party code is licensed under Apache-2.0; see `LICENSE.txt`.
Upstream-derived files retain their existing Apache-2.0 notices and provenance.
Dependencies remain AndroidX annotation
and emoji2 internally, with coroutines exposed in the existing public API.
