package smallthingz.ui.reverb

import android.graphics.Paint
import android.graphics.Path
import smallthingz.ui.components.NativePathIconMask

// Permission semantics retain Control's existing notification and battery glyphs. Accessibility:
// Google Material Icons, Apache-2.0, material-design-icons 10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7,
// src/action/accessibility_new/materialicons/24px.svg. All geometry is cached once in the library.
internal fun permissionOnboardingGlyph(kind: ReverbPermissionOnboardingKind): NativePathIconMask = when (kind) {
    ReverbPermissionOnboardingKind.NOTIFICATIONS -> notificationGlyph
    ReverbPermissionOnboardingKind.ACCESSIBILITY -> accessibilityGlyph
    ReverbPermissionOnboardingKind.BATTERY -> batteryGlyph
}

private val notificationGlyph by lazy { NativePathIconMask(Path().apply {
    moveTo(12f, 22f); cubicTo(13.1f, 22f, 14f, 21.1f, 14f, 20f)
    lineTo(10f, 20f); cubicTo(10f, 21.1f, 10.89f, 22f, 12f, 22f); close()
    moveTo(18f, 16f); lineTo(18f, 11f); cubicTo(18f, 7.93f, 16.36f, 5.36f, 13.5f, 4.68f)
    lineTo(13.5f, 4f); cubicTo(13.5f, 3.17f, 12.83f, 2.5f, 12f, 2.5f)
    cubicTo(11.17f, 2.5f, 10.5f, 3.17f, 10.5f, 4f); lineTo(10.5f, 4.68f)
    cubicTo(7.63f, 5.36f, 6f, 7.92f, 6f, 11f); lineTo(6f, 16f)
    lineTo(4f, 18f); lineTo(4f, 19f); lineTo(20f, 19f); lineTo(20f, 18f); close()
}) }

private val accessibilityGlyph by lazy { NativePathIconMask(Path().apply {
    moveTo(20.5f, 6f); rCubicTo(-2.61f, .7f, -5.67f, 1f, -8.5f, 1f)
    rCubicTo(-2.83f, 0f, -5.89f, -.3f, -8.5f, -1f); lineTo(3f, 8f)
    rCubicTo(1.86f, .5f, 4f, .83f, 6f, 1f); lineTo(9f, 22f); lineTo(11f, 22f)
    lineTo(11f, 16f); lineTo(13f, 16f); lineTo(13f, 22f); lineTo(15f, 22f); lineTo(15f, 9f)
    rCubicTo(2f, -.17f, 4.14f, -.5f, 6f, -1f); rLineTo(-.5f, -2f); close()
    moveTo(12f, 6f); rCubicTo(1.1f, 0f, 2f, -.9f, 2f, -2f)
    rCubicTo(0f, -1.1f, -.9f, -2f, -2f, -2f); rCubicTo(-1.1f, 0f, -2f, .9f, -2f, 2f)
    rCubicTo(0f, 1.1f, .9f, 2f, 2f, 2f); close()
}) }

private val batteryGlyph by lazy {
    val outline = Path().apply {
        moveTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 6f); lineTo(17f, 6f)
        lineTo(17f, 21f); lineTo(7f, 21f); lineTo(7f, 6f); lineTo(9f, 6f); close()
    }
    val filled = Path()
    Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeJoin = Paint.Join.ROUND }.getFillPath(outline, filled)
    filled.addPath(Path().apply {
        moveTo(12.8f, 7f); lineTo(9.5f, 13f); lineTo(12f, 13f)
        lineTo(11.2f, 18f); lineTo(14.5f, 12f); lineTo(12f, 12f); close()
    })
    NativePathIconMask(filled)
}
