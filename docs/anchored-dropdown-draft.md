# Anchored dropdown draft

Branch: `draft/anchored-dropdown-20261123`. This draft is not on main and is not enabled in Reverb.

`NativeAnchoredDropdown.createField` creates the existing `NativeDropdown` with the library's appearance, typography, icons and color scheme. `NativeDropdownPair` and `NativeGroupLayout` produce Reverb's audio-option group: Channels, paired Sample format / Sample rate, paired Audio source / Input device. The example application has no custom control painting.

## Interaction contract

- The selected slot keeps the trigger's physical bounds, including the rounded outer corners and square shared joins.
- Opening expands a connected outline above and below the slot and dims the background.
- Natural option order is preferred. Overflow rotates the finite list: `3, 1, 2` is exactly three rows, never `3, 1, 2, 3`.
- The slot is the selector. A vertical gesture snaps to at most one adjacent row; the selection callback fires when that step settles. Keyboard and accessibility forward/back actions make the same one-row step.
- Tapping the current slot closes. Tapping another visible row slides that option into the slot and collapses in one animation, then commits the selection.
- Back, Escape and an outside tap close at the currently accepted slot. Scrolled choices are retained; dismissal does not revert to the opening value.
- Reaching an end shrinks the exposed empty side. Scrolling cannot create options or wrap again.
- ValueAnimator follows the platform animator duration scale, including disabled animations. Audio/recording is not involved in the demo.
- The owner closes the popup on screen stop/disposal. An anchor that detaches, moves or changes size retires the old popup. Selection callbacks may replace the host safely.

## Integration

Create one host per visible screen using its `NativeUiAppearance`, resolved `NativeColorScheme`, `Density` and font resolver. Each returned field retains typed option identity and caller-provided/localized labels. Update the field binding after `onSelected`; dispose fields and the host with their screen. Keep source mutations outside rendering callbacks.

The expansion recipe is `NativeUiAppearance.anchoredDropdown`. Existing components keep their existing behavior unless this host is used.

## Example

`dropdown-demo` is an Android application depending directly on this checkout's root library project. It includes top/bottom edge examples, the real library's audio group, long sample-rate options, light/dark palettes and an RTL layout switch. The application performs no microphone, file or network operations.

Build: `./gradlew :dropdown-demo:assembleDebug` with Android SDK 37 and JDK 21. Run the pure ordering cases with `./gradlew :testDebugUnitTest --tests smallthingz.ui.components.NativeAnchoredDropdownPlanTest`.
