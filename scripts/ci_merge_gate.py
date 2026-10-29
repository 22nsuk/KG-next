#!/usr/bin/env python3
"""Select existing supplemental CI lanes and validate their same-run results."""

from __future__ import annotations

import argparse
from fnmatch import fnmatchcase
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Iterable, Mapping


COMMON_PATHS = (
    '.github/workflows/ci.yml',
    '.github/rulesets/main-ci.json',
    'scripts/ci_merge_gate.py',
    'scripts/test_ci_merge_gate.py',
    'scripts/run_local_ci.py',
    'scripts/test_run_local_ci.py',
    'scripts/prefer_ubuntu_apt_mirror.py',
    'scripts/test_prefer_ubuntu_apt_mirror.py',
    'pom.xml',
    '.mvn/**',
    'src/test/java/featurecat/lizzie/gui/DesktopProbeProcess.java',
)

SUPPLEMENTAL_PATHS = {
    'fox-search': (
        '.github/workflows/fox-search.yml',
        'scripts/prefer_ubuntu_apt_mirror.py',
        'scripts/test_prefer_ubuntu_apt_mirror.py',
        'scripts/test_publish_release_request.py',
        'pom.xml',
        'src/main/java/featurecat/lizzie/Config.java',
        'src/main/java/featurecat/lizzie/analysis/GetFoxRequest.java',
        'src/main/java/featurecat/lizzie/gui/**',
        'src/main/resources/l10n/**',
        'src/test/java/featurecat/lizzie/analysis/GetFoxRequestTest.java',
        'src/test/java/featurecat/lizzie/gui/FoxKifuDownload*Test.java',
        'src/test/java/featurecat/lizzie/gui/DesktopProbeProcess.java',
        'src/test/java/**/LocalizationResourceParityTest.java',
    ),
    'b11-speed-notice': (
        '.github/workflows/b11-speed-notice.yml',
        'src/main/java/featurecat/lizzie/util/B11ModelNotice.java',
        'src/main/java/featurecat/lizzie/analysis/Leelaz.java',
        'src/main/java/featurecat/lizzie/analysis/remote/ZhiziGtpTransport.java',
        'src/main/java/featurecat/lizzie/gui/B11SpeedNoticePanel.java',
        'src/main/java/featurecat/lizzie/gui/KataGoAutoSetupDialog.java',
        'src/main/java/featurecat/lizzie/gui/LizzieFrame.java',
        'src/main/resources/l10n/**',
        'src/test/java/featurecat/lizzie/**/B11*Test.java',
    ),
    'native-focus': (
        '.github/workflows/move-focus-acceptance.yml',
        'scripts/build_katago_source.py',
        'scripts/package_katago_source_windows.py',
        'src/main/java/featurecat/lizzie/analysis/**',
        'src/main/java/featurecat/lizzie/enginegame/**',
        'src/main/java/featurecat/lizzie/gui/LizzieFrame.java',
        'src/main/java/featurecat/lizzie/gui/Menu.java',
        'src/main/java/featurecat/lizzie/gui/NewEngineGameDialog.java',
        'src/main/java/featurecat/lizzie/gui/EngineGameBatchSpecCapture.java',
        'src/main/java/featurecat/lizzie/gui/EngineGameParameterControls.java',
        'src/main/java/featurecat/lizzie/gui/EngineGameParametersDialog.java',
        'src/main/java/featurecat/lizzie/gui/SetKataEngines.java',
        'src/main/java/featurecat/lizzie/gui/SetKataPDA.java',
        'src/test/java/featurecat/lizzie/gui/MoveFocusNativeAcceptanceIT.java',
        'src/test/java/featurecat/lizzie/gui/EngineGameParametersAcceptanceIT.java',
        'src/main/resources/katago-assets.json',
        'scripts/build_katago_windows_dependencies.py',
        'scripts/katago_source_targets.py',
        'scripts/audit_katago_source_bundle.py',
        'src/main/java/featurecat/lizzie/rules/**',
    ),
    'acceptance-integration': (
        '.github/workflows/acceptance-integration.yml',
        'scripts/run_acceptance.py',
        'scripts/prepare_cpu_engine_acceptance.py',
        'src/main/resources/katago-assets.json',
        'src/test/java/featurecat/lizzie/gui/SgfUiAcceptanceIT.java',
        'src/test/java/featurecat/lizzie/gui/RealCpuEngineAcceptanceIT.java',
        'src/test/resources/featurecat/lizzie/gui/d3-*.sgf',
        'src/test/resources/featurecat/lizzie/gui/d4-*.sgf',
        'scripts/test_run_acceptance.py',
        'scripts/test_prepare_cpu_engine_acceptance.py',
    ),
}


