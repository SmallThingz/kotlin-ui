# SmallThingz UI

A Kotlin library for Android Views, with retained controls, text and layout
primitives, animation, and an inline View construction DSL in `smallthingz.ui.dsl`.
It builds actual Views and requires neither Compose nor the Material3 UI runtime.

## Getting started

Add `implementation(project(":native-ui"))` when included as an Android module.
Use the current window's context for colors, density, and fonts.

```kotlin
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

val appearance = NativeUiAppearance.Default
val colors = appearance.resolveColorScheme(context, darkTheme)
val save = NativeMaterialButtonView(
    context, appearance.buttons.filled, Density(context),
    createFontFamilyResolver(context), onClick = onSave,
)
save.bind(saveLabel, enabled = true, palette = colors.buttonPalette)
```

Mount `save` in your View hierarchy. Keep the same View when rebinding labels,
enabled state, callbacks, or palette, and call `save.close()` when its owner
retires. The app owns domain state, localized labels, and actions.

## API

- `smallthingz.ui.common` offers semantic controls such as `ToggleRow`,
  `SegmentedSelector`, and `DropdownGroup`. Resolve a `ControlTheme` per window
  and rebind accepted state to the retained controls.
- `smallthingz.ui.components` provides `NativeToggleRow`,
  `NativeSegmentedControl`, `NativeDropdown`, `NativeGroupLayout`, and the
  button used above. See [Settings and browser controls](docs/appearance-controls.md)
  and [control binding and lifecycle](docs/reference-guide.md#control-bindings).
- `smallthingz.ui.reverb`, `smallthingz.ui.email`, and `smallthingz.ui.zfm`
  provide application compositions and adapters. See
  [application integration](docs/reference-guide.md#application-boundary).

## Default theme

`NativeUiAppearance()`, `NativeUiAppearance.Default`, and
`ControlTheme.resolve(context, dark)` use `NativeDefaultColorScheme`.
Dark mode uses `NativeDarkSurfacePreset.Amoled`. Light mode uses raw Material
colors with `background` and `surface` set from the qualified
`native_default_light_background` resource. Both defaults use grain intensities
of `1f` for backgrounds and `.5f` for foregrounds.

Resolve colors again when configuration or system colors change. For common
controls with custom appearance, use `ControlTheme.resolve(context, dark, appearance)`.
See [default palette roles and explicit configuration](docs/appearance-controls.md#defaults-and-explicit-configuration).

### Procedural background grain

See [grain setup and texture ownership](docs/reference-guide.md#procedural-background-grain).

## Build

From this directory, use JDK 21 and Android SDK platform 37. Set `ANDROID_HOME`
or `sdk.dir` in an untracked `local.properties`. The library supports API 28+
and targets JVM 17 bytecode.

```sh
./gradlew assembleRelease testDebugUnitTest
```

The standalone build pins Gradle 9.7.1, Android Gradle Plugin 9.4.0, and Kotlin
2.3.0. Reverb includes the library as `:native-ui` and shares its plugin toolchain.

## Test layout

- `src/test` contains the library's unit tests, including Robolectric cases.
- `src/androidTest` contains Android instrumentation regressions. Dropdown
  interaction, viewport, inertia, lifecycle, and Email choice tests use
  non-exported activities declared in its test manifest.
- `scripts/` contains focused host runners. Their script-only fixture sources
  live in `tests/host-fixtures/` under `common`, `views`, and `icons`.
  Other focused host regressions live in `tests/` and `tools/qa/`.
  See `docs/qa/` for retained QA evidence.

## Documentation

- [Anchored dropdowns](docs/anchored-dropdown-draft.md) for interaction and integration.
- [Reference guide](docs/reference-guide.md) for component ownership, menus,
  dialogs, phase input, scrolling, animation, and API migration.
- [Settings and browser controls](docs/appearance-controls.md),
  [permission dialogs](docs/permission-dialog.md), and
  [click interactions](docs/click-interactions.md).
- [Paragraph views and accessibility](docs/paragraph-views.md) and
  [shared icons](docs/icons.md).
- [Integration and provenance](docs/reference-guide.md#integration-and-provenance)
  and [license](LICENSE.txt).
