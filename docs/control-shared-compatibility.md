# Control shared UI integration compatibility

This integration adds grouped permission notices, bottom action docks, launcher
identity artwork, and optional app icons on information toggles. Consumers should
rebuild against the updated library. It is not a drop-in binary replacement for
independently compiled users of `ReverbInformationToggle`.

## Permission notices and dock sizing fixes

`ReverbPermissionNotices` now explicitly enables standalone accessibility for its
summary and permission actions. Ordinary native hosts do not need to install a
source-geometry binder. Descriptions and request admission still come from the
current permission state. A source-geometry owner can still take over; releasing
that owner does not resurrect standalone nodes. The opt-in is internal and does
not change public action constructors or other progress-action callers.

`NativeMaterialButtonView.intrinsicHeight(width)` now reserves the same leading
icon and gap width as actual label layout, includes icon height, and ignores a
hidden label. Its public signature is unchanged. A bottom action dock may become
taller when an icon-bearing label wraps, especially with large fonts or narrow
windows. The 64dp floor, parent height limit and navigation inset remain intact.
Other users of the shared height query receive the same corrected calculation;
no-icon, visible-label sizing is unchanged.

The existing seven-argument `ReverbPermissionOnboardingScreen` constructor remains
available, including ordinary Kotlin trailing-lambda calls and its JVM signature.
Grouped notices are opt-in through `groupMissingPermissions = true`.

## Information toggle app icons (C1)

`ReverbInformationToggle` adds `appIcon: Drawable? = null` as the final constructor
property. Ordinary Kotlin constructor and `copy` calls remain source-compatible
when rebuilt; omitted icons default to null. Explicit Java constructor and `copy`
calls must supply the new final argument, usually `null` for existing behavior.

Adding the primary-constructor property changes the generated constructor,
default-argument constructor, `copy`, and `copy$default` JVM descriptors. Rebuild
dependent modules as well as the app; replacing only the library beneath old
compiled callers can produce `NoSuchMethodError`. The integration does not add
binary compatibility bridges or redesign the model.

## Launcher identity (C2)

`ReverbProductIdentity` is sealed and now also contains `Launcher(drawable)`.
Rebuilt consumers with exhaustive `when` expressions must handle it, for example:

```kotlin
fun artwork(identity: ReverbProductIdentity): Int? = when (identity) {
    ReverbProductIdentity.Reverb -> null
    is ReverbProductIdentity.Monochrome -> identity.drawable
    is ReverbProductIdentity.Launcher -> identity.drawable
}
```

Use the appropriate rendering policy in that new branch: launcher artwork keeps
its original colors, whereas monochrome artwork is palette-tinted. Previously
compiled exhaustive expressions can throw `NoWhenBranchMatchedException` if
passed a `Launcher`; handling the original variants still works. Rebuilding and
migrating exhaustive consumers is required when adopting launcher identity.

## Focused regression sources

- `ReverbPermissionNoticesAccessibilityIntegrationTest`: attached native-host
  action exposure and activation for each permission kind; disabled, granted,
  revoked, collapsed, hidden and closed states; source-geometry takeover/release.
  Admission waits for attached, shown, laid-out, nonzero, focused fixture Views
  with no pending layout. Main-thread dispatch and readiness waits are bounded at
  five seconds. Cleanup waits for the exact launched Activity to be destroyed and
  its decor/root detached, preserving primary failures if cleanup also fails.
- `ReverbBottomActionDockLayoutIntegrationTest`: complete label measurement with
  and without icons, large fonts, narrow widths, both layout directions and
  assigned-size recipes; constrained parent height and navigation fill.

These test sources were added without running a build, instrumentation, or device
operation. Their presence is not a claim that compilation or execution passed.

## Opt-in canonical launcher artwork bounds

