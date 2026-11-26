import re
import struct
import unittest
import zlib

import generate_reverb_brand_plate as plate


class BrandPlateTest(unittest.TestCase):
    def sources(self):
        return [(plate.ROOT / name).read_text(encoding="utf-8") for name in plate.SOURCE_FILES]

    def test_amoled_level_rounds_the_kotlin_float_literal_before_multiplication(self):
        scheme, dark_surfaces, recipe, preset, defaults, groups, _ = self.sources()
        recipe, changed = re.subn(r"amoledForegroundLightness = \d*\.?\d+f",
                                  "amoledForegroundLightness = .0075f", recipe)
        self.assertEqual(changed, 1)
        colors, _ = plate.exported_colors(scheme, dark_surfaces, recipe, preset, defaults, groups)
        # Binary32 arithmetic yields 0.4999999403953552; a Double literal rounds up instead.
        self.assertEqual(colors["values-night"][0], "#000000")

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
