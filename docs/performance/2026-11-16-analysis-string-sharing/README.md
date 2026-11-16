# Share imported analysis tokens before allocating substrings

## Decision and scope

Adopt bounded string sharing while importing SGF analysis. On the existing long-PV fixture,
this reduces the loaded history graph from **33.227 MiB to 4.731 MiB** (85.8%) and reduces
first-edit operation CPU time, including snapshot capture. It adds work to parsing: the first read's calling-thread CPU
median rises from **55.824 ms to 59.720 ms**. This is a memory and first-edit tradeoff,
not a demonstrated improvement in every operation or in overall GUI response time.

The baseline is main [`74dd0fe2`](https://github.com/22nsuk/KG-next/commit/74dd0fe2b2038d956195a1977cde32fb14a57500),
including the insertion repair and Leela 0.11 cleanup. The
[earlier follow-up](../2026-11-15-edit-snapshot-follow-ups/README.md) identified the remaining
string-sharing cost during snapshot capture. This experiment moves some of that preparation
to the existing import boundary; it does not adopt the previously rejected capture-loop/table
variants.

The long fixture contains **151 nodes, 32 candidates per node, 64 PV tokens and 64 visit
tokens per candidate: 618,496 PV/visit tokens**. These are deterministic, repeat-heavy
synthetic records, not a representative real-game sample.

## Implementation and preserved contracts

- One `SGFParser.AnalysisImport` owns one `BoardData.AnalysisStrings` table. The backing
  array is allocated lazily; the table is not retained by the loaded tree or a saved snapshot.
- Look up a retained coordinate/PV/visit token directly in the original text region before
  allocating a substring. Preserve the exact UTF-16 content, case, leading zeros and signs.
  The existing numeric/ownership parser, token boundaries, candidate/PV limits, analysis
  digest and legacy `pvVisits` tail interpretation remain in place.
- Keep the existing bounds: 4,096 entries, at most four probes, no eviction, and no table
  lookup for values longer than 64 UTF-16 units. A miss simply retains a separate string.
- Keep the original capture-time `reuse(String)` algorithm and independent copies of
  candidates and mutable lists. Engine results, other input paths and candidates modified
  after import still require this fallback. There is no global intern pool, shared mutable
  list, import-origin marker or new configuration option.

The normal asynchronous file-open path prepares analysis on `SgfReadQueue`'s background
`lizzie-sgf-read` executor through `prepareFileGame`/`buildFileHistory`. User edit actions
capture their undo/trial state synchronously on the Swing event thread. This makes the
location of the cost relevant, but does not prove an improvement in visible responsiveness.
Other import APIs run on their caller's thread. The GUI open path also retains its existing
event-thread edit checks and final application work.

Existing capture sharing means that the baseline can already have shared strings after an
undo. The measured first-edit reduction must not be generalized to every subsequent edit.

## Long-PV measurements

Baseline → candidate, pooled medians of 15 measured samples per operation and variant,
from three fresh JVMs each. CPU and allocation cover the synchronous calling thread.
Allocated MiB is cumulative allocation during the operation, not peak memory.

| Operation | Wall ms | Calling-thread CPU ms | Allocated MiB |
| --- | ---: | ---: | ---: |
| Read before delete | 67.754 → 73.017 | 55.824 → 59.720 | 70.625 → 42.137 |
| First delete | 13.512 → 5.847 | 13.123 → 5.521 | 3.836 → 3.836 |
| Undo delete | 1.096 → 0.551 | 1.097 → 0.552 | 0.528 → 0.528 |
| Redo delete | 6.176 → 6.373 | 6.086 → 6.376 | 3.830 → 3.829 |
| Read before insertion | 63.950 → 62.516 | 56.636 → 60.134 | 70.625 → 42.137 |
| First insertion | 11.440 → 6.111 | 11.438 → 6.033 | 4.439 → 4.437 |
| Undo insertion | 1.256 → 1.161 | 1.257 → 1.163 | 0.612 → 0.612 |
| Read before trial | 63.856 → 72.949 | 54.131 → 63.960 | 70.625 → 42.137 |
| Trial begin, board core | 12.018 → 5.936 | 11.807 → 5.938 | 3.837 → 3.837 |
| Trial restore, board core | 0.288 → 0.622 | 0.289 → 0.623 | 0.485 → 0.485 |

The first delete, insertion and trial-begin CPU medians decrease by **57.9%, 47.3% and
49.7%**, respectively. First-read allocation decreases by **40.3%**. Snapshot/list allocation
remains essentially unchanged: the capture still independently copies mutable containers.

Read-plus-edit sums include only the indicated timed operations. They exclude validation,
move-list settlement and the intervening trial move; they are not continuous user-workflow
times. Each sum is formed within one sample before taking its median.

| Sum of timed operations | Wall ms | Calling-thread CPU ms | Allocated MiB |
| --- | ---: | ---: | ---: |
| Read + first delete | 83.079 → 78.970 | 68.757 → 66.012 | 74.461 → 45.974 |
| Read + delete + undo | 83.513 → 79.749 | 70.073 → 66.398 | 74.989 → 46.501 |
| Read + insertion + undo | 77.235 → 70.937 | 69.885 → 68.670 | 75.675 → 47.186 |
| Read + trial begin + restore | 82.145 → 78.931 | 66.305 → 69.944 | 74.946 → 46.459 |

The combined CPU result is mixed: the trial sum rises by 5.5%, and the per-JVM delete sums
below do not consistently improve. Reduced allocation, loaded-graph size and first-edit
operation cost support adoption; the results do **not** establish a general throughput improvement.
The slower trial-restore and redo medians are retained above rather than treating unchanged
paths as automatically faster.

### Per-JVM read + delete + undo sums

| JVM pair | Wall ms | Calling-thread CPU ms | Allocated MiB |
| --- | ---: | ---: | ---: |
| 1 | 106.784 → 86.383 | 72.922 → 73.682 | 74.977 → 46.502 |
| 2 | 83.157 → 74.150 | 69.309 → 67.265 | 74.989 → 46.497 |
| 3 | 83.513 → 79.749 | 63.415 → 65.399 | 74.989 → 46.501 |

A GC occurs in 11/15 first-read samples on each side and 1/15 first-delete samples on each
side. Read-before-trial collections occur in 11/15 → 14/15 samples. Per-sample GC count/time,
process CPU and pre-operation heap context are retained in the raw data. Three JVMs per
variant do not establish statistical significance, and unmodified controls also vary.

## Other existing fixtures

The same read + delete + undo sums, including the long fixture for comparison:

| Fixture | Wall ms | Calling-thread CPU ms | Allocated MiB |
| --- | ---: | ---: | ---: |
| wide-variations | 117.109 → 102.511 | 103.070 → 92.766 | 209.173 → 209.173 |
| dense-analysis-ownership | 41.735 → 38.000 | 34.184 → 35.608 | 41.192 → 37.227 |
| long-multilingual-comments | 24.401 → 27.808 | 24.299 → 27.829 | 45.782 → 45.782 |
| dense-analysis-long-pv-visits | 83.513 → 79.749 | 70.073 → 66.398 | 74.989 → 46.501 |
| late-game-main-line | 2.173 → 2.211 | 2.176 → 2.200 | 3.649 → 3.649 |
| late-game-review | 18.265 → 15.227 | 14.736 → 14.587 | 28.923 → 28.923 |

Records without analysis retain the same allocation and graph sizes at the displayed
precision. Their timing shifts are observations, not evidence of a benefit from string
sharing. Shorter analysis also saves memory, with a small increase in this combined CPU
median. All six fixtures preserve their original semantic and insertion checks.

## Object-graph memory

Separate, untimed fresh-JVM measurements, baseline → candidate. These are sums of
`Instrumentation.getObjectSize` over identity-distinct objects reachable from the specified
roots. The union counts shared objects once. Static fields, enum constants and the snapshot's
Board-owner back-reference are excluded; other application/VM roots are not subtracted.
These values are **not exclusive retained heap, total process memory or peak RAM**.

| Fixture | Loaded history MiB | Snapshot MiB | Remaining live + snapshot MiB |
| --- | ---: | ---: | ---: |
| wide-variations | 81.630 → 81.630 | 14.493 → 14.493 | 74.087 → 74.087 |
| dense-analysis-ownership | 7.879 → 3.912 | 2.892 → 2.892 | 2.916 → 2.905 |
| long-multilingual-comments | 5.653 → 5.653 | 5.022 → 5.022 | 5.026 → 5.026 |
| dense-analysis-long-pv-visits | 33.227 → 4.731 | 3.888 → 3.888 | 4.070 → 3.919 |
| late-game-main-line | 1.370 → 1.370 | 0.718 → 0.718 | 0.722 → 0.722 |
| late-game-review | 10.427 → 10.427 | 6.611 → 6.611 | 6.615 → 6.615 |

Loaded history is measured before editing. Snapshot and union are measured after deleting
the root's selected continuation, matching the existing footprint probe. They describe
different states and should not be added together. The wide tree keeps other root siblings,
which explains why its union is larger than the snapshot alone.

The long fixture's loaded graph has **623,792 → 1,298 `String` objects**. Neither the import
nor the capture table is retained in the measured history graph. The snapshot stays at
3.888 MiB because main already shares its strings during capture; the main additional
saving is available immediately after parsing, before any edit.

## Method, reproduction and limits

- Measured on **2026-11-16 KST**; exact UTC timestamps, fixture SHA-256 hashes, source hashes,
  class-directory hashes and changed class files are in [conditions.json](conditions.json).
  Java 21.0.13+9-LTS, Linux amd64, G1, `-Xmx768m -XX:ActiveProcessorCount=2`.
- The local execution policy denied Unix sockets and rejected permission escalation for
  Xvfb. No GUI timing samples were collected. Use the existing headless rules harness with
  the original six fixture generators and semantic oracle. This measures read/parse/install
  and board-edit core costs, not asynchronous GUI open, repaint or native engine latency.
- Three fresh JVMs per side, five excluded warmups and five measured samples per fixture.
  Order: before-1, after-1, after-2, before-2, before-3, after-3. Each JVM runs all six original
  fixtures in their original order. No other task-owned build/test/probe JVM runs concurrently.
  No forced pre-action GC. Files are deterministic UTF-8 records; this is not a cold-disk test.
- Both sides use the same compiled test/probe classes and dependencies, with only production
  class directories switched. Changed classes come from the three edited production source
  files; `BlunderMoves`, also declared in `SGFParser.java`, differs only in line-number tables.
- [AnalysisStringSharingProbe](../../../src/test/java/featurecat/lizzie/rules/AnalysisStringSharingProbe.java)
  uses `Files.readString`, `parseSgf`, root selection and `setHistory` in each read interval.
  It executes production delete/undo/redo and insertion methods. Trial intervals call
  `Board.beginTrial` and `restoreGameState` plus edit-state cleanup. UI/engine callbacks
  are stubbed. Settlement and semantic/insertion validation are outside timed operations.
- All timings start at the root cursor. The insertion oracle checks node count + 1, fork
  count and inserted cursor before undo. The existing full-tree oracle checks comments,
  positions, candidate metadata, exact PV/visit tokens and ownership after read/restoration.

Compile the final test/probe classes once and reuse them against a baseline production
directory compiled from the linked commit and a candidate production directory:

```sh
mvn -B -Dfmt.skip=true -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/probe-classpath.txt
java -Xmx768m -XX:ActiveProcessorCount=2 -Djava.awt.headless=true \
  -cp "target/test-classes:PRODUCTION_CLASSES:$(cat target/probe-classpath.txt)" \
  featurecat.lizzie.rules.AnalysisStringSharingProbe NEW_OUTPUT_DIRECTORY 5 5
```

For memory, run
[EditUndoFootprintProbe](../../../src/test/java/featurecat/lizzie/rules/EditUndoFootprintProbe.java)
as both a javaagent and main class, with the same classpath and JVM limits,
`--add-opens=java.base/java.lang=ALL-UNNAMED` and
`--add-opens=java.base/java.util=ALL-UNNAMED`. Its arguments are an existing generated fixture
directory and a new JSON output path. Its manifest's `Premain-Class` is
`featurecat.lizzie.rules.EditUndoFootprintProbe`.

## Validation and evidence

- Focused regression run: **196 tests passed, zero failures/errors/skips** in
  `SGFAnalysisParsingTest`, `SGFDetachedParseTest`, `BoardEditUndoTest`,
  `AnalysisProvenanceSgfTest`, `AnalysisWrnSgfTest`, and `AnalysisInvalidationSgfTest`.
- Added coverage checks sharing across nodes/candidates/primary and secondary tags on four
  import entry points, separate import lifetimes, writable independent lists and intact
  snapshots after mutation. Exact-text range tests cover UTF-16, hash collisions, saturation,
  length boundaries and interoperability with the existing capture lookup.
- All six final timing processes and both footprint processes pass. The manual probe and
  its shared helpers compile with the repository's Java 17 release target.
- [observations.csv](observations.csv): **3,600 rows**, including 1,800 excluded warmups and
  1,800 measured observations. These are the source for all final timing/allocation tables.
- [footprints.json](footprints.json): exact graph bytes, object counts and class breakdowns.
- [screen-observations.csv](screen-observations.csv): the earlier single-fixture, one-JVM-per-side
  screen. It is retained separately and is not pooled with the final comparison.
- Repository checks and complete platform CI status are recorded on the PR.
