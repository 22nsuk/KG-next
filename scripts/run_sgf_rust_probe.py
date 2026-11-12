#!/usr/bin/env python3
"""Build and run the opt-in SGF comparison. No Rust dependency enters the application build."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
CRATE_REVISION = "c1718bbefe4c2149f545db5b7bb191c7b37b7b27"


def run(command: list[str], *, timeout: int = 1800, capture: bool = False) -> str:
    """Bound builds/probes and terminate their process group on POSIX timeouts."""
    print("+ " + " ".join(command), flush=True)
    with subprocess.Popen(
        command, cwd=ROOT, text=True, start_new_session=os.name != "nt",
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    ) as process:
        try:
            output, _ = process.communicate(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=False)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            raise
        if process.returncode:
            raise subprocess.CalledProcessError(process.returncode, command, output)
        return (output or "").strip()


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tool(name: str) -> str:
    suffix = ".exe" if os.name == "nt" else ""
    if name in ("java", "javac") and os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / (name + suffix)
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if found is None:
        raise SystemExit(f"Required tool is missing: {name}")
    return found


def summary_markdown(report: dict) -> str:
    lines = ["# SGF Rust probe", "", f"Started: {report['started']}", "",
             report["scope"], "", report["timing"], "",
             "## Compatibility", "",
             "| Fixture | Result | Difference / error |", "| --- | --- | --- |"]
    for row in report["results"]:
        detail = (row.get("rustError") or row.get("documentDifference")
                  or row.get("javaHistoryError") or row.get("rustHistoryError") or "")
        if row.get("historyEqual") is False and not detail:
            detail = "KG history differs"
        if row.get("historyComparison") and not detail:
            detail = row["historyComparison"]
        lines.append(f"| {row['fixture']} | {row['status']} | {detail.replace('|', '/')} |")
    lines += ["", "## Median milliseconds (compatible fixtures only)", "",
              "| Fixture | Java syntax | Rust parse | Rust full transport + document | Java history | Java total | Rust total | Rust cold transport + document |",
              "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    metrics = ("javaSyntaxMs", "rustParseMs", "rustTransportDocumentMs", "javaHistoryMs",
               "javaEndToEndMs", "rustEndToEndMs", "rustColdTransportDocumentMs")
    for row in report["results"]:
        if "measurements" in row:
            cells = " | ".join(f"{row['measurements'][metric]['median']:.3f}" for metric in metrics)
            lines.append(f"| {row['fixture']} | {cells} |")
    lines += ["", "Raw samples, hashes, warnings and comparison controls are in summary.json.", ""]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--warmups", type=int, default=2)
    parser.add_argument("--output", type=Path, help="New output directory; must not already exist")
    parser.add_argument("--maven", default=os.environ.get("LIZZIE_MAVEN", "mvn"),
                        help="Maven executable or wrapper path")
    args = parser.parse_args()
    if args.samples < 1 or args.warmups < 0:
        parser.error("samples must be positive and warmups non-negative")
    build = ROOT / "target/sgf-rust"
    build.mkdir(parents=True, exist_ok=True)
    output = args.output or build / (datetime.now(timezone.utc).strftime("run-%Y%m%dT%H%M%SZ-") + str(time.time_ns()))
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    crate = ROOT / "experiments/sgf-rust/Cargo.toml"
    classpath_file = build / "classpath.txt"
    run([args.maven, "-B", "-DskipTests", "test-compile", "dependency:build-classpath",
         "-Dmdep.includeScope=test", f"-Dmdep.outputFile={classpath_file}"])
    cargo, rustc, java, javac = (tool(name) for name in ("cargo", "rustc", "java", "javac"))
    run([cargo, "build", "--release", "--locked", "--manifest-path", str(crate),
         "--target-dir", str(build / "native")])
    binary = build / "native/release" / ("kg-sgf-rust-probe.exe" if os.name == "nt" else "kg-sgf-rust-probe")
    classes = build / "classes"
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join((str(ROOT / "target/test-classes"), str(ROOT / "target/classes"),
                                classpath_file.read_text().strip()))
    source = ROOT / "experiments/sgf-rust/java/featurecat/lizzie/rules/SgfRustProbe.java"
    run([javac, "--release", "17", "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes), str(source)])
    source_paths = [ROOT / "scripts/run_sgf_rust_probe.py", source,
                    ROOT / "experiments/sgf-rust/src/main.rs",
                    ROOT / "experiments/sgf-rust/Cargo.toml", ROOT / "experiments/sgf-rust/Cargo.lock",
                    ROOT / "src/test/java/featurecat/lizzie/gui/SgfPerformanceProbe.java",
                    ROOT / "src/main/java/featurecat/lizzie/util/SgfFileReader.java"]
    source_paths += [ROOT / "src/main/java/featurecat/lizzie/rules" / name for name in
                     ("SGFParser.java", "SgfDocument.java", "Board.java", "BoardData.java",
                      "BoardHistoryList.java", "BoardHistoryNode.java", "Zobrist.java")]
    metadata = {
        "kgRevision": run(["git", "rev-parse", "HEAD"], capture=True),
        "kgWorktreeStatus": run(["git", "status", "--short"], capture=True),
        "trackedDiffSha256": hashlib.sha256(run(["git", "diff", "--no-ext-diff", "HEAD"], capture=True).encode()).hexdigest(),
        "upstreamCrateRevision": CRATE_REVISION,
        "rustc": run([rustc, "--version"], capture=True),
        "cargo": run([cargo, "--version"], capture=True),
        "java": run([java, "-version"], capture=True),
        "maven": run([args.maven, "--version"], capture=True).splitlines()[0],
        "python": platform.python_version(),
        "platform": platform.platform(),
        "binarySha256": digest(binary),
        "sourceSha256": {str(path.relative_to(ROOT)): digest(path) for path in source_paths},
    }
    (output / "manifest.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    run([java, "-Xmx1536m", "-Djava.awt.headless=true", "-cp", str(classes) + os.pathsep + classpath,
         "featurecat.lizzie.rules.SgfRustProbe", str(binary), str(ROOT), str(output),
         str(args.samples), str(args.warmups)], timeout=900)
    report = json.loads((output / "summary.json").read_text(encoding="utf-8"))
    (output / "summary.md").write_text(summary_markdown(report), encoding="utf-8")
    print(f"Evidence: {output}")


if __name__ == "__main__":
    main()
