#!/usr/bin/env python3
"""Keep the CI mirror preference scoped, fail-closed and metadata-preserving."""

import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import prefer_ubuntu_apt_mirror as mirrors


ORIGINAL = (
    "http://azure.archive.ubuntu.com/ubuntu/\tpriority:1\n"
    "https://archive.ubuntu.com/ubuntu/\tpriority:2\n"
    "https://security.ubuntu.com/ubuntu/\tpriority:3\n"
)
EXPECTED = (
    "http://azure.archive.ubuntu.com/ubuntu/\tpriority:2\n"
    "https://archive.ubuntu.com/ubuntu/\tpriority:1\n"
    "https://security.ubuntu.com/ubuntu/\tpriority:3\n"
)
HOSTED = {
    "GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
    "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64",
}


class PreferUbuntuAptMirrorTest(unittest.TestCase):
    def test_swaps_priorities_without_removing_or_reordering_fallbacks(self):
        self.assertEqual(EXPECTED, mirrors.mirrorlist_with_priority(ORIGINAL))

    def test_preserves_comments_blank_lines_and_metadata_spacing(self):
        original = "# runner mirrors\n\n" + ORIGINAL.replace("\t", "\t \t")
        expected = "# runner mirrors\n\n" + EXPECTED.replace("\t", "\t \t")
        self.assertEqual(expected, mirrors.mirrorlist_with_priority(original))

    def test_repeated_configuration_is_idempotent(self):
        self.assertEqual(EXPECTED, mirrors.mirrorlist_with_priority(EXPECTED))

    def test_rejects_unexpected_lists_instead_of_guessing_or_adding_sources(self):
        malformed = (
            "", ORIGINAL.splitlines(keepends=True)[0],
            ORIGINAL + ORIGINAL.splitlines(keepends=True)[0],
            ORIGINAL + "https://example.invalid/ubuntu/\tpriority:4\n",
            ORIGINAL.replace("https://archive.ubuntu.com/", "http://archive.ubuntu.com/"),
            ORIGINAL.replace("priority:1", "priority:0"),
            ORIGINAL.replace("priority:2", "priority:1"),
            ORIGINAL.replace("priority:3", "priority:4"),
            ORIGINAL.replace("\tpriority:2", ""),
            ORIGINAL.replace("\t", " "),
            ORIGINAL.replace("priority:2", "priority:2 arch:amd64"),
        )
        for original in malformed:
            with self.subTest(original=original), self.assertRaises(ValueError):
                mirrors.mirrorlist_with_priority(original)

    def test_invalid_file_is_unchanged(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "apt-mirrors.txt"
            original = ORIGINAL.replace("priority:2", "unknown:2")
            path.write_text(original, encoding="utf-8")
            with self.assertRaises(ValueError):
                mirrors.configure_mirror_file(path)
            self.assertEqual(original, path.read_text(encoding="utf-8"))

    def test_missing_file_is_not_created(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "missing.txt"
            with self.assertRaisesRegex(ValueError, "existing regular"):
                mirrors.configure_mirror_file(path)
            self.assertFalse(path.exists())

    def test_file_update_is_exact_and_idempotent(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "apt-mirrors.txt"
            path.write_text(ORIGINAL, encoding="utf-8")
            mirrors.configure_mirror_file(path)
            self.assertEqual(EXPECTED, path.read_text(encoding="utf-8"))
            with mock.patch.object(
                mirrors.tempfile, "NamedTemporaryFile", side_effect=AssertionError("rewritten")
            ):
                mirrors.configure_mirror_file(path)

    def test_replace_failure_preserves_original_and_removes_only_own_temporary(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            path = directory / "apt-mirrors.txt"
            path.write_text(ORIGINAL, encoding="utf-8")
            with mock.patch.object(mirrors.os, "replace", side_effect=OSError("injected")):
                with self.assertRaisesRegex(OSError, "injected"):
                    mirrors.configure_mirror_file(path)
            self.assertEqual(ORIGINAL, path.read_text(encoding="utf-8"))
            self.assertEqual([path], list(directory.iterdir()))

    def test_only_accepts_hosted_ubuntu_x64(self):
        mirrors.require_hosted_ubuntu_x64(HOSTED, "Linux", "x86_64", "ubuntu")
        for key in HOSTED:
            invalid = {**HOSTED, key: ""}
            with self.subTest(key=key), self.assertRaises(ValueError):
                mirrors.require_hosted_ubuntu_x64(invalid, "Linux", "x86_64", "ubuntu")
        for system, machine, distro in (
            ("Windows", "x86_64", "ubuntu"), ("Linux", "aarch64", "ubuntu"),
            ("Linux", "x86_64", "debian"),
        ):
            with self.subTest(system=system, machine=machine, distro=distro):
                with self.assertRaises(ValueError):
                    mirrors.require_hosted_ubuntu_x64(HOSTED, system, machine, distro)

    def test_download_budget_falls_back_once_and_installs_only_complete_archives(self):
        for results in ((0,), (124, 0), (100, 0), (124, 100), (100, 124), (137,), (130,), (127,)):
            with self.subTest(results=results), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "apt-mirrors.txt"
                path.write_text(ORIGINAL, encoding="utf-8")
                remaining = iter(results)
                priorities = []
                calls = []

                def run(command, *, check):
                    calls.append(command)
                    if command[0] == "timeout":
                        self.assertFalse(check)
                        self.assertEqual(
                            ["timeout", "--kill-after=10s", "120s", "apt-get", "--download-only",
                             "install", "-y", "xvfb", "fonts-noto-cjk"], command)
                        priorities.append(path.read_text(encoding="utf-8"))
                        return mirrors.subprocess.CompletedProcess(command, next(remaining))
                    self.assertTrue(check)
                    return mirrors.subprocess.CompletedProcess(command, 0)

                with mock.patch.object(mirrors, "MIRROR_FILE", path), \
                        mock.patch.object(mirrors.subprocess, "run", side_effect=run):
                    if results[-1] == 0:
                        mirrors.install_packages(["xvfb", "fonts-noto-cjk"])
                        self.assertEqual(
                            ["apt-get", "--no-download", "install", "-y", "xvfb", "fonts-noto-cjk"],
                            calls[-1])
                    else:
                        with self.assertRaises(mirrors.subprocess.CalledProcessError) as error:
                            mirrors.install_packages(["xvfb", "fonts-noto-cjk"])
                        self.assertEqual(results[-1], error.exception.returncode)
                        self.assertFalse(any("--no-download" in call for call in calls))
                self.assertEqual([EXPECTED] if len(results) == 1 else [EXPECTED, ORIGINAL], priorities)
                self.assertEqual(1, calls.count(["apt-get", "update"]))

    def test_invalid_package_or_mirror_cannot_start_apt(self):
        with mock.patch.object(mirrors.subprocess, "run") as run, \
                tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "apt-mirrors.txt"
            path.write_text(ORIGINAL.replace("priority:2", "unexpected:2"), encoding="utf-8")
            with mock.patch.object(mirrors, "MIRROR_FILE", path):
                for packages in ([], ["--allow-unauthenticated"], ["xvfb;echo"], ["xvfb"]):
                    with self.subTest(packages=packages), self.assertRaises(ValueError):
                        mirrors.install_packages(packages)
            run.assert_not_called()

    def test_update_and_install_failures_propagate_without_download_retry(self):
        for stage in ("update", "install"):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "apt-mirrors.txt"
                path.write_text(ORIGINAL, encoding="utf-8")
                calls = []

                def run(command, *, check):
                    calls.append(command)
                    if command == ["apt-get", "update"] and stage == "update" or (
                        "--no-download" in command and stage == "install"
                    ):
                        raise mirrors.subprocess.CalledProcessError(100, command)
                    return mirrors.subprocess.CompletedProcess(command, 0)

                with mock.patch.object(mirrors, "MIRROR_FILE", path), \
                        mock.patch.object(mirrors.subprocess, "run", side_effect=run):
                    with self.assertRaises(mirrors.subprocess.CalledProcessError):
                        mirrors.install_packages(["xvfb"])
                self.assertEqual(0 if stage == "update" else 1,
                                 sum(command[0] == "timeout" for command in calls))
                self.assertEqual(EXPECTED, path.read_text(encoding="utf-8"))

    def test_main_routes_explicit_install_and_preserves_mirror_only_callers(self):
        with mock.patch.object(mirrors, "require_hosted_ubuntu_x64"), \
                mock.patch.object(mirrors, "configure_mirror_file") as configure, \
                mock.patch.object(mirrors, "install_packages") as install:
            self.assertEqual(0, mirrors.main([]))
            configure.assert_called_once_with(mirrors.MIRROR_FILE)
            install.assert_not_called()
            configure.reset_mock()
            self.assertEqual(0, mirrors.main(["--install", "xvfb", "fonts-noto-cjk"]))
            configure.assert_not_called()
            install.assert_called_once_with(["xvfb", "fonts-noto-cjk"])

    def test_main_fails_before_accessing_files_outside_hosted_runner(self):
        with mock.patch.dict(os.environ, {}, clear=True), \
                mock.patch.object(mirrors, "configure_mirror_file") as configure:
            self.assertEqual(1, mirrors.main([]))
            configure.assert_not_called()


if __name__ == "__main__":
    unittest.main()
