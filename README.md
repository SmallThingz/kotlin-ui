# SmallThingz UI

Kotlin library for retained Android Views, text, layout, and animation, with
an inline View DSL in `smallthingz.ui.dsl`. It requires neither Compose nor
the Material3 UI runtime.

## Getting started

Include the library as `:native-ui` and add `implementation(project(":native-ui"))`.
Use the current window context. Resolve colors with
`NativeUiAppearance.Default.resolveColorScheme(context, darkTheme)` or use
`ControlTheme.resolve(context, darkTheme)` for `smallthingz.ui.common` controls.
Dark mode defaults to AMOLED.

Create a control such as `NativeMaterialButtonView` once, then call `bind` with
accepted state and `close()` when its owner retires. The app owns values,
localized labels, and actions. See [control binding](docs/reference-guide.md#control-bindings)
and [appearance configuration](docs/appearance-controls.md#defaults-and-explicit-configuration).

## Mail

`smallthingz.ui.mail` provides `MailComposerFields`, `MailRecipientRow`, and
`mailDropdownGroup` for native mail composition and choice lists. These
delegate to the existing `smallthingz.ui.email` controls.

## Build

Use JDK 21 and Android SDK platform 37. Set `ANDROID_HOME`, or set `sdk.dir`
in an untracked `local.properties`. The library supports API 28+ and targets
JVM 17 bytecode. The standalone build pins Gradle 9.7.1, Android Gradle Plugin
9.4.0, and Kotlin 2.3.0. Reverb supplies its own plugin toolchain.

```sh
./gradlew assembleRelease testDebugUnitTest
```

## Source and tests

- `src/main/kotlin` holds shared UI. `src/main/adapters/kotlin` holds app
  compositions and adapters compiled into the same library.
- `src/test` holds unit and Robolectric tests. `src/androidTest` holds Android
  instrumentation tests.
- `scripts/` runs focused host checks with fixtures in `tests/host-fixtures/`
  (`common`, `views`, `icons`). Other harnesses live in `tests/` and `tools/qa/`.

## Documentation

[Reference guide](docs/reference-guide.md) · [Anchored dropdowns](docs/anchored-dropdown.md) ·
[Permission dialogs](docs/permission-dialog.md) · [Click interactions](docs/click-interactions.md) ·
[Paragraph views](docs/paragraph-views.md) · [Icons](docs/icons.md) ·
[Provenance](docs/reference-guide.md#integration-and-provenance) · [License](LICENSE.txt)

### Procedural background grain

See [grain setup and texture ownership](docs/reference-guide.md#procedural-background-grain).
