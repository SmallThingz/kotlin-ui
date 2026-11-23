# Reverb glyph provenance

`smallthingz.ui.reverb.ReverbIconPaths` retains the exact AndroidX and Reverb path geometry previously shipped in Reverb. SOURCE.json is the historical generation manifest, not a hash of the relocated Kotlin file.

AndroidX Material Rounded glyphs retain Apache-2.0 (APACHE-2.0.txt). Reverb's OneShot and ExportRange paths retain GPL-3.0 (GPL-3.0.txt). Relocation does not relicense those paths. Theme glyphs use the existing shared renderer and SOURCE-theme.json; ArrowDropDown reuses the shared component mask.

Paths use direct Android Path commands and Float32 geometry, without a runtime vector parser. The shared CachedPathIconDrawable retains the existing cached mask rendering, tint, fit and RTL behavior.
