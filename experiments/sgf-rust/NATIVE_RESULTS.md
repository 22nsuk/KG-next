# Native follow-up: 2026-11-12

**Keep Java as the production parser.** Raw KG compatibility now preserves
numbered analysis and provenance in the tested corpus. A real JNA-to-Rust
shared-library path was built and executed on Windows x64 and Linux x64.
It has mixed latency results and higher JVM-thread allocation on all four
large workloads. This experiment does not enable native parsing in the app.

Evidence: [Windows](results/2026-11-12-native-windows.json),
[Linux](results/2026-11-12-native-linux.json), [reproduction and boundaries](README.md).
The [original pipe results](RESULTS.md) describe the earlier unadapted library,
not the new raw API or this actual binding.

## What changed

- Fixed the Java scanner regression where `C[note]];W[bb]` became an unknown
  `]W` property and silently lost the white move. Pending names now end at stray
  closing brackets and tree/node boundaries. Escaped brackets and repeated
  values keep their existing meaning; `LZ2`/`LZOP2` remain supported.
- Vendored the pinned upstream SGF crate with its license and unchanged fixtures.
  Its normal decoder/parser/writer are retained. A separate borrowed `RawDocument`
  API keeps raw values, numbered identifiers, exact escapes and physical newlines.
  It does not reinterpret already-stored `KGP` payload hashes.
- Reused the existing Java decoder and input normalizer. Compressed setup ranges
  and structural newline conventions therefore use the same code in both paths.
- Added a real C ABI shared library and Java JNA owner. The returned allocation
  is copied and freed before decoding/publication, including error paths. Native
  syntax/resource rejection is an explicit, counted Java fallback; protocol,
  library-load and panic failures fail the experiment.
- Kept histories, Go rules, serializer, UI/engine transactions and installed
  applications in Java. The experiment calls actual history/save/reopen classes
  in a headless harness; it does not replace the interactive loader.

## Compatibility and checks

Both platforms completed the same 45-case corpus:

| Route | Cases | Meaning |
| --- | ---: | --- |
| Native | 43 | Raw and interpreted events match; history and save/reopen match |
| Java fallback | 1 | Depth 260 exceeds the explicit native depth-256 limit |
| Java rejected | 1 | Missing final closure is rejected by the existing Java entry point too |

These are diagnostic cases, not a general interoperability percentage. The
fallback is preserved data, not a claim that Rust parsed the input. Collections
retain all syntax and explicitly select the first game for the history check.

