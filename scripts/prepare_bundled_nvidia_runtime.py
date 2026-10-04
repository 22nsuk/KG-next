#!/usr/bin/env python3
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import shutil
import ssl
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from zipfile import ZipFile

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
)
CUDA_12_1_MANIFEST_URL = "https://developer.download.nvidia.com/compute/cuda/redist/redistrib_12.1.1.json"
CUDA_12_8_MANIFEST_URL = "https://developer.download.nvidia.com/compute/cuda/redist/redistrib_12.8.0.json"
CUDA_13_2_MANIFEST_URL = "https://developer.download.nvidia.com/compute/cuda/redist/redistrib_13.2.2.json"
CUDNN_8_MANIFEST_URL = "https://developer.download.nvidia.com/compute/cudnn/redist/redistrib_8.9.7.29.json"
CUDNN_9_MANIFEST_URL = "https://developer.download.nvidia.com/compute/cudnn/redist/redistrib_9.8.0.json"
CUDNN_9_24_MANIFEST_URL = "https://developer.download.nvidia.com/compute/cudnn/redist/redistrib_9.24.0.json"
CUDA_12_8_NVRTC_VERSION = "12.8.61"
CUDA_12_8_NVRTC_SHA256 = "e43603b09f8a52d681ceb814c00b655af19da53692ab91671dabbf8071c8f93d"
CUDNN_9_8_VERSION = "9.8.0.87"
CUDNN_9_8_SHA256 = "d8a23705e3884b137b7e05449fb2b61bfa524e7cfc3fda80743d633f423c6ce4"
CUDA_13_2_NVRTC_VERSION = "13.2.86"
CUDA_13_2_NVRTC_SHA256 = "c8d4254c51bfa3fa982bd40bf36de9800d4c65211945e8393fbf4c310ef9232a"
CUDNN_9_24_VERSION = "9.24.0.43"
CUDNN_9_24_SHA256 = "88f72bd1ce384197cedbc68496c6052d7ff0bd9fd0b3c74470402cf737507e06"
CUDA_13_2_PACKAGE_PINS = {
    "cuda_cudart": ("13.2.86", "024e0c1055343d9d2a7d35c90fb7695db14cc2f05bb962198eb18b92c73b32be"),
    "libcublas": ("13.4.1.3", "bc6f590490134f4dbe29672b72e536c44362cc5f738a0f7e48c8c3c141e9b24a"),
    "libnvjitlink": ("13.2.86", "e1e94fbb5bf45c3c148a34febb29ca5546c760ebfe8ceb6ecc949b0a4d836bd9"),
    "cuda_nvrtc": (CUDA_13_2_NVRTC_VERSION, CUDA_13_2_NVRTC_SHA256),
    "cudnn": (CUDNN_9_24_VERSION, CUDNN_9_24_SHA256),
}
CUDA_13_2_DLLS = frozenset((
    "cudart64_13.dll", "cublas64_13.dll", "cublasLt64_13.dll", "nvJitLink_130_0.dll",
    "nvrtc64_130_0.dll", "nvrtc-builtins64_132.dll", "cudnn64_9.dll", "cudnn_adv64_9.dll",
    "cudnn_cnn64_9.dll", "cudnn_engines_precompiled64_9.dll",
    "cudnn_engines_runtime_compiled64_9.dll", "cudnn_engines_tensor_ir64_9.dll",
    "cudnn_ext64_9.dll", "cudnn_graph64_9.dll", "cudnn_heuristic64_9.dll", "cudnn_ops64_9.dll",
))
NVIDIA_DLL_PATTERNS = ("cudart*.dll", "cublas*.dll", "nvblas*.dll", "nvjitlink*.dll", "nvrtc*.dll", "cudnn*.dll")
TENSORRT_10_9_URL = (
    "https://developer.download.nvidia.com/compute/machine-learning/tensorrt/10.9.0/zip/"
    "TensorRT-10.9.0.34.Windows.win10.cuda-12.8.zip"
)
TENSORRT_10_9_SHA256 = "c2758eb60191f01a47b24f54700e5463f577ebe129cd18fe835d0aa9f1e1a16d"
TENSORRT_10_9_SIZE_BYTES = 1_845_842_538
CUDA_12_1_SPECS = (
    ("CUDA Runtime", CUDA_12_1_MANIFEST_URL, "cuda_cudart", "windows-x86_64"),
    ("CUDA cuBLAS", CUDA_12_1_MANIFEST_URL, "libcublas", "windows-x86_64"),
    ("CUDA nvJitLink", CUDA_12_1_MANIFEST_URL, "libnvjitlink", "windows-x86_64"),
    ("CUDA NVRTC", CUDA_12_1_MANIFEST_URL, "cuda_nvrtc", "windows-x86_64"),
)
CUDA_12_8_SPECS = (
    ("CUDA Runtime", CUDA_12_8_MANIFEST_URL, "cuda_cudart", "windows-x86_64"),
    ("CUDA cuBLAS", CUDA_12_8_MANIFEST_URL, "libcublas", "windows-x86_64"),
    ("CUDA nvJitLink", CUDA_12_8_MANIFEST_URL, "libnvjitlink", "windows-x86_64"),
    ("CUDA NVRTC", CUDA_12_8_MANIFEST_URL, "cuda_nvrtc", "windows-x86_64"),
)
CUDA_13_2_SPECS = (
    ("CUDA Runtime", CUDA_13_2_MANIFEST_URL, "cuda_cudart", "windows-x86_64"),
    ("CUDA cuBLAS", CUDA_13_2_MANIFEST_URL, "libcublas", "windows-x86_64"),
    ("CUDA nvJitLink", CUDA_13_2_MANIFEST_URL, "libnvjitlink", "windows-x86_64"),
    ("CUDA NVRTC", CUDA_13_2_MANIFEST_URL, "cuda_nvrtc", "windows-x86_64"),
)
RUNTIME_PROFILES = {
    "cuda13.2-cudnn9.24": {
        "description": "Optional CUDA 13.2 + cuDNN 9.24 runtime, separate from CUDA 12 packages",
        "manifest_specs": CUDA_13_2_SPECS
        + (("NVIDIA cuDNN", CUDNN_9_24_MANIFEST_URL, "cudnn", "windows-x86_64/cuda13"),),
        "direct_specs": (),
        "dll_patterns": tuple(CUDA_13_2_DLLS),
    },
    "cuda12.1-cudnn8": {
        "description": "Legacy CUDA 12.1 + cuDNN 8 runtime for existing NVIDIA installs",
        "manifest_specs": CUDA_12_1_SPECS
        + (("NVIDIA cuDNN", CUDNN_8_MANIFEST_URL, "cudnn", "windows-x86_64"),),
        "direct_specs": (),
    },
    "cuda12.1-cudnn9": {
        "description": "Legacy CUDA 12.1 + cuDNN 9.8 runtime for existing NVIDIA installs",
        "manifest_specs": CUDA_12_1_SPECS
        + (("NVIDIA cuDNN", CUDNN_9_MANIFEST_URL, "cudnn", "windows-x86_64/cuda12"),),
        "direct_specs": (),
    },
    "cuda12.8-cudnn9": {
        "description": "CUDA 12.8 + cuDNN 9.8 runtime for the unified RTX 20/30/40/50 package",
        "manifest_specs": CUDA_12_8_SPECS
        + (("NVIDIA cuDNN", CUDNN_9_MANIFEST_URL, "cudnn", "windows-x86_64/cuda12"),),
        "direct_specs": (),
    },
    "cuda12.8-cudnn9-tensorrt": {
        "description": "CUDA 12.8 + cuDNN 9 + TensorRT 10.9 runtime for the optional split package",
        "manifest_specs": CUDA_12_8_SPECS
        + (("NVIDIA cuDNN", CUDNN_9_MANIFEST_URL, "cudnn", "windows-x86_64/cuda12"),),
        "direct_specs": (
            {
                "display_name": "NVIDIA TensorRT",
                "key": "tensorrt",
                "version": "10.9.0.34",
                "url": TENSORRT_10_9_URL,
                "sha256": TENSORRT_10_9_SHA256,
                "size_bytes": TENSORRT_10_9_SIZE_BYTES,
                "dll_patterns": ("*.dll",),
            },
        ),
    },
}
DLL_SUFFIX = ".dll"
MANIFEST_FILE_NAME = "lizzieyzy-next-nvidia-runtime-manifest.txt"
LICENSE_DIR_NAME = "licenses"


