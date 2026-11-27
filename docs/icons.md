# Shared icons

Use `smallthingz.ui.icons.MaterialRoundedIcon` for common actions across library
applications. This is the existing, pinned **AndroidX Material Rounded 1.7.8**
subset, with upstream names (`ContentCopy`, `FormatListBulleted`, `GraphicEq`,
`Devices`, etc.). It is not a new drawing style or the complete extended pack.

```kotlin
val icon = MaterialRoundedIconDrawable(
    MaterialRoundedIcon.ContentCopy,
    context.resources.displayMetrics.density,
    contentColor,
)
// Retain the drawable; update its tint and bounds when the host binds/layouts.
icon.setTint(nextContentColor)
```

Library components own icon choice and presentation. Apps supply semantic actions,
labels and state. The host owns accessibility descriptions, padding, touch targets
and drawable layout direction. Only `ArrowBack`, `FormatListBulleted` and `Undo`
automatically mirror in RTL. All common glyphs use a 24 × 24 viewport.

`MaterialRoundedIcon.mask` shares lazy immutable geometry. `MaterialRoundedIconDrawable`
uses the existing `CachedPathIconDrawable` ALPHA_8 cache and SrcIn tint contract;
retain one drawable per host rather than sharing a mutable drawable across Views.
No Compose/icon-extended runtime dependency or runtime SVG parser is needed.
`NativeThemeIconDrawable` and the shared dropdown use this same set. Email uses
shared controls; it does not own a competing glyph set.

`ReverbIcon` and `ReverbIconDrawable` remain compatibility/semantic adapters in
`smallthingz.ui.reverb`. Standard entries delegate to the same common masks. The
Library/Incidents morph receives private copies of the common paths. Existing app
typealiases remain valid. Prefer upstream names in new common components rather
than introducing Reverb names as the standard API.

The credential Reveal action uses `MaterialRoundedIcon.Visibility` /
`VisibilityOff`, exposed as `ReverbIcon.Eye` / `EyeOff`. Its revealed Boolean
chooses the glyph; the localized Show/Hide label remains an accessibility
description. Both use the same pinned Rounded1.7.8 pack, with the additional
source and native-path hashes in `credential-visibility.json` beside `SOURCE.json`.

## Provenance and exceptions

- `docs/resource-provenance/reverb-icons/SOURCE.json` records the historical
  generation: pinned core/extended artifact hashes and per-glyph source hashes.
  `MaterialRoundedPaths.kt` relocates the generated commands without changing
  coordinates. `OWNERSHIP.json` records baseline path-block hashes and platform
  resource hashes; the original source manifest remains historical evidence.
- AndroidX paths are Apache-2.0; the retained notice is in `MaterialRoundedPaths.kt`
  and the full license is beside the source manifest. Extend this subset from the
  same pinned pack with source/version/hash/license evidence and direct native
  paths. Do not approximate an available standard glyph with hand-drawn geometry.
- Reverb `OneShot` and `ExportRange` are existing project domain glyphs, GPL-3.0,
  from the baseline recorded in `SOURCE.json`. They remain library-owned.
- `Browser` is the existing Google Material **filled** Language glyph, not a
  Rounded-pack claim. Its pinned upstream source is recorded in
  `SOURCE-path-icon-mask.md`; `browser-path.txt` retains the input. Its native
  commands expand the old PathParser operations with Float32 reflection math.
  A device regression compares the direct path with that historical parser input.
- GitHub is the retained GitHub mark from the Reverb baseline, not a Material
  glyph or a new artwork/license claim. Reverb's canonical mark is
  `src/main/brand/reverb.svg`. Brand and service marks retain their identity.
- `NativeTileIcon` owns the existing custom amount/unit tile composition. The app's
  `QuickSaveTileIcon` supplies domain state and delegates rendering to it.
- `native_reverb_{github,qs_quick_save,qs_looping,qs_one_shot,notification_recording,notification_saved}`
  are byte-identical relocations of legacy platform resources from the app. These
  are compatibility artwork, not asserted to be the Rounded pack. The app retains
  only drawable aliases so manifest/service resource IDs continue to work. Their
  baseline app commit and exact hashes are in `OWNERSHIP.json`.

New custom UI artwork belongs in this library with provenance. App-owned generated
launcher/splash assets derived from the canonical brand and platform adapters are
exceptions; do not create independent app path generators. Source checks live in
`tests/test_icon_ownership.py` and the consuming app's
`scripts/test_icon_ownership.py` (run both with Python's unittest runner).
