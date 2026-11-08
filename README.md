# smallthingz.ui

Reusable Kotlin primitives and an inline construction DSL for ordinary Android
Views. The library supplies geometry, text/font layout, animation math and
coordination, and native View construction helpers. It has no Reverb recorder or
storage dependency and no Compose or Material3 UI runtime.

The DSL constructs actual Views directly. It does not retain a second UI tree or
run a reconciler. This is an implementation boundary, not a measured zero-cost
claim: text layout, animation, allocation and View operations still have costs.

## Removed dialog API

`NativeDialog`, `NativeDialogStyle`, `NativeDialogPlacement`, `NativeDialogCorners`,
`NativeDialogWidthPolicy`, `NativeDialogDimPolicy` and `NativeDialogSoftInputAdjust`
have been removed. This is a source and binary breaking change; no compatibility
facade is provided. External users must retain their own Android dialog implementation
or choose a component meeting their layout, input, accessibility and lifecycle needs.
Reverb's app-internal action sheet is not a public replacement. No current Reverb caller
uses the removed API; this removal does not establish a runtime speed improvement.

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

## Localized theme selector

`smallthingz.ui.components.NativeThemeSelector` owns its Theme/Auto/Light/Dark
copy, translations, icons and radio-row geometry. Supply a `ControlPalette`, a
`NativeThemeMode`, and an `onSelectionRequest` callback; the owner binds the
authoritative selection through `bind`. Persisted theme codes remain the
application's responsibility. Call `close` when the logical owner retires.
The optional `NativeThemePresentation` adapts retained title/surface rendering
without supplying or replacing intrinsic copy; the default uses `NativeText`
and `NativeSurface`. `inputAllowed` adds the host's admission policy to the
component's attachment, visibility, focus and enabled checks.

Both Theme and app-defined options use `NativeSegmentedControl`: bind retained
`NativeChoice` values and the authoritative selected ID, and provide a Boolean
selection callback. `NativeSegmentedPresentation` optionally adapts the capsule
surface; the default uses `NativeSurface`. Theme adds only localized options,
icons and title layout to this same capsule.

The segmented row's immediate parent implements
`smallthingz.ui.input.NativeAndroidInteropHost`, preserving one owner-scoped
interception-request route for hosts with custom pointer dispatch.
`NativeThemeIconDrawable` and `CachedPathIconDrawable` share a software ALPHA_8
mask renderer with exact Float fitting, per-drawable cache ownership and SrcIn
tint. Each host owns its drawable; do not fit its bounds a second time.
`SOURCE-theme.json` records the unchanged glyph provenance and extraction.

## Phase input for concrete Views

`smallthingz.ui.input.phase.PhasePointerRoot` is a concrete `FrameLayout` for
window owners with source-ordered pointer dispatch. Supply one retained
`NativeViewPointerHitLayout`, authoritative `NativePointerCoordinates` for each
participating View, and a per-event coordinate preparation callback.
`NativeWindowPointerCoordinates` supplies the shared transform snapshot.
Each new pointer is hit-tested once; movement and release retain that node path.
Initial/Main/Final passes share the same consumption and dispatch-state objects.
A handled Android event does not by itself consume movement.

Use `NativeClickPhaseNode` with `ViewClickPhaseEndpoint` to bind an existing
`ViewClickController`, or `NativeAndroidInteropNode` for a real Android child.
The child parent forwards interception requests through the existing
`NativeAndroidInteropHost`; close the interop node when its logical owner retires.
The root cancels retained paths on detach; window owners may explicitly call
`cancelPointerInput`. Callers own shapes, minimum targets, sibling hit order,
clipping, nested scroll policy, frame clocks, accessibility and window lifetime.
Do not also route the same stream through platform `ScrollView` interception.
There is no intrinsic visible text in this input layer.

## Vertical viewport and window scroll events

`smallthingz.ui.scroll.NativeVerticalViewport` is one concrete single-child
viewport. Supply retained `NativeVerticalScrollState`, immutable `Density`, the
window frame clock/scope, and a `NativeVerticalNestedHost`; call `setContent` with
the actual View. It measures natural content height within a finite viewport and
owns focus/rectangle reveal, scroll mutation, fling and overscroll lifetimes.
It does not intercept touch or install a second detector: insert
`NativeVerticalPhaseBinding.nodes` into the existing phase hit layout, with the
same viewport clipping and child order. Close that binding when the node retires.
Mutate phase-tree membership between dispatches; during callbacks only the
implemented deferred cancellation path is supported, not arbitrary tree mutation
or nested dispatch.

Create one `NativeWindowScrollAccessibilityEvents` per window; use
`NativeVerticalScrollAccessibilityBinding` for each attached viewport. Provide
actual source-presence and semantic geometry, and close the window queue on
window disposal. All registered horizontal/vertical sources share its 100 ms
batch boundary; geometry and range are sampled at delivery. App navigation,
refresh, edge dismissal and visibility policy remain caller-owned. The viewport
has no intrinsic visible text. Its density/configuration owner recreates it when
those immutable inputs change; palette updates alone do not require recreation.

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
reuses one 64 KiB pixel array across 64 strips, excluding allocator/object overhead.
These payload sizes do not measure total process memory or generation time.
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

## Source-derived anchored menu (API replacement)

`NativePopupMenu<I>` is a concrete focusable sub-panel using the shared phase
pointer root, vertical viewport, paragraph renderer and retained spring transition.
Pass an attached anchor, density, fully resolved inherited text style/font resolver,
`NativeMenuColors`, retained `NativeVerticalScrollState`, window frame clock/scope,
and one `NativeMenuIcons<I>` rendering adapter. The adapter creates each retained
icon View once and binds any icon token of I to that same View; keep it nonfocusable
and excluded from accessibility. An app can use its existing vector/icon renderer.

Bind `NativeMenuChoices<T>` with typed labels/selection or `NativeMenuAction<I>`
with domain callbacks, then call `show()`. Row identity is positional. Call
`expanded(false)` to animate dismissal; the terminal callback must retire/close
that same popup identity. `close()` is immediate and idempotent. Owners retain
scroll state and admit callback identity/reentrancy; the component does not route
commands or automatically choose callback-before-dismiss versus dismiss-before-
callback. The scope/frame clock and density/configuration lifetime are caller-owned.

This intentionally replaces the former `NativePopupMenu`, `NativeMenuItem` and
`NativePopupMenuStyle` API and removes the unused platform PopupWindow engine.
It is a source/binary break for external consumers: replace item IDs/style bags
with typed row data, explicit environment/renderer inputs and owned callbacks.
There is no NativeButton itemView or compatibility facade. Choice selection is exposed through standard selected accessibility semantics
and the same visual trailing check; action-row reuse clears selection. Window
flags/secure inheritance, candidate placement and transition behavior follow the
source-derived component. The intrinsic popup title stays in library translations;
domain row labels stay with their owner. Unknown external consumers must migrate
when adopting this commit rather than assuming the old constructor/defaults persist.
