# Anchored dropdown draft

Branch: `draft/anchored-dropdown-20261123`. This draft is not on main and is not enabled in Reverb.

`NativeAnchoredDropdown.createField` creates the existing `NativeDropdown` with the library's appearance, typography, icons and color scheme. `NativeDropdownPair` and `NativeGroupLayout` produce Reverb's audio-option group: Channels, paired Sample format / Sample rate, paired Audio source / Input device. The example application has no custom control painting.

## Interaction contract

- The selected slot keeps the trigger's physical bounds, including the rounded outer corners and square shared joins.
- Opening expands a connected outline above and below the slot and dims the background.
- Natural option order is preferred. Overflow rotates the finite list: `3, 1, 2` is exactly three rows, never `3, 1, 2, 3`.
- The slot is the selector. A vertical gesture can cross multiple rows and snaps to a whole row on release. The selection callback follows each row entering the slot. Keyboard and accessibility forward/back actions advance one row.
- Tapping the current slot closes. Tapping another visible row commits that choice and slides it into the slot while collapsing. A new tap during collapse reverses it instead of being swallowed.
- Back, Escape and an outside tap close at the currently accepted slot. Scrolled choices are retained; dismissal does not revert to the opening value.
- Reaching an end shrinks the exposed empty side. Scrolling cannot create options or wrap again.
- Top and bottom corner radii blend continuously between the group's joined shape and the expanded outline as an endpoint reaches the slot.
- ValueAnimator follows the platform animator duration scale, including disabled animations. Audio/recording is not involved in the demo.
- The owner closes the popup on screen stop/disposal. An anchor that detaches, moves or changes size retires the old popup. Selection callbacks may replace the host safely.

## Integration

Create one controller per visible screen using its outer `FrameLayout`, `NativeUiAppearance`, resolved `NativeColorScheme`, `Density` and font resolver. The explicit frame must sit outside scrolling/clipping content. The library mounts a modal layer in that same window, suppresses/restores sibling accessibility and focus, and handles Back. There is no separate popup window or cancelled first draw. Each returned field retains typed option identity and caller-provided/localized labels. Update the field binding after `onSelected`; dispose fields and the controller with their screen. Keep source mutations outside rendering callbacks.

The expansion recipe is `NativeUiAppearance.anchoredDropdown`. Existing components keep their existing behavior unless this host is used.

Option rows retain library typography and input semantics. Immutable palette/icon assets are shared. Only rows entering the current reveal are measured and laid out; rows outside the reveal skip drawing. These remove avoidable opening work, but device timing remains separate evidence from source allocation counts.

New taps reverse from the current reveal and position, using duration proportional to remaining travel. One gesture cannot be handed between two platform windows. The layer is synchronously laid out before the opening click returns, so another tap can reach it before the next frame.

## Example

`dropdown-demo` is an Android application depending directly on this checkout's root library project. It includes top/bottom edge examples, the real library's audio group, long sample-rate options, light/dark palettes and an RTL layout switch. The application performs no microphone, file or network operations.

Build: `./gradlew :dropdown-demo:assembleDebug` with Android SDK 37 and JDK 21. Run the pure ordering cases with `./gradlew :testDebugUnitTest --tests smallthingz.ui.components.NativeAnchoredDropdownPlanTest`.
