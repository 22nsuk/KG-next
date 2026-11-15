# Clearing drag layers without allocating board-sized images

## Change and result

`BoardRenderer.removedrawmovestone()` now resets the two drag-layer references to the
existing transparent `emptyImage`. Drawing a new drag stone still creates its own full-size
images. The existing forwarding to the comparison board in double-engine mode is preserved.

On the deterministic 2,761-node `late-game-review` fixture, the pooled insertion wall-time
median changed from **1,094.617 to 20.868 ms**. EDT allocation during the action changed
from **18,774.326 to 25.720 MiB**. These are cumulative allocations,
not peak heap use. This workload repeatedly calls the clear path while replaying moves.

The result supports removing this allocation before considering a broader insertion refactor.
It does not measure ordinary mouse-drag latency or establish the same ratio for real records,
different board pixel sizes, native engines, or other operating systems.

## Comparison conditions

- Baseline: [`8620a7d4`](https://github.com/22nsuk/KG-next/commit/8620a7d46ea3d81ae8e914997c02d34a63e91c47).
- Candidate: the same production source with only the two reference assignments above changed.
  A SHA-256 comparison of compiled class directories found only `BoardRenderer.class` different.
  Both variants used the same compiled probe/test classes and dependency classpath.
- Java 21.0.13+9-LTS, Linux amd64, Xvfb 1600×1000×24, application bounds 1180×820,
  `-Xmx768m -XX:ActiveProcessorCount=2`, default G1 collector.
- Three fresh JVMs per variant. Order: before-1, after-1, after-2, before-2, before-3, after-3.
  Each JVM ran five excluded warmups and five measured samples per operation. No other build
  or probe JVM ran concurrently during this comparison.
- Existing [EditUndoPerformanceProbe](../../../src/test/java/featurecat/lizzie/gui/EditUndoPerformanceProbe.java),
  with `-Dprobe.fixture=late-game-review` and arguments `OUTPUT_DIRECTORY 5 5`.
  Each output directory must be new. Use the test dependency classpath and an owned display.
- The probe measures synchronous production actions on the EDT. It excludes repaint,
  deferred move metadata, and native engine I/O. Every measured/warmup sample completed the
  probe's existing full-tree restoration and cursor checks.
- Fixture: 2,761 nodes, 120 forks, 191,037 SGF bytes; SHA-256
  `c39f4776c1fd2b1e7885a6fff9e7991acdca4c91763d22bc3b65fa61e7fed7dc`.
  This is generated legal play with variations, not a representative real game.

## Action observations

Pooled medians, before → after; 15 measured samples per operation and variant.
All 420 rows, including the explicitly marked warmups, are in
[action-observations.csv](action-observations.csv). Wall and CPU are milliseconds;
allocation is MiB. GC and heap context remain in the raw data.

| Operation | Wall ms | EDT CPU ms | Allocated MiB |
| --- | ---: | ---: | ---: |
| delete | 4.392 → 4.026 | 4.449 → 4.034 | 6.464 → 6.464 |
| undo-delete | 5.356 → 5.183 | 5.363 → 5.187 | 8.855 → 8.854 |
| redo-delete | 4.865 → 4.054 | 4.081 → 4.061 | 6.446 → 6.446 |
| insert | 1,094.617 → 20.868 | 913.522 → 17.708 | 18,774.326 → 25.720 |
| undo-insert | 10.253 → 9.173 | 10.261 → 9.182 | 15.312 → 15.312 |
| trial-enter | 5.574 → 5.768 | 5.581 → 5.777 | 6.549 → 6.549 |
| trial-return | 8.649 → 5.820 | 8.653 → 5.851 | 8.853 → 8.853 |

Per-JVM insertion wall medians:

| Run | Before ms | After ms |
| --- | ---: | ---: |
| 1 | 1,029.394 | 15.085 |
| 2 | 1,168.831 | 20.560 |
| 3 | 1,094.617 | 27.398 |

Insertion had a collection in 15/15 measured baseline samples and 7/15 candidate samples.
Median collection counts during insertion were 73 and 0 respectively. Allocation reduction
is consistent across runs; timing summaries are descriptive. Three JVM runs per variant are
not a basis for general claims about the smaller differences in other operations. The few
millisecond differences there should not be reported as established improvements or regressions.

These measurements were made on a different environment from the earlier edit-snapshot
records. Absolute times and allocation values must not be pooled with those records.

## Functional checks

- The extended `BoardImageReuseTest` fails on the baseline in both normal and double-engine
  modes because repeated clears allocate another pixel buffer; the existing seven cases pass.
- With the change, all nine image cases pass. The new cases cover clearing stone/shadow pixels,
  keeping the comparison renderer untouched in normal mode, clearing both in double-engine mode,
  repeated empty clears, resize followed by redrawing, and leaving previously empty layers clear.
- `BoardImageReuseTest`, `BoardEditUndoTest`, and `BoardEditReplayLinkedListTest`: 39 tests
  passed, zero failures/errors/skips in the focused Java run.
- Portable repository checks and line-ending policy passed locally. Full PR CI is recorded
  on the pull request; this measurement is not a Windows/native-GPU acceptance result.
