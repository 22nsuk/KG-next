"""File-backed assembly checks; synthetic binaries do not claim GPU acceptance."""

from dataclasses import replace
import json
from pathlib import Path
import shutil
import subprocess
import unittest
import zipfile

from assemble_windows_dual_cuda import DOCUMENTS, Inputs, assemble
import prepare_bundled_nvidia_runtime as nvidia
from stage_katago_source_release import digest, record
import test_pin_custom_cuda_assets as fixture_tools


class DualCudaAssemblyTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture_tools.CustomCudaAssetsTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        runtimes = []
        cuda12_names = ("cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll", "nvblas64_12.dll",
                        "nvJitLink_120_0.dll", "nvrtc64_120_0.dll", "nvrtc64_120_0.alt.dll",
                        "nvrtc-builtins64_128.dll", "cudnn64_9.dll", "cudnn_adv64_9.dll",
                        "cudnn_cnn64_9.dll", "cudnn_engines_precompiled64_9.dll",
                        "cudnn_engines_runtime_compiled64_9.dll", "cudnn_graph64_9.dll",
                        "cudnn_heuristic64_9.dll", "cudnn_ops64_9.dll")
        for target, profile, names in (
            ("windows-nvidia", "cuda12.8-cudnn9", cuda12_names),
            ("windows-nvidia-cuda13", "cuda13.2-cudnn9.24", sorted(nvidia.CUDA_13_2_DLLS)),
        ):
            runtime = self.root / profile
            runtime.mkdir()
            for name in names:
                (runtime / name).write_bytes(f"fixture {profile}: {name}".encode())
            if target.endswith("cuda13"):
                pins = nvidia.CUDA_13_2_PACKAGE_PINS
            else:
                pins = {"cuda_nvrtc": (nvidia.CUDA_12_8_NVRTC_VERSION, nvidia.CUDA_12_8_NVRTC_SHA256),
                        "cudnn": (nvidia.CUDNN_9_8_VERSION, nvidia.CUDNN_9_8_SHA256)}
            displays = {spec[2]: spec[0] for spec in nvidia.RUNTIME_PROFILES[profile]["manifest_specs"]}
            packages = [dict(key=key, display_name=displays[key], version=version, sha256=sha,
                             url=f"https://example.invalid/{key}.zip") for key, (version, sha) in pins.items()]
            nvidia.write_manifest(runtime, profile, packages, list(names))
            records = [record(runtime / name, name) for name in names]
            if target.endswith("cuda13"):
                # The SDK audits nvBLAS too, but the established CUDA13 GUI runtime omits it.
                sdk_nvblas = self.root / "nvblas64_13.dll"
                sdk_nvblas.write_bytes(b"unshipped SDK nvBLAS fixture")
                records.append(record(sdk_nvblas, sdk_nvblas.name))
            self.fixture.rewrite_metadata(target, lambda metadata, records=records:
                                          metadata.update(auditedRuntimeFiles=records))
            runtimes.append(runtime)
        catalog = self.fixture.pin()
        self.fixture.prepare(catalog, list(self.fixture.archives))
        image = self.root / "이전 GUI 이미지"
        app = image / "app"
        app.mkdir(parents=True)
        self.original_cfg = (
            "[Application]\napp.classpath=$APPDIR\\old.jar\napp.mainclass=featurecat.lizzie.Lizzie\n\n"
            "[JavaOptions]\njava-options=-Djpackage.app-version=2.6.27401\n"
            "java-options=-XX:MaxRAMPercentage=50.0\njava-options=-Xshare:auto\n"
            "java-options=-Dlizzie.work.dir=C:\\old-data\njava-options=-Dlizzie.next.version=old\n"
        )
        (image / "Old GUI.exe").write_bytes(b"trusted native launcher fixture")
        (app / "Old GUI.cfg").write_text(self.original_cfg, encoding="utf-8")
        for name in ("java.exe", "javaw.exe", "server/jvm.dll"):
            path = image / "runtime" / "bin" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"JRE fixture")
        for name, required in (("jcef-bundle", "libcef.dll"), ("readboard", "readboard.exe")):
            (app / name).mkdir()
            (app / name / required).write_bytes(b"component fixture")
        for name in ("user-data/config.txt", "user-data/secure-credentials/secret", "save/game.sgf",
                     "app/engines/katago/windows-x64/old.dll", "app/README_KO.md", "app/old.jar"):
            path = image / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("private or obsolete source data")
        docs = self.root / "current-documents"
        (docs / "packaging").mkdir(parents=True)
        for name in DOCUMENTS:
            (docs / name).write_text(f"fresh {name}")
        (docs / "packaging/PROJECT_INFO.txt").write_text("fresh project info")
        (docs / "packaging/WINDOWS_DUAL_CUDA_README_KO.md").write_text("fresh dual CUDA quick-start")
        (docs / "scripts").mkdir()
        shutil.copy2(Path(__file__).with_name("windows_dual_cuda_test.ps1"), docs / "scripts/windows_dual_cuda_test.ps1")
        jar = self.root / "latest.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("featurecat/lizzie/Lizzie.class", b"GUI class fixture")
        model = app / "weights/default.bin.gz"
        model.parent.mkdir()
        model.write_bytes(b"trusted shared model fixture")
        catalog["models"][catalog["defaultModelId"]].update(sizeBytes=model.stat().st_size, sha256=digest(model))
        self.fixture.output.write_text(json.dumps(catalog), encoding="utf-8")
        self.inputs = Inputs(image, jar, self.root / "engines/windows-x64-nvidia",
                             self.root / "engines/windows-x64-nvidia-cuda13", *runtimes,
                             self.root / "새 설치 경로" / "KG-next", "next-test.1",
                             catalog=self.fixture.output, docs_root=docs)

    def test_one_shared_gui_with_isolated_profiles_and_clean_relocatable_data(self):
        output = assemble(self.inputs)
        app = output / "app"
        cfg = (app / "KG-next.cfg").read_text()
        self.assertIn("app.classpath=$APPDIR\\latest.jar", cfg)
        self.assertIn("java-options=-Dlizzie.next.version=next-test.1", cfg)
        self.assertIn("java-options=-Dlizzie.work.dir=$APPDIR\\..\\user-data", cfg)
        self.assertEqual(1, cfg.count("java-options=-Dlizzie.work.dir="))
        self.assertIn("java-options=-XX:MaxRAMPercentage=50.0", cfg)
        self.assertIn("java-options=-Djpackage.app-version=2.6.27401", cfg)
        self.assertNotIn(str(self.root), cfg)
        self.assertEqual(self.original_cfg, (self.inputs.app_image / "app/Old GUI.cfg").read_text())
        self.assertEqual((self.inputs.app_image / "Old GUI.exe").read_bytes(), (output / "KG-next.exe").read_bytes())
        self.assertEqual(["latest.jar"], [path.name for path in output.rglob("*.jar")])
        self.assertEqual(1, len(list(output.rglob("default.bin.gz"))))
        self.assertFalse((output / "user-data").exists())
        self.assertFalse((output / "save").exists())
        self.assertFalse(list(output.rglob("secret")))
        self.assertFalse(list(output.rglob("old.dll")))
        self.assertFalse(list(output.rglob("nvblas64_13.dll")))
        self.assertTrue((output / "Test-CUDA.ps1").is_file())
        self.assertEqual("fresh dual CUDA quick-start", (output / "README-CUDA.md").read_text())
        self.assertEqual("fresh README_KO.md", (app / "README_KO.md").read_text())
        katago = app / "engines/katago"
        self.assertEqual("fixture gtp", (katago / "configs/gtp.cfg").read_text())
        for engine_dir, runtime in (("windows-x64", self.inputs.cuda12_runtime),
                                    ("windows-x64-nvidia-cuda13", self.inputs.cuda13_runtime)):
            self.assertEqual((runtime / "cudnn64_9.dll").read_bytes(), (katago / engine_dir / "cudnn64_9.dll").read_bytes())
        manifest = json.loads((app / "lizzieyzy-next-dual-cuda-manifest.json").read_text())
        self.assertEqual(2, len(manifest["engines"]))
        self.assertNotIn(str(self.root), json.dumps(manifest))
        installed = json.loads((app / "lizzieyzy-next-installed-manifest.json").read_text())
        self.assertEqual("", installed["components"][0]["sha256"],
                         "Updater core hashes identify update archives; a standalone JAR is not that archive")

    def test_accepts_empty_output_but_never_overwrites_an_installation(self):
        self.inputs.output.mkdir(parents=True)
        assemble(self.inputs)
        sentinel = self.inputs.output / "user-data/config.txt"
        sentinel.parent.mkdir()
        sentinel.write_text("user settings")
        with self.assertRaisesRegex(ValueError, "never overwritten"):
            assemble(self.inputs)
        self.assertEqual("user settings", sentinel.read_text())

    def test_rejects_overlapping_paths_before_copying(self):
        for output in (self.inputs.app_image, self.inputs.app_image / "nested", self.root,
                       self.inputs.cuda12_runtime / "nested"):
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, "must not overlap"):
                assemble(replace(self.inputs, output=output))
        self.assertFalse(self.inputs.output.exists())

    def test_build_output_can_live_beside_documents_inside_repository(self):
        output = self.inputs.docs_root / "dist" / "KG-next"
        self.assertEqual(output.resolve(), assemble(replace(self.inputs, output=output)))

    def test_installed_probe_reports_each_failed_engine_without_touching_gui_settings(self):
        pwsh = shutil.which("pwsh")
        if pwsh is None:
            self.skipTest("PowerShell 7 is required for the installed probe check")
        output = assemble(self.inputs)
        sentinel = output / "user-data/config.txt"
        sentinel.parent.mkdir()
        sentinel.write_text("saved GUI settings")
        result = subprocess.run([pwsh, "-NoProfile", "-File", str(output / "Test-CUDA.ps1")],
                                capture_output=True, text=True, encoding="utf-8", timeout=30)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        summaries = list(output.glob("user-data/test-results/*/summary.json"))
        self.assertEqual(1, len(summaries), result.stdout + result.stderr)
        summary = json.loads(summaries[0].read_text(encoding="utf-8-sig"))
        self.assertEqual(["CUDA12", "CUDA13"], [entry["Profile"] for entry in summary])
        self.assertTrue(all(not entry["Passed"] and entry["Error"] for entry in summary))
        self.assertEqual("saved GUI settings", sentinel.read_text())

    def test_untrusted_engine_or_model_fails_before_publishing(self):
        executable = self.inputs.cuda13_engine / "katago.exe"
        original = executable.read_bytes()
        executable.write_bytes(b"wrong engine")
        with self.assertRaisesRegex(ValueError, "missing or modified"):
            assemble(self.inputs)
        executable.write_bytes(original)
        (self.inputs.app_image / "app/weights/default.bin.gz").write_bytes(b"wrong model")
        with self.assertRaisesRegex(ValueError, "default model differs"):
            assemble(self.inputs)
        self.assertFalse(self.inputs.output.exists())

    def test_runtime_content_tamper_cleans_staging_without_publishing(self):
        dll = self.inputs.cuda13_runtime / "cudnn64_9.dll"
        dll.write_bytes(b"x" * dll.stat().st_size)
        with self.assertRaisesRegex(ValueError, "content differs"):
            assemble(self.inputs)
        self.assertFalse(self.inputs.output.exists())
        self.assertFalse(list(self.inputs.output.parent.glob(".dual-cuda-*")))

    def test_wrong_profile_and_mixed_cuda_major_are_rejected(self):
        manifest = self.inputs.cuda13_runtime / nvidia.MANIFEST_FILE_NAME
        original = manifest.read_text()
        manifest.write_text(original.replace("Profile: cuda13.2-cudnn9.24", "Profile: cuda12.8-cudnn9"))
        with self.assertRaisesRegex(nvidia.RuntimeErrorWithContext, "does not match"):
            assemble(self.inputs)
        manifest.write_text(original)
        (self.inputs.cuda13_runtime / "cudart64_12.dll").write_bytes(b"mixed major")
        with self.assertRaisesRegex(nvidia.RuntimeErrorWithContext, "unexpected"):
            assemble(self.inputs)
        self.assertFalse(self.inputs.output.exists())

    def test_unsupported_launcher_and_missing_gui_entry_are_rejected(self):
        cfg = self.inputs.app_image / "app/Old GUI.cfg"
        cfg.write_text(self.original_cfg.replace("$APPDIR\\old.jar", "C:\\old.jar"))
        with self.assertRaisesRegex(ValueError, "Unsupported jpackage"):
            assemble(self.inputs)
        cfg.write_text(self.original_cfg)
        with zipfile.ZipFile(self.inputs.jar, "w") as archive:
            archive.writestr("unrelated.class", b"unrelated")
        with self.assertRaisesRegex(ValueError, "GUI JAR is missing"):
            assemble(self.inputs)
        self.assertFalse(self.inputs.output.exists())


if __name__ == "__main__":
    unittest.main()
