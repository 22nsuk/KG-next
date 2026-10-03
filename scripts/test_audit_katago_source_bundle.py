import json
import hashlib
from pathlib import Path
import shutil
import unittest
from unittest import mock
import zipfile

from audit_katago_source_bundle import audit, restore_after_jpackage
from katago_asset_catalog import engine_manifest_text
from prepare_katago_source_assets import OFFICIAL_RECEIPT, inventory_digest, unpack
from test_stage_katago_source_release import SourceReleaseTest


class InstalledSourceTest(unittest.TestCase):
    def setUp(self):
        self.fixture = SourceReleaseTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.catalog = self.fixture.run_stage()
        self.target = "windows-cpu"
        self.engine = self.fixture.root / "installed"
        asset = self.catalog["assets"][self.target]
        unpack(self.fixture.root / "release" / asset["assetName"], self.engine, self.target, asset)
        metadata = json.loads((self.engine / "source-release.json").read_text())
        (self.engine / "lizzieyzy-next-katago-engine-manifest.txt").write_text(
            engine_manifest_text(self.catalog, self.target, metadata["sourceCommit"]),
            encoding="utf-8",
        )

    def test_unchanged_installed_source_passes(self):
        audit(self.catalog, self.target, self.engine)

    def test_wrong_executable_is_rejected(self):
        (self.engine / "katago.exe").write_bytes(b"old executable")
        with self.assertRaisesRegex(ValueError, "missing or modified"):
            audit(self.catalog, self.target, self.engine)

    def test_dropped_license_is_rejected(self):
        (self.engine / "licenses/LICENSE").unlink()
        with self.assertRaisesRegex(ValueError, "missing or modified"):
            audit(self.catalog, self.target, self.engine)

    def test_tampered_engine_provenance_manifest_is_rejected(self):
        manifest = self.engine / "lizzieyzy-next-katago-engine-manifest.txt"
        manifest.write_text(manifest.read_text().replace("Origin: project-source-build",
                                                         "Origin: official-release"))
        with self.assertRaisesRegex(ValueError, "provenance manifest"):
            audit(self.catalog, self.target, self.engine)

    def tensor_rt_bundle(self):
        target = "windows-tensorrt"
        engine = self.fixture.root / "installed-tensorrt"
        asset = self.catalog["assets"][target]
        unpack(self.fixture.root / "release" / asset["assetName"], engine, target, asset)
        metadata = json.loads((engine / "source-release.json").read_text())
        manifest = engine / "lizzieyzy-next-katago-engine-manifest.txt"
        manifest.write_text(
            engine_manifest_text(self.catalog, target, metadata["sourceCommit"]),
            encoding="utf-8",
        )
        return target, engine, manifest

    def test_tensor_rt_without_companion_accepts_base_manifest(self):
        target, engine, _ = self.tensor_rt_bundle()
        audit(self.catalog, target, engine)

    def test_tensor_rt_unbound_companion_is_rejected(self):
        target, engine, _ = self.tensor_rt_bundle()
        (engine / "katago-human-sl-cuda.exe").write_bytes(b"unbound companion")
        with self.assertRaisesRegex(ValueError, "companion"):
            audit(self.catalog, target, engine)

    def test_different_backend_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "identity differs"):
            audit(self.catalog, "windows-opencl", self.engine)

    def test_duplicate_inventory_is_rejected(self):
        path = self.engine / "source-release.json"
        metadata = json.loads(path.read_text())
        metadata["files"].append(metadata["files"][0])
        path.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            audit(self.catalog, self.target, self.engine)

    def test_official_catalog_keeps_its_existing_audits(self):
        audit({"origin": "official-release"}, "windows-cpu", self.fixture.root / "absent")

    def official_cuda13_bundle(self):
        target = "windows-nvidia-cuda13"
        engine = self.fixture.root / "official-cuda13"
        archive = self.fixture.root / "official.zip"
        contents = {"katago.exe": b"official engine", "z.dll": b"official zlib",
                    "default_gtp.cfg": b"official gtp", "analysis_example.cfg": b"official analysis"}
        with zipfile.ZipFile(archive, "w") as opened:
            for name, content in contents.items():
                opened.writestr(name, content)
        asset = self.catalog["assets"][target]
        asset.update(sizeBytes=archive.stat().st_size, sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
                     executableSha256=hashlib.sha256(contents["katago.exe"]).hexdigest(),
                     inventorySha256=inventory_digest([
                         dict(file=name, sizeBytes=len(content), sha256=hashlib.sha256(content).hexdigest())
                         for name, content in sorted(contents.items())]))
        unpack(archive, engine, target, asset)
        (engine / "lizzieyzy-next-katago-engine-manifest.txt").write_text(
            engine_manifest_text(self.catalog, target, ""), encoding="utf-8")
        return target, engine

    def test_official_cuda13_inventory_allows_appended_runtime_and_checks_original_files(self):
        target, engine = self.official_cuda13_bundle()
        (engine / "cudart64_13.dll").write_bytes(b"appended matching runtime")
        audit(self.catalog, target, engine)
        (engine / "z.dll").write_bytes(b"changed zlib")
        with self.assertRaisesRegex(ValueError, "official file missing or modified: z.dll"):
            audit(self.catalog, target, engine)

    def test_official_cuda13_forged_receipt_cannot_approve_changed_zlib(self):
        target, engine = self.official_cuda13_bundle()
        (engine / "z.dll").write_bytes(b"changed zlib")
        receipt = engine / OFFICIAL_RECEIPT
        metadata = json.loads(receipt.read_text())
        entry = next(item for item in metadata["files"] if item["file"] == "z.dll")
        entry.update(sizeBytes=len(b"changed zlib"), sha256=hashlib.sha256(b"changed zlib").hexdigest())
        receipt.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, "official identity or inventory"):
            audit(self.catalog, target, engine)

    def test_official_cuda13_rejects_forged_source_identity_and_manifest(self):
        target, engine = self.official_cuda13_bundle()
        receipt = engine / OFFICIAL_RECEIPT
        metadata = json.loads(receipt.read_text())
        metadata["sourceCommit"] = self.catalog["katagoSourceCommit"]
        receipt.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, "official identity or inventory"):
            audit(self.catalog, target, engine)
        metadata["sourceCommit"] = ""
        receipt.write_text(json.dumps(metadata))
        manifest = engine / "lizzieyzy-next-katago-engine-manifest.txt"
        manifest.write_text(manifest.read_text().replace("Origin: official-release", "Origin: project-source-build"))
        with self.assertRaisesRegex(ValueError, "official provenance manifest"):
            audit(self.catalog, target, engine)

    def mac_bundle(self, target="macos-arm64"):
        source = self.fixture.root / "input" / target
        engine = self.fixture.root / "app" / target
        asset = self.catalog["assets"][target]
        unpack(self.fixture.root / "release" / asset["assetName"], source, target, asset)
        metadata = json.loads((source / "source-release.json").read_text())
        (source / "lizzieyzy-next-katago-engine-manifest.txt").write_text(
            engine_manifest_text(self.catalog, target, metadata["sourceCommit"]),
            encoding="utf-8",
        )
        shutil.copytree(source, engine)
        (engine / "katago").write_bytes(b"jpackage ad-hoc signature")
        return source, engine

    def test_restores_original_bytes_and_licenses_for_both_macos_architectures(self):
        for target in ("macos-arm64", "macos-amd64"):
            with self.subTest(target=target):
                source, engine = self.mac_bundle(target)
                (engine / "licenses/LICENSE").unlink()
                restore_after_jpackage(self.catalog, target, source, engine)
                audit(self.catalog, target, engine)
                audit(self.catalog, target, source)
                self.assertEqual((source / "katago").read_bytes(), (engine / "katago").read_bytes())

    def test_corrupted_input_does_not_replace_existing_app(self):
        source, engine = self.mac_bundle()
        (source / "katago").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "missing or modified"):
            restore_after_jpackage(self.catalog, "macos-arm64", source, engine)
        self.assertEqual(b"jpackage ad-hoc signature", (engine / "katago").read_bytes())

    def test_copy_failure_leaves_existing_app_intact(self):
        source, engine = self.mac_bundle()
        with mock.patch("audit_katago_source_bundle.shutil.copytree", side_effect=OSError("disk full")):
            with self.assertRaisesRegex(OSError, "disk full"):
                restore_after_jpackage(self.catalog, "macos-arm64", source, engine)
        self.assertEqual(b"jpackage ad-hoc signature", (engine / "katago").read_bytes())

    def test_restoration_rejects_same_directory_and_non_macos_targets(self):
        source, engine = self.mac_bundle()
        with self.assertRaisesRegex(ValueError, "separate"):
            restore_after_jpackage(self.catalog, "macos-arm64", source, source)
        with self.assertRaisesRegex(ValueError, "only for reviewed"):
            restore_after_jpackage(self.catalog, "windows-cpu", source, engine)

    def test_packager_restores_before_audit_and_without_deep_resigning(self):
        script = (Path(__file__).resolve().parents[1] / "scripts/package_macos_dmg.sh").read_text()
        restore = script.index('    --restore-from "$INPUT_DIR/engines/katago/$ENGINE_PLATFORM_DIR"')
        self.assertLess(script.index("jpackage \\\n"), restore)
        self.assertLess(restore, script.index('"$KATAGO_BUNDLE_SCRIPT" audit'))
        self.assertIn('codesign --force --sign - "$APP_IMAGE_DIR/$APP_NAME.app"', script)
        self.assertNotIn("codesign --force --deep", script)


if __name__ == "__main__":
    unittest.main()