`ReverbProductIdentity.Launcher(drawable, artworkBounds = null)` retains the
existing full-drawable `FIT_CENTER` policy when the optional bounds are omitted.
The one-argument JVM constructor remains available. Other identity variants,
color/tint behavior, action/accessibility ownership and the centered 48dp toolbar
slot are unchanged.

For canonical foreground artwork whose vector viewport contains launcher safe
padding, pass its original drawable resource and `ReverbLauncherArtworkBounds`
with normalized ink bounds (including strokes). The retained ImageView uniformly
fits and centers that declared ink, without changing paths or resource colors.
Do not pass adaptive-wrapper coordinates as if they were the original foreground
viewport: the platform owns the wrapper inset. Bounds outside [0,1], empty or
nonfinite bounds are rejected. Missing/zero dimensions use ordinary FIT_CENTER
until measurable; a size change recomputes the matrix.

For Control's existing 108x108 `ic_rc_foreground`, all fill and stroke ink lies
inside x34..74/y26..82:

```kotlin
ReverbProductIdentity.Launcher(
    R.drawable.ic_rc_foreground,
    ReverbLauncherArtworkBounds(34f / 108f, 26f / 108f, 74f / 108f, 82f / 108f),
)
```

This reuses its original foreground, background-colored cutouts, and accent.
It does not introduce a new mark or recolor it. At a square 48dp slot, the declared
40x56 ink becomes about 34.286x48dp, centered on both axes; normal unconstrained
full-viewport fit was about 17.778x24.889dp before any adaptive inset.

`Launcher` is a data class with a new optional constructor property. Rebuild
consumers using generated `copy`/default-copy descriptors; the legacy one-argument
constructor alone is not a general binary-compatibility guarantee. There is no
new sealed variant requiring another exhaustive `when` branch.

Six focused pure-JVM geometry controls cover canonical and asymmetric ink,
resize/density, ordinary full bounds, unmeasured/nonintrinsic/subnormal inputs,
and invalid bounds. They do not establish Android draw/accessibility behavior;
combined Android compilation and actual visual fit remain pending.

## Opt-in circular Launcher outline

`ReverbProductIdentity.Launcher(drawable, artworkBounds = null, circularOutline = false)`
adds an explicit presentation option. The default keeps the existing unoutlined
launcher presentation. Existing one- and two-argument JVM constructors remain
available through `@JvmOverloads`; rebuild Kotlin consumers because the data-class
`copy` and default-constructor ABI gains a property.

For a complete adaptive icon, pass the same canonical resource used by the app
manifest and leave `artworkBounds` unset:

```kotlin
ReverbProductIdentity.Launcher(R.mipmap.ic_launcher, circularOutline = true)
```

The shared identity host applies `NativeRoundedClipLayout`'s circular clip and
uses the same retained `MarkPlateOutline` painter as the Reverb brand mark. Its
color and width come from `NativeDarkSurfacePreset.Amoled`, as in
`setReverbBrandOutline(true)`, independently of the window palette. The painter
runs after the artwork, with the existing shared control-border geometry and
rounding. No second glyph renderer, bitmap cache, or app wrapper is introduced.

The drawable is still loaded from application resources and drawn by the same
untinted ImageView. Paths, fill colors, texture, adaptive layers and platform
adaptive-mask behavior remain resource-owned. A fixed-dark icon in both themes
must therefore use fixed-dark resources; this option does not recolor arbitrary
light-qualified artwork. Do not apply original-foreground ink bounds to an
adaptive wrapper. The outer circle does not remove the adaptive drawable's own
mask or imply identical launcher-process rasterization.

The option follows the identity into both the product toolbar and its About
panel. It changes neither the existing identity slot nor action, focus or
accessibility ownership. Rebinding to an unoutlined Launcher, Monochrome or Reverb
removes the added decoration. Size/configuration changes refresh the outline
geometry; the shared clip retains its attached-hardware and detached/software
paths. Default Reverb and Monochrome rendering policies remain unchanged.
