#!/usr/bin/env python3
"""Validate identity and integrity of Windows release metadata and split assets."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile


EXPECTED_RELEASE_REPOSITORY = "wimi321/lizzieyzy-next"


class ValidationError(RuntimeError):
    """A Windows release asset invariant failed."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValidationError(f"Unable to read JSON metadata {path.name}: {exc}") from exc
    require(isinstance(value, dict), f"{path.name} must contain a JSON object")
    return value


def parse_sha256_file(path: Path) -> dict[str, str]:
    checksums: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise ValidationError(f"Unable to read checksum file {path.name}: {exc}") from exc
    for line_number, line in enumerate(lines, 1):
        match = re.fullmatch(r"([0-9a-fA-F]{64})  ([^/\\]+)", line)
        require(match is not None, f"Malformed SHA-256 entry at {path.name}:{line_number}")
        assert match is not None
        name = match.group(2)
        require(name not in checksums, f"Duplicate SHA-256 entry for {name}")
        checksums[name] = match.group(1).lower()
    return checksums


def validate_update_manifest(
    release_dir: Path,
    date_tag: str,
    release_tag: str,
    prerelease: bool,
) -> None:
    manifest_path = release_dir / "lizzieyzy-next-update-manifest.json"
    manifest = load_json(manifest_path)
    require(manifest.get("schemaVersion") == 1, "Update manifest schemaVersion must be 1")
    require(
        manifest.get("releaseTag") == release_tag,
        f"Update manifest releaseTag must be {release_tag}",
    )
    require(
        manifest.get("publishedAt") == f"{date_tag}T00:00:00Z",
        f"Update manifest publishedAt must match {date_tag}",
    )
    require(
        type(manifest.get("prerelease")) is bool
        and manifest.get("prerelease") is prerelease,
        f"Update manifest prerelease must be {str(prerelease).lower()}",
    )
    expected_notes_url = (
        f"https://github.com/{EXPECTED_RELEASE_REPOSITORY}/releases/tag/{release_tag}"
    )
    require(
        manifest.get("notesUrl") == expected_notes_url,
        "Update manifest notesUrl must use the exact official repository and release tag",
    )

    components = manifest.get("components")
    require(
        isinstance(components, list) and bool(components),
        "Update manifest must include components",
    )
    cores = [
        item
        for item in components
        if isinstance(item, dict) and item.get("id") == "core"
    ]
    require(len(cores) == 1, "Update manifest must include exactly one core component")
    core = cores[0]
    expected_asset = f"{date_tag}-windows64.core-update.zip"
    require(core.get("assetName") == expected_asset, "Core update assetName is incorrect")
    require(core.get("platform") == "windows", "Core update platform must be windows")
    require(core.get("flavor") == "all", "Core update flavor must be all")
    require(core.get("version") == release_tag, "Core update version must match release tag")
    require(
        core.get("installAction") == "replace-core",
        "Core installAction is incorrect",
    )
    require(
        core.get("defaultSelectedIfChanged") is True,
        "Core update must be selected when changed",
    )
    expected_download_url = (
        f"https://github.com/{EXPECTED_RELEASE_REPOSITORY}/releases/download/"
        f"{release_tag}/{expected_asset}"
    )
    require(
        core.get("downloadUrl") == expected_download_url,
        "Core update downloadUrl must use the exact official repository, tag, and asset",
    )
    core_path = release_dir / expected_asset
    require(core_path.is_file(), f"Missing core update asset: {expected_asset}")
    require(
        core.get("sizeBytes") == core_path.stat().st_size,
        "Core update sizeBytes is incorrect",
    )
    core_hash = sha256(core_path)
    require(
        str(core.get("sha256") or "").lower() == core_hash,
        "Core update SHA-256 is incorrect",
    )

    try:
        with zipfile.ZipFile(core_path) as archive:
            entries = {
                name.replace("\\", "/")
                for name in archive.namelist()
                if name and not name.endswith("/")
            }
    except (OSError, zipfile.BadZipFile) as exc:
        raise ValidationError(f"Invalid core update ZIP: {exc}") from exc
    require(
        "app/lizzie-yzy2.5.3-shaded.jar" in entries,
        "Core update is missing the main jar",
    )
    require(
        "lizzieyzy-next-core.jar" in entries,
        "Core update is missing the legacy updater alias",
    )
    require(
        any(entry.startswith("app/KG-next") and entry.endswith(".cfg") for entry in entries),
        "Core update must include launcher cfg files",
    )
    require("README.txt" in entries, "Core update is missing README.txt")
    require(
        "lizzieyzy-next-core-update-manifest.json" in entries,
        "Core update is missing its installed manifest",
    )
    forbidden = {
        "weights",
        "engines",
        "runtime",
        "jcef-bundle",
        "readboard",
        "user-data",
    }
    for entry in entries:
        normalized = entry.strip("/")
        first = normalized.split("/", 1)[0]
        require(first not in forbidden, f"Core update contains resource path: {entry}")
        if normalized.startswith("app/"):
            remainder = normalized[4:]
            second = remainder.split("/", 1)[0] if "/" in remainder else ""
            require(second not in forbidden, f"Core update contains app resource path: {entry}")


