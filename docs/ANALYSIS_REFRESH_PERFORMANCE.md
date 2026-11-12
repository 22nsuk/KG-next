# Whole-game move-list metadata refresh

Whole-game analysis publishes each accepted payload to `BoardData` and updates
the affected move immediately. Its progress checkpoints also request a whole-tree
metadata refresh: `updateMovelist(node)` writes the previous node's `NodeInfo`,
so a parent result arriving after a child can require another traversal. The
graph's existing 33ms UI coalescer does not merge this background metadata work.

## Ownership and completion

[`Board.setMovelistAll`](../src/main/java/featurecat/lizzie/rules/Board.java)
keeps one worker owner and one waiting request batch per Board. Requests arriving
during a pass supersede it at the next node boundary. The same worker consumes
the latest batch; completion callbacks from superseded work move to that batch
in request order. A callback-free progress request cannot erase a waiting
completion. The final node also checks supersession before publishing completion.

The worker waits up to 50ms when idle so nearby results reuse it. A request wakes
it immediately. Completion callbacks run before this idle wait, and an idle
worker exits instead of retaining a permanent executor for every Board. The
existing low thread priority, navigation pause and periodic yield/sleep remain.
Requests also merge during the initial navigation pause, before traversal starts.

History identity and a separate refresh epoch distinguish supersession from
invalidation. `setHistory`, `restoreClearState` and `clearNodeInfo` retire older
work and its callbacks. Clearing metadata after a topology or match-setting edit
must invalidate an already-stacked branch: otherwise a deleted equal-visit
variation can repopulate a cache that suppresses the surviving main-line update.
Restoring the same history object does not revive callbacks from before the reset.
An update exception or interruption does not transfer its success callback to a
later pass; an independently accepted waiting request can still run. Callback
failures are reported after other completions in the same batch are delivered.

Node updates and callbacks execute outside both ownership locks. Requests never
wait for metadata work while holding the Board monitor. The queue belongs to its
Board instance; it does not require that instance to remain `Lizzie.board`.

[`LizzieFrame.onWholeGameAnalysisFinished`](../src/main/java/featurecat/lizzie/gui/LizzieFrame.java)
uses the existing `refreshCompletedSilentAnalysisProgress` helper once after a
valid terminal handoff. Completion, cancellation and failure may all leave
accepted analysis to display. The helper refreshes the problem-list snapshot and
graph on the Swing event thread after metadata settles, and rejects a replaced
Board/history. Engine handback, user pause, dialog disposal and session completion
remain independent of the metadata wait. Duplicate or stale session handoffs do
not enqueue another final refresh.

## Controlled comparison, 2026-11-12

Baseline: main `cbaf8a981f97bd00a2fb04fe3acb49615e7c848c`.
The candidate's exact source and class fingerprints, individual observations,
medians and ranges are in
[`movelist-refresh.json`](performance/2026-11-12/movelist-refresh.json).
The same [`MovelistRefreshProbe.java`](performance/2026-11-12/MovelistRefreshProbe.java)
class and dependency jars ran against both builds.

The probe reuses the existing analysis-request test fixtures to feed 201
controlled responses through the real `startWholeGameRequest` and `parseResult`
entrypoints. It uses synthetic 3×3 metadata trees with 201 nodes, or 4,201 nodes
including side variations. These trees are metadata workloads, not a measurement
of legal-game generation or engine search. Each run ends with one explicit
metadata-completion fence. This produces 45 refresh requests in the baseline,
including progress checkpoints, request completion and that fence.

Four fresh JVMs run in baseline/candidate/candidate/baseline order. Each JVM runs
two warm-ups and five retained observations for each tree/cadence combination:
168 runs total, 120 retained, and ten retained observations per version per
condition. The environment is Linux x86-64, Temurin 21.0.13+9, with a 512MiB
initial and 1,536MiB maximum heap. No engine process or GPU is used. No other
build or test workload was run concurrently with these comparisons.

All 168 observations produced the same final metadata hash for each tree size
across both versions and matched an additional settled traversal. The hash
covers the public fields of all four `NodeInfo` records and last-move candidate
ranks. Source/class identities matched before and after every phase; each
version's two phases used identical artifacts. Production class differences were
confined to Board and LizzieFrame.

Values below are medians, **baseline → candidate**. CPU and elapsed times are
milliseconds. A fractional node count is the median of ten integer counts.

