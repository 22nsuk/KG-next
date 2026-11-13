#!/usr/bin/env python3
"""Configure hosted Ubuntu mirrors and optionally bound CI package acquisition."""

from collections.abc import Mapping
import argparse
import os
from pathlib import Path
import platform
import re
import stat
import subprocess
import sys
import tempfile


AZURE = "http://azure.archive.ubuntu.com/ubuntu/"
# AWS-hosted runners list a regional mirror instead of Azure. Retain the supplied URI.
EC2 = re.compile(r"http://[a-z]{2}(?:-[a-z]+)+-[1-9][0-9]*\.ec2\.archive\.ubuntu\.com/ubuntu/")
ARCHIVE = "https://archive.ubuntu.com/ubuntu/"
SECURITY = "https://security.ubuntu.com/ubuntu/"
CLOUD = "cloud mirror"  # A role, not a URI: accept exactly one Azure or EC2 mirror.
DEFAULT_PRIORITIES = {CLOUD: 1, ARCHIVE: 2, SECURITY: 3}
PREFERRED_PRIORITIES = {CLOUD: 2, ARCHIVE: 1, SECURITY: 3}
MIRROR_FILE = Path("/etc/apt/apt-mirrors.txt")


def require_hosted_ubuntu_x64(
    environment: Mapping[str, str], system: str, machine: str, distribution: str
) -> None:
    expected = {
        "GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
        "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64",
    }
    if any(environment.get(key) != value for key, value in expected.items()) or (
        system, machine, distribution
    ) != ("Linux", "x86_64", "ubuntu"):
        raise ValueError("APT mirror preference is restricted to GitHub-hosted Ubuntu x64 runners")


def mirrorlist_with_priority(content: str, *, prefer_archive: bool = True) -> str:
    # Match the runner's explicit metadata; moving lines alone does not change apt priority.
    # https://github.com/actions/runner-images/blob/main/images/ubuntu/scripts/build/configure-apt-sources.sh
    entries = {}
    priorities = PREFERRED_PRIORITIES if prefer_archive else DEFAULT_PRIORITIES
    updated = []
    for number, line in enumerate(content.splitlines(keepends=True), 1):
        if not line.strip() or line.startswith("#"):
            updated.append(line)
            continue
        match = re.fullmatch(r"([^\s]+)(\t[ \t]*priority:)([1-3])([ \t]*\n?)", line)
        uri = match[1] if match else ""
        role = CLOUD if uri == AZURE or EC2.fullmatch(uri) else uri
        if role not in DEFAULT_PRIORITIES or role in entries:
            raise ValueError(f"Unexpected hosted-runner mirrorlist entry at line {number}")
        entries[role] = int(match[3])
        updated.append(f"{uri}{match[2]}{priorities[role]}{match[4]}")
    if entries not in (DEFAULT_PRIORITIES, PREFERRED_PRIORITIES):
        raise ValueError("Expected all three runner mirrors with known explicit priorities")
    return "".join(updated)


def configure_mirror_file(path: Path, *, prefer_archive: bool = True) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError("Expected an existing regular hosted-runner apt-mirrors.txt")
    original = path.read_text(encoding="utf-8")
    print("APT mirrorlist before:\n" + original, end="\n", flush=True)
    updated = mirrorlist_with_priority(original, prefer_archive=prefer_archive)
    if updated != original:
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", newline="", dir=path.parent,
                prefix=f".{path.name}.", delete=False,
            ) as output:
                temporary = Path(output.name)
                output.write(updated)
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, stat.S_IMODE(path.stat().st_mode))
            os.replace(temporary, path)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    print("APT mirrorlist after (all fallback URIs retained):\n" + updated, end="\n", flush=True)


def install_packages(packages: list[str]) -> None:
    if not packages or any(not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name) for name in packages):
        raise ValueError("Expected package names, not apt options")
    configure_mirror_file(MIRROR_FILE)
    subprocess.run(["apt-config", "dump", "Acquire::Retries", "Acquire::http::Timeout",
                    "Acquire::https::Timeout"], check=True)
    subprocess.run(["apt-get", "update"], check=True)
    for prefer_archive in (True, False):
        if not prefer_archive:
            # Retain the runner's existing URIs, signed indexes and downloaded archives.
            configure_mirror_file(MIRROR_FILE, prefer_archive=False)
        # Two acquisition attempts leave time for update/install within Fox's five-minute step.
        result = subprocess.run(
            ["timeout", "--kill-after=10s", "120s", "apt-get", "--download-only", "install",
             "-y", *packages], check=False)
        if result.returncode == 0:
            break
        # APT network errors and a bounded slow download permit one alternate-mirror attempt.
        # Cancellation, kill escalation and unexpected launcher failures must still stop the job.
        if not prefer_archive or result.returncode not in (100, 124):
            raise subprocess.CalledProcessError(result.returncode, result.args)
        print(f"APT download failed ({result.returncode}); retrying runner-default mirror priority",
              flush=True)
    # Only acquisition is time-bounded: never interrupt dpkg while installing packages.
    subprocess.run(["apt-get", "--no-download", "install", "-y", *packages], check=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", nargs="+", metavar="PACKAGE")
    args = parser.parse_args(argv)
    try:
        distribution = platform.freedesktop_os_release().get("ID", "") if sys.platform == "linux" else ""
        require_hosted_ubuntu_x64(os.environ, platform.system(), platform.machine(), distribution)
        if args.install:
            install_packages(args.install)
        else:
            configure_mirror_file(MIRROR_FILE)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"APT preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