def validate_tensorrt_split(
    release_dir: Path,
    date_tag: str,
    release_tag: str,
) -> None:
    prefix = f"{date_tag}-windows64.nvidia.tensorrt.portable.7z"
    expected_names = [f"{prefix}.001", f"{prefix}.002"]
    actual_names = sorted(
        path.name
        for path in release_dir.glob(f"{prefix}.[0-9][0-9][0-9]")
    )
    require(
        actual_names == expected_names,
        "TensorRT split volumes must be exactly contiguous .001 and .002; "
        f"found {actual_names or '<none>'}",
    )

    manifest_name = f"{date_tag}-windows64.nvidia.tensorrt.portable.manifest.json"
    readme_name = f"{date_tag}-windows64.nvidia.tensorrt.portable.README.txt"
    checksum_name = f"{date_tag}-windows64.nvidia.tensorrt.portable.sha256.txt"
    manifest_path = release_dir / manifest_name
    readme_path = release_dir / readme_name
    checksum_path = release_dir / checksum_name
    require(readme_path.is_file(), f"Missing TensorRT README: {readme_name}")
    manifest = load_json(manifest_path)
    require(
        manifest.get("dateTag") == date_tag,
        "TensorRT manifest dateTag is incorrect",
    )
    require(
        manifest.get("releaseDisplayVersion") == release_tag,
        "TensorRT manifest releaseDisplayVersion must match release tag",
    )
    require(
        manifest.get("assetKind") == "optional-tensorrt-split-package",
        "TensorRT manifest assetKind is incorrect",
    )
    require(
        manifest.get("archivePrefix") == prefix,
        "TensorRT manifest archivePrefix is incorrect",
    )
    require(
        manifest.get("engineBackend") == "nvidia-tensorrt",
        "TensorRT engineBackend is incorrect",
    )
    parts = manifest.get("parts")
    require(isinstance(parts, list), "TensorRT manifest parts must be a list")
    assert isinstance(parts, list)
    require(len(parts) == 2, "TensorRT manifest must list exactly two parts")

    computed: dict[str, str] = {}
    for index, expected_name in enumerate(expected_names):
        part = parts[index]
        require(
            isinstance(part, dict),
            f"TensorRT manifest part {index + 1} must be an object",
        )
        assert isinstance(part, dict)
        require(
            part.get("name") == expected_name,
            "TensorRT manifest parts are not contiguous/in order",
        )
        path = release_dir / expected_name
        digest = sha256(path)
        computed[expected_name] = digest
        require(
            part.get("sizeBytes") == path.stat().st_size,
            f"TensorRT size mismatch for {expected_name}",
        )
        require(
            str(part.get("sha256") or "").lower() == digest,
            f"TensorRT SHA mismatch for {expected_name}",
        )

    computed[readme_name] = sha256(readme_path)
    computed[manifest_name] = sha256(manifest_path)
    checksums = parse_sha256_file(checksum_path)
    require(
        set(checksums) == set(computed),
        "TensorRT checksum inventory must contain exactly both parts, README, and manifest",
    )
    for name, digest in computed.items():
        require(checksums[name] == digest, f"TensorRT checksum mismatch for {name}")


def validate_windows_release_assets(
    release_dir: Path,
    date_tag: str,
    release_tag: str,
    prerelease: bool,
) -> None:
    require(
        re.fullmatch(r"\d{4}-\d{2}-\d{2}", date_tag) is not None,
        "Invalid date tag",
    )
    require(
        re.fullmatch(rf"next-{re.escape(date_tag)}\.[1-9][0-9]*", release_tag) is not None,
        "Release tag must exactly match the date tag",
    )
    validate_update_manifest(release_dir, date_tag, release_tag, prerelease)
    validate_tensorrt_split(release_dir, date_tag, release_tag)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("release_dir", type=Path)
    parser.add_argument("date_tag")
    parser.add_argument("release_tag")
    parser.add_argument("prerelease", choices=("true", "false"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        validate_windows_release_assets(
            args.release_dir,
            args.date_tag,
            args.release_tag,
            args.prerelease == "true",
        )
    except ValidationError as exc:
        print(f"Windows release validation failed: {exc}", file=sys.stderr)
        return 1
    print("Validated Windows update manifest and TensorRT split integrity.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
