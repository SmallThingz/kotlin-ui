package smallthingz.ui.tools

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import smallthingz.ui.components.NativeDarkSurfaceRecipe
import smallthingz.ui.graphics.NOISE_TILE_SIZE
import smallthingz.ui.graphics.generateNoisePixels

/** Build-time transport only. Noise and paint policy remain the runtime library's pure sources. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Expected generated resource directory" }
    val output = File(args.single())
    fun destination(path: String) = File(output, path).also { it.parentFile.mkdirs() }
    val bitmap = BufferedImage(NOISE_TILE_SIZE, NOISE_TILE_SIZE, BufferedImage.TYPE_INT_ARGB)
    bitmap.setRGB(0, 0, NOISE_TILE_SIZE, NOISE_TILE_SIZE, generateNoisePixels(), 0, NOISE_TILE_SIZE)
    check(ImageIO.write(bitmap, "png", destination("drawable-nodpi/native_foreground_noise.png")))
    val fill = Integer.toHexString(NativeDarkSurfaceRecipe.amoledRaisedColor).uppercase()
    destination("values/native_amoled_foreground.xml").writeText("""
        <?xml version="1.0" encoding="utf-8"?>
        <!-- Generated from NativeDarkSurfaceRecipe. Fixed AMOLED identity, not an active window palette. -->
        <resources>
            <color name="native_amoled_foreground_fill">#$fill</color>
        </resources>
    """.trimIndent() + "\n")
    destination("drawable/native_amoled_foreground_background.xml").writeText("""
        <?xml version="1.0" encoding="utf-8"?>
        <!-- Generated from NativeDarkSurfaceRecipe and ProceduralNoise; platform-only inflation. -->
        <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
            <item><shape android:shape="rectangle"><solid android:color="@color/native_amoled_foreground_fill" /></shape></item>
            <item><bitmap android:src="@drawable/native_foreground_noise" android:tileMode="repeat"
                android:alpha="${NativeDarkSurfaceRecipe.amoledForegroundGrain}" android:gravity="fill"
                android:antialias="true" android:filter="true" android:dither="true" android:mipMap="false" /></item>
        </layer-list>
    """.trimIndent() + "\n")
}
