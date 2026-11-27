# Native UI reference guide

[Quickstart](../README.md#getting-started) · [Appearance and controls](appearance-controls.md) · [Source and licenses](#integration-and-provenance)

## Application boundary

Application code supplies localized domain content, stable option IDs,
authoritative state, permissions, and actions. The library owns component
rendering, layout, and intrinsic labels. Keep controls for their logical
owner's lifetime and publish accepted state back through their bind APIs.
Hiding a retained screen and disposing it are separate operations.

`smallthingz.ui.common` provides semantic controls such as `ToggleRow`,
`SegmentedSelector`, and `DropdownGroup`. `smallthingz.ui.reverb` supplies
Reverb compositions and presets, including `ReverbEnvironment`,
`ReverbTopBar`, Settings, Library, Incidents, Home, and dialogs.
`smallthingz.ui.email` supplies composer fields and recipient and choice
controls. `smallthingz.ui.zfm.ZfmTheme` delegates to the shared palette,
modal, and backdrop recipes. See [appearance configuration](appearance-controls.md#defaults-and-explicit-configuration).

### Dropdown selection and dismissal

`DropdownSelectionPolicy` controls delivery before dismissal. `DropdownGroup`
defaults to `PROGRESSIVE`, which delivers each newly centered choice while
browsing. `EXPLICIT` lets users browse before activating an option to deliver it.

`DropdownDismissPolicy` applies to both selection modes. Its default, `COMMIT`,
delivers a changed choice on an outside tap, Back, or the accessibility dismiss
action. An unavailable stored value is kept if the user dismisses without
choosing or browsing to an available option.

For a field that requires separate confirmation, construct the group with
`dismissPolicy = DropdownDismissPolicy.CANCEL` alongside its selection policy.
This discards the pending choice on those dismissal actions and preserves any
changes already delivered in progressive mode. Escape discards pending choices
under either policy. Use `dismiss()` or `close()` for lifecycle cleanup, which
also discards pending choices.

The callback delivers a field value to its owner for rebinding. In Reverb
Settings this updates the draft. The screen's Done action still owns saving the
settings.

### Email

`EmailDropdownGroup` retains one anchored choice at a time in the window's
unscrolled `FrameLayout`. Pass `ControlTheme.resolve(context, dark)`, the overlay
host and an input-admission callback. Bind each field with `DropdownContent`,
using stable values and localized labels. Dragging, arrow keys and accessibility
scroll actions browse choices using `EXPLICIT`. The group uses the default
dismiss policy above. Activating a row or pressing Enter on the centered choice
also delivers the choice. An unavailable stored value can be displayed and
replaced with an available choice, including a single remaining option.

Email values wrap to four lines. The field reserves room for its tallest option
at the actual field width, keeping the open wheel aligned to its trigger.
Longer values end in an ellipsis and retain the complete accessibility label.

Rebind the authoritative value after accepting a change. Dismiss the group
when the screen stops. Close the field when its row retires and close the group
with the owning window. Supply a new `ControlTheme` on a configuration or theme
change.

`EmailComposerFields` joins caller-supplied recipient rows with an editable
Subject. Its `subjectEditor` retains Android text input, selection and IME
behavior. `EmailRecipientRow` styles address text and the selected row while
the caller supplies children and actions. Library instrumentation covers
Email field presentation, unavailable-choice recovery, sender label layout,
and composer behavior.

## Control bindings

For button input under a custom root or scrolling parent, see
[click admission and button lifetime](click-interactions.md#shared-material-button).
`setLeadingIcon` uses the button recipe's sizing and tint. Use `buttons.tonal`
for filled secondary actions and `buttons.text` for text actions.
`buttons.footerBack` and `buttons.footerContinue` use the parent's assigned
bounds. For an expanded hit target, use `NativeButtonTouchTarget` or size
the wrapper from `minimumTouchTargetDp`.

A toggle callback requests a Boolean, and a segmented callback requests a
stable option ID. Rebind the authoritative value with `bindToggle` or `bind`.
Recreate an open dropdown popup when its immutable density or typography
changes, or when its anchor changes layout direction. Retain the caller's
scroll state for that choice.

Default-input buttons and toggles expose one named Android accessibility
action. Toggles expose checked state. Supply `contentDescription` when the
visible content supplies no name. With an explicit input root, bind the
control's `mergedRowAccessibility` geometry. The first explicit binding
takes over for the view's lifetime, and closing its lease hides the source
node until a new lease is bound. See [merged rows](paragraph-views.md#merged-rows).

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
rows retain 16dp corner geometry, preset-aware side outlines, defaulting to 1dp,
and a physical-pixel top separator. `setPaletteImmediately` accepts colors
already sampled by a caller's
multi-channel animation. The optional `onPaintedSurfaceChanged` callback observes
actual fill changes for caller-owned contrast invalidation.
Use `squareTop` or `squareBottom` on `NativeGroupEdges` for a closed, square border
facing a gap between topics; the caller still owns the gap itself.

`NativeMergedActionLayout` is the common Cancel/confirm action group used by Trim,
Rename and confirmation sheets. It accepts any nonempty list of retained actions,
stacks when labels need more width, and excludes `GONE` actions from layout and
dividers. It preserves each button's semantic paints, callbacks and accessibility
owner. Rebind its palette with the window; close the child actions with their owner.
Joining a `NativeMergedActionChild` changes that instance's geometry for its lifetime.
In short viewports, put the group inside the owning scroll container and measure
its content at natural height; the group clips to hard bounds without shrinking
targets. Phase hit trees must retain its actual group and viewport shape clips.

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
See the [theme icon source record](../SOURCE-theme.json).

## Anchored menus

`NativePopupMenu<I>` is a concrete focusable sub-panel using the shared phase
pointer root, vertical viewport, paragraph renderer and retained spring transition.
Pass an attached anchor, density, the window's typography, font resolver and
resolved `NativeColorScheme`, retained scroll state, frame clock/scope and a
`NativeMenuIcons<I>` adapter. The adapter creates each retained
icon View once and binds any icon token of I to that same View; keep it nonfocusable
and excluded from accessibility. An app can use its existing vector/icon renderer.

When migrating from `NativeMenuColors`, pass the window's `NativeTypography`
and `NativeColorScheme` to the constructor. Replace action text and icon
colors with a `NativeMenuActionRole`. The default is `NORMAL`. Use
`MUTED_ICON` for a secondary glyph or `DESTRUCTIVE` for a destructive action.

```kotlin
val palette = appearance.resolveColorScheme(anchor.context, darkTheme)
val menu = NativePopupMenu(
    anchor, density, appearance.typography, resolver, palette, scrollState,
    frames, scope, icons, recipe = appearance.modals.anchoredPanel,
    onDismissRequest = onDismissRequest, onTerminal = onTerminal,
)
menu.bindActions(listOf(
    NativeMenuAction(deleteLabel, deleteIcon, role = NativeMenuActionRole.DESTRUCTIVE, onClick = onDelete),
))
menu.show()
```

Use `bindChoices(choices, select)` for a choice menu. On a palette change, call
`menu.bindTheme(palette)` with the newly resolved window palette. Reverb callers
using `ReverbActionMenuHost` migrate from `bindPalette` to `bindTheme(theme)`,
passing the current `ReverbTheme`.

Row identity is positional. Call
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
close action, drag handle and tooltip. Resolve the sheet style from the window
theme and pass it to `NativeActionSheet.create(anchor, style, title, onDismiss)`.
An optional `NativeActionSheetIcon` supplies the close glyph, accessibility
binding and tint callback. The shared `NativeStandardIconButtonView` handles
input, ripple and the merged role. See [shared modal surfaces](appearance-controls.md#shared-modal-surfaces).

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

Pass `statusBarProtection = protection` to `NativeActionSheet.create(...)` or
to the `NativeFloatingDialog` constructor. Both wire the protection to their
window. This policy does not observe
SystemUI's rendered icon tint or guarantee other windows' appearance.
Navigation-bar appearance keeps its existing owner.

The unused `NativeSheetModel` tokens (`NativeSheetBehavior`,
`NativeSheetHandleStyle`, `NativeSheetState`) were removed with no in-repository
callers. This is a source/binary break; the concrete sheet supports Hidden and
Expanded only. It does not promise partial expansion. Intrinsic sheet strings,
translations and themes belong to the library, including the header's localized
`close_sheet` description; caller titles and domain actions remain supplied by the app.
See the [dialog source record](../SOURCE-dialogs.json) for provenance and test scope.

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

See the [scroll source record](../src/main/kotlin/smallthingz/ui/scroll/SOURCE.md).

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
See the [View animation source record](../SOURCE-view-animation.json).

Sampler and clock-owner tests run with the unit suite. The separate
`scripts/test_view_animation_host.py` runner accepts compiler/test classpaths and
uses `tests/host-fixtures/views` to check View owner cancellation against a narrow host
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

## Font-scaling host regression

Run all five `FontScaleConverterSnapshotHostTest` cases separately from Gradle:

```sh
python3 -B scripts/test_font_scaling_host.py \
  --compiler-classpath "$KOTLIN_COMPILER_CP" \
  --test-classpath "$FONT_SCALING_TEST_CP" \
  --output /path/to/new/font-scaling-receipt
```

Run from this repository root with JDK 17+ (`--java-home` is optional).
The compiler classpath needs Kotlin compiler-embeddable and its dependencies;
the test classpath needs Kotlin stdlib, AndroidX and JetBrains annotations,
JUnit 4 and Hamcrest. No built library or Android runtime is needed. The runner
compiles the actual font-scaling sources and `unit/InlineClassHelper.kt` against
the existing host SparseArray fixture. `--source-root` can select another
native-ui production checkout while retaining this runner's fixtures.

The output directory must be new. It retains stage logs (including JUnit's test
count), `commands.json`, and `summary.json` with before/after source, fixture,
test and runner hashes. Compilation/test failures or changed inputs fail the
run. This checks deterministic publication interleavings, not Android rendering
or platform SparseArray behavior.

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

## Integration and provenance

This Git repository was extracted from Reverb with its module-only history.
[ORIGIN.json](../ORIGIN.json) records the source snapshot and split commit.
Reverb pins a Git submodule commit and supplies a compact offline Git bundle plus
bootstrap script; an offline checkout does not require network access.

[Foundation](../SOURCE-foundation.json), [motion](../SOURCE-motion.json),
[motion notes](../SOURCE-motion.md), [text](../SOURCE-text.json), and
[resource provenance](resource-provenance/SOURCE.md) retain the original
upstream attribution and historical qualification records. Their `native-ui/`
target paths are relative to the original Reverb checkout. The package migration
from `app.smallthingz.nativeui` to `smallthingz.ui` also moved the corresponding
source directories from `app/smallthingz/nativeui/` to `smallthingz/ui/`; the
historical manifests retain their original paths and hashes. Old temporary source
paths are historical provenance, not current build inputs. Historical validation
statements are not a claim that a new checkout has passed current tests.

First-party code is licensed under Apache-2.0; see [LICENSE.txt](../LICENSE.txt).
Upstream-derived files retain their existing Apache-2.0 notices and provenance.
The relocated Reverb OneShot and ExportRange glyph paths retain their explicit
GPL-3.0 notices; see [Reverb icon provenance](resource-provenance/reverb-icons/README.md). Their relocation
does not relicense those paths.
Dependencies include AndroidX annotation, core, lifecycle, savedstate and emoji2.
Coroutines, activity-ktx 1.13.0 and RecyclerView 1.4.0 are exposed by the
window, runtime and retained-list APIs.

## API migration

- Move Theme calls that omitted `NativeThemePresentation` to
  `appearance.settingsSelectors.createThemeSelector` and rebuild against the
  library. The direct `NativeThemeSelector` constructor requires an explicit
  custom presentation.
- Use `NativeGroupLayout` for grouped settings, and opt each divider in through
  its row's layout parameters. [Control source records](../SOURCE-controls.json)
- Choose `NativeActionSheet` or `NativeFloatingDialog` when moving a dialog to
  the current window components. [Dialog source records](../SOURCE-dialogs.json)
- Bind typed choices or action callbacks when upgrading an anchored menu.
  See [Anchored menus](#anchored-menus) for window ownership and dismissal.
