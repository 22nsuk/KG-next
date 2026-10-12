#!/usr/bin/env python3
"""Write the schema v1 manifest still fetched by older Windows updaters.

Clients from next-2026-06-12.2 through next-2026-08-01.4 request
``lizzieyzy-next-update-manifest.json`` from GitHub ``releases/latest/download``
and accept only ``schemaVersion`` 1. Their core entry must keep the
next-2026-09-26.2 zip: that archive still has the root jar alias those
builds install, plus the envelope-client jar that can take later updates.
A newly built core zip fails in the pre-migration helper after the app exits.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


OFFICIAL_REPOSITORY = "wimi321/lizzieyzy-next"
MANIFEST_NAME = "lizzieyzy-next-update-manifest.json"
PINNED_LEGACY_CORE_ASSET_NAME = "2026-09-26-windows64.core-update.zip"
PINNED_LEGACY_CORE_DOWNLOAD_URL = (
    "https://github.com/wimi321/lizzieyzy-next/releases/download/"
    "next-2026-09-26.2/2026-09-26-windows64.core-update.zip"
)
PINNED_LEGACY_CORE_SIZE_BYTES = 43803520
PINNED_LEGACY_CORE_SHA256 = (
    "08d6d0846b7c892de49b16ff6d708d7ba231b37720bc521270f333dc26e16c3f"
)


def build_pinned_legacy_manifest(
    *,
    release_tag: str,
    published_at: str,
    notes_url: str,
    prerelease: bool,
) -> dict[str, object]:
    """Return a v1 document whose core bytes are the pinned bridge zip."""
    if not str(release_tag).strip() or not str(published_at).strip() or not str(notes_url).strip():
        raise ValueError("Legacy update manifest is missing release identity")
    if not isinstance(prerelease, bool):
        raise TypeError("prerelease must be a bool")
    return {
        "schemaVersion": 1,
        "releaseTag": release_tag,
        "publishedAt": published_at,
        "notesUrl": notes_url,
        "minUpdaterVersion": "1",
        "prerelease": prerelease,
        "components": [
            {
                "id": "core",
                "platform": "windows",
                "flavor": "all",
                "version": release_tag,
                "assetName": PINNED_LEGACY_CORE_ASSET_NAME,
                "downloadUrl": PINNED_LEGACY_CORE_DOWNLOAD_URL,
                "sizeBytes": PINNED_LEGACY_CORE_SIZE_BYTES,
                "sha256": PINNED_LEGACY_CORE_SHA256,
                "installAction": "replace-core",
                "defaultSelectedIfChanged": True,
                "mirrorUrls": [],
            }
        ],
    }


def official_notes_url(release_tag: str) -> str:
    return f"https://github.com/{OFFICIAL_REPOSITORY}/releases/tag/{release_tag}"


def write_manifest(path: Path, payload: dict[str, object]) -> None:
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--date-tag", required=True)
    parser.add_argument("--prerelease", required=True, choices=("true", "false"))
    args = parser.parse_args(argv)
    payload = build_pinned_legacy_manifest(
        release_tag=args.release_tag,
        published_at=f"{args.date_tag}T00:00:00Z",
        notes_url=official_notes_url(args.release_tag),
        prerelease=args.prerelease == "true",
    )
    write_manifest(args.output, payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
