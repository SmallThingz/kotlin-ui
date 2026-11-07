# Android overscroll provenance

NativeAxisOverscroll consolidates Reverb's scalar horizontal and vertical adaptations of
AndroidX foundation 1.11.2 `AndroidOverscroll.android.kt`. The original source SHA-256 is
`dd166bcb11e9632bfc6b1dd3fd26e75a11b21b6e46158d91551b943fb065c70e`.

NativeEdgeEffectCompat is transferred from Reverb unchanged apart from its package. It
mechanically adapts foundation 1.11.2 `EdgeEffectCompat.android.kt` (package/object rename),
SHA-256 `80ca93369ec21a88339df3086cd88e0024207f1b80df653f9bb9a5c1f62d7965`.
Both retain Android Open Source Project Apache-2.0 provenance. Reverb's complete pinned
source inventory remains in `nativeui/scroll/PROVENANCE.json`.

Physical positive deltas pull the near (left/top) edge. Logical RTL reversal belongs to
the scroll owner. API31 stretch rotates each edge independently and expands the backing
RenderNode only perpendicular to the scroll axis; pre31 glow uses explicit axis-specific transforms.
Measured viewport bounds size edges; Canvas bounds size the backing RenderNode. Density
changes require a new owner. Retained hidden owners are not disposed. Finish a fling only
after normal nested post-fling completion, never in cancellation cleanup.
