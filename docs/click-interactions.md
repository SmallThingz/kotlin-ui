# Explicit View click, ripple and elevation owners

`smallthingz.ui.interaction.view` provides independent input and indication owners for an existing Android View. `NativeToggleRow` uses these owners for the checked action, with `NativeMaterialSwitchView` drawing the switch.

## Shared material button

`NativeMaterialButtonView` supplies click handling, ripple, accessibility and a capsule shadow for a filled button by default.

```kotlin
val save = NativeMaterialButtonView(context, true, textStyle, density, resolver,
    NativeMaterialButtonStyle(horizontalPaddingDp = 24)) { onSave() }
save.bind(saveLabel, enabled = canSave, container = accentColor, contentColor = onAccentColor)
```

Supply `NativeButtonInputPolicy` when the button belongs to a custom native input root or a recycled row. An optional `revisionKey` produces the current gesture owner identity. Changing that key at `bind` cancels held input, while rebinding the same key keeps the gesture and dispatches to the current callback. A null key suspends gesture admission. A finite `radiusDp` uses the rounded style without elevation. The enclosing owner can delay a press for an interested parent and should close the button on retirement.

## Click admission and interaction identity

Construct `ViewClickController(host, environment, indication)` once for the logical host. `NativeClickEnvironment` supplies actual down admission, interested-parent information, long-press ownership and minimum touch dimensions. `NativeClickIndication` receives identity-bound press/release/cancel and hover/focus tokens. The host forwards its touch, key, hover, focus and lifecycle callbacks; it also supplies pointer bounds on actual pointer passes. The controller does not synthesize accessibility clicks, set native pressed state or build a new event tree.

`NativeClickBinding(ownerKey, enabled, onClick, onLongClick, hapticFeedbackEnabled)` separates stable owner identity from current actions. Value-equal immutable owner keys keep a gesture owner; recycled identities must receive a new key. Rebinding enabled/long-click capability follows the existing cancellation rules. `detached` retires interactions and clears the binding; a new active owner must bind explicitly. `cancel` does not invent focus loss. The controller intentionally has no double-click policy.

Delayed presses, long-click scheduling, key cancellation and queued indication delivery are distinct from ordinary platform Button drawable state. Preserve input-root/parent coordination; do not substitute a guessed rectangle or treat every pager as interested. An indication adapter may deliver the same admitted tokens to multiple existing visual owners. Tokens are created by the owner, not forged by consumers.

## Row indication

`RowIndication.install(drawingHost, contentColor, durationScale)` decorates the existing foreground. Install it before any wrapper that adds a separator/outline. Forward measured bounds/density and content-color changes. `RowIndicationSink` maps input-local positions into the drawing host and pairs hover/focus state tokens by identity. Input and drawing hosts can differ without creating another View.

This implementation preserves native RippleDrawable behavior plus the source state-layer/animation timing. Its private alpha helper replaces the alpha channel using the original Float clamp/rounding order; it does not multiply the source alpha. The module does not depend on an app palette. Retire/reset the indication with the host's existing lifecycle rather than dropping queued interactions silently.

## Filled-button elevation

`FilledButtonElevation` attaches to the exclusively owned ordinary shadow host, with the caller supplying the actual elevation property setter. Feed the same admitted interaction identities to this owner and the ripple. Latest active interaction start wins; ending an older token does not reorder newer active interactions. Default source targets are hover1dp and other states0dp. Do not infer these interactions from drawable state.

`configureButtonShadowHost` installs only the capsule outline/shadow policy. It disables competing native state-list elevation and does not clip the separate content host or supply fill/border. `FilledButtonElevationState` is the pure state/math seam for a caller that owns its own frame delivery; it shares the exact implementation with the View controller. The app's decision that a particular Continue button is filled and a Back button has no elevation is intentionally outside this package.

Attachment/detachment represents the documented logical ownership lifetime. Retaining an original logical owner through a temporary window detach requires a deliberate host policy and qualification; the helper does not infer that policy from visibility. Owner retirement invalidates earlier interaction tokens.

## Duration-scale policy

`readCachedAnimatorDurationScale` preserves the process-scoped cached observer/retry behavior used by this source-derived interaction family below API33. It retains application ContentResolver state only; frame reads use the cache, and transient provider failures keep the last value with bounded retries. API33+ uses the public ValueAnimator getter. Read/register failure handling remains explicit.

This is not interchangeable with the existing library direct duration reader, whose old-API access, fallback and invalid-value normalization differ. Neither implementation is silently substituted for the other. Callers may supply an explicit durationScale lambda to RowIndication to preserve their own frame-context policy.

## Copy, errors and qualification

This cluster owns no user-facing labels or accessibility role descriptions. Actions, shape policies, domain enabled state and all text/translations remain with the host or the existing library component that owns them.

Callback ordering, exceptions and cancellation remain the extracted source behavior; no new exception swallowing, synthetic clicks, input admission fallback or global listener registry is added. The extraction does not establish new actual-window input, render-thread ripple, elevation raster or temporary-detach parity. Existing independent input/elevation fixtures are still required. Original sources and exact hashes are recorded in `SOURCE-click-interactions.json`; source headers and the Apache license accompany the implementation.
