import re
import struct
import unittest
import zlib

import generate_reverb_brand_plate as plate


class BrandPlateTest(unittest.TestCase):
    def sources(self):
        return [(plate.ROOT / name).read_text(encoding="utf-8") for name in plate.SOURCE_FILES]

    def test_all_brand_fills_alias_the_canonical_foreground_resource(self):
        *palette, _ = self.sources()
        colors, _ = plate.exported_colors(*palette)
        self.assertEqual({fill for fill, _, _ in colors.values()},
                         {"@color/native_amoled_foreground_fill"})

    def test_grain_opacity_follows_the_canonical_foreground_recipe(self):
        scheme, recipe, preset, defaults, _ = self.sources()
        recipe, changed = re.subn(r"amoledForegroundGrain = \d*\.?\d+f",
                                  "amoledForegroundGrain = .23f", recipe)
        self.assertEqual(changed, 1)
        _, opacity = plate.exported_colors(scheme, recipe, preset, defaults)
        self.assertEqual(opacity, "0.23")
        preset = preset.replace("foregroundGrain = NativeDarkSurfaceRecipe.amoledForegroundGrain",
                                "foregroundGrain = .5f")
        with self.assertRaisesRegex(ValueError, "Shared paint contract changed"):
            plate.exported_colors(scheme, recipe, preset, defaults)

    def test_resource_modes_share_all_three_dark_paint_roles_on_each_sdk_range(self):
        *palette, _ = self.sources()
        colors, _ = plate.exported_colors(*palette)
        for sdk in ("", "-v31", "-v34"):
            with self.subTest(sdk=sdk):
                self.assertEqual(colors["values" + sdk], colors["values-night" + sdk])
        # Light roles can be customized without changing the fixed brand exports.
        palette[0] = palette[0].replace("0xFF1D1B20", "0xFF000000").replace(
            "system_on_surface_light", "system_background_light")
        self.assertEqual(colors, plate.exported_colors(*palette)[0])

    def test_raw_png_preserves_the_first_central_sample_without_preset_opacity(self):
        png = plate.grain_png(self.sources()[-1])
        self.assertEqual(struct.unpack_from("!II", png, 16), (plate.ICON_TILE_SIZE,) * 2)
        self.assertEqual(png[37:41], b"IDAT")
        length = struct.unpack_from("!I", png, 33)[0]
        pixels = zlib.decompress(png[41:41 + length])
        self.assertEqual(pixels[:3], bytes((0, 0, 15)))  # Filter byte, black, alpha 0x0f.

    def test_unrecognized_sampler_change_cannot_silently_keep_old_pixels(self):
        source = self.sources()[-1].replace("state = state xor", "state = state +", 1)
        with self.assertRaisesRegex(ValueError, "Shared paint contract changed"):
            plate.grain_png(source)