class RuntimeErrorWithContext(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Download and prepare the official NVIDIA runtime DLLs for the Windows NVIDIA bundle."
    )
    parser.add_argument(
        "--cache-dir",
        default=str(root / ".cache" / "nvidia-runtime"),
        help="Cache directory for manifests and downloaded archives.",
    )
    parser.add_argument(
        "--output-dir",
        default=str(root / "dist" / "windows" / "nvidia-runtime"),
        help="Directory where extracted DLLs and license files will be written.",
    )
    parser.add_argument(
        "--profile",
        choices=sorted(RUNTIME_PROFILES),
        default="cuda12.8-cudnn9",
        help="Runtime profile to prepare.",
    )
    parser.add_argument(
        "--verify-output", action="store_true",
        help="Verify a prepared directory and its manifest without downloading or changing files.",
    )
    return parser.parse_args()


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_path = destination.with_suffix(destination.suffix + ".part")
    context = ssl.create_default_context()
    resume_from = temp_path.stat().st_size if temp_path.exists() else 0

    while True:
        headers = {"User-Agent": USER_AGENT}
        if resume_from > 0:
            headers["Range"] = f"bytes={resume_from}-"
            print(
                f"Resuming {url} -> {destination} from {resume_from} bytes",
                flush=True,
            )
        else:
            print(f"Downloading {url} -> {destination}", flush=True)
        request = Request(url, headers=headers)
        with urlopen(request, timeout=60, context=context) as response:
            status = getattr(response, "status", None)
            if resume_from > 0 and status == 200:
                # Server ignored Range; restart so the archive is not duplicated.
                temp_path.unlink(missing_ok=True)
                resume_from = 0
                continue
            mode = "ab" if resume_from > 0 and status == 206 else "wb"
            with temp_path.open(mode) as handle:
                shutil.copyfileobj(response, handle)
        break
    temp_path.replace(destination)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest_package_specs(
    cache_dir: Path, manifest_specs: tuple[tuple[str, str, str, str], ...]
) -> list[dict[str, object]]:
    manifests_dir = cache_dir / "manifests"
    manifests_dir.mkdir(parents=True, exist_ok=True)
    packages: list[dict[str, object]] = []

    for display_name, manifest_url, package_key, platform_key in manifest_specs:
        manifest_path = manifests_dir / Path(urlparse(manifest_url).path).name
        if not manifest_path.exists():
            download_file(manifest_url, manifest_path)
        manifest_data = json.loads(manifest_path.read_text(encoding="utf-8"))

        package_json = manifest_data.get(package_key)
        if not isinstance(package_json, dict):
            raise RuntimeErrorWithContext(f"Missing NVIDIA package metadata: {package_key}")
        platform_json = package_json
        for platform_part in platform_key.split("/"):
            if not isinstance(platform_json, dict):
                platform_json = None
                break
            platform_json = platform_json.get(platform_part)
        if not isinstance(platform_json, dict):
            raise RuntimeErrorWithContext(
                f"Missing NVIDIA platform metadata: {package_key} {platform_key}"
            )
        relative_path = str(platform_json.get("relative_path", "")).strip()
        sha256_value = str(platform_json.get("sha256", "")).strip().lower()
        size_text = str(platform_json.get("size", "0")).strip()
        version = str(package_json.get("version", "")).strip()
        if not relative_path or not sha256_value:
            raise RuntimeErrorWithContext(f"Incomplete NVIDIA metadata: {package_key}")
        try:
            size_bytes = int(size_text)
        except ValueError as exc:
            raise RuntimeErrorWithContext(f"Invalid size in NVIDIA metadata: {package_key}") from exc
        packages.append(
            {
                "display_name": display_name,
                "key": package_key,
                "version": version,
                "url": (
                    relative_path
                    if relative_path.startswith("http")
                    else manifest_url.rsplit("/", 1)[0] + "/" + relative_path
                ),
                "sha256": sha256_value,
                "size_bytes": size_bytes,
                "dll_patterns": ("*.dll",),
            }
        )
    return packages


