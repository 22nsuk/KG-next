import json
from pathlib import Path
import shutil
import subprocess
import sys
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


class DocumentationLinksTests(unittest.TestCase):
    def test_package_and_support_links_use_the_fork(self):
        targets = {
            'docs/PACKAGES.md': 'releases',
            'docs/PACKAGES_EN.md': 'releases',
            'docs/TROUBLESHOOTING.md': 'issues',
            'docs/TROUBLESHOOTING_EN.md': 'issues',
        }
        for name, destination in targets.items():
            with self.subTest(document=name):
                text = (ROOT / name).read_text(encoding='utf-8')
                self.assertIn(f'https://github.com/22nsuk/KG-next/{destination}', text)
                self.assertNotIn(f'https://github.com/wimi321/lizzieyzy-next/{destination}', text)
                self.assertNotIn('https://goagent.top/download/', text)

    def test_markdown_checker_detects_and_accepts_chinese_readme_link(self):
        # Exercise the real checker without modifying repository documentation.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'docs').mkdir()
            checker = root / 'scripts/check_markdown_links.py'
            shutil.copy2(ROOT / 'scripts/check_markdown_links.py', checker)
            for source in ROOT.glob('*.md'):
                (root / source.name).write_text('', encoding='utf-8')
            (root / 'README_ZH_CN.md').write_text(
                '[fixture](missing-chinese-guide.md)\n', encoding='utf-8')

            def run_checker():
                return subprocess.run(
                    [sys.executable, str(checker)], cwd=root, capture_output=True,
                    text=True, encoding='utf-8', timeout=10)

            missing = run_checker()
            self.assertEqual(1, missing.returncode, missing.stdout + missing.stderr)
            self.assertIn('README_ZH_CN.md -> missing-chinese-guide.md', missing.stdout)
            (root / 'missing-chinese-guide.md').write_text('', encoding='utf-8')
            valid = run_checker()
            self.assertEqual(0, valid.returncode, valid.stdout + valid.stderr)


if __name__ == "__main__":
    unittest.main()
