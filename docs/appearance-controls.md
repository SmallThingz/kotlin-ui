# Settings and browser controls

Choose an immutable `NativeUiAppearance` when constructing a window's content.
Several controls retain their construction-time geometry and renderer recipes.
Apply a different appearance by constructing the window content with it.

Keep the same appearance and Views across palette-only changes. Resolve the
new colors with `appearance.resolveColorScheme(context, darkTheme)` and pass
them to the component's palette bind. On a font or display configuration
change, obtain `Density(context)` and `createFontFamilyResolver(context)` for
the current window and pass them to the retained controls' typography binds
where available. Recreate a component when its API binds the changed metrics
at construction. Preserve the host's selection and draft state when doing so.

Application resources supply localized labels and descriptions. The application
also owns stable option IDs, validation, permissions, and click callbacks.
Use `colors.pageControls` for general page controls and `colors.settingsControls`
for grouped Settings controls. Pass `colors.switchPalette` to the switch or
toggle row and bind its checked and enabled state.

## Defaults and explicit configuration

The public singleton `NativeDefaultColorScheme : NativeColorSchemeResolver`
is the default for both `NativeUiAppearance()` and `NativeUiAppearance.Default`.
`ControlTheme.resolve(context, dark)` uses that default appearance for common
controls. Dark mode uses `NativeDarkSurfacePreset.Amoled.colorScheme()`: a black
page, 25% foreground lightness, 100% background grain, 50% foreground grain,
and a 1 dp outline at 15% lightness. Light mode starts from raw `NativeColorScheme`
colors and sets `background` and `surface` from the library's
`native_default_light_background` resource. It resolves to `#F3EDF4` below API 31,
`@android:color/system_neutral2_50` on API 31–33, and
`@android:color/system_background_light` on API 34 and later. These values also
apply when the caller selects Light under a night configuration. The resolver
preserves every other Light Material color role and the existing light outline
policy, and adds `NativeGrainPreset(backgroundGrain = 1f, foregroundGrain = .5f)`.

Apps can configure the window theme. Raw Material, gray, tinted and custom
resolvers remain supported, including overrides supplied through `appearance.copy`.
Copying an appearance without supplying `colorScheme` preserves its existing
resolver; the shared default does not replace an explicit choice.

```kotlin
val materialAppearance = NativeUiAppearance(colorScheme = NativeColorScheme)
val grayAppearance = NativeUiAppearance.Default.copy(
    colorScheme = NativeColorScheme.withDarkSurfaces(NativeDarkSurfaces.GRAY),
)
val tintedAppearance = NativeUiAppearance.Default.copy(
    colorScheme = NativeColorScheme.withDarkSurfaces(
        NativeDarkSurfaces.TINTED, tint = 0xff6682dc.toInt(), tintStrength = .45f,
    ),
)
// customResolver implements NativeColorSchemeResolver.
val customAppearance = appearance.copy(colorScheme = customResolver)
```

Each explicit resolver controls its own palette and preset policy. For common
controls, pass the chosen appearance to the public three-argument
`ControlTheme.resolve(context, dark, appearance)` overload. The existing
two-argument overload continues to use `NativeUiAppearance.Default`.

```kotlin
val theme = ControlTheme.resolve(context, dark, tintedAppearance)
val colors = materialAppearance.resolveColorScheme(context, dark)
val recoloredTheme = theme.withColorScheme(colors)
```

`theme.withColorScheme(colors)` supplies an explicit resolved palette while
retaining the window's appearance, typography and density. `ReverbDesign` uses
`NativeDefaultColorScheme`, including its qualified Light backdrop and grain
preset.

