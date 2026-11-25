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
