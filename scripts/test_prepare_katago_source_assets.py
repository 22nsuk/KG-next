import json
import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

import test_stage_katago_source_release as fixtures
from katago_asset_catalog import DEFAULT_CATALOG, validate_catalog
from prepare_katago_source_assets import DESTINATIONS, download, inventory_digest, prepare, unpack


class PrepareSourceAssetsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.SourceReleaseTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.catalog = self.fixture.run_stage()
        self.root = self.fixture.root
        self.catalog_path = self.root / "release/katago-assets.json"
        self.engines = self.root / "engines"

    def archive(self, target):
        return self.root / "release" / self.catalog["assets"][target]["assetName"]

    def prepare(self, targets):
        with mock.patch("prepare_katago_source_assets.download", side_effect=lambda c, t, p: self.archive(t)):
            prepare(self.catalog_path, targets, self.root / "cache", self.engines)

    def official_cuda13_archive(self, *, missing_zlib=False, unsafe_member=None, wrong_executable=False,
                                symlink_member=False):
        asset = self.catalog["assets"]["windows-nvidia-cuda13"]
        archive = self.archive("windows-nvidia-cuda13")
        executable = b"official CUDA13 test engine"
        with zipfile.ZipFile(archive, "w") as opened:
            opened.writestr("katago.exe", executable)
            opened.writestr("default_gtp.cfg", b"official gtp")
            opened.writestr("analysis_example.cfg", b"official analysis")
            if not missing_zlib:
                opened.writestr("z.dll", b"official dynamic zlib")
            if unsafe_member:
                opened.writestr(unsafe_member, b"unsafe")
            if symlink_member:
                link = zipfile.ZipInfo("linked.dll")
                link.external_attr = 0o120777 << 16
                opened.writestr(link, b"z.dll")
        asset.update(sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
                     sizeBytes=archive.stat().st_size,
                     executableSha256="0" * 64 if wrong_executable else hashlib.sha256(executable).hexdigest())
        with zipfile.ZipFile(archive) as opened:
            asset["inventorySha256"] = inventory_digest([
                dict(file=name, sizeBytes=opened.getinfo(name).file_size,
                     sha256=hashlib.sha256(opened.read(name)).hexdigest()) for name in sorted(opened.namelist())])
        self.catalog_path.write_text(json.dumps(self.catalog), encoding="utf-8")
        return archive

    def test_official_cuda13_is_explicit_separate_and_keeps_dynamic_zlib(self):
        self.official_cuda13_archive()
        self.prepare(["windows-cpu", "windows-nvidia-cuda13"])
        optional = self.engines / "windows-x64-nvidia-cuda13"
        self.assertEqual(b"official dynamic zlib", (optional / "z.dll").read_bytes())
        manifest = (optional / "lizzieyzy-next-katago-engine-manifest.txt").read_text()
        self.assertIn("Origin: official-release", manifest)
        self.assertIn("Zlib linkage: dynamic", manifest)
        self.assertNotIn(self.catalog["katagoSourceCommit"], manifest)
        self.assertFalse((self.engines / "windows-x64-nvidia").exists())
        self.assertEqual("gtp test config", (self.engines / "configs/gtp.cfg").read_text())

    def test_official_cuda13_rejects_bad_executable_missing_zlib_and_unsafe_archive_before_replace(self):
        destination = self.engines / "windows-x64-nvidia-cuda13"
        destination.mkdir(parents=True)
        (destination / "katago.exe").write_bytes(b"previous engine")
        cases = ((dict(wrong_executable=True), "trusted catalog"),
                 (dict(missing_zlib=True), "missing executable, zlib"),
                 (dict(unsafe_member="../escaped.dll"), "unsafe relative"),
                 (dict(unsafe_member="Z.DLL"), "duplicate file"),
                 (dict(symlink_member=True), "cannot contain symlinks"))
        for arguments, message in cases:
            with self.subTest(arguments=arguments):
                self.official_cuda13_archive(**arguments)
                with self.assertRaisesRegex(ValueError, message):
                    self.prepare(["windows-nvidia-cuda13"])
                self.assertEqual(b"previous engine", (destination / "katago.exe").read_bytes())
                self.assertFalse((self.root / "escaped.dll").exists())

    def test_official_cuda13_download_uses_override_even_with_github_credentials(self):
        archive = self.official_cuda13_archive()

        def fake_download(command, **kwargs):
            self.assertEqual("curl", command[0])
            Path(command[command.index("--output") + 1]).write_bytes(archive.read_bytes())

        with mock.patch.dict(os.environ, {"GH_TOKEN": "test fixture"}), mock.patch(
                "prepare_katago_source_assets.subprocess.run", side_effect=fake_download) as run:
            downloaded = download(self.catalog, "windows-nvidia-cuda13", self.root / "fresh-cache")
        self.assertEqual(archive.read_bytes(), downloaded.read_bytes())
        self.assertEqual(self.catalog["assets"]["windows-nvidia-cuda13"]["downloadUrl"], run.call_args.args[0][-1])

    def test_prepares_exact_configurations_licenses_and_source_identity(self):
        self.prepare(["windows-cpu", "windows-nvidia", "macos-arm64"])
        self.assertEqual("gtp test config", (self.engines / "configs/gtp.cfg").read_text())
        self.assertEqual("analysis test config", (self.engines / "configs/analysis.cfg").read_text())
        self.assertTrue((self.engines / "macos-arm64/licenses/LICENSE").is_file())
        manifest = (self.engines / "windows-x64/lizzieyzy-next-katago-engine-manifest.txt").read_text()
        self.assertIn("Manifest schema: 2", manifest)
        self.assertIn("Origin: project-source-build", manifest)
        self.assertIn(self.catalog["katagoSourceCommit"], manifest)
        nvidia_manifest = (self.engines / "windows-x64-nvidia/lizzieyzy-next-katago-engine-manifest.txt").read_text()
        self.assertIn("Asset ID: windows-nvidia", nvidia_manifest)
        self.assertIn("Zlib linkage: static", nvidia_manifest)

    def test_one_invalid_archive_leaves_existing_build_engines_untouched(self):
        (self.engines / "windows-x64").mkdir(parents=True)
        original = self.engines / "windows-x64/katago.exe"
        original.write_bytes(b"prior build")
        self.archive("windows-opencl").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "untrusted source archive"):
            self.prepare(["windows-cpu", "windows-opencl"])
        self.assertEqual(b"prior build", original.read_bytes())
        self.assertFalse((self.engines / "configs").exists())

    def test_experimental_rocm_family_marker_matches_existing_installer(self):
        self.prepare(["windows-rocm-gfx120x"])
        self.assertEqual("rocm-gfx120x\n", (self.engines / DESTINATIONS["windows-rocm-gfx120x"]
                         / "lizzieyzy-next-engine-backend.txt").read_text())

    def test_corrupted_cache_is_not_accepted_and_verified_cache_needs_no_network(self):
        with mock.patch("prepare_katago_source_assets.subprocess.run") as run:
            path = download(self.catalog, "windows-cpu", self.root / "release")
            self.assertEqual(self.archive("windows-cpu"), path)
            run.assert_not_called()

    def test_official_catalog_and_duplicate_targets_cannot_enter_source_path(self):
        official = json.loads(DEFAULT_CATALOG.read_text())
        official["origin"] = "official-release"
        official.pop("engineReleaseRepository", None)
        official.pop("engineReleaseTag", None)
        for target, asset in official["assets"].items():
            asset["assetName"] = f"katago-{official['katagoReleaseTag']}-{target}.zip"
            if asset.get("downloadUrl"):
                asset["downloadUrl"] = ("https://github.com/lightvector/KataGo/releases/download/"
                                        + official["katagoReleaseTag"] + "/" + asset["assetName"])
            asset.pop("zlibLinkage", None)
        validate_catalog(official)
        official_path = self.root / "official.json"
        official_path.write_text(json.dumps(official))
        with mock.patch("prepare_katago_source_assets.download") as fetch:
            with self.assertRaisesRegex(ValueError, "reviewed source catalog"):
                prepare(official_path, ["windows-cpu"], self.root / "cache", self.engines)
            fetch.assert_not_called()
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.prepare(["windows-cpu", "windows-cpu"])

    def test_archive_cannot_be_used_for_another_backend(self):
        with self.assertRaisesRegex(ValueError, "different source or target"):
            unpack(self.archive("windows-cpu"), self.root / "wrong", "windows-opencl",
                   self.catalog["assets"]["windows-cpu"])

    def test_trt_destination_matches_existing_runtime_packager(self):
        self.assertEqual("windows-x64-nvidia-tensorrt", DESTINATIONS["windows-tensorrt"])


if __name__ == "__main__":
    unittest.main()