def load_direct_package_specs(direct_specs: tuple[dict[str, object], ...]) -> list[dict[str, object]]:
    packages: list[dict[str, object]] = []
    for direct in direct_specs:
        sha256_value = str(direct.get("sha256", "")).strip().lower()
        if len(sha256_value) != 64 or any(
            character not in "0123456789abcdef" for character in sha256_value
        ):
            raise RuntimeErrorWithContext(
                f"Missing or invalid pinned SHA-256 for {direct['display_name']}"
            )
        packages.append(
            {
                "display_name": str(direct["display_name"]),
                "key": str(direct["key"]),
                "version": str(direct["version"]),
                "url": str(direct["url"]),
                "sha256": sha256_value,
                "size_bytes": int(direct.get("size_bytes", 0)),
                "dll_patterns": tuple(direct.get("dll_patterns", ("*.dll",))),
            }
        )
    return packages


def ensure_archive(package: dict[str, object], archives_dir: Path) -> Path:
    url = str(package["url"])
    file_name = Path(urlparse(url).path).name
    destination = archives_dir / file_name
    expected_sha = str(package["sha256"])
    expected_size = int(package.get("size_bytes", 0))
    if not expected_sha:
        raise RuntimeErrorWithContext(
            f"Missing pinned SHA-256 for {package['display_name']}"
        )
    if (
        destination.exists()
        and (expected_size <= 0 or destination.stat().st_size == expected_size)
        and sha256(destination) == expected_sha
    ):
        print(f"Using cached NVIDIA runtime archive: {destination}", flush=True)
        return destination
    if destination.exists():
        destination.unlink()
    download_file(url, destination)
    actual_sha = sha256(destination)
    if actual_sha != expected_sha:
        destination.unlink(missing_ok=True)
        raise RuntimeErrorWithContext(
            f"SHA-256 mismatch for {package['display_name']}: expected {expected_sha}, got {actual_sha}"
        )
    actual_size = destination.stat().st_size
    if expected_size > 0 and actual_size != expected_size:
        destination.unlink(missing_ok=True)
        raise RuntimeErrorWithContext(
            f"Size mismatch for {package['display_name']}: expected {expected_size}, got {actual_size}"
        )
    return destination


