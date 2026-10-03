"""Synthetic archives exercise fork identity without claiming hardware acceptance."""

import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

from audit_katago_source_bundle import audit
from katago_asset_catalog import DEFAULT_CATALOG, SOURCE_OVERRIDE_FIELDS, asset_download_url, load_catalog, validate_catalog
from pin_custom_cuda_assets import pin
from prepare_katago_source_assets import download, prepare
from stage_katago_source_release import record, stage, write_zip
from test_stage_katago_source_release import SourceReleaseTest


COMMIT = "a" * 40


class CustomCudaAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.archives = {}
        for target in ("windows-nvidia", "windows-nvidia-cuda13"):
            directory = self.root / target
            (directory / "licenses").mkdir(parents=True)
            for name, content in {"katago.exe": f"synthetic fixture {target}",
                                  "default_gtp.cfg": "fixture gtp", "analysis_example.cfg": "fixture analysis",
                                  "licenses/LICENSE": "fixture license"}.items():
                (directory / name).write_text(content)
            cuda13 = target.endswith("cuda13")
            metadata = dict(schemaVersion=1, target=target, backend="CUDA", origin="project-source-build",
                            sourceRepository="https://github.com/22nsuk/KataGo", sourceCommit=COMMIT,
                            buildStatus="PASS", packagingStatus="PASS", dependencyAuditStatus="PASS",
                            hardwareAcceptanceStatus="PENDING_HARDWARE", hardwareAcceptanceReason="synthetic fixture",
                            dependencyLockSha256="b" * 64, versionOutput=f"Git revision: {COMMIT}\n",
                            runtimeProfile="cuda13.2-cudnn9.24" if cuda13 else "cuda12.8-cudnn9.8",
                            dependencies=[
                                dict(name="zlib", version="1.3.1", sha256="9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23",
                                     linkage="static", msvcRuntime="MultiThreaded"),
                                dict(name="cuda_cudart", version="13.2.86" if cuda13 else "12.8.57",
                                     sha256="024e0c1055343d9d2a7d35c90fb7695db14cc2f05bb962198eb18b92c73b32be" if cuda13 else
                                     "2c7aa62a195d79229d4381c8bd0174a30502cf3d8124c6e94ee50a7fc8a1e9f4"),
                                dict(name="cudnn", version="9.24.0.43" if cuda13 else "9.8.0.87",
                                     sha256="88f72bd1ce384197cedbc68496c6052d7ff0bd9fd0b3c74470402cf737507e06" if cuda13 else
                                     "d8a23705e3884b137b7e05449fb2b61bfa524e7cfc3fda80743d633f423c6ce4")])
            files = {path.relative_to(directory).as_posix(): path for path in directory.rglob("*") if path.is_file()}
            metadata.update(files=[record(path, name) for name, path in sorted(files.items())],
                            executable=record(directory / "katago.exe", "katago.exe"))
            archive = self.root / f"katago-source-{COMMIT[:12]}-{target}.zip"
            write_zip(archive, files, metadata)
            self.archives[target] = archive
        self.output = self.root / "catalog.json"

    def pin(self):
        return pin(DEFAULT_CATALOG, self.archives["windows-nvidia"], self.archives["windows-nvidia-cuda13"],
                   COMMIT, f"kg-next-{COMMIT[:12]}", self.output)

    def rewrite_metadata(self, target, transform):
        archive = self.archives[target]
        with zipfile.ZipFile(archive) as opened:
            contents = [(item, opened.read(item)) for item in opened.infolist()]
        with zipfile.ZipFile(archive, "w") as opened:
            for item, data in contents:
                if item.filename == "source-release.json":
                    metadata = json.loads(data)
                    transform(metadata)
                    data = json.dumps(metadata).encode()
                opened.writestr(item, data)

    def prepare(self, catalog, targets):
        with mock.patch("prepare_katago_source_assets.download", side_effect=lambda c, t, p: self.archives[t]):
            prepare(self.output, targets, self.root / "cache", self.root / "engines")

    def test_generator_changes_only_two_assets_using_measured_bytes(self):
        baseline, generated = load_catalog(DEFAULT_CATALOG), self.pin()
        self.assertNotIn(b"\r\n", self.output.read_bytes())
        for key, value in baseline.items():
            if key != "assets":
                self.assertEqual(value, generated[key])
        for target, asset in generated["assets"].items():
            if target not in self.archives:
                self.assertEqual(baseline["assets"][target], asset)
                continue
            self.assertEqual(self.archives[target].stat().st_size, asset["sizeBytes"])
            self.assertEqual(hashlib.sha256(self.archives[target].read_bytes()).hexdigest(), asset["sha256"])
            self.assertEqual(f"https://github.com/22nsuk/KataGo/releases/download/kg-next-{COMMIT[:12]}/"
                             + asset["assetName"], asset_download_url(generated, target))
        self.assertEqual(16, len(generated["assets"]))

    def test_prepare_and_audit_both_custom_profiles_without_dynamic_zlib(self):
        catalog = self.pin()
        self.prepare(catalog, list(self.archives))
        for target, suffix in (("windows-nvidia", "windows-x64-nvidia"),
                               ("windows-nvidia-cuda13", "windows-x64-nvidia-cuda13")):
            engine = self.root / "engines" / suffix
            audit(catalog, target, engine)
            self.assertFalse((engine / "z.dll").exists())
            manifest = (engine / "lizzieyzy-next-katago-engine-manifest.txt").read_text()
            self.assertIn(f"Source commit: {COMMIT}", manifest)
            self.assertIn("Source repository: https://github.com/22nsuk/KataGo", manifest)
            self.assertIn("Zlib linkage: static", manifest)
            (engine / "source-release.json").write_text("{}")
            with self.assertRaisesRegex(ValueError, "metadata differs"):
                audit(catalog, target, engine)

    def test_rejects_wrong_origin_profile_pins_or_static_zlib_before_output(self):
        original = self.archives["windows-nvidia-cuda13"].read_bytes()
        for transform, message in (
            (lambda m: m.update(sourceRepository="https://github.com/lightvector/KataGo"), "different source"),
            (lambda m: m.update(runtimeProfile="cuda12.8-cudnn9.8"), "runtime profile"),
            (lambda m: m["dependencies"][0].update(linkage="dynamic"), "static zlib"),
            (lambda m: m["dependencies"][2].update(version="9.8.0.87"), "pinned cudnn"),
            (lambda m: m["files"][0].update(sha256="0" * 64), "checksum mismatch"),
        ):
            self.archives["windows-nvidia-cuda13"].write_bytes(original)
            self.rewrite_metadata("windows-nvidia-cuda13", transform)
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                self.pin()
            self.assertFalse(self.output.exists())

    def test_catalog_cannot_repoint_unrelated_assets_or_accept_wrong_release_origin(self):
        catalog = self.pin()
        for field, value in (("engineReleaseRepository", "evil/KataGo"), ("engineReleaseTag", "latest"),
                             ("engineReleaseTag", "kg-next-000000000000"), ("katagoSourceCommit", "master"),
                             ("katagoSourceRepository", "https://github.com/lightvector/KataGo"),
                             ("sourceMetadataSha256", ""), ("origin", "official-release")):
            candidate = copy.deepcopy(catalog)
            candidate["assets"]["windows-nvidia"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_catalog(candidate)
        candidate = copy.deepcopy(catalog)
        candidate["assets"]["windows-cpu"].update(
            {key: catalog["assets"]["windows-nvidia"][key] for key in SOURCE_OVERRIDE_FIELDS})
        with self.assertRaisesRegex(ValueError, "cannot override"):
            validate_catalog(candidate)

    def test_authenticated_download_uses_effective_asset_release(self):
        catalog = self.pin()
        archive = self.archives["windows-nvidia"]
        def fetch(command, **kwargs):
            self.assertEqual(["gh", "release", "download", f"kg-next-{COMMIT[:12]}", "--repo", "22nsuk/KataGo"], command[:6])
            Path(command[-1], archive.name).write_bytes(archive.read_bytes())
        with mock.patch.dict(os.environ, {"GH_TOKEN": "synthetic fixture"}), mock.patch(
                "prepare_katago_source_assets.subprocess.run", side_effect=fetch):
            self.assertEqual(archive.read_bytes(), download(catalog, "windows-nvidia", self.root / "cache").read_bytes())

    def test_legacy_tensor_rt_companion_is_bound_to_new_custom_cuda12(self):
        fixture = SourceReleaseTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        legacy = fixture.run_stage()
        base = self.root / "legacy.json"
        base.write_text(json.dumps(legacy))
        catalog = pin(base, self.archives["windows-nvidia"], self.archives["windows-nvidia-cuda13"],
                      COMMIT, f"kg-next-{COMMIT[:12]}", self.output)
        target = "windows-tensorrt"
        self.archives[target] = fixture.root / "release" / catalog["assets"][target]["assetName"]
        self.prepare(catalog, [target, "windows-nvidia"])
        engine = self.root / "engines/windows-x64-nvidia-tensorrt"
        companion = engine / "katago-human-sl-cuda.exe"
        companion.write_bytes((self.root / "engines/windows-x64-nvidia/katago.exe").read_bytes())
        manifest = engine / "lizzieyzy-next-katago-engine-manifest.txt"
        manifest.write_text(manifest.read_text() + "HumanSL companion: katago-human-sl-cuda.exe\n"
                            + f"HumanSL companion SHA-256: {catalog['assets']['windows-nvidia']['executableSha256']}\n")
        audit(catalog, target, engine)
        companion.write_bytes(b"legacy CUDA12 companion")
        with self.assertRaisesRegex(ValueError, "companion differs"):
            audit(catalog, target, engine)

    def test_legacy_matrix_promotion_preserves_both_independent_custom_assets(self):
        original = self.pin()
        fixture = SourceReleaseTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        regenerated = stage(fixture.packages, fixture.acceptance, self.output, self.root / "legacy-release",
                            "next-2026-09-17.1", fixture.source)
        for target in self.archives:
            self.assertEqual(original["assets"][target], regenerated["assets"][target])
            self.assertFalse((self.root / "legacy-release" / f"katago-source-47aadc08518b-{target}.zip").exists())


if __name__ == "__main__":
    unittest.main()
