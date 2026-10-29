#!/usr/bin/env python3
"""Select supplemental CI on the PR diff and reject missing execution evidence."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
from typing import Any, Mapping, Sequence


ACCEPTANCE_JOBS = ("fox-search", "b11-speed-notice", "native-windows", "sgf-cpu-acceptance")


def documentation_only(paths: Sequence[str]) -> bool:
    """Only conventional prose is exempt; unknown/build/resource paths run all lanes."""
    def is_prose(path: str) -> bool:
        value = PurePosixPath(path)
        if path.startswith("docs/") and value.suffix.lower() in {".md", ".rst"}:
            return True
        return len(value.parts) == 1 and value.suffix.lower() == ".md"

    return all(is_prose(path) for path in paths)


def changed_paths(event: Mapping[str, Any], repository: Path) -> list[str]:
    try:
        pull_request = event["pull_request"]
        base = pull_request["base"]["sha"]
        head = pull_request["head"]["sha"]
    except (KeyError, TypeError) as failure:
        raise ValueError("Pull-request base/head SHA is missing") from failure
    if any(not isinstance(sha, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", sha)
           for sha in (base, head)):
        raise ValueError("Pull-request base/head must be full GitHub commit SHAs")
    # No API pagination/file cap. Deletions and both sides of a rename must select tests.
    result = subprocess.run(
        ["git", "diff", "--no-ext-diff", "--no-textconv", "--name-only",
         "--no-renames", "-z", f"{base}...{head}", "--"],
        cwd=repository, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        timeout=60,
    )
    return [item.decode("utf-8", errors="surrogateescape")
            for item in result.stdout.split(b"\0") if item]


def acceptance_required(event_name: str, event: Mapping[str, Any], repository: Path) -> bool:
    if event_name in {"push", "merge_group"}:
        return True
    if event_name != "pull_request":
        raise ValueError(f"Unsupported CI event: {event_name!r}")
    return not documentation_only(changed_paths(event, repository))


def require_acceptance(needs: Mapping[str, Any]) -> None:
    owner = needs.get("repository-checks")
    if not isinstance(owner, dict) or owner.get("result") != "success":
        raise ValueError("Acceptance selection did not complete successfully")
    outputs = owner.get("outputs")
    required = outputs.get("acceptance_required") if isinstance(outputs, dict) else None
    if required not in ("true", "false"):
        raise ValueError("Acceptance selection is missing or invalid")
    failures = []
    for job in ACCEPTANCE_JOBS:
        record = needs.get(job)
        result = record.get("result") if isinstance(record, dict) else None
        allowed = {"success"} if required == "true" else {"success", "skipped"}
        if result not in allowed:
            failures.append(f"{job}={result!r}")
    if failures:
        raise ValueError("Supplemental acceptance did not pass: " + ", ".join(failures))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "check"))
    args = parser.parse_args()
    try:
        if args.command == "plan":
            event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
            required = acceptance_required(os.environ["GITHUB_EVENT_NAME"], event, Path.cwd())
            value = "true" if required else "false"
            with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8", newline="\n") as output:
                output.write(f"acceptance_required={value}\n")
            print(f"Supplemental acceptance required: {value}")
        else:
            needs = json.loads(os.environ["NEEDS_JSON"])
            if not isinstance(needs, dict):
                raise ValueError("CI dependency results must be an object")
            require_acceptance(needs)
            print("Supplemental acceptance matches the successful selection")
    except (KeyError, OSError, ValueError, subprocess.SubprocessError) as failure:
        print(f"CI acceptance gate failed: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