The singleton caches the immutable AMOLED resolver and light grain preset.
It still resolves colors for the supplied SDK and resource reader on each call;
resolve again when configuration or system colors change. Grain rendering uses the
shared texture described in [Procedural background grain](../README.md#procedural-background-grain).

The `NativeColorScheme` companion remains the explicit low-level Material oracle.
`rawMaterialNativeColorScheme` access belongs to this explicit raw policy.
Direct `NativeColorScheme.resolve` calls and hand-painted consumers that bypass
appearance resolution cannot automatically restyle. To inherit the selected
appearance, resolve through it and bind the resulting palettes and surface
recipes; a raw color alone does not carry a grain-rendering instruction.

`ZfmTheme` delegates directly to `ControlTheme` and the shared modal/backdrop
recipes, without importing Reverb defaults. It preserves
`ZfmTheme.resolve(context, dark)` and also accepts
`ZfmTheme.resolve(context, dark, appearance)`. Its default appearance therefore
has the common 66 dp compact-toolbar recipe; Reverb explicitly selects 58 dp.
ZFM itself does not construct a toolbar.

## Shared modal surfaces

Configure the color scheme on `NativeUiAppearance` to change the three
surface materials across an app. Pages and ordinary dialogs pair
`palette.background` with background grain. Groups and links use the shared
foreground fill and grain. Context menus and Reverb's off-page recording tab
use the same popup fill and grain. Text, icons, accents, and error colors
continue to use their semantic roles.

Reverb's About panel and discard confirmations inherit the shared modal fill.
A bottom sheet highlights its exposed top edge and rounded corners. A panel
attached at the top highlights its bottom edge. Both use the palette's border
color and the shared thin outline. About's link uses the shared foreground
fill and `onSurface` for the label.

Resolve a sheet's style with
`appearance.actionSheetStyle(density, fontResolver, palette)`. The
factory uses `onSurface` and `onSurfaceVariant` for content and resolves
the grain from the window backdrop seed and background grain intensity.
The default handle is 2 dp high with 16 dp vertical padding.

## Grouped surfaces

Use `appearance.groupSurfaces.settingsGroup` for the container around a related
set of Settings rows. Create it once with `recipe.create(context)`, add the
retained rows through `addRow`, and call `recipe.bind(group, colors)` when the
scheme changes. The recipe supplies the shared foreground fill and the subtle group border.
`colors.pageControls.outline` is the outline role for general page controls.
Set `NativeGroupLayout.LayoutParams.dividerTop` on rows that need a divider.

## Provider selection

`NativeSettingsRow` can hold a trailing selector. The selector's preferred
width comes from its bound labels and icons. When space is tight, the row
places the same selector beneath the title and the selector can wrap its
retained options. Use a title-only row when the choice itself owns activation.

```kotlin
val providerRow = NativeSettingsRow(context, colors.settingsControls)
val providerSelector = appearance.settingsSelectors.createSegmentedControl(
    context, colors.settingsControls, density.density,
)
providerRow.bind(title = localizedProviderTitle)
providerRow.setAccessory(providerSelector)
providerSelector.onSelectionRequest = requestProvider
providerSelector.bind(
    listOf(NativeChoice("CHATGPT", localizedChatGpt), NativeChoice("CLOUDFLARE", localizedCloudflare)),
    selectedProviderId, colors.settingsControls, density.density,
)
```

`requestProvider` receives the stable ID and returns whether the request was
admitted. The owner publishes the selected ID through `bind`. Close the selector
when its host retires.

## Fields and validation

Use `NativeLabeledTextField` for a retained Android editor with a floating label,
optional supporting text, and error semantics. `setError(null)` clears the
validation message. Keep the current `editor`, its text and selection when the
palette or validation changes. Set `editor.inputType` to the appropriate Android
input type for each value, including `TYPE_CLASS_TEXT or
TYPE_TEXT_VARIATION_PASSWORD` for a password. Keep validation and draft ownership
in the app.

Use `appearance.joinedFields` for vertically joined editors. The group owns
the perimeter and divider, including when the fields receive focus or errors.
Add `fields.view` to the screen. `fields.bindPalette(colors)` updates the
retained fields and their validation paint when the scheme changes.

```kotlin
val recipe = appearance.joinedFields
val tunnelId = recipe.createField(context, colors, localizedTunnelId)
val runtimeKey = recipe.createField(context, colors, localizedRuntimeKey, editor = existingKeyEditor)
runtimeKey.editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
val fields = recipe.createGroup(context, colors, tunnelId, runtimeKey)
tunnelId.setError(tunnelIdError)
runtimeKey.setError(runtimeKeyError)
```

For two side-by-side field views, use `appearance.pairedFields.createFields`
with the two existing views and an optional supporting view. Mount that body in
`appearance.pairedFields.createSurface`. `bindSurface` applies the scheme to the
retained group. For a no-label, single-line outline drawn around a custom editor,
`appearance.outlinedField` supplies text styles, sizing, selection color, and a
retained `NativeOutlinedFieldOutline`. The owner feeds focus/error state and
closes that outline when its editor retires.

## Toolbar and permission actions

`NativeMergedActionLayout` retains any number of action views in one surface and
stacks them when their labels need more width. Its existing constructor keeps a
compact trailing group; the five-argument constructor accepts `fillAvailableWidth`
for full-width rows and equal horizontal shares. Reverb's `actionPair` uses this
shared full-width mode, including when only one action is visible.

`NativeThemedToolbarAction` is the shared themed toolbar action: it binds the
palette's surface preset, contrast-aware ink, border/fill transitions and
predictive glyph preview while retaining one input owner. Pass the chosen
`appearance.toolbars` as its style to keep app configuration explicit.

`NativeCenteredTopBarLayout(context, density.density, back, title, actions)`
measures the title separately from its navigation and action slots. Use a
`ParagraphView` with `appearance.typography[NativeTypographyRole.TITLE_LARGE]`
for a title that can wrap. `NativeTopBarAction` handles an icon action's target,
press indication, and semantics. The app supplies the glyph, accessible action
name, and callback. A horizontal action slot can retain distinct Undo and Apply
controls. For labeled toolbar actions, choose `appearance.buttons.neutralText`
and `appearance.buttons.filled` and bind enabled state from the app's draft.

Use `NativeToggleRow` when one tap requests a Boolean change. For permissions
that open an Android grant or settings flow, use `appearance.detailCards.createGroup`
with `NativePermissionRow`. Supply the localized title, status, and glyph assets.
`bindPermission` gives a granted row a readable status and suppresses its request
action. Rebind after the platform reports its authoritative state.

Set `independentAccessory = true` when the trailing child is its own button.
Bind its localized description, enabled state and callback independently. Leave
`onRequest` empty when the trailing button owns the grant flow. The row remains
readable and the accessory remains separately reachable through keyboard and
accessibility focus.

## Browser tabs

For an address or search editor, `appearance.compactField` supplies the raised
fill, body typography, padding, and spacing for relative compound drawables.
It can construct a new `NativeTextField` or bind an existing standalone editor.

```kotlin
val recipe = appearance.compactField
val address = recipe.createField(context, colors)
address.hint = localizedAddressHint
address.setCompoundDrawablesRelativeWithIntrinsicBounds(addressGlyph, null, null, null)
```

Call `recipe.bind(address, colors)` when the palette changes or when adopting
the recipe on a retained editor. Keep input type, single-line mode, draft text,
selection, and IME actions with the app. Android continues to own its native
hint when the editor uses compound drawables.

For a standalone New Tab action, bind a `NativeMaterialButtonView` with
`appearance.buttons.filled` and `setLeadingIcon` as usual. Mount
`NativeButtonTouchTarget(newTabButton)` so the Android parent can deliver
finger input in the surrounding minimum target. The underlying button still
paints at its intrinsic height with normal-sized text. Animate the wrapper if
the entire action moves, and close the button when its owner retires.

Bind each tab's title, optional site text and icon to one `NativeSettingsRow`.
Use the row click to select that tab ID and `isSelected` to publish its
selection. The row owns the surface and samples its title, detail, value and
leading-icon ink together. Pass the close glyph's foreground binding to
`setAccessory` to use the sampled row surface during selection and palette
transitions. The accessory keeps its own click, enabled, and selected state.
Construct the row with `containerFill = true` when the group can animate its
palette independently. A transparent row uses `colors.settingsControls.surface`
as its underlay, so its group should paint that same surface color.

```kotlin
val tabRow = NativeSettingsRow(context, colors.settingsControls, NativeSettingsRowStyle(containerFill = true))
tabRow.bind(tab.title, detail = tab.siteName, icon = tabIcon) { selectTab(tab.id) }
tabRow.isSelected = selectedTabId == tab.id
val closeGlyph = ImageView(context).apply { setImageDrawable(closeIcon) }
val closeButton = NativeMaterialButtonView(
    context, appearance.buttons.raisedIcon, density, resolver, onClick = { closeTab(tab.id) },
).apply {
    contentDescription = context.getString(closeTabStringId, tab.title)
    setLeadingView(closeGlyph)
    bind("", closeEnabled, colors.buttonPalette)
}
tabRow.setAccessory(closeButton) { ink -> closeButton.setLeadingImageTint(ink) }
```

Resolve the button's localized description with the tab title so Android can
identify the close action separately. `setLeadingView` leaves the external
glyph's tint with the row binding. `setLeadingImageTint` uses the button
recipe's content opacity, including after a change to the button's own enabled
state. Rebind enabled state and actions independently of the row.


## Shared foreground bindings

`smallthingz.ui.components.NativeForeground` binds the window palette to retained
progress indicators, toggle rows, groups, surfaces, buttons and plain
foreground drawables. It uses the same foreground intensity independently of page
or document texture. Pass the resolved window's `palette.pageControls`; rebind when
the palette changes. Colors, progress values, input and lifecycle remain with the
existing component owner. `floatingSurfaceStyle(palette, appearance)` accepts an
explicit appearance; omitting it uses the common default.

`NativeForegroundGrain` supports caller-painted enclosures such as recycler
item decorations. Keep one instance per drawing View for that window's lifetime,
bind its palette and draw after the base fill using the same bounds and radius.
It retains the drawing owner even while flat, so a later grain enablement schedules
its redraw. It reuses the shared texture and retained grain layer. This is not a
process-global View cache.

The existing `AllDocAppearance` and `AllDocHomeGroupGrain` APIs delegate to these
common implementations. Reverb's circular and linear progress consumers bind the
same window preset on creation and retained palette changes. A zero-width circular
hairline keeps its original stroke and skips grain when Android cannot provide a
fillable stroke outline. No frame-time or RAM improvement is claimed from extraction.
