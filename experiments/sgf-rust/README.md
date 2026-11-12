# SGF parser and native binding experiments

These opt-in tools compare KG Java parsing with the SGF crate originally pinned
at [lizzieyzy-next-v2 c1718bb](https://github.com/wimi321/lizzieyzy-next-v2/tree/c1718bbefe4c2149f545db5b7bb191c7b37b7b27/crates/sgf).
They do not enable Rust in the application or add Cargo/native libraries to the Maven build.

The [initial pipe results](RESULTS.md) remain a historical baseline. The
[native follow-up](NATIVE_RESULTS.md) evaluates the patched raw compatibility mode
through a real Rust shared library and the repository's existing JNA dependency.
The original crate, fixtures, license and local changes are recorded in
[vendor/sgf/UPSTREAM.md](vendor/sgf/UPSTREAM.md). The upstream decoder/parser/writer
remain available; raw values have a separate type that cannot enter that writer.

## Run

Use JDK 21, Maven, Python 3.10+, Rust 1.96+, and a native linker. Windows uses
MSVC Build Tools; Linux uses GCC and `/usr/bin/time`. The probe compiles Java with
`--release 17`. The first build needs Maven Central and crates.io. Use a disposable
checkout with no other Maven process writing to its `target` directory.

```bash
python3 scripts/run_sgf_rust_probe.py --transport native --forks 3 --samples 9 --warmups 8
```

The default transport is `native`. `--maven /path/to/mvn` or `LIZZIE_MAVEN` chooses
Maven; `JAVA_HOME` chooses Java, and Cargo/rustc come from `PATH`. `--output` must
name a new directory. No application window, engine or GPU is required.

For the original decoded-value pipe comparison:

```bash
python3 scripts/run_sgf_rust_probe.py --transport pipe --samples 5 --warmups 2
```

The executable is `kg-sgf-rust-pipe`; the library is `kg_sgf_rust_probe.dll`,
`libkg_sgf_rust_probe.so`, or `libkg_sgf_rust_probe.dylib`. The native follow-up was
run on Windows x64 and Linux x64; the macOS filename is recognized by the runner,
but macOS/ARM builds and release packaging have not been validated.

## Compatibility boundary

The production Java entry points keep their owners and installation transaction.
`SgfDocument` owns immutable raw syntax; `SGFParser.buildHistory` consumes explicit
captured options and builds a private history. Per-position geometry/hash tables
remain Java-owned. Rust is tested at the syntax/document boundary only.

The native path deliberately retains `SgfFileReader` decoding and Java's existing
input normalization, including compressed setup ranges and structural escaped
newlines. It passes normalized UTF-8 to `parse_kg_raw`, which returns ordered raw
property values without stripping escapes or physical linebreaks. `LZ2` and
`LZOP2` remain second-engine analysis, alongside `LZ`, `LZOP`, and `KGP` metadata.
The original pipe path still sends original bytes to the upstream decoder and
uses its decoded values; the two experiments have different input policies.

Native syntax/resource rejection takes an explicit Java fallback. Reports count
`native`, `Java fallback`, and `Java rejected` separately. Protocol, library-load,
and native panic failures fail the experiment. Timing refuses to count a Java
fallback as native work. Raw mode limits are depth 256, one million events, and
64 MiB input/encoded output. It returns errors rather than synthesizing missing
delimiters. Retained legacy inputs outside this subset remain Java's responsibility.

The corpus includes the four large performance fixtures, four checked-in SGFs,
numbered tags, escaped names/values, compressed setup, malformed input, collections,
deep branches, legacy encodings, and valid/stale primary/secondary provenance.
It compares exact raw events, interpreted events, and KG histories, then serializes
and reopens through the actual Java save/parser classes in a headless rules harness.
All provenance controls must use native parsing on both initial read and reopening.
The digest covers engine identities/type/perspective flags as well as nodes, stones,
move numbers, comments, raw properties, candidates/PVs, ownership, sources and
invalidation. It excludes process-random Zobrist numbers and ephemeral live identities.

A collection retains all syntax; the history experiment explicitly selects its
first game instead of merging games. Decoder behavior is unchanged, including
existing ambiguities for undeclared legacy encodings. Equality to Java therefore
establishes compatibility, not that every decoded comment is linguistically correct.

## Native ownership and failure checks

JNA calls a C ABI with opaque result handles. Rust owns the response allocation;
Java copies its bounded bytes and frees the handle in `finally` before decoding
or returning a document. Each JVM fork has one binding owner. Synchronized parse
and close prevent that owner's library from unloading during a call. Success,
parse-error, repeated free accounting, retry, interrupted entry and closed-owner
checks exercise the actual library. The Rust tests also cover null/oversized/invalid
UTF-8 inputs, response bounds and panic containment.

An in-flight native call cannot be forcibly interrupted by Java. The wrapper
checks interruption before entry and after return, and releases the result before
that second check. The probe verifies interrupted entry/retry; it does not claim
a deterministic mid-call interruption or application stale-result publication test.
The entire probe JVM has an external timeout. Allocation failure can still abort
a native process; panic containment is not an out-of-memory recovery guarantee.

## Measurements and interpretation

Native mode runs compatibility first, then separate Java-only and Rust/JNA-only
JVM forks in alternating order. Each fixture has excluded warmups and retained
samples. There is no extra Java-only history build or cold-child measurement
inside timing loops. No speed threshold is used.

| Metric | Included work |
| --- | --- |
| Java syntax | Existing normalization and complete Java document allocation |
| Rust/JNA syntax | Same normalization, UTF-8 encoding, actual native call, raw parser, full response encoding/copy/free, Java decoding and document allocation |
| Total | Syntax path plus the same detached Java history builder |
| Java allocated bytes | Current JVM thread's allocations for total work; excludes Rust/JNA native allocations |
| Peak RSS | The complete isolated JVM fork, including fixture setup, across all four workloads; not a per-file peak or retained heap |

Raw samples, p50/p95, source/binary hashes, warnings, route decisions, compiler
versions, allocations and RSS are retained. RSS is read from Windows process
counters or Linux `/usr/bin/time`; these platform measures are not interchangeable.
Warmup, GC, scheduling and single-host noise remain. No UI installation, actual
engine synchronization or disk/decode work is timed. This is JNA, not JNI or FFM,
and its results should not be projected onto an unmeasured binding.

Production adoption requires a representative user-facing benefit that justifies
conversion costs, native lifecycle, cancellation/publication behavior and supported
platform packaging. Passing this experiment alone does not enable that adoption.