def copy_entry(zip_file: ZipFile, member_name: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zip_file.open(member_name) as source, destination.open("wb") as target:
        shutil.copyfileobj(source, target)


def extract_package(package: dict[str, object], archive_path: Path, output_dir: Path) -> list[str]:
    extracted_dlls: list[str] = []
    licenses_dir = output_dir / LICENSE_DIR_NAME
    dll_patterns = tuple(str(pattern).lower() for pattern in package.get("dll_patterns", ("*.dll",)))
    with ZipFile(archive_path) as zip_file:
        for member in zip_file.infolist():
            if member.is_dir():
                continue
            file_name = Path(member.filename).name
            lower_name = file_name.lower()
            normalized_member = member.filename.replace("\\", "/").lower()
            if lower_name.endswith(DLL_SUFFIX) and any(
                fnmatch.fnmatchcase(lower_name, pattern) for pattern in dll_patterns
            ):
                copy_entry(zip_file, member.filename, output_dir / file_name)
                extracted_dlls.append(file_name)
            elif lower_name == "license.txt" or "/license" in normalized_member:
                copy_entry(
                    zip_file,
                    member.filename,
                    licenses_dir / f"{package['key']}-{file_name}",
                )
    return extracted_dlls


def write_manifest(
    output_dir: Path, profile: str, packages: list[dict[str, object]], extracted_names: list[str]
) -> None:
    manifest_path = output_dir / MANIFEST_FILE_NAME
    lines = [
        f"Prepared at: {datetime.now(timezone.utc).astimezone().strftime('%Y-%m-%d %H:%M:%S %z')}",
        f"Profile: {profile}",
        f"DLL count: {len(extracted_names)}",
        "",
        "DLLs:",
    ]
    lines.extend(f"- {name}" for name in sorted(set(extracted_names), key=str.lower))
    lines.append("")
    lines.append("Packages:")
    for package in packages:
        lines.append(
            f"- {package['display_name']}: {package['version']} | {package['url']} | sha256={package['sha256']}"
        )
    manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def validate_profile_packages(profile_name: str, packages: list[dict[str, object]]) -> None:
    if profile_name == "cuda13.2-cudnn9.24":
        if (len(packages) != len(CUDA_13_2_PACKAGE_PINS)
                or {package.get("key") for package in packages} != set(CUDA_13_2_PACKAGE_PINS)):
            raise RuntimeErrorWithContext(f"{profile_name} requires exactly its five pinned packages")
        for package in packages:
            version, digest = CUDA_13_2_PACKAGE_PINS[str(package["key"])]
            if package.get("version") != version or package.get("sha256") != digest:
                raise RuntimeErrorWithContext(
                    f"{profile_name} requires {package['key']} {version} with SHA-256 {digest}"
                )
        return
    if not profile_name.startswith("cuda12.8-"):
        return
    if profile_name in ("cuda12.8-cudnn9", "cuda12.8-cudnn9-tensorrt"):
        cudnn = [package for package in packages if package.get("key") == "cudnn"]
        if (len(cudnn) != 1 or cudnn[0].get("version") != CUDNN_9_8_VERSION
                or cudnn[0].get("sha256") != CUDNN_9_8_SHA256):
            raise RuntimeErrorWithContext(f"{profile_name} requires pinned cuDNN {CUDNN_9_8_VERSION}")
    nvrtc_packages = [package for package in packages if package.get("key") == "cuda_nvrtc"]
    if len(nvrtc_packages) != 1:
        raise RuntimeErrorWithContext(
            f"{profile_name} must contain exactly one CUDA NVRTC package"
        )
    nvrtc = nvrtc_packages[0]
    if (
        nvrtc.get("version") != CUDA_12_8_NVRTC_VERSION
        or nvrtc.get("sha256") != CUDA_12_8_NVRTC_SHA256
    ):
        raise RuntimeErrorWithContext(
            f"{profile_name} requires CUDA NVRTC {CUDA_12_8_NVRTC_VERSION} "
            f"with SHA-256 {CUDA_12_8_NVRTC_SHA256}"
        )


def validate_profile_dlls(profile_name: str, extracted_names: list[str]) -> None:
    runtime_names = {name.lower() for name in extracted_names if any(
        fnmatch.fnmatchcase(name.lower(), pattern) for pattern in NVIDIA_DLL_PATTERNS
    )}
    if profile_name == "cuda13.2-cudnn9.24":
        expected = {name.lower() for name in CUDA_13_2_DLLS}
        if runtime_names != expected:
            raise RuntimeErrorWithContext(
                f"{profile_name} runtime DLL set mismatch; missing={sorted(expected - runtime_names)}, "
                f"unexpected={sorted(runtime_names - expected)}"
            )
    elif profile_name.startswith("cuda12."):
        cuda12_patterns = ("cudart64_12.dll", "cublas64_12.dll", "cublaslt64_12.dll", "nvblas64_12.dll",
                           "nvjitlink_120_0.dll", "nvrtc64_120_0.dll", "nvrtc-builtins64_12?.dll")
        if profile_name.startswith("cuda12.8-"):
            cuda12_patterns += ("nvrtc64_120_0.alt.dll",)
        cudnn_major = "8" if profile_name.endswith("cudnn8") else "9"
        for name in runtime_names:
            if name.startswith("cudnn"):
                compatible = name.endswith(f"64_{cudnn_major}.dll")
            else:
                compatible = any(fnmatch.fnmatchcase(name, pattern) for pattern in cuda12_patterns)
            if not compatible:
                raise RuntimeErrorWithContext(f"{profile_name} contains incompatible runtime DLL: {name}")


def verify_prepared_output(profile_name: str, output_dir: Path) -> None:
    manifest_path = output_dir / MANIFEST_FILE_NAME
    if not manifest_path.is_file():
        raise RuntimeErrorWithContext(f"Prepared NVIDIA runtime manifest is missing: {manifest_path}")
    manifest = manifest_path.read_text(encoding="utf-8")
    if f"Profile: {profile_name}" not in manifest.splitlines():
        raise RuntimeErrorWithContext(f"Prepared NVIDIA manifest does not match {profile_name}")
    validate_profile_dlls(profile_name, [path.name for path in output_dir.glob("*")
                                     if path.is_file() and path.suffix.lower() == DLL_SUFFIX])
    pins = CUDA_13_2_PACKAGE_PINS if profile_name == "cuda13.2-cudnn9.24" else {}
    if profile_name.startswith("cuda12.8-"):
        pins = {"cuda_nvrtc": (CUDA_12_8_NVRTC_VERSION, CUDA_12_8_NVRTC_SHA256)}
        if profile_name in ("cuda12.8-cudnn9", "cuda12.8-cudnn9-tensorrt"):
            pins["cudnn"] = (CUDNN_9_8_VERSION, CUDNN_9_8_SHA256)
        if not all((output_dir / name).is_file() for name in
                   ("nvrtc64_120_0.dll", "nvrtc-builtins64_128.dll")):
            raise RuntimeErrorWithContext(f"{profile_name} is missing NVRTC compiler or builtins")
    specs = RUNTIME_PROFILES[profile_name]["manifest_specs"]
    display_names = {spec[2]: spec[0] for spec in specs}
    for key, (version, digest) in pins.items():
        if not any(line.startswith(f"- {display_names[key]}: {version} | ")
                   and line.endswith(f" | sha256={digest}") for line in manifest.splitlines()):
            raise RuntimeErrorWithContext(f"Prepared NVIDIA manifest is missing pinned {key} {version}")


def prepare_runtime_profile(
    profile_name: str,
    profile: dict[str, object],
    cache_dir: Path,
    output_dir: Path,
) -> tuple[list[dict[str, object]], list[str]]:
    archives_dir = cache_dir / "archives"
    output_dir.mkdir(parents=True, exist_ok=True)
    archives_dir.mkdir(parents=True, exist_ok=True)

    packages = load_manifest_package_specs(cache_dir, profile["manifest_specs"])
    packages.extend(load_direct_package_specs(profile["direct_specs"]))
    validate_profile_packages(profile_name, packages)
    if "dll_patterns" in profile:
        for package in packages:
            package["dll_patterns"] = profile["dll_patterns"]

    for child in output_dir.iterdir():
        if child.is_dir():
            shutil.rmtree(child)
        else:
            child.unlink()

    extracted_names: list[str] = []
    for package in packages:
        archive_path = ensure_archive(package, archives_dir)
        extracted_names.extend(extract_package(package, archive_path, output_dir))

    if not extracted_names:
        raise RuntimeErrorWithContext("No NVIDIA runtime DLLs were extracted.")
    validate_profile_dlls(profile_name, extracted_names)

    write_manifest(output_dir, profile_name, packages, extracted_names)
    return packages, extracted_names


def main() -> int:
    args = parse_args()
    profile = RUNTIME_PROFILES[args.profile]
    cache_dir = Path(args.cache_dir).resolve()
    output_dir = Path(args.output_dir).resolve()
    if args.verify_output:
        verify_prepared_output(args.profile, output_dir)
        print(f"Verified NVIDIA runtime profile {args.profile} in: {output_dir}")
        return 0
    _, extracted_names = prepare_runtime_profile(args.profile, profile, cache_dir, output_dir)
    print(f"Prepared NVIDIA runtime profile {args.profile} in: {output_dir}")
    print(f"DLLs: {len(set(extracted_names))}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeErrorWithContext as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
