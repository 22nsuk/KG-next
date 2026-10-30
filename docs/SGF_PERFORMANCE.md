# SGF read and serialization measurements

Interactive SGF opening from the chooser, a single dropped file, and the recent
file menu reads and decodes on `lizzie-sgf-read`. There is one active read and at
most one waiting selection. A newer accepted selection retires earlier results. Existing quick-analysis and
AI Coach teardown admission runs first; a rejected continuation cannot retire
the first accepted open. The read/edit snapshot is captured after admission. Before
applying text on the EDT, the frame checks the selection, original frame/config,
window lifetime, explicit game edits and foreground game/analysis ownership.
Ordinary engine analysis is not an authored edit. An immediate Open after Save
waits for accepted saves and their metadata publication, including writes
accepted while waiting.

Parsing, board application and serialization still use the original EDT/global
board context. In particular, `parseSgf` temporarily changes board dimensions
and Zobrist tables: a detached return value does not make it thread safe.
The synchronous boolean `loadFile`/`SGFParser.load` contracts remain available
for batch, saved-list and startup callers. GIB loading is also still synchronous.
`openFileAsync` completes after parsing/application; its result does not promise
engine synchronization. Navigation and successful-open callbacks retain their
existing next-EDT-turn ordering.

## Opt-in phase recording

The Java Flight Recorder event `kg.sgf.Phase` is disabled by default. It contains
phase names and durations, not SGF text, filenames or engine commands. Packaged
runtimes already include `jdk.jfr`. Enable this event in a JFR recording when
investigating a particular file. The manual probe below does so automatically.

| Phase | What is timed |
| --- | --- |
| `file-read` | Reading the complete byte sequence once |
| `detect-decode` | Legacy charset detection/fallback and byte-to-text decoding |
| `open-edit-capture` | The existing explicit-edit snapshot used to protect the current game |
| `board-clear` | Preparing the board for file application |
| `parse-build-tree` | Token processing and history construction, which are interleaved |
| `file-apply` | The interactive EDT application envelope, including clear/parse and UI updates |
| `board-finalize` | Deferred cursor navigation and final board cleanup |
| `engine-sync-attempt` | One coordinator worker attempt, including engine response waits |
| `save-capture` | Frozen topology and node payload copies |
| `save-serialize` | Formatting the captured history as SGF |

Nested events overlap: do not add `file-apply` to its clear/parse children.
Engine attempts exclude queue/retry delays and are not an engine search speed
measurement. Offline attempts do not measure a native engine. The parser already
applies nodes while reading tokens; these events do not claim an independent
pure parse versus pure board-install split.

## Reproduce with the actual window

`src/test/java/featurecat/lizzie/gui/SgfPerformanceProbe.java` is an explicit
manual probe, not a timing threshold in CI. It starts the real Korean application
in its own work directory with no engine, generates deterministic SGFs, checks
their tree/comment/analysis content, saves and immediately reopens them, and
verifies semantic equality. It leaves the fixtures, JFRs and `summary.json` in
the selected **new** output directory. It does not use the installed application
or personal configuration.

Compile the test classes and resolve their classpath:

```sh
mvn -B -Dfmt.skip=true -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/sgf-probe-classpath.txt -Dmdep.includeScope=test
```

On Linux, run in an operational desktop (Xvfb with a window manager is also
supported):

```sh
java -Xmx2g -Djava.awt.headless=false \
  -cp "target/test-classes:target/classes:$(cat target/sgf-probe-classpath.txt)" \
  featurecat.lizzie.gui.SgfPerformanceProbe target/sgf-measurement 3 1
```

On Windows, use PowerShell 7 and the classpath separator `;`:

```powershell
$probeClasspath = 'target/test-classes;target/classes;' +
  (Get-Content -Raw target/sgf-probe-classpath.txt).Trim()
java -Xmx2g -Djava.awt.headless=false -cp $probeClasspath `
  featurecat.lizzie.gui.SgfPerformanceProbe target/sgf-measurement 3 1
