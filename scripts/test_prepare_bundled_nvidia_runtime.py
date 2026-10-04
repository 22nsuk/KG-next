#!/usr/bin/env python3
"""Regression tests for the self-contained Windows NVIDIA runtime bundles."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from zipfile import ZipFile


SCRIPT_PATH = Path(__file__).with_name("prepare_bundled_nvidia_runtime.py")
SPEC = importlib.util.spec_from_file_location("prepare_bundled_nvidia_runtime", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
NVIDIA_RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NVIDIA_RUNTIME)


class PrepareBundledNvidiaRuntimeTest(unittest.TestCase):
    CUDA13_DLLS = {
        "cuda_cudart": ("cudart64_13.dll",),
        "libcublas": ("cublas64_13.dll", "cublasLt64_13.dll"),
        "libnvjitlink": ("nvJitLink_130_0.dll",),
        "cuda_nvrtc": ("nvrtc64_130_0.dll", "nvrtc-builtins64_132.dll"),
        "cudnn": ("cudnn64_9.dll", "cudnn_adv64_9.dll", "cudnn_cnn64_9.dll",
                  "cudnn_engines_precompiled64_9.dll", "cudnn_engines_runtime_compiled64_9.dll",
                  "cudnn_engines_tensor_ir64_9.dll", "cudnn_ext64_9.dll", "cudnn_graph64_9.dll",
                  "cudnn_heuristic64_9.dll", "cudnn_ops64_9.dll"),
    }

    def cuda13_packages(self):
        names = {spec[2]: spec[0] for spec in NVIDIA_RUNTIME.CUDA_13_2_SPECS}
        names["cudnn"] = "NVIDIA cuDNN"
        return [dict(key=key, display_name=names[key], version=version, sha256=digest,
                     url=f"https://example.invalid/{key}.zip")
                for key, (version, digest) in NVIDIA_RUNTIME.CUDA_13_2_PACKAGE_PINS.items()]

    def shell(self):
        git_bash = Path("C:/Program Files/Git/bin/bash.exe")
        bash = str(git_bash) if git_bash.is_file() else shutil.which("bash")
        if bash is None:
            self.skipTest("Bash is required for the release script integration fixture")
        return bash

    def shell_function(self, name):
        script = Path(__file__).with_name("package_windows_exe.sh").read_text(encoding="utf-8")
        start = script.index(name + "() {")
        return script[start:script.index("\n}\n", start) + len("\n}\n")]

    def test_windows_packager_copies_cuda13_runtime_and_propagates_mixed_major_failure(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            runtime = root / "runtime"
            runtime.mkdir()
            names = [name for dlls in self.CUDA13_DLLS.values() for name in dlls]
            for name in names:
                (runtime / name).write_bytes(name.encode())
            NVIDIA_RUNTIME.write_manifest(runtime, "cuda13.2-cudnn9.24", self.cuda13_packages(), names)
            script = '''set -euo pipefail
PYTHON_BIN="$2"
NVIDIA_RUNTIME_PREPARE_SCRIPT="$3"
STANDARD_ENGINE_PLATFORM_DIR=windows-x64
resolve_python_bin() { :; }
''' + self.shell_function("copy_bundle_nvidia_runtime_assets") + '''
copy_bundle_nvidia_runtime_assets "$1/input" windows-x64 "$1/runtime"
'''
            command = [self.shell(), "-c", script, "fixture", root.as_posix(),
                       Path(sys.executable).as_posix(), SCRIPT_PATH.resolve().as_posix()]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("", result.stdout, "staging diagnostics must not corrupt app-image path output")
            engine = root / "input/engines/katago/windows-x64"
            self.assertEqual(b"nvrtc-builtins64_132.dll", (engine / "nvrtc-builtins64_132.dll").read_bytes())
            (engine / "cudart64_12.dll").write_bytes(b"mixed major")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("unexpected=['cudart64_12.dll']", result.stderr)

    def test_windows_cuda13_build_route_is_opt_in_and_uses_separate_runtime_without_installer(self):
        package_script = Path(__file__).with_name("package_windows_exe.sh").read_text(encoding="utf-8")
        start = package_script.index('if [[ "${WINDOWS_BUILD_CUDA13:-false}" == "true" ]]; then')
        end = package_script.index('\nif [[ "${WINDOWS_BUILD_EXPERIMENTAL_PORTABLES', start)
        script = '''set -euo pipefail
WINDOWS_BUILD_CUDA13="$1"
NVIDIA_CUDA13_ENGINE_PLATFORM_DIR=windows-x64-nvidia-cuda13
STANDARD_ENGINE_PLATFORM_DIR=windows-x64
NVIDIA_CUDA13_RUNTIME_STAGE_DIR=runtime13
NVIDIA_CUDA13_APP_NAME='KG-next NVIDIA CUDA13'
NVIDIA_CUDA13_APP_DESCRIPTION='Optional CUDA13'
NVIDIA_CUDA13_ARCH_TAG=windows64.nvidia.cuda13
WINDOWS_UPGRADE_UUID_NVIDIA=unchanged-default-uuid
has_bundled_katago() { [[ "$1" == windows-x64-nvidia-cuda13 && "$HAS_ENGINE" == true ]]; }
prepare_bundled_nvidia_runtime_assets() { printf 'runtime %s %s\\n' "$1" "$2"; }
build_release_variant() { printf 'variant'; printf ' <%s>' "$@"; printf '\\n'; }
''' + package_script[start:end]
        for enabled, has_engine, expected_status in (("false", "true", 0), ("true", "true", 0), ("true", "false", 1)):
            with self.subTest(enabled=enabled, has_engine=has_engine):
                result = subprocess.run([self.shell(), "-c", script, "fixture", enabled],
                                        env=dict(os.environ, HAS_ENGINE=has_engine),
                                        capture_output=True, text=True)
                self.assertEqual(expected_status, result.returncode, result.stderr)
                if enabled == "false":
                    self.assertEqual("", result.stdout)
                elif has_engine == "true":
                    self.assertIn("runtime cuda13.2-cudnn9.24 runtime13", result.stdout)
                    self.assertIn("<windows-x64-nvidia-cuda13> <windows-x64> <nvidia>", result.stdout)
                    self.assertIn("<windows64.nvidia.cuda13> <unchanged-default-uuid> <runtime13> <false>", result.stdout)
                else:
                    self.assertIn("CUDA13 packaging requires", result.stderr)

    def test_cuda13_profile_prepares_all_runtime_dlls_from_cuda13_manifest_variant(self):
        profile_name = "cuda13.2-cudnn9.24"
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            cache = root / "cache"
            manifests = cache / "manifests"
            manifests.mkdir(parents=True)
            cuda, cudnn, archives = {}, {}, {}
            for package in self.cuda13_packages():
                key = package["key"]
                entry = dict(relative_path=f"{key}.zip", sha256=package["sha256"], size="1")
                target = cudnn if key == "cudnn" else cuda
                target[key] = dict(version=package["version"], **{"windows-x86_64": (
                    {"cuda12": dict(entry, sha256="0" * 64), "cuda13": entry} if key == "cudnn" else entry)})
                archive = root / f"{key}.zip"
                with ZipFile(archive, "w") as opened:
                    for name in self.CUDA13_DLLS[key]:
                        opened.writestr(f"{key}/bin/x64/{name}", name.encode())
                    opened.writestr(f"{key}/LICENSE", b"license")
                    if key == "libcublas":
                        opened.writestr(f"{key}/bin/x64/nvblas64_13.dll", b"optional NVBLAS")
                archives[key] = archive
            for url, data in ((NVIDIA_RUNTIME.CUDA_13_2_MANIFEST_URL, cuda),
                              (NVIDIA_RUNTIME.CUDNN_9_24_MANIFEST_URL, cudnn)):
                (manifests / url.rsplit("/", 1)[1]).write_text(json.dumps(data), encoding="utf-8")
            with mock.patch.object(NVIDIA_RUNTIME, "ensure_archive", side_effect=lambda p, d: archives[p["key"]]):
                packages, extracted = NVIDIA_RUNTIME.prepare_runtime_profile(
                    profile_name, NVIDIA_RUNTIME.RUNTIME_PROFILES[profile_name], cache, root / "runtime")
            expected = {name for names in self.CUDA13_DLLS.values() for name in names}
            self.assertEqual(expected, set(extracted))
            self.assertEqual(16, len(extracted))
            self.assertFalse((root / "runtime/nvblas64_13.dll").exists())
            self.assertTrue((root / "runtime/licenses/cudnn-LICENSE").is_file())
            self.assertEqual("88f72bd1ce384197cedbc68496c6052d7ff0bd9fd0b3c74470402cf737507e06",
                             next(p["sha256"] for p in packages if p["key"] == "cudnn"))
            NVIDIA_RUNTIME.verify_prepared_output(profile_name, root / "runtime")

    def test_cuda13_rejects_mixed_packages_wrong_pins_and_missing_cudnn_components(self):
        valid = self.cuda13_packages()
        NVIDIA_RUNTIME.validate_profile_packages("cuda13.2-cudnn9.24", valid)
        for key in ("cuda_cudart", "libcublas", "libnvjitlink", "cuda_nvrtc", "cudnn"):
            with self.subTest(package=key):
                mixed = [dict(p, version="12.8.61") if p["key"] == key else dict(p) for p in valid]
                with self.assertRaisesRegex(NVIDIA_RUNTIME.RuntimeErrorWithContext, "requires " + key):
                    NVIDIA_RUNTIME.validate_profile_packages("cuda13.2-cudnn9.24", mixed)
                wrong_digest = [dict(p, sha256="0" * 64) if p["key"] == key else dict(p) for p in valid]
                with self.assertRaisesRegex(NVIDIA_RUNTIME.RuntimeErrorWithContext, "requires " + key):
                    NVIDIA_RUNTIME.validate_profile_packages("cuda13.2-cudnn9.24", wrong_digest)
        names = [name for dlls in self.CUDA13_DLLS.values() for name in dlls]
        for invalid in (names + ["cudart64_12.dll"], names + ["nvrtc-builtins64_128.dll"],
                        [n for n in names if n != "cudnn_ext64_9.dll"],
                        [n for n in names if n != "cudnn_engines_tensor_ir64_9.dll"]):
            with self.subTest(dlls=invalid), self.assertRaisesRegex(
                    NVIDIA_RUNTIME.RuntimeErrorWithContext, "runtime DLL set mismatch"):
                NVIDIA_RUNTIME.validate_profile_dlls("cuda13.2-cudnn9.24", invalid)
        with self.assertRaisesRegex(NVIDIA_RUNTIME.RuntimeErrorWithContext, "incompatible runtime DLL"):
            NVIDIA_RUNTIME.validate_profile_dlls("cuda12.8-cudnn9", ["nvrtc64_130_0.dll"])

    def test_cuda13_cli_verifies_prepared_engine_and_rejects_cross_major_dlls(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory)
            names = [name for dlls in self.CUDA13_DLLS.values() for name in dlls]
            for name in names + ["z.dll"]:
                (output / name).write_bytes(b"test runtime")
            NVIDIA_RUNTIME.write_manifest(output, "cuda13.2-cudnn9.24", self.cuda13_packages(), names)
            command = [sys.executable, str(SCRIPT_PATH), "--profile", "cuda13.2-cudnn9.24",
                       "--output-dir", str(output), "--verify-output"]
            self.assertEqual(0, subprocess.run(command, capture_output=True, text=True).returncode)
            (output / "nvrtc64_120_0.dll").write_bytes(b"wrong major")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("unexpected=['nvrtc64_120_0.dll']", result.stderr)
            self.assertEqual(b"wrong major", (output / "nvrtc64_120_0.dll").read_bytes())

    def test_every_cuda_profile_includes_nvrtc(self) -> None:
        for profile_name, profile in NVIDIA_RUNTIME.RUNTIME_PROFILES.items():
            package_keys = {spec[2] for spec in profile["manifest_specs"]}
            self.assertIn(
                "cuda_nvrtc",
                package_keys,
                f"{profile_name} must include NVRTC for cuDNN runtime-compiled engines",
            )

    def test_existing_cuda12_profiles_accept_original_dll_names_and_reject_cuda13(self) -> None:
        for profile_name in ("cuda12.1-cudnn8", "cuda12.1-cudnn9", "cuda12.8-cudnn9",
                             "cuda12.8-cudnn9-tensorrt"):
            with self.subTest(profile=profile_name):
                names = ["cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll", "nvblas64_12.dll",
                         "nvJitLink_120_0.dll", "nvrtc64_120_0.dll",
                         "nvrtc-builtins64_128.dll" if "12.8" in profile_name else "nvrtc-builtins64_121.dll",
                         "cudnn64_8.dll" if profile_name.endswith("cudnn8") else "cudnn64_9.dll"]
                NVIDIA_RUNTIME.validate_profile_dlls(profile_name, names)
                with self.assertRaisesRegex(NVIDIA_RUNTIME.RuntimeErrorWithContext, "incompatible runtime DLL"):
                    NVIDIA_RUNTIME.validate_profile_dlls(profile_name, names + ["nvJitLink_130_0.dll"])

    def test_cuda128_accepts_pinned_nvrtc_alternate_but_rejects_cuda13_alternate(self) -> None:
        for profile in ("cuda12.8-cudnn9", "cuda12.8-cudnn9-tensorrt"):
            NVIDIA_RUNTIME.validate_profile_dlls(profile, ["nvrtc64_120_0.alt.dll"])
            with self.assertRaisesRegex(NVIDIA_RUNTIME.RuntimeErrorWithContext, "incompatible runtime DLL"):
                NVIDIA_RUNTIME.validate_profile_dlls(profile, ["nvrtc64_130_0.alt.dll"])
        with self.assertRaisesRegex(NVIDIA_RUNTIME.RuntimeErrorWithContext, "incompatible runtime DLL"):
            NVIDIA_RUNTIME.validate_profile_dlls("cuda12.1-cudnn9", ["nvrtc64_120_0.alt.dll"])

    def test_cuda128_requires_pinned_cudnn98_package_and_prepared_manifest(self) -> None:
        packages = [dict(key="cuda_nvrtc", version=NVIDIA_RUNTIME.CUDA_12_8_NVRTC_VERSION,
                         sha256=NVIDIA_RUNTIME.CUDA_12_8_NVRTC_SHA256, display_name="CUDA NVRTC", url="https://example.invalid/nvrtc.zip"),
                    dict(key="cudnn", version=NVIDIA_RUNTIME.CUDNN_9_8_VERSION,
                         sha256=NVIDIA_RUNTIME.CUDNN_9_8_SHA256, display_name="NVIDIA cuDNN", url="https://example.invalid/cudnn.zip")]
        NVIDIA_RUNTIME.validate_profile_packages("cuda12.8-cudnn9", packages)
        for change in (dict(version="9.24.0.43"), dict(sha256="0" * 64)):
            with self.assertRaisesRegex(NVIDIA_RUNTIME.RuntimeErrorWithContext, "pinned cuDNN"):
                NVIDIA_RUNTIME.validate_profile_packages("cuda12.8-cudnn9", [packages[0], dict(packages[1], **change)])
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            names = ["nvrtc64_120_0.dll", "nvrtc-builtins64_128.dll", "nvrtc64_120_0.alt.dll"]
            for name in names:
                (output / name).write_bytes(b"synthetic runtime")
            NVIDIA_RUNTIME.write_manifest(output, "cuda12.8-cudnn9", packages, names)
            NVIDIA_RUNTIME.verify_prepared_output("cuda12.8-cudnn9", output)
            NVIDIA_RUNTIME.write_manifest(output, "cuda12.8-cudnn9", packages[:1], names)
            with self.assertRaisesRegex(NVIDIA_RUNTIME.RuntimeErrorWithContext, "missing pinned cudnn"):
                NVIDIA_RUNTIME.verify_prepared_output("cuda12.8-cudnn9", output)

    def test_nvrtc_archive_extracts_compiler_and_builtins(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            archive_path = root / "cuda_nvrtc.zip"
            output_dir = root / "runtime"
            with ZipFile(archive_path, "w") as archive:
                archive.writestr("cuda_nvrtc/bin/nvrtc64_120_0.dll", b"compiler")
                archive.writestr("cuda_nvrtc/bin/nvrtc-builtins64_128.dll", b"builtins")
                archive.writestr("cuda_nvrtc/LICENSE.txt", b"license")

            extracted = NVIDIA_RUNTIME.extract_package(
                {"key": "cuda_nvrtc", "dll_patterns": ("*.dll",)},
                archive_path,
                output_dir,
            )

            self.assertEqual(
                {"nvrtc64_120_0.dll", "nvrtc-builtins64_128.dll"}, set(extracted)
            )
            self.assertTrue((output_dir / "nvrtc64_120_0.dll").is_file())
            self.assertTrue((output_dir / "nvrtc-builtins64_128.dll").is_file())

    def test_tensorrt_runtime_uses_mandatory_official_sha256(self) -> None:
        profile = NVIDIA_RUNTIME.RUNTIME_PROFILES["cuda12.8-cudnn9-tensorrt"]
        packages = NVIDIA_RUNTIME.load_direct_package_specs(profile["direct_specs"])

        self.assertEqual(1, len(packages))
        self.assertEqual("tensorrt", packages[0]["key"])
        self.assertEqual(NVIDIA_RUNTIME.TENSORRT_10_9_SHA256, packages[0]["sha256"])
        self.assertEqual(
            "c2758eb60191f01a47b24f54700e5463f577ebe129cd18fe835d0aa9f1e1a16d",
            packages[0]["sha256"],
        )

        broken = dict(profile["direct_specs"][0])
        broken["sha256"] = ""
        with self.assertRaisesRegex(
            NVIDIA_RUNTIME.RuntimeErrorWithContext, "Missing or invalid pinned SHA-256"
        ):
            NVIDIA_RUNTIME.load_direct_package_specs((broken,))

    def test_cuda_12_8_profile_requires_exact_nvrtc_manifest_package(self) -> None:
        valid = [
            {
                "key": "cuda_nvrtc",
                "version": NVIDIA_RUNTIME.CUDA_12_8_NVRTC_VERSION,
                "sha256": NVIDIA_RUNTIME.CUDA_12_8_NVRTC_SHA256,
            }
        ]
        NVIDIA_RUNTIME.validate_profile_packages("cuda12.8-fixture", valid)

        wrong_version = [dict(valid[0], version="12.8.60")]
        with self.assertRaisesRegex(
            NVIDIA_RUNTIME.RuntimeErrorWithContext, "requires CUDA NVRTC 12.8.61"
        ):
            NVIDIA_RUNTIME.validate_profile_packages("cuda12.8-fixture", wrong_version)

    def test_profile_preparation_verifies_archive_and_real_output_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source_archive = root / "fixture-runtime.zip"
            cache_dir = root / "cache"
            output_dir = root / "output"
            with ZipFile(source_archive, "w") as archive:
                archive.writestr("fixture/bin/nvrtc64_120_0.dll", b"compiler")
                archive.writestr("fixture/bin/nvrtc-builtins64_128.dll", b"builtins")
                archive.writestr("fixture/LICENSE.txt", b"license")
            digest = hashlib.sha256(source_archive.read_bytes()).hexdigest()
            cached_archive = cache_dir / "archives" / source_archive.name
            cached_archive.parent.mkdir(parents=True)
            shutil.copy2(source_archive, cached_archive)
            output_dir.mkdir()
            (output_dir / "stale.dll").write_bytes(b"stale")
            profile = {
                "manifest_specs": (),
                "direct_specs": (
                    {
                        "display_name": "Fixture NVRTC",
                        "key": "fixture_nvrtc",
                        "version": "12.8.61",
                        "url": source_archive.as_uri(),
                        "sha256": digest,
                        "size_bytes": source_archive.stat().st_size,
                        "dll_patterns": ("*.dll",),
                    },
                ),
            }

            packages, extracted = NVIDIA_RUNTIME.prepare_runtime_profile(
                "fixture", profile, cache_dir, output_dir
            )

            self.assertEqual(digest, packages[0]["sha256"])
            self.assertEqual(
                {"nvrtc64_120_0.dll", "nvrtc-builtins64_128.dll"}, set(extracted)
            )
            self.assertEqual(b"compiler", (output_dir / "nvrtc64_120_0.dll").read_bytes())
            self.assertEqual(
                b"builtins", (output_dir / "nvrtc-builtins64_128.dll").read_bytes()
            )
            self.assertFalse((output_dir / "stale.dll").exists())
            manifest = (output_dir / NVIDIA_RUNTIME.MANIFEST_FILE_NAME).read_text(
                encoding="utf-8"
            )
            self.assertIn("Profile: fixture", manifest)
            self.assertIn(f"sha256={digest}", manifest)

    def test_windows_packager_pins_runtime_digests_and_companion(self) -> None:
        package_script = Path(__file__).with_name("package_windows_exe.sh").read_text(
            encoding="utf-8"
        )
        java_runtime_helper = (
            Path(__file__).parents[1]
            / "src/main/java/featurecat/lizzie/util/KataGoRuntimeHelper.java"
        ).read_text(encoding="utf-8")
        companion_pins = Path(__file__).with_name("katago_windows_pins.sh").read_text(
            encoding="utf-8"
        )
        catalog = json.loads(
            (
                Path(__file__).parents[1]
                / "src/main/resources/katago-assets.json"
            ).read_text(encoding="utf-8")
        )
        companion_sha256 = catalog["assets"]["windows-nvidia"]["executableSha256"]

        self.assertIn('HUMAN_SL_CUDA_COMPANION_NAME="katago-human-sl-cuda.exe"', package_script)
        self.assertIn(
            "assets.windows-nvidia.executableSha256", companion_pins
        )
        self.assertIn(
            "assets.windows-tensorrt.assetName", companion_pins
        )
        self.assertIn("NVIDIA_CUDA_ASSET.executableSha256()", java_runtime_helper)
        self.assertIn("TENSORRT_KATAGO_ASSET_INFO.sha256()", java_runtime_helper)
        self.assertEqual(64, len(companion_sha256))
        self.assertIn('source "$ROOT_DIR/scripts/katago_windows_pins.sh"', package_script)
        self.assertIn(NVIDIA_RUNTIME.TENSORRT_10_9_SHA256, package_script)
        self.assertIn(NVIDIA_RUNTIME.CUDA_12_8_NVRTC_SHA256, package_script)
        self.assertIn("HumanSL companion SHA-256:", package_script)
        self.assertIn("shutil.copy2(companion_source, companion_target)", package_script)
        self.assertIn(
            'companion_source="$ROOT_DIR/engines/katago/'
            '$NVIDIA_ENGINE_PLATFORM_DIR/katago.exe"',
            package_script,
        )
        self.assertIn(
            'TensorRT HumanSL CUDA companion source is missing: $companion_source',
            package_script,
        )
        self.assertNotIn("NVIDIA50_CUDA_ENGINE_PLATFORM_DIR", package_script)

    def test_tensorrt_split_support_matrix_requires_unified_nvidia_cuda(self) -> None:
        package_script = Path(__file__).with_name("package_windows_exe.sh").read_text(
            encoding="utf-8"
        )
        gate = re.search(
            r'if \[\[ "\$\{WINDOWS_BUILD_TENSORRT_SPLIT:-true\}" == "true" \]\] \\\n'
            r'  && \[\[ -f "\$ROOT_DIR/weights/default\.bin\.gz" \]\] \\\n'
            r'  && \[\[ "\$has_nvidia_katago_assets" == "true" \]\]; then',
            package_script,
        )

        self.assertIsNotNone(gate)
        required_asset_flags = set(
            re.findall(r'\$has_([a-z0-9_]+)_katago_assets', gate.group(0))
        )
        self.assertEqual({"nvidia"}, required_asset_flags)
        self.assertNotIn("has_nvidia50_cuda_katago_assets", package_script)
        support_matrix = {
            (False, False): False,
            (True, False): True,
            (False, True): False,
            (True, True): True,
        }
        for (has_standard_nvidia, has_nvidia50_cuda), expected in support_matrix.items():
            with self.subTest(
                has_standard_nvidia=has_standard_nvidia,
                has_nvidia50_cuda=has_nvidia50_cuda,
            ):
                available_assets = {
                    "nvidia": has_standard_nvidia,
                }
                actual = all(available_assets[flag] for flag in required_asset_flags)
                self.assertEqual(expected, actual)


if __name__ == "__main__":
    unittest.main()
