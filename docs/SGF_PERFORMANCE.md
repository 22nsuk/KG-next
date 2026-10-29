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

On 2026-10-30, the actual offline window was measured with Ubuntu Java
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
