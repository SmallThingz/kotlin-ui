# smallthingz.ui

Reusable Kotlin primitives and an inline construction DSL for ordinary Android
Views. The library supplies geometry, text/font layout, animation math and
coordination, and native View construction helpers. It has no Reverb recorder or
storage dependency and no Compose or Material3 UI runtime.

The DSL constructs actual Views directly. It does not retain a second UI tree or
run a reconciler. This is an implementation boundary, not a measured zero-cost
claim: text layout, animation, allocation and View operations still have costs.

## Getting started

Add `implementation(project(":native-ui"))` when the library is included as an
Android module. See [Build](#build) for the standalone library build.

Use `NativeMaterialButtonView` for a labeled action,
`NativeStandardIconButtonView` for a circular glyph action, and `NativeToggleRow`
for a checked setting with a title and optional description. Use
`NativeSegmentedControl` for a small set of peer options and `NativeDropdown` for
a typed choice that opens a list. `NativeGroupLayout` gives related rows one
surface, an outer border, and optional dividers.

```kotlin
import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

fun actions(
    context: Context,
    darkTheme: Boolean,
    saveLabel: String,
    resetLabel: String,
    onSave: () -> Unit,
    onReset: () -> Unit,
): Triple<NativeGroupLayout, NativeMaterialButtonView, NativeMaterialButtonView> {
    val density = Density(context)
    val resolver = createFontFamilyResolver(context)
    val appearance = NativeUiAppearance.Default
    val colors = appearance.resolveColorScheme(context, darkTheme)
    val save = NativeMaterialButtonView(context, appearance.buttons.filled, density, resolver, onClick = onSave)
    val reset = NativeMaterialButtonView(context, appearance.buttons.neutralText, density, resolver, onClick = onReset)
    save.bind(saveLabel, true, colors.buttonPalette)
    reset.bind(resetLabel, true, colors.buttonPalette)

    val group = NativeGroupLayout(context)
    group.setPaletteImmediately(colors.field, colors.border)
    group.addRow(save, NativeGroupLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    group.addRow(reset, NativeGroupLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        dividerTop = true
    })
    return Triple(group, save, reset)
}
```

Mount the group in an existing Activity or Fragment. The default button input
handles ordinary Android touch and keys. Supply `NativeButtonInputPolicy` when
the host needs an explicit input root, an owner revision key, or delayed press
admission under a scrolling parent.

Use `setLeadingIcon` for a tinted decorative drawable on a labeled action. The
selected recipe supplies its size, gap, typography, and enabled/disabled paint.
Use `buttons.tonal` for a filled secondary action and `buttons.text` for a text
action. `buttons.footerBack` and `buttons.footerContinue` use their parent's
assigned button bounds. If the natural-width button is wrapped in a larger hit
target, use `minimumTouchTargetDp` to size that wrapper.

See [Settings and browser controls](docs/appearance-controls.md) for retained
selectors, editable fields, toolbar actions, and a separate close action in a
selectable row.

Use [NativePermissionDialog](docs/permission-dialog.md) for an app-owned permission
request with joined Allow/Deny actions and optional scope controls. Its default
recipe lives in `appearance.modals.permissionDialog`; authorization and localized
request text remain with the caller. Do not vendor a second copy of the component.

Keep the returned controls for the owner’s lifetime. Rebind labels, enabled
state, actions and palette on the same views. For font or display changes, obtain
a new `Density` and shared font resolver from the current window and call
`bindTypography` before rebinding labels. For language changes, resolve
domain labels and descriptions again from the current resources. The library's
intrinsic control states and roles use its translated Android resources. Call
`close()` on retained buttons, toggles, selectors and dropdowns when their
logical owner retires, such as at `Fragment.onDestroyView()`.

A toggle callback requests the next checked value. A segmented callback
requests an option ID. Publish the authoritative selection back through
`bindToggle` or `bind`; labels can change with locale while option identity
stays stable. For a dropdown, keep the popup in the window owner and pass typed
options to `NativePopupMenu`. Recreate an open popup if its immutable typography
or density changes, or if the anchor changes layout direction. Retain the
caller’s scroll state for that same choice.

Mounted buttons and toggles with default input expose a single named Android
accessibility action using the view's visible bounds. Toggle semantics include
the current checked state. Set `contentDescription` for a control whose visible
content does not provide a name.

Hosts with an explicit input root supply the control's `mergedRowAccessibility`
geometry. A standalone host may also bind geometry for custom traversal and
bounds. The first explicit binding takes over for the lifetime of that view.
Closing its lease hides the source node until a new lease is bound. See
[Paragraph views and merged rows](docs/paragraph-views.md#merged-rows).

## Grouped controls

`NativeGroupLayout` owns a rounded surface and one retained horizontal or vertical
content layout. Its `LayoutParams.dividerTop` and `dividerStart` opt individual
children into one-physical-pixel foreground separators, including RTL start edges.
The separators share the surface's sampled border color and preserve the child's
ripple, alpha and transform. The group adds no padding or spacing; callers own
their content and 48dp minimum targets. `pointerContent` and `containsVisualPoint`
expose the actual shared clip for phase-input and accessibility bindings.

`setJoinedEdges(NativeGroupEdges(first, last))` decorates an individual row in a
joined vertical group; a null edge binding restores the uniform surface. Joined
rows retain 16dp corner geometry, raw 1dp side outlines and a physical-pixel top
separator. `setPaletteImmediately` accepts colors already sampled by a caller's
multi-channel animation. The optional `onPaintedSurfaceChanged` callback observes
actual fill changes for caller-owned contrast invalidation.

Use `NativeSettingsRow` as row content when a setting needs a title, description,
and a value. A `NativeToggleRow` supplies one checked action for the whole row.

## Progress

Use `NativeCircularProgress` for an indeterminate spinner,
`NativeIndeterminateLinearProgress` for an unknown total, and
`NativeDeterminateLinearProgress` for a known fraction. Linear views take palette
colors and density; the spinner takes a color and uses its Context's density.
The indeterminate views take the window's `MonotonicFrameClock`,
an owner-scoped `CoroutineScope`, and a duration-scale provider; they release
their frame work and setting listeners on detach. Call `motionScaleChanged()`
when an injected scale changes without a platform setting notification.

For a retained offscreen spinner, `setPresentationActive(false)` freezes its
current pose. Determinate `bind` takes an operation ID and terminal flag so a
new operation resets smoothing while accessibility reports the authoritative
fraction. Both linear views share drawing, measurement and localized progress
semantics. Use `bindAccessibility` when the host supplies custom clipping and
coordinate mapping; each returned lease can retire only its own binding.

## Top bars and icon actions

`NativeTopBarLayout` owns retained action hit routing and the fixed start/center/end
slot layout. `ActionLayoutParams` selects a slot; `bindMetrics` supplies density,
remaining top inset and content height. `NativeTopBarHitTarget` exposes the actual
clipped visual bounds, so a direct sibling hit takes priority over an expanded
48dp near hit. Custom toolbar layouts may override measurement and placement while
retaining this router.

`NativeCenteredTopBarLayout` measures a supplied navigation View, title View and
action View independently. It owns the 64dp minimum content height, multiline title
growth, constrained full-width title centering, RTL placement and content clipping.
`NativeTopBarInsets.remaining` computes bars/cutout union minus ancestor consumption;
the window owner applies those remaining insets once.

Use `NativeToolbarContentAction` for an app mark or other decorative View that
fills the action bounds. Pass `inputRoot = null` and
`standaloneAccessibility = true` in an ordinary Android hierarchy, supply a
localized description, and call `bindAction` with the current action. Android
accessibility exposes one named button across the allocated bounds. Toolbars
with source-window accessibility can bind their geometry and description child
through `bindAccessibility`.

`NativeTopBarAction` owns the rounded 46dp action, optional 48dp wrapper, described
23dp icon semantics, source-node accessibility bindings and phase-input endpoint.
A caller supplies `iconPainter`; application glyphs and branding stay outside the
library. Shared-surface actions retain separate input and semantic identities.
`NativeStandardIconButtonView` owns the circular 40dp action and 24dp glyph layout,
with an independent 48dp minimum target. Supply a real glyph View and its description
accessibility binding; `bindAction` controls density, enabled state and inherited
ripple color. Explicit glyph tint stays with the glyph owner. These components use
the shared click, ripple and animation-scale infrastructure and create no second
input or animation engine.

## Named color spaces

`Color` retains its packed sRGB, Unspecified and Oklab encodings, named conversion,
Oklab interpolation, compositing and luminance. `ColorSpace.connect` retains its
four rendering intents for these named spaces. Custom RGB profiles are no longer
part of this component library: `Rgb`, `TransferParameters`, `WhitePoint`,
`Illuminant`, `Adaptation` and `ColorSpace.adapt` are removed. `ColorSpaces.Srgb`
now exposes the named `ColorSpace` type rather than `Rgb`; custom subclass/profile
construction and RGB-specific accessors are unavailable. This is a source and
binary breaking change for those low-level APIs. No compatibility facade or
framework-profile equivalence is promised. Component consumers use
`ColorSpaces.Srgb`, `ColorSpaces.Oklab` and the existing `Color` operations.
Numerical equivalence and runtime costs require independent qualification; this
API reduction alone is not a measured speed or battery claim.

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
copy, translations, icons and radio-row geometry. Create it with
`appearance.settingsSelectors.createThemeSelector`, using the window's shared
`Density` and font resolver. The preset owns the paragraph title and animated
group surface. Supply a `ControlPalette`, a `NativeThemeMode`, and an
`onSelectionRequest` callback. Bind the authoritative selection through `bind`
and call `close` when the logical owner retires. The app owns persisted theme
codes. `inputAllowed` adds the host's admission policy to the component's
attachment, visibility, focus and enabled checks.

```kotlin
val theme = appearance.settingsSelectors.createThemeSelector(
    context, palette, selected, density, resolver,
    onSelectionRequest = onThemeRequested,
)
theme.bind(selected, palette, density, resolver)
```

Both Theme and app-defined options use `NativeSegmentedControl`. Use
`appearance.settingsSelectors.createSegmentedControl` for the same grouped
capsule with app-defined choices. Bind retained `NativeChoice` values and the
authoritative selected ID, and provide a Boolean selection callback. Direct
`NativeSegmentedControl` construction supports custom capsule presentation
through `NativeSegmentedPresentation`.

The capsule uses the bound labels and icons to determine its preferred width.
Longer labels, larger fonts or a small window can increase its height and wrap
the same choices into additional rows. Theme places the capsule below its title
when they cannot fit comfortably side by side. Reflow keeps the bound selection
and the existing option views.

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

`Animatable.animateTo` suspends until completion and returns `Unit`; read the
retained state or use its frame callback for samples. Unused bounds and result
APIs are removed. Fling trajectories use `FloatDecayAnimationSpec` directly;
generic vector decay and suspended decay entry points are removed.

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
Dependencies include AndroidX annotation, core, lifecycle, savedstate and emoji2.
Coroutines and activity-ktx 1.13.0 are exposed by the window/runtime APIs.

## Anchored menus

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

Choice selection is exposed through standard selected accessibility semantics
and the same visual trailing check; action-row reuse clears selection. Window
flags/secure inheritance, candidate placement and transition behavior follow the
source-derived component. The intrinsic popup title stays in library translations;
domain row labels stay with their owner.

For mixed content in an existing `PopupWindow`, use the same surface and motion
through `appearance.modals.anchoredPanel`.

```kotlin
val panel = appearance.modals.anchoredPanel.createPanel(body, density, palette)
val popup = PopupWindow(panel, widthPx, heightPx, true)
panel.applyWindowStyle(popup)
panel.expanded(true)
popup.showAsDropDown(anchor)

// From the app's close action
panel.expanded(false, popup::dismiss)
```

The panel measures the supplied unattached body inside its vertical padding.
The window owner continues to choose size, anchor, focus, outside touch and
dismissal. The child keeps its own keyboard, touch and accessibility behavior.
Call `bindPalette` to update a retained panel. To align motion with the actual
placement, pass physical origin fractions to `setAnimationPivot`. It defaults
to the center of the top edge. A new expand request or window detach retires
a pending collapse callback. The panel uses the platform animator duration scale.

## Action sheets and floating windows

`NativeActionSheet` owns a real `ComponentDialog`, its two-anchor state, phase
pointer root, nested drag/scroll connection, predictive transform, scrim, title,
close action, drag handle and tooltip. Supply an anchor, `NativeActionSheetStyle`
(density, font resolver, text styles and colors), title, dismissal
callback and `NativeActionSheetIcon`. The icon supplies an existing glyph View,
its accessibility binding and tint callback; input/ripple/merged role use the
shared `NativeStandardIconButtonView`. An optional caller Drawable decorates the
content. No application environment or domain policy is retained by the library.

Build body/actions against `contentContext`, then call `setContent` with their
explicit hit layouts and `show()`. The context supplies the real pointer root,
window runtime, nested-scroll parent, source accessibility geometry/cursor and
scroll events. `bind` updates title/callback; `updateStyle` updates retained
paints and typography. Recreate for anchor/density/configuration changes.
Direct Close invokes the owner immediately; scrim, drag and Back animate hidden
before their callback. The owner removes its route and calls `dismiss()`.
`setDismissalAdmission` and `refreshDismissalAdmission` let busy owners veto those
animated dismissals, including an already-running hide; direct content actions
retain caller policy. Owner teardown is immediate and idempotent.

`NativeFloatingDialog` owns the distinct top-aligned floating window, measured
entry/exit and predictive Back continuation. Its content factory receives the
themed context, `NativeModalWindowRuntime` and animated dismiss request. Return
`NativeFloatingDialogContent` with the actual View, predictive target and explicit
accessibility/disposal hooks. Domain text, links, scroll state and callback policy
stay in that content. Explicit `dismiss()` retires the window/content once without
invoking navigation; `bind` updates the owner callback and inherited window policy.
Both modal owners retain secure-window inheritance and lifecycle frame gating.
`NativeWindowInsetsBinding` is also available for other native window roots.

### Optional status-bar protection

`NativeStatusBarProtection` draws a caller-supplied background only within the
visible status-bar insets. Supply an opaque background that contrasts with the
chosen icon polarity (`darkIcons`, false by default). Retain one protection
instance and its background Drawable per window; do not share them between
window roots. For a root owned by the host, install it as the root foreground,
feed `updateInsets` the raw window insets before child consumption, and call
`applyWindowAppearance` for that window. Use the root's existing inset listener;
this adds no layout, frame loop or inset observer. Hidden status bars produce no
protection; cutout, navigation-bar and IME insets are not substituted for
status-bar insets.

The explicit protected constructors of `NativeActionSheet` and
`NativeFloatingDialog` perform this wiring themselves. Their original
constructors retain their existing behavior. This policy does not observe
SystemUI's rendered icon tint or guarantee other windows' appearance.
Navigation-bar appearance keeps its existing owner.

The unused `NativeSheetModel` tokens (`NativeSheetBehavior`,
`NativeSheetHandleStyle`, `NativeSheetState`) were removed with no in-repository
callers. This is a source/binary break; the concrete sheet supports Hidden and
Expanded only. It does not promise partial expansion. Intrinsic sheet strings,
translations and themes belong to the library, including the header's localized
`close_sheet` description; caller titles and domain actions remain supplied by the app.
`SOURCE-dialogs.json` and the retained
resource provenance record source versions and licenses. Moved unit tests cover
state/admission, geometry, tooltip release and floating motion; those tests do not
prove Android window/input/IME or rendering parity, and moving code establishes
no runtime performance improvement.

## API migration

- Move Theme calls that omitted `NativeThemePresentation` to
  `appearance.settingsSelectors.createThemeSelector` and rebuild against the
  library. The direct `NativeThemeSelector` constructor requires an explicit
  custom presentation.
- Use `NativeGroupLayout` for grouped settings, and opt each divider in through
  its row's layout parameters. [Control source records](SOURCE-controls.json)
- Choose `NativeActionSheet` or `NativeFloatingDialog` when moving a dialog to
  the current window components. [Dialog source records](SOURCE-dialogs.json)
- Bind typed choices or action callbacks when upgrading an anchored menu.
  See [Anchored menus](#anchored-menus) for window ownership and dismissal.