| Tree nodes | Response cadence | Workers started | Nodes visited | Sampled worker CPU | Total elapsed | After-response settlement |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 201 | Burst | 45 → 1 | 4,510.5 → 1,915 | 7.75 → 1.43 | 24.87 → 14.43 | 1.53 → 2.01 |
| 201 | 2ms between responses | 45 → 1 | 8,844 → 8,844 | 8.84 → 5.16 | 446.06 → 434.92 | 1.40 → 1.23 |
| 201 | Burst after navigation | 45 → 1 | 201 → 201 | 0.37 → 0.35 | 452.03 → 451.00 | 441.88 → 446.53 |
| 4,201 | Burst | 45 → 1 | 7,512 → 5,435 | 8.05 → 4.23 | 49.77 → 42.74 | 37.87 → 38.07 |
| 4,201 | 2ms between responses | 45 → 1 | 54,888.5 → 53,036 | 34.99 → 28.61 | 478.53 → 467.84 | 37.08 → 37.12 |
| 4,201 | Burst after navigation | 45 → 1 | 4,201 → 4,201 | 3.45 → 3.66 | 488.11 → 488.04 | 478.29 → 484.84 |

Every retained baseline run started 45 metadata workers; every retained candidate
run started one. This is workload-specific: a gap longer than the idle interval
can start another worker. The serialized queue prevents concurrent traversals;
brief thread-lifetime overlap during an exit/failure handoff is not another
concurrent traversal.

The comparison supports fewer worker starts and less repeated work in the burst
cases. It does not show an across-the-board reduction in settlement latency or
CPU. In particular, the large navigation case still waits for the existing 450ms
navigation pause and performs one complete pass; its sampled CPU increases from
3.45 to 3.66ms. Faster response submission can leave more of that fixed pause
after the final response. The small burst also has a slightly longer final
settlement despite lower total elapsed time. No whole-game search speedup,
Windows/macOS timing result or native-engine throughput improvement is claimed.

JFR counts worker lifecycle events without stack traces, deduplicated by thread
ID. CPU/allocation counters are sampled through each worker's last node update.
They include instrumentation overhead and exclude exit/callback work, workers
cancelled before visiting a node, producer-side allocations and native thread
stacks. They are not total application CPU or memory measurements. Reported
completion time ends at the final callback; the following idle retirement wait
is excluded. Timing observations are descriptive, not CI thresholds.

### Reproduction

Use the same probe source in both checkouts, with JDK 21 on Linux. Build each
checkout's own production and test classes and its dependency classpath:

```sh
mvn -B -ntp -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/probe-classpath.txt -Dmdep.includeScope=test

movelist_probe_source="/path/to/candidate/docs/performance/2026-11-12/MovelistRefreshProbe.java"
movelist_probe_cp="target/classes:target/test-classes:$(cat target/probe-classpath.txt)"
mkdir -p target/movelist-probe
javac -cp "$movelist_probe_cp" -d target/movelist-probe "$movelist_probe_source"
java -Xms512m -Xmx1536m -Djava.awt.headless=true \
  -cp "target/movelist-probe:$movelist_probe_cp" \
  MovelistRefreshProbe target/movelist-measurements
```

Run the four JVM phases sequentially, with separate output directories, in ABBA
order. The probe writes per-run JFR files and `measurements.json`, verifies final
metadata against a settled traversal, and fails on an unfinished worker or an
unexpected update exception. Its fixture references are tied to the compared
revisions; it is a measurement artifact, not a packaged application entrypoint.

## Regression evidence

- `BoardMovelistRefreshLifecycleTest`: serial/concurrent bursts, callback
  retention, history replacement and same-object restore, reentrant requests,
  update/callback failures, interruption, and worker retirement.
- `AnalysisEngineRequestTest`: real out-of-order whole-game result publication
  followed by completion or cancellation, and a deleted equal-visit variation
  that would otherwise corrupt surviving main-line metadata. Fixture teardown
  still waits for owned workers, preserves interruption, surfaces worker errors
  and drains queued event-thread work before restoring globals.
- `LizzieFrameRegressionTest`: terminal handback completes while metadata is
  blocked; releasing it publishes the real final problem-list snapshot for
  COMPLETE/CANCELLED/FAILED. Board/history replacement and duplicate handoffs
  suppress stale publication without resuming a user pause.
- The final focused run passed 439 tests with no failures, errors or skips.
  A separate baseline run of the new lifecycle/UI cases ran 17 cases: nine
  intended failures, no errors and eight passes. The deleted-variation case
  also detected the intermediate implementation that did not invalidate an
  active pass on `clearNodeInfo`; the final implementation passes it.
- Six headful desktop cases passed with no failures, errors or skips: analysis
  progress before/after completion, table refresh, window close/reopen, and SGF
  loading. These checks use controlled/offline inputs, not live engine search.

The repository's [required CI policy](CI_MERGE_POLICY.md) still applies to the PR;
these local results do not replace its Linux/Windows/Java 17, desktop and native
acceptance gates. No tests, CI selection rules or required checks were removed.
