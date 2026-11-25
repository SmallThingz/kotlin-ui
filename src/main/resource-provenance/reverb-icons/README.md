# Icon provenance

The recommended shared API is `smallthingz.ui.icons.MaterialRoundedIcon`.
Generated AndroidX commands live in `icons/MaterialRoundedPaths.kt`; Reverb domain
artwork remains in `reverb/ReverbIconPaths.kt`. Theme and dropdown adapters share
these masks. See [conventions](../../../../docs/icons.md) for mappings and exceptions.

SOURCE.json is the original generation manifest, not a hash of the relocated
Kotlin file. AndroidX Material Rounded 1.7.8 retains Apache-2.0 (APACHE-2.0.txt).
Reverb's OneShot and ExportRange retain GPL-3.0 (GPL-3.0.txt). Relocation does not
relicense artwork. OWNERSHIP.json records path-block and moved platform-resource
hashes against the baseline commits. Browser's historical filled Language input
is retained in browser-path.txt; its direct expansion has a pixel parity test.

The shared CachedPathIconDrawable retains cached mask rendering, tint, fit and RTL.
