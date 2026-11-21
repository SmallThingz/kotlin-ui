# Appearance control sources

The button presets, paired field layout, and single-line field outline use the
Reverb snapshot `88446459ac2ce5e43ad224eb27af18fa80208e98`. The files below lived
in `app/src/main/java/app/smallthingz/reverb/nativeui/` in that snapshot. The
first-party source is included in smallthingz.ui under Apache-2.0. Existing
Android Open Source Project notices remain on the adapted code.

| Reverb source | Git blob | Shared owner |
| --- | --- | --- |
| `LibrarySheetButton.kt` | `d9d5fe426588f523af739653d69ae99c0d70fc68` | `NativeButtonRecipes` natural filled, destructive, and text |
| `ReverbLibraryTextAction.kt` | `0b30cf0d9536a376c6a6637bd46541b0085ef3b9` | `NativeButtonRecipes` natural text |
| `ReverbLibraryUndoCard.kt` | `be3186aa63d0ffccf20238df41e248883a4af420` | `NativeButtonRecipes` neutral text |
| `OnboardingFooterView.kt` | `89bda4c0eb0dbb22884e751c79789a5419335e0d` | `NativeButtonRecipes` assigned footer, `NativeOnboardingFooterRecipe` and `NativePageIndicator` |
| `NativePermissionRequiredView.kt` | `aa1c512bee1929aa60f5e17fb45ad8c2ad6af9ec` | `NativeButtonRecipes` raised glyph action and `NativeRectangularViewport` |
| `SettingsStorageActionView.kt` | `085c50d95a57eee4d29b331b22a1f987b2c844a1` | `NativeButtonRecipes` raised text and glyph action |
| `ReverbRenameFieldView.kt` | `9dadbe43b395302ebdad60bb2f8d0fd916604c94` | `NativeOutlinedFieldRecipe` and `NativeOutlinedFieldOutline` |
| `NativeRenameFieldMetrics.kt` | `80297895a962be018e5794ed69b2461a197b2f62` | `NativeOutlinedFieldRecipe.height` |
| `SettingsSectionLayouts.kt` | `10a04d3121e7d29be6e37bb2b84a98ca4bd0323d` | `NativePairedFieldRecipe` and `NativePairedFieldLayout` |
| `ReverbRangeExportControls.kt` | `e302ccf117d95a5b64695ed197ef511db4ccad43` | `NativeButtonRecipes.rangeExport` export action and pending indicator role |
| `ReverbLibraryPlayerControls.kt` | `c1974f86149a70fa1dbd67ccd9b93085e08f2f96` | `NativeButtonRecipes.trimActions` joined Cancel and Save action style |

The no-label outline follows AndroidX Material3 1.4.0 and foundation 1.11.2.
The extracted source and test provenance are also recorded in Reverb's
`nativeui/library-sheets/SOURCE.md` and `SOURCE.json`. Button typography follows
the shared inherited body-to-label rule. The new tonal action uses the same
natural geometry with the scheme's secondary container roles.

`NativeHorizontalPagerViewport` preserves the child clip from the same
snapshot's `app/src/main/java/app/smallthingz/reverb/NativeOnboardingPlacedPagerViewport.kt`
(blob `40174d7bd2827e4e47a96b3958b802134c5e8610`). The caller still owns page
placement and the overscroll draw boundary.

Default matching is covered by `NativeButtonRecipeTest` and Reverb's
`NativeRenameFieldMetricsTest`. `NativeSettingsRowIntegrationTest` exercises
selected row fill, foreground, and the separate close action on actual Views.
`NativeOnboardingPresentationIntegrationTest` checks raster geometry and
viewport clipping on Android canvases.
`NativeButtonWindowRecipeIntegrationTest` mounts the range and trim recipes
under default and alternate window typography and checks their native paint
and input admission. It also checks disabled opacity on the independent close
button's external glyph.
These fixtures cover source contracts and require a separate rendering and
interaction qualification run for release or device claims.
