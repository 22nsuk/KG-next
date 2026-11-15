# Preserve insertion branches and copy positions once

## Scope decision and the misleading initial result

The starting main was [`a397814a`](https://github.com/22nsuk/KG-next/commit/a397814a43469fd0260b0c6f3ef250b07086a625),
including #187's drag-layer allocation fix and #186's Tencent changes. Remeasuring all
six existing edit fixtures initially put insertion in the single-digit/tens-of-milliseconds
range. A copies-only experiment then showed almost no allocation change on the wide tree.
Tracing that discrepancy found a pre-existing correctness problem: the continuation
extractor pushed one parent per fork but popped a parent at every leaf. Third and later
siblings could become children of another sibling or attach outside the returned subtree.

On main, the wide fixture has **21,845 nodes** and its first root child has **5,461 nodes**.
Only **15 moves** remain in the returned continuation. Root insertion leaves **16,400 nodes**
instead of **21,846**. A small four-branch insertion test independently fails with two
children where four are required. The old probe checks restoration after undo, so it does
not detect this incomplete insertion. These observations change the scope decision:
the PR first repairs continuation parents, then removes duplicate position copies.

Initial main observations below use the old probe; 15 measured samples per fixture.
They describe what main did, and are **not** a correct-full-replay baseline for the wide tree.

| Fixture | Main wall ms | Main EDT CPU ms | Main allocated MiB |
| --- | ---: | ---: | ---: |
| wide-variations | 28.265 | 27.747 | 15.930 |
| dense-analysis-ownership | 11.364 | 10.508 | 7.901 |
| long-multilingual-comments | 5.634 | 5.639 | 3.046 |
| dense-analysis-long-pv-visits | 13.705 | 13.712 | 5.760 |
| late-game-main-line | 2.038 | 2.027 | 3.181 |
| late-game-review | 13.532 | 13.362 | 25.783 |

## Changes and comparison boundary

- Pair every pending source node with its output parent in the existing traversal stacks.
  Reverse child pushing preserves visit/append order. Skipped setup/dummy nodes pass the
  same parent to their children. The public helper signature and first selected-subtree
  return convention remain unchanged.
- Let the existing `BoardData.move/pass` factories detach position inputs once, then
  mutate only the unpublished candidate. This follows `BoardHistoryList`'s existing pattern.
  Public factory ownership, trial numbering, explicit-branch resets, capture/ko/suicide
  decisions, existing-variation reuse, and engine/UI call order remain in place.
- Check insertion node/fork counts and the inserted cursor before undo in the existing
  manual probe. The check is outside the timed action. Regression tests separately verify
  actual parent links, sibling order, nested forks, pass and undo/redo behavior.

**Fair comparison baseline:** [`573ad091`](https://github.com/22nsuk/KG-next/commit/573ad091ea395a450e7695481657a7a7008fac7e),
the first commit in this PR: main plus the continuation repair, with the original copies.
**Candidate:** that same correct replay with the duplicate copies removed. Both variants
must preserve all expected nodes/forks and the inserted cursor. Comparing broken main
directly with the final wide-tree time would compare different amounts of work.

## Correct-replay insertion results

Pooled medians, correctness-only baseline → final candidate. Fifteen measured samples
per fixture and variant, from three fresh JVMs. Allocated MiB is cumulative allocation
during the action, not peak heap usage.

| Fixture | Input nodes | Wall ms | EDT CPU ms | Allocated MiB |
| --- | ---: | ---: | ---: | ---: |
| wide-variations | 21,845 | 72.662 → 58.981 | 44.803 → 41.539 | 57.522 → 42.145 |
| dense-analysis-ownership | 301 | 10.933 → 11.224 | 10.867 → 10.405 | 7.896 → 7.040 |
| long-multilingual-comments | 201 | 5.371 → 5.951 | 5.374 → 5.956 | 3.045 → 2.494 |
| dense-analysis-long-pv-visits | 151 | 13.785 → 13.700 | 13.793 → 13.708 | 5.755 → 5.327 |
| late-game-main-line | 361 | 1.462 → 1.741 | 1.465 → 1.744 | 3.151 → 2.134 |
| late-game-review | 2,761 | 18.720 → 9.447 | 10.838 → 8.893 | 25.654 → 17.881 |

### wide-variations

Pooled allocation reduction: **26.7%**. Per-JVM insertion medians:

| JVM pair | Wall ms | EDT CPU ms | Allocated MiB |
| --- | ---: | ---: | ---: |
| 1 | 61.301 → 71.710 | 43.169 → 56.279 | 57.522 → 42.147 |
| 2 | 76.531 → 48.824 | 44.803 → 40.973 | 57.440 → 42.145 |
| 3 | 81.510 → 57.569 | 47.635 → 39.809 | 57.522 → 42.145 |

A GC occurred in 11/15 baseline and 8/15 candidate samples.

### late-game-review

Pooled allocation reduction: **30.3%**. Per-JVM insertion medians:

| JVM pair | Wall ms | EDT CPU ms | Allocated MiB |
| --- | ---: | ---: | ---: |
| 1 | 12.404 → 9.447 | 12.414 → 8.893 | 25.654 → 17.881 |
| 2 | 21.243 → 11.036 | 12.949 → 10.399 | 25.616 → 17.881 |
| 3 | 19.447 → 8.678 | 10.442 → 8.643 | 25.654 → 17.881 |

A GC occurred in 8/15 baseline and 7/15 candidate samples.

Timings vary between JVMs; three runs per variant do not establish statistical significance
or a uniform latency improvement. The allocation reduction is the direct effect established
by this comparison. The largest fixture also includes whole-tree undo capture and replay of
its selected 5,461-node subtree, with an additional inserted move. Other root siblings remain.
The current scope repairs that work and removes avoidable copies at the existing construction
boundary; it does not introduce a new UI/engine batching lifecycle.

## Method and limits

- Measured on 2026-11-16 KST; exact UTC timestamps and fixture SHA-256 hashes are in
  [conditions.json](conditions.json). Java 21.0.13+9-LTS, Linux amd64, G1,
  `-Xmx768m -XX:ActiveProcessorCount=2`; Xvfb 1600×1000×24, Openbox, app 1180×820.
- Three fresh JVMs per variant, five excluded warmups and five measured samples per action.
  Order: before-1, after-1, after-2, before-2, before-3, after-3. Every JVM runs all six
  original fixtures in their original order. No other task-owned build/probe JVM runs
  concurrently during a comparison run.
- [EditUndoPerformanceProbe](../../../src/test/java/featurecat/lizzie/gui/EditUndoPerformanceProbe.java)
  arguments: `OUTPUT_DIRECTORY 5 5`, no fixture filter and no forced pre-action GC.
  Use the test dependency classpath and a new output directory. Both final-comparison
  variants use the same updated probe/test classes and dependencies. Only `Board.class`
  and its generated nested classes differ in the production class-directory comparison.
- The probe opens each record with the cursor at the root. It measures the existing
  selected-continuation replay path, not a sampled middle-game cursor. All final-comparison
  warmup/measured insertion sequences preserve input node count + 1, fork count and the
  inserted cursor, then pass the original whole-tree and root-cursor restoration checks.
- A separate middle-position regression checks four continuation branches and their exact
  moves, the untouched sibling, game information/sound setting, final cursor, and undo/redo.
- Timings cover synchronous offline EDT work. Repaint, deferred move metadata and native
  engine I/O are excluded. Native Windows/HiDPI/GPU performance is not measured. The six
  deterministic synthetic records are not a representative real-game population.
- Initial exploration used the old probe. Its copies-only runs led to the continuation
  diagnosis and are retained for transparency in [initial-screen-observations.csv](initial-screen-observations.csv).
  They are not pooled with the final comparison. Earlier reports used another environment
  and/or one selected fixture per JVM; their absolute times are also not pooled here.

## Validation and raw observations

- Before the traversal fix, the four-branch insertion regression fails with expected 4
  children versus actual 2. A diagnostic using unchanged main classes confirms the wide
  record's 21,845 → 16,400 node loss on insertion.
- Final focused run: **250 tests passed, zero failures/errors/skips** across
  `BoardHistoryMoveOwnershipTest`, `PassVariationNumberingTest`, `BoardEditUndoTest`,
  `BoardPlayCaptureHistoryRegressionTest`, `BoardEditReplayLinkedListTest`, and
  `SGFParserSemanticRoundTripTest`. Existing setup-node/edit-delete/pass round trips
  cover the shared extractor's other edit caller.
- Extended ownership checks cover actual Board placement/pass paths, rectangular positions,
  normal/explicit branches, trial numbering, capture, dummy passes, and rejected suicide/ko
  candidates leaving parent/ancestor/sibling positions unchanged.
- All six final-comparison processes pass. [action-observations.csv](action-observations.csv)
  contains 2,520 rows: 1,260 warmups and 1,260 measured action observations, including wall/EDT/
  process CPU, allocation, GC, contention/waiting and pre-action heap context.
- Full Linux/Windows Java and desktop CI results are recorded on the PR.

### All final-comparison action medians

Before → after. Timing shifts in unchanged actions are observations, not established
improvements or regressions; their GC context and per-run values remain in the raw data.

| Fixture | Action | Wall ms | EDT CPU ms | Allocated MiB |
| --- | --- | ---: | ---: | ---: |
| wide-variations | delete | 23.021 → 23.832 | 22.858 → 23.513 | 13.184 → 13.184 |
| wide-variations | undo-delete | 45.743 → 35.750 | 32.918 → 33.440 | 79.861 → 79.861 |
| wide-variations | redo-delete | 43.897 → 37.827 | 37.180 → 36.902 | 65.688 → 65.688 |
| wide-variations | insert | 72.662 → 58.981 | 44.803 → 41.539 | 57.522 → 42.145 |
| wide-variations | undo-insert | 45.629 → 69.234 | 39.564 → 38.538 | 83.229 → 83.229 |
| wide-variations | trial-enter | 27.652 → 26.618 | 27.666 → 26.633 | 13.430 → 13.430 |
| wide-variations | trial-return | 67.848 → 56.849 | 17.191 → 17.009 | 70.005 → 70.005 |
| dense-analysis-ownership | delete | 2.453 → 2.589 | 2.460 → 2.594 | 2.854 → 2.854 |
| dense-analysis-ownership | undo-delete | 0.705 → 0.818 | 0.707 → 0.795 | 0.996 → 0.996 |
| dense-analysis-ownership | redo-delete | 1.541 → 1.531 | 1.545 → 1.454 | 2.839 → 2.839 |
| dense-analysis-ownership | insert | 10.933 → 11.224 | 10.867 → 10.405 | 7.896 → 7.040 |
| dense-analysis-ownership | undo-insert | 1.136 → 1.023 | 1.143 → 1.010 | 1.164 → 1.164 |
| dense-analysis-ownership | trial-enter | 2.954 → 2.498 | 2.959 → 2.509 | 2.855 → 2.855 |
| dense-analysis-ownership | trial-return | 0.674 → 0.689 | 0.677 → 0.688 | 0.970 → 0.970 |
| long-multilingual-comments | delete | 0.316 → 0.315 | 0.318 → 0.317 | 0.125 → 0.125 |
| long-multilingual-comments | undo-delete | 0.447 → 0.405 | 0.449 → 0.406 | 0.651 → 0.651 |
| long-multilingual-comments | redo-delete | 0.370 → 0.448 | 0.372 → 0.450 | 0.117 → 0.117 |
| long-multilingual-comments | insert | 5.371 → 5.951 | 5.374 → 5.956 | 3.045 → 2.494 |
| long-multilingual-comments | undo-insert | 0.652 → 0.730 | 0.655 → 0.749 | 0.763 → 0.763 |
| long-multilingual-comments | trial-enter | 0.442 → 0.497 | 0.444 → 0.500 | 0.126 → 0.126 |
| long-multilingual-comments | trial-return | 0.424 → 0.516 | 0.426 → 0.474 | 0.649 → 0.649 |
| dense-analysis-long-pv-visits | delete | 12.580 → 12.655 | 12.587 → 12.662 | 3.836 → 3.836 |
| dense-analysis-long-pv-visits | undo-delete | 0.584 → 0.595 | 0.585 → 0.597 | 0.531 → 0.531 |
| dense-analysis-long-pv-visits | redo-delete | 4.826 → 5.372 | 4.830 → 5.318 | 3.833 → 3.833 |
| dense-analysis-long-pv-visits | insert | 13.785 → 13.700 | 13.793 → 13.708 | 5.755 → 5.327 |
| dense-analysis-long-pv-visits | undo-insert | 0.716 → 0.704 | 0.719 → 0.707 | 0.615 → 0.615 |
| dense-analysis-long-pv-visits | trial-enter | 11.929 → 10.634 | 11.938 → 10.645 | 3.837 → 3.837 |
| dense-analysis-long-pv-visits | trial-return | 0.471 → 0.440 | 0.468 → 0.441 | 0.489 → 0.489 |
| late-game-main-line | delete | 0.388 → 0.391 | 0.389 → 0.393 | 0.726 → 0.726 |
| late-game-main-line | undo-delete | 0.714 → 0.740 | 0.716 → 0.743 | 1.163 → 1.163 |
| late-game-main-line | redo-delete | 0.474 → 0.477 | 0.476 → 0.478 | 0.708 → 0.708 |
| late-game-main-line | insert | 1.462 → 1.741 | 1.465 → 1.744 | 3.151 → 2.134 |
| late-game-main-line | undo-insert | 1.124 → 1.099 | 1.129 → 1.102 | 1.864 → 1.864 |
| late-game-main-line | trial-enter | 0.573 → 0.591 | 0.575 → 0.593 | 0.727 → 0.727 |
| late-game-main-line | trial-return | 0.718 → 0.928 | 0.721 → 0.776 | 1.162 → 1.162 |
| late-game-review | delete | 3.661 → 3.681 | 3.664 → 3.686 | 6.464 → 6.464 |
| late-game-review | undo-delete | 3.856 → 3.895 | 3.862 → 3.901 | 8.854 → 8.854 |
| late-game-review | redo-delete | 3.638 → 3.914 | 3.643 → 3.866 | 6.445 → 6.445 |
| late-game-review | insert | 18.720 → 9.447 | 10.838 → 8.893 | 25.654 → 17.881 |
| late-game-review | undo-insert | 8.378 → 8.722 | 8.386 → 8.714 | 15.311 → 15.311 |
| late-game-review | trial-enter | 4.467 → 4.392 | 4.457 → 4.398 | 6.465 → 6.465 |
| late-game-review | trial-return | 4.163 → 4.219 | 4.165 → 4.179 | 8.852 → 8.852 |
