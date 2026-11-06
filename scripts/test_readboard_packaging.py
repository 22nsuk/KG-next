#!/usr/bin/env python3
"""ReadBoard WebView2 staging, executable manifest and pipe smoke regressions."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

import validate_readboard_bundle as validator


ROOT = Path(__file__).resolve().parents[1]
TAG = "v3.2.0"
ASSET = "readboard-webview2-v3.2.0.zip"
SHA256 = "6abb1c2764fe14f72f77a2444d314e037d1d1e95c6c95974cd6c9c67de6e9512"
PACKAGE_SCRIPT = (ROOT / "scripts/package_windows_exe.sh").read_text(encoding="utf-8")
START = PACKAGE_SCRIPT.index("prepare_bundled_readboard_assets() {")
STAGING_CODE = PACKAGE_SCRIPT[START:].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
# Exercise the release's .NET Framework host as well as the preferred PowerShell 7.
POWERSHELLS = list(dict.fromkeys(filter(None, (
    shutil.which("pwsh"), shutil.which("powershell.exe") if os.name == "nt" else None,
))))


def manifest(level: str = "asInvoker", ui_access: str = "false") -> bytes:
    # The official resource contains all three elevation examples in a comment.
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<assembly manifestVersion="1.0" xmlns="urn:schemas-microsoft-com:asm.v1">
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v2"><security>
    <requestedPrivileges xmlns="urn:schemas-microsoft-com:asm.v3">
      <!-- <requestedExecutionLevel level="asInvoker" uiAccess="false" />
           <requestedExecutionLevel level="requireAdministrator" uiAccess="false" />
           <requestedExecutionLevel level="highestAvailable" uiAccess="false" /> -->
      <requestedExecutionLevel level="{level}" uiAccess="{ui_access}" />
    </requestedPrivileges>
  </security></trustInfo>
</assembly>'''.encode()


def pe_fixture(xml: bytes, resource_id: int = 1) -> bytes:
    """Non-executable PE fixture with a real type/name/language resource table."""
    resources = bytearray(88 + len(xml))
    struct.pack_into("<HII", resources, 14, 1, 24, 0x80000018)
    struct.pack_into("<HII", resources, 38, 1, resource_id, 0x80000030)
    struct.pack_into("<HII", resources, 62, 1, 1033, 72)
    struct.pack_into("<IIII", resources, 72, 0x1000 + 88, len(xml), 0, 0)
    resources[88:] = xml
    image = bytearray(0x200)
    image[:2] = b"MZ"
    struct.pack_into("<I", image, 0x3C, 0x80)
    image[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<HH", image, 0x84, 0x8664, 1)
    struct.pack_into("<H", image, 0x94, 240)
    struct.pack_into("<H", image, 0x98, 0x20B)
    struct.pack_into("<I", image, 0x98 + 108, 16)
    struct.pack_into("<II", image, 0x98 + 128, 0x1000, len(resources))
    image[0x188:0x190] = b".rsrc\0\0\0"
    struct.pack_into("<IIII", image, 0x190, len(resources), 0x1000, len(resources), 0x200)
    return bytes(image + resources)


def create_bundle(root: Path, xml: bytes | None = None) -> None:
    for name in validator.REQUIRED_FILES:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(("fixture " + name).encode())
    (root / "readboard.exe").write_bytes(pe_fixture(xml if xml is not None else manifest()))
    # Full-directory copying must also retain files outside the required anchor list.
    extra = root / "ja/PresentationFramework.resources.dll"
    extra.parent.mkdir()
    extra.write_bytes(b"additional upstream satellite assembly")


def inventory(root: Path) -> dict[str, bytes]:
    return {path.relative_to(root).as_posix(): path.read_bytes()
            for path in root.rglob("*") if path.is_file()}


class ReadBoardPackagingTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory(prefix="readboard bundle ")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.source = self.root / "source/readboard"
        self.stage = self.root / "stage"
        create_bundle(self.source)

    def stage_bundle(self, source: bool = True, digest: str | None = None,
                     metadata_digest: str | None = None) -> subprocess.CompletedProcess[str]:
        archive_path = self.root / ASSET
        with zipfile.ZipFile(archive_path, "w") as archive:
            for name, content in inventory(self.source).items():
                archive.writestr("readboard/" + name, content)
        actual = hashlib.sha256(archive_path.read_bytes()).hexdigest()
        metadata = self.root / "release.json"
        metadata.write_text(json.dumps({
            "tag_name": TAG,
            "assets": [{"name": ASSET, "browser_download_url": archive_path.as_uri(),
                        "digest": "sha256:" + (metadata_digest or digest or actual)}],
        }), encoding="utf-8")
        environment = os.environ.copy()
        environment["READBOARD_SOURCE_DIR"] = str(self.source.parent) if source else ""
        return subprocess.run(
            [sys.executable, "-", metadata.as_uri(), str(self.root / "cache"), str(self.stage),
             TAG, ASSET, digest or actual, str(ROOT / "scripts")],
            input=STAGING_CODE, env=environment, capture_output=True, text=True, timeout=20,
        )

    def test_release_pins_match_workflow_and_documented_asset(self) -> None:
        workflow = (ROOT / ".github/workflows/build-windows-release.yml").read_text(encoding="utf-8")
        for name, value in (("READBOARD_RELEASE_TAG", TAG), ("READBOARD_ASSET_NAME", ASSET),
                            ("READBOARD_ASSET_SHA256", SHA256)):
            self.assertIn(f'{name}="${{{name}:-{value}}}"', PACKAGE_SCRIPT)
        for entry in (f"release-tag={TAG}", f"asset-name={ASSET}", f"sha256={SHA256}"):
            self.assertIn(f'grep -qx "{entry}"', workflow)
        self.assertIn('scripts/validate_readboard_bundle.py "$(dirname "$readboard_exe")"', workflow)
        self.assertNotIn("patch_readboard_manifest_to_as_invoker", PACKAGE_SCRIPT)
        self.assertNotIn("grep -a -q 'level=", workflow)

    def test_staging_preserves_all_files_and_executable_bytes(self) -> None:
        expected = inventory(self.source)
        for source in (True, False):
            with self.subTest(local_source=source):
                result = self.stage_bundle(source=source)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                actual = inventory(self.stage)
                provenance = actual.pop("lizzieyzy-next-readboard-manifest.txt").decode()
                self.assertEqual(expected, actual)
                self.assertIn("requested-execution-level=asInvoker\n", provenance)
                self.assertIn("source=local:" if source else "source=github:v3.2.0", provenance)
                validator.validate_bundle(self.stage)

    def test_staging_rejects_metadata_and_archive_checksum_mismatches(self) -> None:
        for metadata_digest, digest, message in (("b" * 64, "a" * 64, "release digest mismatch"),
                                                  (None, "a" * 64, "checksum mismatch")):
            with self.subTest(message=message):
                result = self.stage_bundle(False, digest, metadata_digest)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)
                self.assertFalse(self.stage.exists())

    def test_staging_rejects_incomplete_webview_bundle_without_success_manifest(self) -> None:
        (self.source / "WebView/index.html").unlink()
        result = self.stage_bundle()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("WebView/index.html", result.stderr)
        self.assertFalse((self.stage / "lizzieyzy-next-readboard-manifest.txt").exists())

    def test_runtime_language_and_ui_anchors_cannot_be_missing_or_empty(self) -> None:
        for name in ("hostfxr.dll", "language_kr.txt", "WebView2Loader.dll",
                     "runtimes/win-x64/native/WebView2Loader.dll", "WebView/app.js",
                     "WebView/fonts/LICENSE-Inter.txt"):
            path = self.source / name
            original = path.read_bytes()
            for absent in (True, False):
                with self.subTest(name=name, absent=absent):
                    if absent:
                        path.unlink()
                    else:
                        path.write_bytes(b"")
                    with self.assertRaisesRegex(ValueError, re.escape(name)):
                        validator.validate_bundle(self.source)
                    path.write_bytes(original)

    def test_active_elevation_and_ui_access_are_rejected_despite_safe_comments(self) -> None:
        for level, ui_access in (("requireAdministrator", "false"), ("highestAvailable", "false"),
                                 ("asInvoker", "true")):
            with self.subTest(level=level, ui_access=ui_access):
                (self.source / "readboard.exe").write_bytes(pe_fixture(manifest(level, ui_access)))
                with self.assertRaisesRegex(ValueError, "asInvoker with uiAccess=false"):
                    validator.validate_bundle(self.source)
        result = self.stage_bundle()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.stage / "lizzieyzy-next-readboard-manifest.txt").exists())

    def test_malformed_or_non_application_resources_are_rejected(self) -> None:
        valid = pe_fixture(manifest())
        corrupt_rva = bytearray(valid)
        struct.pack_into("<I", corrupt_rva, 0x200 + 72, 0xFFFFFFFF)
        for data in (b"not a PE " + manifest(), valid[:90], valid[:-1], bytes(corrupt_rva),
                     pe_fixture(manifest(), resource_id=2), pe_fixture(b"<broken")):
            with self.subTest(length=len(data)):
                (self.source / "readboard.exe").write_bytes(data)
                with self.assertRaises(ValueError):
                    validator.validate_bundle(self.source)


@unittest.skipUnless(POWERSHELLS, "PowerShell is needed to execute the native smoke control flow")
class ReadBoardPipeSmokeTest(unittest.TestCase):
    def test_protocol_success_and_failure_cleanup(self) -> None:
        source = (ROOT / "scripts/windows_smoke_test.ps1").read_text(encoding="utf-8")
        start = source.index("function Invoke-NativeReadBoardPipeProbe {")
        end = source.index("\nfunction Assert-PackagedJavaRuntimeLauncher", start)
        function = source[start:end]
        with tempfile.TemporaryDirectory(prefix="readboard pipe ") as directory:
            root = Path(directory)
            # Python is the only substituted process. Production ProcessStartInfo,
            # pipe reads, deadlines, diagnostics and quit/kill paths run unchanged.
            (root / "yzy").write_text('''import os, sys, time
mode = os.environ["READBOARD_FIXTURE_MODE"]
sys.stderr.write("diagnostic " + "x" * 131072 + "\\n")
sys.stderr.flush()
if mode == "early-exit":
    sys.exit(3)
if mode != "no-ready":
    print("ready", flush=True)
for line in sys.stdin.buffer:
    print("command bytes: " + line.hex(), file=sys.stderr, flush=True)
    if line.strip() == b"version":
        print("version: " + ("wrong" if mode == "wrong-version" else "220430"), flush=True)
    if line.strip() == b"quit" and mode != "no-quit":
        print("shutdown " + "x" * 131072, flush=True)
        # The host must close stdin after its final command, not wait with it open.
        sys.stdin.buffer.read()
        sys.exit(7 if mode == "quit-error" else 0)
# Refusing quit must also refuse EOF; exiting this loop alone is not success.
while True:
    time.sleep(0.1)
''', encoding="utf-8")
            script = root / "probe.ps1"
            script.write_text('''$ErrorActionPreference = 'Stop'
# Reproduce the UTF-8 Windows CI console without changing the parent console.
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($true)
$originalInputEncoding = [Console]::InputEncoding
function Get-NativeReadBoardAssets {
    param([string]$AppExe)
    return @{ ExePath = $env:READBOARD_FIXTURE_PYTHON; Directory = $env:READBOARD_FIXTURE_ROOT }
}
function Stop-NativeReadBoardProcesses { param([string]$ReadBoardExePath) }
''' + function + '''
try { Invoke-NativeReadBoardPipeProbe -AppExe fixture -TimeoutSeconds 2 -ExitTimeoutSeconds 2 }
catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
finally {
    if (-not $originalInputEncoding.Equals([Console]::InputEncoding)) {
        throw 'Probe did not restore the caller input encoding'
    }
}
''', encoding="utf-8")
            for shell, mode in ((shell, mode) for shell in POWERSHELLS
                                for mode in ("success", "no-ready", "wrong-version", "early-exit", "no-quit", "quit-error")):
                with self.subTest(shell=shell, mode=mode):
                    result = subprocess.run(
                        [shell, "-NoProfile", "-File", str(script)],
                        env=dict(os.environ, READBOARD_FIXTURE_MODE=mode,
                                 READBOARD_FIXTURE_ROOT=str(root), READBOARD_FIXTURE_PYTHON=sys.executable),
                        capture_output=True, text=True, timeout=20,
                    )
                    self.assertEqual(mode == "success", result.returncode == 0,
                                     result.stdout + result.stderr)
                    stdout = (root / "lizzieyzy-next-readboard-probe.stdout.txt").read_text()
                    stderr = (root / "lizzieyzy-next-readboard-probe.stderr.txt").read_text()
                    self.assertIn("diagnostic " + "x" * 131072, stderr)
                    if mode in ("success", "wrong-version", "no-quit", "quit-error"):
                        newline = "0d0a" if os.name == "nt" else "0a"
                        commands = [line for line in stderr.splitlines()
                                    if line.startswith("command bytes: ")]
                        expected = ["command bytes: 76657273696f6e" + newline]
                        if mode != "wrong-version":
                            expected.append("command bytes: 71756974" + newline)
                        self.assertEqual(expected, commands)
                    if mode == "success":
                        self.assertIn("ready\nversion: 220430\nshutdown ", stdout)
                    elif mode == "no-ready":
                        self.assertIn("did not send 'ready'", result.stderr)
                    elif mode == "wrong-version":
                        self.assertIn("did not send 'version: 220430'", result.stderr)
                    elif mode == "early-exit":
                        self.assertIn("closed stdout before 'ready'", result.stderr)
                    elif mode == "no-quit":
                        self.assertIn("did not exit after quit", result.stderr)
                    elif mode == "quit-error":
                        self.assertIn("quit with exit code 7", result.stderr)


if __name__ == "__main__":
    unittest.main()