def required_supplemental_jobs(paths: Iterable[str]) -> dict[str, bool]:
    # fnmatch crosses directory separators, conservatively including nested dependencies.
    changed = tuple(paths)
    common = any(fnmatchcase(path, pattern) for path in changed for pattern in COMMON_PATHS)
    return {
        job: common or any(fnmatchcase(path, pattern) for path in changed for pattern in patterns)
        for job, patterns in SUPPLEMENTAL_PATHS.items()
    }


def plan_for_event(event: str, root: Path = Path(".")) -> dict[str, bool]:
    if event in ("push", "merge_group"):
        # Verify the complete target/queued tree, including multi-commit pushes and queued PRs.
        return dict.fromkeys(SUPPLEMENTAL_PATHS, True)
    if event != "pull_request":
        raise ValueError(f"Unsupported CI event: {event}")
    parents = subprocess.check_output(
        ["git", "show", "-s", "--format=%P", "HEAD"], cwd=root, timeout=30
    ).split()
    if len(parents) != 2:
        raise ValueError("PR scope requires the checked-out two-parent merge commit")
    # No REST pagination/300-file limit; --no-renames includes both the old and new path.
    raw = subprocess.check_output(
        ["git", "diff", "--name-only", "--no-renames", "-z",
         parents[0].decode("ascii"), "HEAD", "--"],
        cwd=root, timeout=60,
    )
    return required_supplemental_jobs(os.fsdecode(path) for path in raw.split(b"\0") if path)


def verify_supplemental_results(needs: Mapping[str, object]) -> list[str]:
    if not isinstance(needs, dict):
        raise ValueError("Missing CI needs object")
    scope = needs.get("change-scope")
    if not isinstance(scope, dict) or scope.get("result") != "success":
        raise ValueError("change-scope did not succeed; applicability is unknown")
    outputs = scope.get("outputs")
    if not isinstance(outputs, dict) or set(outputs) != set(SUPPLEMENTAL_PATHS):
        raise ValueError("Missing or unexpected supplemental scope outputs")
    messages = []
    failures = []
    for job in SUPPLEMENTAL_PATHS:
        selected = outputs[job]
        if selected not in ("true", "false"):
            raise ValueError(f"Invalid applicability for {job}: {selected!r}")
        observed = needs.get(job)
        result = observed.get("result") if isinstance(observed, dict) else None
        expected = "success" if selected == "true" else "skipped"
        if result != expected:
            failures.append(f"{job}: expected {expected}, got {result!r}")
        messages.append(f"{job}: " + ("executed successfully" if selected == "true"
                                       else "not applicable (not executed)"))
    if failures:
        raise ValueError("Supplemental CI did not satisfy its scope: " + "; ".join(failures))
    return messages


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    plan = commands.add_parser("plan")
    plan.add_argument("--event", required=True)
    plan.add_argument("--output", type=Path, required=True)
    commands.add_parser("check")
    args = parser.parse_args()
    try:
        if args.command == "plan":
            selected = plan_for_event(args.event)
            text = "".join(f"{job}={str(required).lower()}\n"
                           for job, required in selected.items())
            with args.output.open("a", encoding="utf-8", newline="\n") as output:
                output.write(text)
            print(text, end="")
        else:
            for message in verify_supplemental_results(json.loads(os.environ["NEEDS_JSON"])):
                print(message)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as failure:
        print(f"CI merge gate: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
