#!/usr/bin/env python3
"""Validate the Windows x64 ReadBoard WebView2 bundle without modifying it."""

from __future__ import annotations

import argparse
from pathlib import Path
import struct
import xml.etree.ElementTree as ET


# Keep the whole upstream publish directory; these are the launch/UI/runtime anchors.
# Source: qiyi71w/readboard v3.2.0 scripts/package-readboard-release.local.ps1.
REQUIRED_FILES = (
    "readboard.exe",
    "readboard.dll",
    "readboard.runtimeconfig.json",
    "readboard.deps.json",
    "coreclr.dll",
    "hostfxr.dll",
    "hostpolicy.dll",
    "System.Private.CoreLib.dll",
    "language_cn.txt",
    "language_en.txt",
    "language_jp.txt",
    "language_kr.txt",
    "readme.rtf",
    "readme_en.rtf",
    "readme_jp.rtf",
    "OpenCvSharp.dll",
    "OpenCvSharp.Extensions.dll",
    "OpenCvSharpExtern.dll",
    "opencv_videoio_ffmpeg4100_64.dll",
    "Microsoft.Web.WebView2.Core.dll",
    "Microsoft.Web.WebView2.WinForms.dll",
    "WebView2Loader.dll",
    "runtimes/win-x64/native/WebView2Loader.dll",
    "WebView/index.html",
    "WebView/styles.css",
    "WebView/app.js",
    "WebView/lizziey.ico",
    "WebView/fonts/InterVariable.woff2",
    "WebView/fonts/LICENSE-Inter.txt",
)


def application_manifests(data: bytes) -> list[bytes]:
    """Read RT_MANIFEST / CREATEPROCESS_MANIFEST_RESOURCE_ID (24 / 1).

    Searching EXE text also finds XML comments and unrelated resources. Read the
    PE resource table instead, checking every language of the application manifest.
    Only PE32+ AMD64 is supported by this Windows x64 packaging path.
    """
    def unpack(fmt: str, offset: int, source: bytes = data) -> tuple:
        if offset < 0 or offset + struct.calcsize(fmt) > len(source):
            raise ValueError("Truncated ReadBoard PE resource data")
        return struct.unpack_from(fmt, source, offset)

    if data[:2] != b"MZ":
        raise ValueError("ReadBoard executable is not a PE image")
    pe, = unpack("<I", 0x3C)
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("Invalid ReadBoard PE signature")
    machine, section_count = unpack("<HH", pe + 4)
    optional_size, = unpack("<H", pe + 20)
    optional = pe + 24
    magic, = unpack("<H", optional)
    if machine != 0x8664 or magic != 0x20B or optional_size < 136:
        raise ValueError("ReadBoard must be a Windows x64 PE32+ executable")
    directory_count, = unpack("<I", optional + 108)
    if directory_count < 3:
        raise ValueError("ReadBoard has no PE resource directory")
    resource_rva, resource_size = unpack("<II", optional + 128)
    sections = [
        unpack("<IIII", optional + optional_size + index * 40 + 8)
        for index in range(section_count)
    ]

    def read_rva(rva: int, size: int) -> bytes:
        for _virtual_size, address, raw_size, raw_offset in sections:
            delta = rva - address
            if 0 <= delta and delta + size <= raw_size:
                start = raw_offset + delta
                if start + size <= len(data):
                    return data[start:start + size]
        raise ValueError("ReadBoard resource points outside the PE file")

    if not resource_rva or not resource_size:
        raise ValueError("ReadBoard has no PE resource directory")
    resources = read_rva(resource_rva, resource_size)

    def entries(directory: int) -> list[tuple[int, int]]:
        named, numbered = unpack("<HH", directory + 12, resources)
        return [
            unpack("<II", directory + 16 + index * 8, resources)
            for index in range(named + numbered)
        ]

    def child(entries_: list[tuple[int, int]], identifier: int) -> int:
        matches = [target for name, target in entries_ if name == identifier]
        if len(matches) != 1 or not matches[0] & 0x80000000:
            raise ValueError("Missing or invalid ReadBoard application manifest resource")
        return matches[0] & 0x7FFFFFFF

    manifest_type = child(entries(0), 24)
    manifest_name = child(entries(manifest_type), 1)
    manifests = []
    for _language, target in entries(manifest_name):
        if target & 0x80000000:
            raise ValueError("Invalid ReadBoard manifest language resource")
        rva, size, _codepage, _reserved = unpack("<IIII", target, resources)
        manifests.append(read_rva(rva, size))
    if not manifests:
        raise ValueError("ReadBoard application manifest is empty")
    return manifests


def validate_bundle(root: Path) -> None:
    missing = [
        name for name in REQUIRED_FILES
        if not (root / name).is_file() or (root / name).stat().st_size == 0
    ]
    if missing:
        raise ValueError("ReadBoard bundle is missing required files: " + ", ".join(missing))
    for manifest in application_manifests((root / "readboard.exe").read_bytes()):
        try:
            document = ET.fromstring(manifest)
        except ET.ParseError as error:
            raise ValueError("Invalid ReadBoard application manifest XML") from error
        levels = document.findall(
            "{urn:schemas-microsoft-com:asm.v2}trustInfo/"
            "{urn:schemas-microsoft-com:asm.v2}security/"
            "{urn:schemas-microsoft-com:asm.v3}requestedPrivileges/"
            "{urn:schemas-microsoft-com:asm.v3}requestedExecutionLevel"
        )
        if (document.tag != "{urn:schemas-microsoft-com:asm.v1}assembly"
                or len(levels) != 1
                or levels[0].get("level") != "asInvoker"
                or levels[0].get("uiAccess") != "false"):
            raise ValueError("ReadBoard must request asInvoker with uiAccess=false")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Packaged readboard directory")
    args = parser.parse_args()
    try:
        validate_bundle(args.directory)
    except (OSError, ValueError) as error:
        parser.exit(1, f"ReadBoard bundle validation failed: {error}\n")
    print(f"ReadBoard WebView2 bundle verified (asInvoker): {args.directory}")


if __name__ == "__main__":
    main()