Valid and stale provenance, both analysis slots, metadata-before-payload order,
setup snapshot ownership, special engine names containing `]` and `\`, short
zero-visit headers, literal `\n` and escaped physical linebreaks are covered.
Every `provenance-*` control must use native parsing for both its original input
and saved output. Candidate/source/invalidation assertions additionally prevent
two equally empty histories from being counted as success.

The comparison detects changed values and missing branches. One hundred
success/error pairs leave zero outstanding native results. Interrupted entry,
retry and closed-owner rejection are exercised. Rust tests check null/oversized
input, invalid UTF-8, resource/response bounds and panic containment. The upstream
tests and property tests remain enabled.

- Windows and Linux: **47 Rust tests passed** on each platform, including 35
  retained upstream tests, nine raw-mode tests and three FFI/protocol tests.
- Windows release build, Clippy with `-D warnings`, formatting and real pipe
  protocol controls passed. Linux release build and workspace tests passed.
- Final Java preservation suite: **507 tests, zero failures/errors/skips**.
  The new regression class has 17 cases; six defect cases fail with the original
  production scanner and pass with the fix. The other controls remain passing.
- An initial local run had four pre-existing English-comment test failures under
  the host locale. The same names/messages reproduce without the bracket fix;
  the suite passes under CI's `en_US` locale. No expectations were weakened.

The tests use a headless rules harness, not a real GPU or native application
window. Existing PR CI remains a separate delivery gate.

## Measurement method

Each path ran in three independent JVMs, alternating Java/native fork order.
Each fixture/JVM had eight excluded warmups and nine samples: 27 measured samples
per fixture per path. Fixed heap options were `-Xms256m -Xmx1536m`. Every sample
includes normalization, document construction, and the same detached history
builder. Native samples also include UTF-8 handoff, JNA, response serialization,
copy/free, Java response decoding and allocation. Native fallback cannot be timed
as Rust. All timed history digests match between paths.

The Windows run used Temurin 21.0.13+9; Linux used Ubuntu/WSL OpenJDK
21.0.13.0.3. Both used Rust 1.96.0. Full tool versions and hashes are in the
manifests. The measured code was based on PR head `426f2689` with these local
changes; source hashes identify the exact state rather than attributing it to
the unmodified commit. Compatibility alone was rerun on both platforms after adding reopen-route
assertions; timing/binding/driver code was unchanged. Those final compatibility
results and their updated source hash are included explicitly.

Median total milliseconds (p95 in parentheses):

| Fixture | Windows Java | Windows Rust/JNA | Linux Java | Linux Rust/JNA |
| --- | ---: | ---: | ---: | ---: |
| Wide variations, 21,845 nodes | 40.745 (47.469) | 54.053 (63.168) | 40.815 (88.293) | 57.548 (90.765) |
| Dense analysis + ownership | 25.495 (27.867) | 24.183 (29.001) | 26.813 (32.413) | 24.528 (28.303) |
| Long multilingual comments | 26.957 (28.534) | 52.569 (58.274) | 26.263 (27.711) | 38.360 (44.725) |
| Dense analysis + long PV | 83.337 (90.475) | 72.362 (83.999) | 53.967 (61.445) | 51.248 (62.219) |

JVM-thread total allocation per call, Windows medians in MiB (Linux results are
within 0.01 MiB of these values):

| Fixture | Java | Rust/JNA |
| --- | ---: | ---: |
| Wide variations | 170.00 | 197.73 |
| Dense analysis + ownership | 31.35 | 43.90 |
| Long multilingual comments | 21.38 | 57.01 |
| Dense analysis + long PV | 54.82 | 81.09 |

This allocation metric excludes Rust and JNA native allocation. The response
buffer and per-property UTF-8 decoding add Java allocation despite faster native
scanning in some workloads. These measurements do not isolate each copy's cost.

Whole-fork peak RSS is also retained: approximately 1164-1193 MiB for Windows
Java and 1000-1124 MiB for Windows native; 1131-1367 MiB for Linux Java and
988-1055 MiB for Linux native. Those peaks include fixture creation and all four
workloads, vary with GC/scheduling, and cannot establish lower retained memory
or a per-file native memory advantage. The Windows/Linux counters differ.

## Decision and limits

The raw compatibility work resolves the tested loss of `LZ2`/`LZOP2`, primary
and secondary provenance, invalidation and KG escapes. The original upstream
decoder is not silently substituted, so known legacy-decoding ambiguities remain
unchanged. This is not a declaration that the entire SGF ecosystem is supported.

The current binding benefits some analysis-heavy medians but regresses wide
trees and long comments, increases Java allocation, and does not consistently
improve p95. Production adoption is not justified by these observations. A later
bounded experiment could test a more compact bulk result representation before
adding native runtime distribution; it should preserve the same compatibility
controls and compare against Java improvements as well.

This is a JNA C ABI experiment, not JNI/FFM. macOS/ARM, release signing/packaging,
full UI load latency, engine synchronization and stale-result UI publication are
unmeasured. A running native call is not forcibly cancellable: interruption is
checked before entry and after return, with native memory released first. Only
interrupted entry/retry was deterministically exercised here. Native OOM can
abort the process. These limits must be addressed or explicitly accepted for
any future product integration; the current production path remains Java.
