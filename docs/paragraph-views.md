# Paragraph Views and plain-text accessibility

`smallthingz.ui.text.view.ParagraphView` renders a retained plain String through the library Paragraph backend. It supports exact Paragraph measurement, baselines, wrapping/overflow and intrinsic sizing. It is not an editor, selectable text container, annotated-link renderer or autosizing TextView. `NativeText` remains the simpler native TextView component; existing consumers do not change automatically.

```kotlin
val label = ParagraphView(
    context,
    TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    Density(context),
    createFontFamilyResolver(context),
    textColor,
)
label.setText("Caller-owned text")
label.setTextLayout(maxLines = 2, overflow = TextOverflow.Ellipsis)
```

Use the corresponding `smallthingz.ui.text`, `smallthingz.ui.text.font`, `smallthingz.ui.text.style` and `smallthingz.ui.unit` imports. Keep one shared resolver and immutable Density implementation per host configuration. On a configuration change, the host supplies a new resolver/density through `setTypography`; on a palette-only change, `animateTextColor` retains measurement. `setTextColor` applies immediate paint for an external frame owner. A host using visible overflow must also permit the required ancestor drawing overflow.

## Optional source-derived accessibility

Unbound labels provide ordinary native label semantics. For a port that needs exact source geometry/traversal, create one `PlainTextAccessibilityWindowScope(root)` per existing window/root and a `NativePlainTextViewGeometry` using explicit clip/transform policies. Bind the label through `bindPlainTextAccessibility` and close the returned lease when that owner retires. A temporary detach does not represent a new semantic identity; use `replacePlainTextAccessibilityIdentity` only when a retained View represents a different logical item.

A clip policy distinguishes known `Unclipped`, known `Rectangle` and unknown (`null`) source correspondence. Unknown geometry does not produce guessed character boxes. Source rectangular clip bounds, transform order, ancestor scrolling and optional root-content clipping must come from the owning layout. Native `clipChildren` defaults or rounded painted outlines are not sufficient evidence for a source semantics clip. Geometry is captured on demand; there is no second semantic tree or process-global registry.

`NativeSourceAccessibilityNode` and its provider represent caller-supplied presence, hidden state, visibility and screen bounds. They do not authorize input or infer screen state. `originalAccessibilityBoundsInView`, `createOriginalSemanticsParagraph` and `isAccessibilityNodePresent` expose the original semantic view for geometry/qualification; `currentParagraph` may instead describe translated display text. Do not substitute one for the other.

`PlainTextAccessibility` can also serve a non-paragraph label/content-description node using a caller-provided `PlainTextAccessibilitySnapshot`; a snapshot without layout/geometry cannot claim line/page/character geometry. The window scope owns only traversal cursor bookkeeping; Android owns actual accessibility focus.

## Merged rows

`MergedRowAccessibility` adapts an existing ViewGroup for BUTTON, SWITCH, TAB or RADIO_BUTTON source semantics. The owner supplies current enabled/checked/selected facts, ordered semantic children and click/long-click callbacks. Install the delegate and append its ordinary non-drawing `roleView`, preserving its measured/layout bounds. The host implements `NativeMergedRowAccessibilityHost` and delegates its `addChildrenForAccessibility` override to this helper. The helper does not replace painted children, manage screen state or build a parallel node provider.

Invoke the currently bound source callback directly from the supplied action function; do not call `View.performClick` again. The delegate emits its source click envelope. Domain labels and action descriptions remain caller-owned. Intrinsic state and role copy uses the library's existing translated state_on/state_off/selected/not_selected/switch_role/tab resources.

## Translation and lifetime limits

API31+ translation keeps distinct original/display caches. Original text remains authoritative for semantics, content capture and translation requests. Translation callbacks do not change permissions or enable any system service. Detached Views retain caches but retire pending observation work as documented by the implementation.

This extraction preserves the existing source-derived behavior and limitations. It does not establish full TalkBack or runtime translation equivalence. Independent native/Paragraph rendering, geometry and host lifecycle fixtures remain necessary for consumers claiming a source-exact port. Source provenance and original hashes are recorded in `SOURCE-paragraph-views.json`.
