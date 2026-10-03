import json
from pathlib import Path
import shutil
import tempfile
import unittest
from validate_kg_metadata import validate

ROOT = Path(__file__).resolve().parents[1]


class MetadataTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ('README.md', 'src/main/resources/katago-assets.json', 'docs/KG_MODEL_VERIFICATION.json'):
            dst = self.root / name
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, dst)

    def test_current_metadata_matches_verified_model(self):
        validate(self.root)

    def test_rejects_a_different_default_model_hash(self):
        p = self.root / 'src/main/resources/katago-assets.json'
        data = json.loads(p.read_text())
        data['models'][data['defaultModelId']]['sha256'] = '0' * 64
        p.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'sha256 differs'):
            validate(self.root)

    def test_rejects_readme_version_drift(self):
        p = self.root / 'README.md'
        p.write_text(p.read_text().replace('KataGo v1.18.2', 'KataGo v1.18.1'))
        with self.assertRaisesRegex(ValueError, 'metadata missing or outdated'):
            validate(self.root)


if __name__ == "__main__":
    unittest.main()
