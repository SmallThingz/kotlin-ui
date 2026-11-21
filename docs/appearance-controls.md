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

## Grouped surfaces

Use `appearance.groupSurfaces.settingsGroup` for the container around a related
set of Settings rows. Create it once with `recipe.create(context)`, add the
retained rows through `addRow`, and call `recipe.bind(group, colors)` when the
scheme changes. The recipe supplies the field fill and the subtle group border.
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
