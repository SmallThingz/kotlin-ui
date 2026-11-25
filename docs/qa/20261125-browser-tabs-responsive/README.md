# Browser tab cards in short hosts

Candidate directly based on library `5c443299e9c7a2917222850431cc4ebec5e7e8e9`.
Fresh published main `e3a40dee1edfdb01f050c21032e91b3d6ad2fe99` had byte-identical
BrowserPanelTabs source; no unrelated upstream changes are included.

The old card puts Close in the preview below the title and forces preview height
from width alone. Root's actual blank overview at 1000x782 / 421dpi shows the
card continuing behind New tab. The correction puts the existing Close action
beside the ellipsized title, reserves its 48dp target plus 8dp end/gap space,
and measures the preview from the remaining scroll viewport after the actual
header, grid padding and card margins. The dock already owns bottom insets.
Header text/actions keep their natural height; if the header cannot fit, it
remains scrollable and the preview has zero height. Additional card rows scroll.

No public API changes. `tabPreviewBounds(key)` still returns the actual preview.
Close is now outside it: geometry assertions must verify the full close component
in the header beside the separate SELECT row, logically END in LTR/RTL, with its
bottom no lower than preview.top. Discover existing named glyph/component and
public row/titleView; no private indexes or reflection are required. The existing
Control test requiring preview containment encoded the old layout and is being
updated independently by its owner.

## Host evidence

Seven focused JUnit geometry tests PASS (pure Kotlin only): short viewport
regression including old-algorithm overflow; tall-short-tall at constant width;
bottom inset budget; larger header and tiny viewport; unbounded/tall limits;
multiple rows; zero width and other densities. Source checks confirm unchanged
public methods/card column parameters, dispatch/bind/retirement, swipe setup and
bitmap top-fit code. No Android/App build or device actions were performed.

Retained host commands/results: `/dev/shm/codex-browser-tabs-responsive-20261125/`
`check.py` and `checks-01/`. Result SHA256: cd2436fb89edfb68dc91cc6adad2a07f6cee5bf6932f4ec3a18098acbfdd2a6c.
Independent source-only review: Planck `review-9ee6.json`, SHA256
`5aca31d8e89493d8b434207cf9dae88094c59c76ea2a1a025c975c2969266a1f`.
No concrete normal EXACT-height path blocker; this is not runtime acceptance.

Pending combined Android validation: actual short-host first-card fit, same-width
height/inset remeasure, RTL/long text/font changes, tiny-host scrolling, separate
SELECT/CLOSE accessibility and close-only dispatch. Arithmetic checks do not prove
View/ScrollView/GridLayout convergence, particularly AT_MOST preliminary passes,
or hit/accessibility behavior. Only a blank overview was observed; no actual
web-preview or screenshot-perfect outcome is claimed.