```

The final arguments are retained samples and excluded warmups. The probe records
wall time, process CPU, EDT CPU, queued EDT callback delay and GC events. Queue
delay is not physical display FPS. Samples use warm filesystem caches; no
cold-disk, network share, native engine or end-user machine claim follows.
The old reader comparison runs only the former detect/character-at-a-time read
algorithm against the same bytes; it is not an old full-application benchmark.

The workload set includes 21,845 nodes with 5,461 branching points, 301 nodes
with 32 candidates and 361 ownership values per node, and 201 nodes with long
escaped multilingual comments. Generated fixture hashes and source identities
belong with any reported measurement so results cannot silently outlive changes.

## Interpretation and next boundary

On 2026-10-30, revision `8b5f27d9f2363485b26e9e9f243cb426be560ea8`
was measured in the actual offline window with Ubuntu Java
21.0.12.1, WSL2/Xvfb/Openbox, 24 logical processors and a 2 GiB heap. One warmup
was excluded and three samples retained per workload. Values below are medians
in milliseconds, from the opt-in JFR phases; these are synthetic workloads, not
a throughput guarantee.

| Workload (input bytes) | File read | Detect/decode | Parse/tree | Save capture | Serialize |
| --- | ---: | ---: | ---: | ---: | ---: |
| Wide variations (1,551,041) | 0.73 | 1.99 | 44.33 | 42.54 | 42.81 |
| Candidates + ownership (1,603,477) | 0.48 | 1.96 | 77.84 | 1.33 | 29.42 |
| Long multilingual comments (4,825,457) | 1.54 | 5.31 | 51.05 | 0.55 | 40.96 |

Open wall-time median/maximum was respectively 121.51/140.43, 119.16/176.45 and
79.41/86.78 ms. Immediate reopen medians were 129.31, 113.61 and 83.10 ms.
The wide-tree explicit-edit snapshot alone took a median 11.22 ms before reading;
its completion checks also traverse the authored state. This is a cost to keep
visible when deciding the next optimization. Save EDT queue-delay maxima reached
83.56 ms for that tree, while parse/application also continued to delay EDT
callbacks. These are observed queue delays, not rendered frame durations. The wide-tree
save also spent a median 106.85 ms in the worker-side atomic write, showing why
save wall time (200.78 ms) must be separated from EDT time. Filesystem flush
latency varied between runs; three samples are not a stable disk benchmark.

The isolated old/current reader algorithm medians were 48.75/2.55, 68.68/3.19 and
132.14/7.57 ms. This comparison includes the old extra detection pass and
character-at-a-time loop, and uses warm caches. It does not compare complete
old/new application opening. All fixtures passed node/branch, authored-comment,
candidate/ownership and save/reopen semantic checks; raw per-sample JFRs and
source/fixture hashes were retained with the local measurement record.

Reading and decoding can be isolated without giving a worker the live board.
The retained parser, explicit-edit capture, save capture and serialization can
still delay EDT work on large trees. Use the phase measurements to choose the
next boundary; moving a global-context parser or serializer wholesale to a
worker would change their ownership contract. Synthetic offline timings should
be supplemented with the user's representative files and actual engine when
investigating a specific slow opening.

Automatic-save interruption and recovery are a separate correctness question.
Individual SGF/manifest/preview replacement does not establish a multi-file
transaction; throughput measurements do not validate crash recovery.

## Tree capture and analysis parsing follow-up

The follow-up at `fb432640504d35780d6e1ac5b47b937f049ad3a3` reduces repeated
work in the existing context:

- `BoardData.clone()` lets the constructor detach the core arrays once instead
  of copying them before construction and again afterward. The per-node payload
  lock and existing property/analysis copies remain unchanged. Topology capture
  still releases the original history lock before copying node payloads.
- Move/comment output uses literal appends and literal escaping. Nodes without
  either analysis or an opaque `KGP` property need no metadata JSON.
- Analysis headers, candidates and ownership use the same six ASCII whitespace
  delimiters without regex tokenization. Ordinary nonnegative integer visits
  take a bounded direct path; legacy abbreviations, decimals, signs, formatted
  values and overflow retain their previous fallback.
- The existing MultiGo branch-format heuristic inspects the suffix instead of
  matching the complete SGF. All three parser entry implementations retain the
  same final-parenthesis and whitespace conditions.

The parser and serializer still run in their original global/EDT context. This
does not change file-open ownership, the save queue, completion/retry rules or
automatic-save recovery. The save snapshot still contains detached arrays; it
does not share the live stones or move-number arrays to obtain these savings.

On 2026-10-30, the unchanged manual probe compared main
`e37c87ed883046daf8d72f9eec4f241fb6252951` with the follow-up revision above.
Both used Ubuntu OpenJDK 21.0.12.1 on WSL2, Xvfb/Openbox, 24 logical processors,
`-Xmx2g`, no engine and warm filesystem caches. Each workload had five excluded
warmups followed by ten retained samples. Production class, probe and dependency
classpath hashes were checked against the corresponding known builds before
each run. All 45 saved files, including warmups, were byte-identical between
revisions; the probe's tree/comment/candidate/ownership/reopen checks also passed.

| Phase / workload | Before median (min–max), ms | After median (min–max), ms |
| --- | ---: | ---: |
| Capture / wide variations | 39.69 (33.17–45.39) | 21.54 (20.71–22.75) |
| Serialize / wide variations | 35.87 (32.09–53.48) | 20.88 (13.16–23.25) |
| Parse/tree / candidates + ownership | 56.37 (53.02–82.88) | 34.32 (29.14–48.91) |
| Reopen parse/tree / candidates + ownership | 56.08 (53.39–64.67) | 34.12 (29.45–42.77) |
| Serialize / long multilingual comments | 34.10 (32.18–54.75) | 5.95 (5.65–9.64) |

Wide-tree save EDT CPU fell from a median 76.10 to 43.36 ms. Its maximum observed
queued-callback delay was 87.64 before and 50.23 ms after; these are finite-sample
queue delays, not frame-rate or worst-case latency guarantees. Save wall time
still includes variable filesystem flush time. Parsing, explicit-edit snapshots,
capture and serialization can still occupy the EDT.

Separate JFR profiling at the same source revisions estimated wide-tree capture
allocation at 216.26 → 91.18 MiB per operation, serialization at 86.92 → 26.55 MiB,
and dense-analysis parsing at 78.59 → 47.78 MiB. These estimates sum new-TLAB sizes
and outside-TLAB allocation sizes within each phase; they are neither exact
object counts nor retained heap measurements. Profiling was separate from the
timing runs.

The earlier two-warmup/five-sample final run had wide-tree capture values of
21.17–69.69 ms. The longer warmup/sample protocol was fixed before the paired
rerun, and all ten samples were retained. One attempted paired baseline reused
stale Maven classes after archive restoration; it was rejected as an invalid
build before reporting the valid pair above. Source/class preflight, raw results,
fixture hashes and that exclusion were retained with the measurement evidence.

## Analysis token parsing follow-up

Revision `29ac14849e2680a936b1263de0ef0885c0f5e7aa` reads analysis detail
regions directly from the original SGF property text. It avoids splitting the
whole detail line, all candidates and each candidate's fields into intermediate
strings/arrays. Candidate limits stop further candidate parsing while ownership
is still read. PV length limits stop retaining coordinates but continue scanning
for the full `pvVisits` suffix. Ownership is accumulated in a primitive buffer
and copied once into the existing immutable compact list, without a temporary
boxed list or a second compact-list copy during adoption.

Header fields, numeric conversion, the literal ` info ` / `ownership` separators,
ASCII whitespace and edge trimming retain their existing interpretation. The
four LZ tags, primary/secondary suggestion policies, malformed-tail behavior,
original payload digests and KGP adoption are unchanged. Parsing remains in the
existing EDT/global context; this revision does not change the serializer or
file-open/save completion contracts.

The actual-window probe retains the original three fixtures and adds a
3,040,737-byte fixture with 151 nodes, 32 candidates per node, 64 PV coordinates,
64 `pvVisits` values and 361 ownership values. The probe checks every coordinate,
visit count and ownership value after opening and reopening, as well as the
existing tree/comment/evaluation checks.

On 2026-10-30, main `0212ead4e2ddbba867a8659f33f013d4f95915eb` and the
revision above were compared with the same extended probe, Ubuntu Java 21 on
WSL2/Xvfb/Openbox, `-Xmx2g`, no engine and warm filesystem caches. Each fixture
used five excluded warmups and ten retained samples in each revision. Clean
builds and source/compiled-artifact hashes were checked before and after running.
All 60 saved SGFs, including warmups, were byte-identical between revisions.

| Parse/tree phase | Before median (min–max), ms | After median (min–max), ms |
| --- | ---: | ---: |
| Candidates + ownership / open | 37.01 (33.29–55.72) | 32.05 (28.83–49.08) |
| Candidates + ownership / reopen | 41.25 (32.92–55.24) | 34.00 (30.75–44.17) |
| Long PV + visits / open | 61.70 (59.02–80.84) | 56.06 (54.46–59.99) |
| Long PV + visits / reopen | 66.20 (61.04–87.87) | 56.43 (54.59–64.90) |
| Wide variations / open | 41.89 (40.17–46.32) | 39.55 (38.19–42.60) |
| Long comments / open | 28.38 (23.90–32.42) | 27.73 (25.77–42.96) |

Open EDT CPU medians were 52.13 → 45.58 ms for the original dense fixture and
74.59 → 68.71 ms for the long-PV fixture. This is one prespecified sequential
pair, with all ten samples retained. Sample ranges overlap, and unchanged
workloads also vary; these observations do not establish a universal percentage
improvement or a latency bound. They do not measure a native engine, cold disk,
physical display FPS or the installed user's session. Raw samples, fixture and
source identities, artifact pre/postflight and saved-output comparisons were
retained with the local measurement evidence.
