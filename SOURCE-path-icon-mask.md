# Typed native icon masks

The generic paint reset and path draw order are based on the first-party mask logic at Reverb `88446459ac2ce5e43ad224eb27af18fa80208e98`, `app/src/main/java/app/smallthingz/reverb/nativeui/ReverbIconPaths.kt`. Its source SHA-256 is `f27d6bce9519e639ea3d0dfad6b4a75e0041e80b3b8416a45024b92e08eb23ae`.

`NativePathIconMask` consumes paths supplied by the app and draws one or two ordered parts with fill or round stroke before the existing ALPHA_8 mask composite. The actual generated Reverb and AndroidX glyph commands and their licenses stay in the original app asset source. The claim here covers only the generic mask painting and wiring.

The Browser glyph uses Google's filled Material Icons Language 24px path
under Apache-2.0
([source](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/src/action/language/materialicons/24px.svg)).
