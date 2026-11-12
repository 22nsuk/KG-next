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
import statistics
import sys
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


def measured_java(command: list[str], output: Path) -> dict:
    """Peak RSS belongs to this complete JVM fork, not to one parse call."""
    rss_file = output / "peak-rss-kib.txt"
    if sys.platform.startswith("linux"):
        command = ["/usr/bin/time", "-f", "%M", "-o", str(rss_file), *command]
    print("+ " + " ".join(command), flush=True)
    with (output / "probe.log").open("w", encoding="utf-8") as log:
        with subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                              start_new_session=os.name != "nt") as process:
            try:
                code = process.wait(timeout=900)
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=False)
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                raise
            peak = None
            if os.name == "nt":
                import ctypes
                from ctypes import wintypes
                class Counters(ctypes.Structure):
                    _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
                        (name, ctypes.c_size_t) for name in (
                            "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                            "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage",
                            "PagefileUsage", "PeakPagefileUsage")]
                counters = Counters()
                counters.cb = ctypes.sizeof(counters)
                query = ctypes.WinDLL("psapi").GetProcessMemoryInfo
                query.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
                query.restype = wintypes.BOOL
                if query(wintypes.HANDLE(int(process._handle)), ctypes.byref(counters), counters.cb):
                    peak = counters.PeakWorkingSetSize
            elif rss_file.exists():
                peak = int(rss_file.read_text().strip()) * 1024
            if code:
                raise subprocess.CalledProcessError(code, command)
    return {"peakRssBytes": peak, "rssScope": "complete isolated JVM including fixture setup"}


def native_experiment(java: str, classpath: str, library: Path, output: Path,
                      samples: int, warmups: int, forks: int) -> None:
    runs = []
    for index, mode in [(-1, "compat")] + [
            (i, lane) for i in range(forks)
            for lane in (("java", "native") if i % 2 == 0 else ("native", "java"))]:
        directory = output / ("compatibility" if index < 0 else f"fork-{index}-{mode}")
        directory.mkdir()
        command = [java, "-Xms256m", "-Xmx1536m", "-Djava.awt.headless=true", "-cp", classpath,
                   "featurecat.lizzie.rules.SgfNativeProbe", str(library), str(ROOT), str(directory),
                   mode, str(samples), str(warmups)]
        memory = measured_java(command, directory)
        report = json.loads((directory / "summary.json").read_text(encoding="utf-8"))
        runs.append({"fork": index, **memory, **report})
    metrics = []
    for fixture in runs[1]["results"]:
        row = {"fixture": fixture["fixture"]}
        histories = set()
        for mode in ("java", "native"):
            cases = [case for fork in runs if fork["mode"] == mode for case in fork["results"]
                     if case["fixture"] == fixture["fixture"]]
            histories.update(case["historySha256"] for case in cases)
            values = sorted(sample["totalMs"] for case in cases for sample in case["samples"])
            allocations = [sample["javaAllocatedBytes"] for case in cases for sample in case["samples"]]
            row[mode] = {"medianMs": statistics.median(values), "p95Ms": values[max(0, (len(values)*95+99)//100-1)],
                         "medianJavaAllocatedBytes": statistics.median(allocations), "samples": len(values)}
        if len(histories) != 1:
            raise RuntimeError(f"Timed paths produced different histories: {fixture['fixture']}")
        metrics.append(row)
    summary = {"binding": "JNA C ABI to Rust cdylib; no pipe in native measurement", "forksPerPath": forks,
               "samplesPerFixturePerFork": samples, "warmupsPerFixturePerFork": warmups,
               "results": metrics, "runs": runs}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    lines = ["# Native SGF experiment", "", summary["binding"], "",
             "| Fixture | Java median ms | Rust/JNA median ms | Java p95 ms | Rust/JNA p95 ms |",
             "| --- | ---: | ---: | ---: | ---: |"]
    for row in metrics:
        lines.append(f"| {row['fixture']} | {row['java']['medianMs']:.3f} | {row['native']['medianMs']:.3f} | "
                     f"{row['java']['p95Ms']:.3f} | {row['native']['p95Ms']:.3f} |")
    lines += ["", "Full samples, compatibility/fallback paths, Java allocation and per-fork peak RSS are in summary.json."]
    (output / "summary.md").write_text("\n".join(lines)+"\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=9)
    parser.add_argument("--warmups", type=int, default=8)
    parser.add_argument("--forks", type=int, default=3, help="Independent JVMs per path in native mode")
    parser.add_argument("--transport", choices=("native", "pipe"), default="native")
    parser.add_argument("--output", type=Path, help="New output directory; must not already exist")
    parser.add_argument("--maven", default=os.environ.get("LIZZIE_MAVEN", "mvn"),
                        help="Maven executable or wrapper path")
    args = parser.parse_args()
    if args.samples < 1 or args.warmups < 0 or args.forks < 1:
        parser.error("samples/forks must be positive and warmups non-negative")
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
    run([cargo, "test", "--workspace", "--locked", "--manifest-path", str(crate),
         "--target-dir", str(build / "native")])
    binary = build / "native/release" / ("kg-sgf-rust-pipe.exe" if os.name == "nt" else "kg-sgf-rust-pipe")
    classes = build / "classes"
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join((str(ROOT / "target/test-classes"), str(ROOT / "target/classes"),
                                classpath_file.read_text().strip()))
    sources = sorted((ROOT / "experiments/sgf-rust/java").rglob("*.java"))
    run([javac, "--release", "17", "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes), *map(str, sources)])
    source_paths = [ROOT / "scripts/run_sgf_rust_probe.py", *sources,
                    ROOT / "experiments/sgf-rust/Cargo.toml", ROOT / "experiments/sgf-rust/Cargo.lock",
                    ROOT / "src/test/java/featurecat/lizzie/gui/SgfPerformanceProbe.java",
                    ROOT / "src/main/java/featurecat/lizzie/util/SgfFileReader.java"]
    source_paths += [ROOT / "src/main/java/featurecat/lizzie/rules" / name for name in
                     ("SGFParser.java", "SgfDocument.java", "Board.java", "BoardData.java",
                      "BoardHistoryList.java", "BoardHistoryNode.java", "Zobrist.java")]
    source_paths += sorted((ROOT / "experiments/sgf-rust/src").rglob("*.rs"))
    source_paths += sorted(path for path in (ROOT / "experiments/sgf-rust/vendor").rglob("*") if path.is_file())
    library = build / "native/release" / ("kg_sgf_rust_probe.dll" if os.name == "nt" else
              "libkg_sgf_rust_probe.dylib" if sys.platform == "darwin" else "libkg_sgf_rust_probe.so")
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
        "nativeLibrarySha256": digest(library),
        "transport": args.transport,
        "sourceSha256": {str(path.relative_to(ROOT)): digest(path) for path in source_paths},
    }
    (output / "manifest.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.transport == "native":
        native_experiment(java, str(classes) + os.pathsep + classpath, library, output,
                          args.samples, args.warmups, args.forks)
        print(f"Evidence: {output}")
        return
    run([java, "-Xmx1536m", "-Djava.awt.headless=true", "-cp", str(classes) + os.pathsep + classpath,
         "featurecat.lizzie.rules.SgfRustProbe", str(binary), str(ROOT), str(output),
         str(args.samples), str(args.warmups)], timeout=900)
    report = json.loads((output / "summary.json").read_text(encoding="utf-8"))
    (output / "summary.md").write_text(summary_markdown(report), encoding="utf-8")
    print(f"Evidence: {output}")


if __name__ == "__main__":
    main()
