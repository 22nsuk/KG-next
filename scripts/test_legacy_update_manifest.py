#!/usr/bin/env python3
"""The generated public update manifest stays schema v1 and pinned."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.legacy_update_manifest import (
    MANIFEST_NAME,
    build_pinned_legacy_manifest,
    main as write_main,
)


ROOT = Path(__file__).resolve().parents[1]
PINNED_URL = (
    "https://github.com/wimi321/lizzieyzy-next/releases/download/"
    "next-2026-09-26.2/2026-09-26-windows64.core-update.zip"
)
PINNED_SIZE = 43803520
PINNED_SHA256 = "08d6d0846b7c892de49b16ff6d708d7ba231b37720bc521270f333dc26e16c3f"


def assert_pinned_core(test: unittest.TestCase, manifest: dict[str, object]) -> None:
    test.assertEqual(1, manifest["schemaVersion"])
    test.assertIsInstance(manifest["schemaVersion"], int)
    test.assertNotIsInstance(manifest["schemaVersion"], bool)
    test.assertEqual("1", manifest["minUpdaterVersion"])
    test.assertNotIn("packages", manifest)
    components = manifest["components"]
    test.assertIsInstance(components, list)
    assert isinstance(components, list)
    test.assertEqual(1, len(components))
    core = components[0]
    test.assertIsInstance(core, dict)
    assert isinstance(core, dict)
    test.assertEqual("core", core["id"])
    test.assertEqual("2026-09-26-windows64.core-update.zip", core["assetName"])
    test.assertEqual(PINNED_URL, core["downloadUrl"])
    test.assertEqual(PINNED_SIZE, core["sizeBytes"])
    test.assertEqual(PINNED_SHA256, core["sha256"])
    test.assertEqual([], core["mirrorUrls"])
    test.assertNotIn(str(manifest.get("releaseTag")), core["downloadUrl"])


class PinnedLegacyManifestTest(unittest.TestCase):
    def test_builder_emits_schema_v1_with_pinned_core_metadata(self) -> None:
        manifest = build_pinned_legacy_manifest(
            release_tag="next-2026-10-12.1",
            published_at="2026-10-12T00:00:00Z",
            notes_url=(
                "https://github.com/wimi321/lizzieyzy-next/releases/tag/next-2026-10-12.1"
            ),
            prerelease=False,
        )

        assert_pinned_core(self, manifest)
        self.assertEqual("next-2026-10-12.1", manifest["releaseTag"])
        self.assertEqual("next-2026-10-12.1", manifest["components"][0]["version"])
        self.assertFalse(manifest["prerelease"])

    def test_cli_writes_the_same_pinned_document(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / MANIFEST_NAME
            status = write_main(
                [
                    "--output",
                    str(output),
                    "--release-tag",
                    "next-2026-10-12.1",
                    "--date-tag",
                    "2026-10-12",
                    "--prerelease",
                    "true",
                ]
            )
            payload = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(0, status)
        assert_pinned_core(self, payload)
        self.assertEqual("2026-10-12T00:00:00Z", payload["publishedAt"])
        self.assertTrue(payload["prerelease"])
        self.assertEqual(
            "https://github.com/wimi321/lizzieyzy-next/releases/tag/next-2026-10-12.1",
            payload["notesUrl"],
        )

    def test_packaging_and_promotion_both_publish_that_generator(self) -> None:
        package_script = (ROOT / "scripts/package_windows_exe.sh").read_text(encoding="utf-8")
        workflow = (ROOT / ".github/workflows/build-windows-release.yml").read_text(
            encoding="utf-8"
        )
        publisher = (ROOT / "scripts/r2_release.py").read_text(encoding="utf-8")

        self.assertIn("scripts/legacy_update_manifest.py", package_script)
        self.assertIn('"$RELEASE_PRERELEASE"', package_script)
        self.assertNotIn('cp "$JAR_PATH" "$core_dir/lizzieyzy-next-core.jar"', package_script)
        self.assertNotIn("legacy-in-app-updater-source", package_script)
        self.assertIn('LIZZIE_RELEASE_PRERELEASE="$release_prerelease"', workflow)
        self.assertIn(
            "replace_github_asset(session, release, LEGACY_MANIFEST_ASSET, json_bytes(legacy), \"application/json\")",
            publisher,
        )
        completed = subprocess.run(
            [sys.executable, str(ROOT / "scripts/legacy_update_manifest.py"), "--help"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)


if __name__ == "__main__":
    unittest.main()
