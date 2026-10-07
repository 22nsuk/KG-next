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


class LocalizedReadmeLinksTests(unittest.TestCase):
    READMES = tuple(ROOT / f'README_{locale}.md' for locale in ('EN', 'JA', 'TH', 'ZH_CN', 'ZH_TW'))

    def test_repository_badges_and_contributors_use_the_fork(self):
        for path in self.READMES:
            with self.subTest(readme=path.name):
                text = path.read_text(encoding='utf-8')
                for kind in ('v/release', 'stars', 'downloads'):
                    self.assertIn(f'https://img.shields.io/github/{kind}/22nsuk/KG-next', text)
                    self.assertNotIn(f'https://img.shields.io/github/{kind}/wimi321/', text)
                self.assertIn('https://contrib.rocks/image?repo=22nsuk/KG-next', text)
                self.assertNotIn('https://contrib.rocks/image?repo=wimi321/', text)

    def test_chinese_navigation_reaches_the_chinese_document(self):
        for path in self.READMES:
            with self.subTest(readme=path.name):
                text = path.read_text(encoding='utf-8')
                self.assertNotIn('<a href="README.md">简体中文</a>', text)
                if path.name != 'README_ZH_CN.md':
                    self.assertIn('<a href="README_ZH_CN.md">简体中文</a>', text)
                self.assertTrue((path.parent / 'README_ZH_CN.md').is_file())

    def test_download_entries_identify_fork_and_upstream_separately(self):
        for path in self.READMES:
            with self.subTest(readme=path.name):
                text = path.read_text(encoding='utf-8')
                self.assertNotIn('pan.baidu.com', text)
                self.assertNotIn('https://goagent.top/download/', text)
                self.assertRegex(text, r'<a href="https://github\.com/22nsuk/KG-next/releases"><strong>[^<]*KG-next[^<]*</strong></a>')
                self.assertRegex(text, r'<a href="https://goagent\.top/"><strong>[^<]*LizzieYzy Next[^<]*</strong></a>')
                self.assertIn('alt="LizzieYzy Next (upstream)"', text)


if __name__ == "__main__":
    unittest.main()
