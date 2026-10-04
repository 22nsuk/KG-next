#!/usr/bin/env python3
"""Assemble one portable GUI/JRE/model with two verified, isolated CUDA engines."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import fnmatch
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile
import zipfile

from audit_katago_source_bundle import audit
from katago_asset_catalog import DEFAULT_CATALOG, load_catalog
from prepare_bundled_nvidia_runtime import (
    MANIFEST_FILE_NAME, NVIDIA_DLL_PATTERNS, RUNTIME_PROFILES, RuntimeErrorWithContext, verify_prepared_output,
)
from stage_katago_source_release import digest, normalized_name


REPO_ROOT = Path(__file__).resolve().parents[1]
ENGINE_MANIFEST = "lizzieyzy-next-katago-engine-manifest.txt"
BACKEND_MARKER = "lizzieyzy-next-engine-backend.txt"
VARIANTS = (
    ("windows-nvidia", "windows-x64", "cuda12.8-cudnn9", "nvidia"),
    ("windows-nvidia-cuda13", "windows-x64-nvidia-cuda13", "cuda13.2-cudnn9.24", "nvidia-cuda13"),
)
DOCUMENTS = ("README.md", "README_EN.md", "README_JA.md", "README_KO.md", "LICENSE.txt",
             "readme_cn.pdf", "readme_en.pdf")


@dataclass(frozen=True)
class Inputs:
    app_image: Path
    jar: Path
    cuda12_engine: Path
    cuda13_engine: Path
    cuda12_runtime: Path
    cuda13_runtime: Path
    output: Path
    release_tag: str
    app_name: str = "KG-next"
    catalog: Path = DEFAULT_CATALOG
    docs_root: Path = REPO_ROOT


def require_file(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"Required regular file is missing or linked: {path}")


def require_tree(path: Path) -> None:
    if path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction()) or not path.is_dir():
        raise ValueError(f"Required directory is missing or linked: {path}")
    for child in path.rglob("*"):
        # Do not follow junctions or symlinks outside a selected component.
        if child.is_symlink() or (hasattr(child, "is_junction") and child.is_junction()):
            raise ValueError(f"Linked component entry is unsupported: {child}")


def validate_output(inputs: Inputs) -> Path:
    output = inputs.output.resolve()
    sources = (inputs.app_image, inputs.jar, inputs.cuda12_engine, inputs.cuda13_engine,
               inputs.cuda12_runtime, inputs.cuda13_runtime, inputs.catalog,
               *(inputs.docs_root / name for name in DOCUMENTS),
               inputs.docs_root / "packaging" / "PROJECT_INFO.txt",
               inputs.docs_root / "packaging" / "WINDOWS_DUAL_CUDA_README_KO.md",
               inputs.docs_root / "scripts" / "windows_dual_cuda_test.ps1")
    for source in sources:
        resolved = source.resolve()
        if output == resolved or output in resolved.parents or resolved in output.parents:
            raise ValueError(f"Output and input must not overlap: {output} / {resolved}")
    if inputs.output.is_symlink() or (hasattr(inputs.output, "is_junction") and inputs.output.is_junction()):
        raise ValueError("Output must not be a link or junction")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError("Output must be a new or empty directory; existing installations are never overwritten")
    return output


def launcher_config(inputs: Inputs) -> tuple[Path, str]:
    candidates = [path for path in inputs.app_image.glob("*.exe")
                  if (inputs.app_image / "app" / f"{path.stem}.cfg").is_file()]
    if len(candidates) != 1:
        raise ValueError("App image must contain exactly one native launcher with a matching app/*.cfg")
    launcher = candidates[0]
    require_file(launcher)
    config_file = inputs.app_image / "app" / f"{launcher.stem}.cfg"
    require_file(config_file)
    config = config_file.read_text(encoding="utf-8")
    classpath = re.findall(r"(?m)^app\.classpath=\$APPDIR[\\/]([^\\/;\r\n]+\.jar)$", config)
    if (len(classpath) != 1 or "app.mainclass=featurecat.lizzie.Lizzie" not in config.splitlines()
            or len(re.findall(r"(?m)^java-options=-Dlizzie\.next\.version=.*$", config)) != 1):
        raise ValueError("Unsupported jpackage launcher configuration; expected one GUI JAR and version option")
    config = re.sub(r"(?m)^app\.classpath=.*$", lambda _: f"app.classpath=$APPDIR\\{inputs.jar.name}", config)
    config = re.sub(r"(?m)^java-options=-Dlizzie\.work\.dir=.*\n?", "", config)
    config = re.sub(r"(?m)^java-options=-Dlizzie\.next\.version=.*$",
                    lambda _: (f"java-options=-Dlizzie.next.version={inputs.release_tag}\n"
                               "java-options=-Dlizzie.work.dir=$APPDIR\\..\\user-data"), config)
    return launcher, config


def nvidia_dll(name: str) -> bool:
    return any(fnmatch.fnmatchcase(name.lower(), pattern) for pattern in NVIDIA_DLL_PATTERNS)


def runtime_inventory(runtime: Path, metadata: dict, profile: str) -> list[dict]:
    require_tree(runtime)
    verify_prepared_output(profile, runtime)
    shipped_patterns = RUNTIME_PROFILES[profile].get("dll_patterns", NVIDIA_DLL_PATTERNS)
    records = [record for record in metadata.get("auditedRuntimeFiles", []) if any(
        fnmatch.fnmatchcase(str(record.get("file", "")).lower(), pattern.lower()) for pattern in shipped_patterns
    )]
    names = [normalized_name(record.get("file")) for record in records]
    actual = {path.name.casefold() for path in runtime.iterdir() if path.is_file() and nvidia_dll(path.name)}
    if (not names or any("/" in name for name in names)
            or len({name.casefold() for name in names}) != len(names)
            or actual != {name.casefold() for name in names}):
        raise ValueError(f"{profile} DLL inventory differs from the audited source build")
    for record, name in zip(records, names):
        require_file(runtime / name)
        if (runtime / name).stat().st_size != record.get("sizeBytes"):
            raise ValueError(f"{profile} runtime file size differs from audited build: {name}")
    return records


def copy_engine(engine: Path, runtime: Path, destination: Path, metadata: dict,
                records: list[dict], backend: str) -> None:
    destination.mkdir(parents=True)
    # Copy the trusted inventory, rather than unrelated files in a previously used engine folder.
    for record in metadata["files"]:
        name = normalized_name(record["file"])
        target = destination.joinpath(*name.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(engine.joinpath(*name.split("/")), target)
    for name in ("source-release.json", ENGINE_MANIFEST):
        shutil.copy2(engine / name, destination / name)
    for record in records:
        name = record["file"]
        target = destination / name
        shutil.copy2(runtime / name, target)
        if digest(target) != record.get("sha256"):
            raise ValueError(f"Runtime content differs from audited build: {name}")
    shutil.copy2(runtime / MANIFEST_FILE_NAME, destination / MANIFEST_FILE_NAME)
    if (runtime / "licenses").is_dir():
        shutil.copytree(runtime / "licenses", destination / "licenses" / "nvidia-runtime")
    (destination / BACKEND_MARKER).write_text(backend + "\n", encoding="utf-8")


def assemble(inputs: Inputs) -> Path:
    output = validate_output(inputs)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 ._-]*", inputs.app_name) or inputs.app_name.endswith((" ", ".")):
        raise ValueError("App name must be a simple Windows launcher basename")
    if not inputs.release_tag or any(character in inputs.release_tag for character in "\r\n\x00"):
        raise ValueError("Release tag must be a nonempty single-line value")
    require_tree(inputs.app_image)
    require_file(inputs.jar)
    with zipfile.ZipFile(inputs.jar) as archive:
        if "featurecat/lizzie/Lizzie.class" not in archive.namelist():
            raise ValueError("GUI JAR is missing featurecat.lizzie.Lizzie")
    launcher, config = launcher_config(inputs)
    for name in ("java.exe", "javaw.exe", "server/jvm.dll"):
        require_file(inputs.app_image / "runtime" / "bin" / name)
    for path in (inputs.app_image / "runtime", inputs.app_image / "app" / "jcef-bundle",
                 inputs.app_image / "app" / "readboard"):
        require_tree(path)
    require_file(inputs.app_image / "app" / "jcef-bundle" / "libcef.dll")
    require_file(inputs.app_image / "app" / "readboard" / "readboard.exe")
    for name in DOCUMENTS:
        require_file(inputs.docs_root / name)
    require_file(inputs.docs_root / "packaging" / "PROJECT_INFO.txt")
    quick_start = inputs.docs_root / "packaging" / "WINDOWS_DUAL_CUDA_README_KO.md"
    require_file(quick_start)
    probe_script = inputs.docs_root / "scripts" / "windows_dual_cuda_test.ps1"
    require_file(probe_script)
    catalog = load_catalog(inputs.catalog)
    model = inputs.app_image / "app" / "weights" / "default.bin.gz"
    require_file(model)
    model_pin = catalog["models"][catalog["defaultModelId"]]
    if model.stat().st_size != model_pin["sizeBytes"] or digest(model) != model_pin["sha256"]:
        raise ValueError("Shared default model differs from the trusted asset catalog")
    prepared = []
    engines = (inputs.cuda12_engine, inputs.cuda13_engine)
    runtimes = (inputs.cuda12_runtime, inputs.cuda13_runtime)
    for (target, platform_dir, profile, backend), engine, runtime in zip(VARIANTS, engines, runtimes):
        require_tree(engine)
        audit(catalog, target, engine)
        metadata = json.loads((engine / "source-release.json").read_text(encoding="utf-8"))
        records = runtime_inventory(runtime, metadata, profile)
        prepared.append((target, platform_dir, profile, backend, engine, runtime, metadata, records))
    if prepared[0][6]["sourceCommit"] != prepared[1][6]["sourceCommit"]:
        raise ValueError("Both CUDA engines must use the same audited source commit")
    # Build beside the output, then publish the whole directory only after every check succeeds.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".dual-cuda-", dir=output.parent) as temporary:
        staged = Path(temporary) / inputs.app_name
        app = staged / "app"
        app.mkdir(parents=True)
        shutil.copy2(launcher, staged / f"{inputs.app_name}.exe")
        shutil.copy2(probe_script, staged / "Test-CUDA.ps1")
        shutil.copy2(quick_start, staged / "README-CUDA.md")
        shutil.copytree(inputs.app_image / "runtime", staged / "runtime")
        for name in ("jcef-bundle", "readboard"):
            shutil.copytree(inputs.app_image / "app" / name, app / name)
        shutil.copy2(inputs.jar, app / inputs.jar.name)
        (app / f"{inputs.app_name}.cfg").write_text(config, encoding="utf-8", newline="\n")
        for name in DOCUMENTS:
            shutil.copy2(inputs.docs_root / name, app / name)
        shutil.copy2(inputs.docs_root / "packaging" / "PROJECT_INFO.txt", app / "PROJECT_INFO.txt")
        katago = app / "engines" / "katago"
        for target, platform_dir, profile, backend, engine, runtime, metadata, records in prepared:
            destination = katago / platform_dir
            copy_engine(engine, runtime, destination, metadata, records, backend)
            audit(catalog, target, destination)
        configs = katago / "configs"
        configs.mkdir()
        for source, name in (("default_gtp.cfg", "gtp.cfg"), ("analysis_example.cfg", "analysis.cfg")):
            shutil.copy2(inputs.cuda12_engine / source, configs / name)
        (app / "weights").mkdir()
        shutil.copy2(model, app / "weights" / "default.bin.gz")
        (staged / ".lizzie-portable").write_text("portable\n", encoding="utf-8")
        state = {"schemaVersion": 1, "releaseTag": inputs.release_tag, "platform": "windows",
                 "flavor": "nvidia", "components": [
                     {"id": "core", "version": inputs.release_tag, "sha256": ""}]}
        (app / "lizzieyzy-next-installed-manifest.json").write_text(
            json.dumps(state, indent=2) + "\n", encoding="utf-8")
        manifest = {"schemaVersion": 1, "releaseTag": inputs.release_tag,
                    "launcher": f"{inputs.app_name}.exe", "jar": f"app/{inputs.jar.name}",
                    "jarSha256": digest(inputs.jar), "modelSha256": model_pin["sha256"],
                    "engines": [{"target": target, "directory": f"app/engines/katago/{platform_dir}",
                                 "runtimeProfile": profile, "sourceCommit": metadata["sourceCommit"],
                                 "executableSha256": metadata["executable"]["sha256"]}
                                for target, platform_dir, profile, _, _, _, metadata, _ in prepared]}
        (app / "lizzieyzy-next-dual-cuda-manifest.json").write_text(
            json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        # rmdir can only remove the pre-existing empty directory and fails safely if it changed.
        if output.exists():
            output.rmdir()
        staged.rename(output)
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("app-image", "jar", "cuda12-engine", "cuda13-engine", "cuda12-runtime", "cuda13-runtime", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--app-name", default="KG-next")
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    args = parser.parse_args()
    try:
        output = assemble(Inputs(**vars(args)))
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeErrorWithContext) as error:
        print(f"Dual CUDA assembly failed: {error}", file=sys.stderr)
        return 1
    print(f"Assembled {output}; launch {output / (args.app_name + '.exe')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
