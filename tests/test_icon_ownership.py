"""Source-only icon ownership and byte-identical geometry relocation checks."""
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROVENANCE = ROOT / 'docs/resource-provenance/reverb-icons'
SHARED_ICONS = ROOT / 'src/main/kotlin/smallthingz/ui/icons'
REVERB_ICONS = ROOT / 'src/main/adapters/kotlin/smallthingz/ui/reverb/ReverbIconPaths.kt'


class IconOwnershipTest(unittest.TestCase):
    def test_relocated_path_commands_retain_recorded_geometry(self):
        manifest = json.loads((PROVENANCE / 'OWNERSHIP.json').read_text())
        found = {}
        for source in (SHARED_ICONS / 'MaterialRoundedPaths.kt', REVERB_ICONS):
            for match in re.finditer(r'    private val (\w+): Path by lazy \{.*?\n    \}',
                                     source.read_text(), re.S):
                name = match.group(1)
                self.assertNotIn(name, found, 'Duplicate geometry owner')
                found[name] = hashlib.sha256(match.group().encode()).hexdigest()
        extension = json.loads((PROVENANCE / 'credential-visibility.json').read_text())
        self.assertEqual({'visibilityPath', 'visibilityOffPath'}, set(extension['paths']))
        self.assertFalse(set(manifest['paths']) & set(extension['paths']))
        self.assertEqual({**manifest['paths'], **extension['paths']}, found)

    def test_common_api_has_no_reverb_or_runtime_vector_dependency(self):
        for source in SHARED_ICONS.glob('*.kt'):
            text = source.read_text()
            for forbidden in ('smallthingz.ui.reverb', 'androidx.compose', 'PathParser',
                              'createPathFromPathData', 'ImageVector'):
                self.assertNotIn(forbidden, text, str(source))
        self.assertNotIn('PathParser.createPathFromPathData',
                         REVERB_ICONS.read_text())

    def test_browser_parser_oracle_retains_historical_input(self):
        manifest = json.loads((PROVENANCE / 'OWNERSHIP.json').read_text())
        data = (PROVENANCE / 'browser-path.txt').read_bytes()
        self.assertEqual(manifest['browserPathDataSha256'], hashlib.sha256(data).hexdigest())
        oracle = (ROOT / 'src/androidTest/java/smallthingz/ui/icons/MaterialRoundedIconIntegrationTest.kt').read_text()
        self.assertIn(data.decode().strip(), oracle)

    def test_platform_assets_retain_original_bytes(self):
        manifest = json.loads((PROVENANCE / 'OWNERSHIP.json').read_text())
        for item in manifest['platformResources'].values():
            data = (ROOT / 'src/main/res/drawable' / (item['resource'] + '.xml')).read_bytes()
            self.assertEqual(item['sha256'], hashlib.sha256(data).hexdigest())


if __name__ == '__main__':
    unittest.main()
