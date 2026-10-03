#!/usr/bin/env python3
"""Pin only the two reviewed fork CUDA archives, without uploading or replacing source assets."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import zipfile

from katago_asset_catalog import (
    CUSTOM_ENGINE_REPOSITORY, CUSTOM_SOURCE_REPOSITORY, DEFAULT_CATALOG,
    SOURCE_COMMIT_RE, load_catalog, validate_catalog,
)
from stage_katago_source_release import digest, verify_archive


def pin(base_catalog: Path, cuda12: Path, cuda13: Path, source_commit: str,
        tag: str, output: Path) -> dict:
    if not SOURCE_COMMIT_RE.fullmatch(source_commit) or tag != f"kg-next-{source_commit[:12]}":
        raise ValueError("custom release tag must identify the full reviewed source commit")
    if output.exists():
        raise ValueError("catalog output must be new; review before replacing the trusted catalog")
    catalog = copy.deepcopy(load_catalog(base_catalog))
    for target, archive in (("windows-nvidia", cuda12), ("windows-nvidia-cuda13", cuda13)):
        name = f"katago-source-{source_commit[:12]}-{target}.zip"
        if archive.is_symlink() or not archive.is_file() or archive.name != name:
            raise ValueError(f"{target}: archive name does not identify the source and target")
        metadata = verify_archive(archive, target, source_commit=source_commit,
                                  source_repository=CUSTOM_SOURCE_REPOSITORY)
        with zipfile.ZipFile(archive) as opened:
            metadata_digest = hashlib.sha256(opened.read("source-release.json")).hexdigest()
        asset = catalog["assets"][target]
        for field in ("downloadUrl", "inventorySha256"):
            asset.pop(field, None)
        asset.update(origin="project-source-build", katagoSourceCommit=source_commit,
                     katagoSourceRepository=CUSTOM_SOURCE_REPOSITORY,
                     engineReleaseRepository=CUSTOM_ENGINE_REPOSITORY, engineReleaseTag=tag,
                     assetName=name, sizeBytes=archive.stat().st_size, sha256=digest(archive),
                     executableSha256=metadata["executable"]["sha256"],
                     sourceMetadataSha256=metadata_digest, zlibLinkage="static")
    validate_catalog(catalog)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Both archives must verify before any generated catalog becomes visible.
    with output.open("x", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(catalog, indent=2) + "\n")
    return catalog


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--cuda12", type=Path, required=True)
    parser.add_argument("--cuda13", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    pin(arguments.base_catalog, arguments.cuda12, arguments.cuda13,
        arguments.source_commit, arguments.tag, arguments.output)
