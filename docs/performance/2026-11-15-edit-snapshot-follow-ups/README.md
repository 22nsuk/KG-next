# Edit snapshot follow-ups: dense cutoff and long-PV capture cost

Measured on 2026-11-15 UTC. Two follow-ups to the [edit snapshot compaction](../2026-11-14-edit-snapshot-compaction/README.md) record, both against `main` at `1ff5830f`:

1. **Dense cutoff** — a production change. Positions are packed up to 90% occupancy instead of about two thirds.
2. **Long-PV capture cost** — an investigation that ends with **no production change**. It is recorded so the same candidates are not tried again.

## Result

- **Dense cutoff: a memory-for-restore-time trade, not a pure win.** On a generated late-game review record the snapshot graph shrinks **7.465 → 6.611 MiB (−11.4%)**, while undoing a deletion goes **2.12 → 2.70 ms** and returning from a trial goes **1.86 → 2.66 ms**. Records whose positions stay below two thirds occupancy are unaffected in size and show no established time difference.
- **No existing record reaches the affected range.** Across the probe fixtures, the repository's test SGFs and its joseki SGFs (16 files, 77,689 nodes), the only positions above the old cutoff are the 1,500 in the two late-game fixtures generated for this measurement.
- **Long-PV capture cost: nothing adopted.** The compaction change raised long-PV deletion from 1.18 to 6.46 ms on this machine. Two candidates inside the restore representation were built and measured, and five more table variants were microbenchmarked; none is a justified improvement. The cost stays as documented.

## Conditions

- Java 21.0.13, Linux amd64 under WSL2, Xvfb display (1600×1200×24), `-Xmx768m -XX:ActiveProcessorCount=2` for the action probe. Absolute times are not comparable with the Xorg-dummy runs in the compaction record and must not be pooled with them.
- Every comparison uses one compiled probe/test-class set and dependency classpath against separately compiled production class directories, sequential fresh JVMs, and alternating or rotating variant order. No other build or probe JVM ran concurrently.
- [EditUndoPerformanceProbe](../../../src/test/java/featurecat/lizzie/gui/EditUndoPerformanceProbe.java) now records, for each timed action, the collection count and time, process CPU time, EDT blocked/waited time and heap in use before the action. `-Dprobe.fixture=NAME` restricts a run to one fixture. Each run has five excluded warmups and ten measured samples per action unless stated otherwise.
- p-values are exact permutation tests over runs, not samples. Samples within a JVM are not independent. With six or eight runs per side the smallest attainable two-sided p is about 0.002 and 0.0002 respectively.

## Dense cutoff

### Change

[BoardPositionSnapshot](../../../src/main/java/featurecat/lizzie/rules/BoardPositionSnapshot.java) stored a position as dense clones once `3 × occupied + 4 ≥ 2 × cells`, which is 240 of 361 cells on 19×19. That bound was chosen for the earlier 12-byte sparse entries. With one 8-byte packed cell per occupied point, sparse storage is smaller at every occupancy. Measured object sizes on this JVM (compressed oops):

| Object | Bytes |
| --- | ---: |
| `Stone[361]` | 1,464 |
| `int[361]` | 1,464 |
| `long[240]` | 1,936 |
| `long[361]` | 2,904 |

A full board packs into 2,904 bytes against 2,928 dense. The change packs every position below 90% occupancy (`occupied < cells − cells / 10`, so 325 cells on 19×19) and decides after the scan instead of leaving the packing loop early. Boards at or above the bound keep dense clones. The oversized-board and larger-Stone-enum fallbacks are unchanged.

### Fixtures

No earlier probe fixture gets past seven stones on the board, so [SgfPerformanceProbe](../../../src/test/java/featurecat/lizzie/gui/SgfPerformanceProbe.java) gains two generated 19×19 records:

| Fixture | Nodes | Nodes above the old cutoff | Most occupied cells |
| --- | ---: | ---: | ---: |
| `late-game-main-line` | 361 | 118 | 319 |
| `late-game-review` | 2,761 | 1,382 | 320 |

They are seeded, deterministic legal play with captures: no suicide, no immediate simple-ko recapture, no filling of a point surrounded only by the mover's own stones. The review record adds a 20-ply side variation at every second node from ply 120. **They are not realistic game shapes**, and neither reaches the new 325-cell bound, so every position in them is packed after the change.

### Snapshot object graphs

[EditUndoFootprintProbe](../../../src/test/java/featurecat/lizzie/rules/EditUndoFootprintProbe.java), same method and caveats as the compaction record: sums of reachable object sizes, not exclusive retained heap.

| Fixture | Graph MiB, main → change | Outside live-history graph, MiB |
| --- | ---: | ---: |
| Late-game review (2,761 nodes) | 7.465 → 6.611 | 7.459 → 6.605 |
| Late-game main line (361 nodes) | 0.793 → 0.718 | 0.787 → 0.712 |
| Wide variations (21,845 nodes) | 14.493 → 14.493 | 12.862 → 12.862 |
| Dense analysis and ownership (301 nodes) | 2.892 → 2.892 | 2.884 → 2.884 |
| Long multilingual comments (201 nodes) | 5.022 → 5.022 | 4.992 → 4.992 |
| Long PV and visits (151 nodes) | 3.888 → 3.888 | 3.843 → 3.843 |

Position storage alone, counted per node from the measured object sizes, is 6,071,632 → 5,175,648 bytes for the review record and 595,104 → 515,960 bytes for the main line.

### Action results

Wall and CPU are pooled medians in milliseconds; allocation is MiB. Positive changes mean slower.

**Late-game review (2,761 nodes), eight runs and 80 samples per side:**

| Action | Main wall | Change wall | Difference | p | EDT CPU | Allocated MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Delete | 2.59 | 2.42 | −6.7% | 0.099 | 2.50 → 2.31 | 7.315 → 6.464 |
| Undo deletion | 2.12 | 2.70 | +27.0% | <0.001 | 2.05 → 2.60 | 8.854 → 8.854 |
| Redo deletion | 2.45 | 2.30 | −6.5% | 0.028 | 2.36 → 2.21 | 7.296 → 6.445 |
| Insert | 1475.46 | 1479.45 | +0.3% | 0.164 | 1271.22 → 1271.11 | 21090.615 → 21089.817 |
| Undo insertion | 4.46 | 4.86 | +8.9% | <0.001 | 4.30 → 4.67 | 16.163 → 15.311 |
| Enter trial | 3.17 | 2.98 | −6.0% | 0.020 | 3.05 → 2.84 | 7.400 → 6.549 |
| Return from trial | 1.86 | 2.66 | +42.6% | <0.001 | 1.80 → 2.54 | 8.853 → 8.853 |

Every one of the eight per-run medians is higher after the change for the three restoring actions. The capturing actions are 0.15–0.19 ms faster; that is established for redo and trial entry but not for deletion.

**Late-game main line (361 nodes), six runs and 60 samples per side:**

| Action | Main wall | Change wall | Difference | p |
| --- | ---: | ---: | ---: | ---: |
| Delete | 0.50 | 0.52 | +3.8% | 0.786 |
| Undo deletion | 0.61 | 0.66 | +8.3% | 0.002 |
| Redo deletion | 0.65 | 0.60 | −7.3% | 0.039 |
| Insert | 203.42 | 200.38 | −1.5% | 0.896 |
| Undo insertion | 1.18 | 1.18 | +0.3% | 0.751 |
| Enter trial | 0.67 | 0.63 | −5.8% | 0.022 |
| Return from trial | 0.65 | 0.69 | +6.4% | 0.002 |

**Wide variations (21,845 nodes), six runs and 60 samples per side:** no action has an established difference (p from 0.195 to 0.686). Several of its actions allocate tens of MiB and have a collection inside the timed action in roughly half of their samples, so their wall medians move between runs as described in the [trial-return follow-up](../2026-11-14-edit-snapshot-compaction/README.md#trial-return-wall-time-follow-up); for example undo insertion reads 33.80 → 40.72 ms with EDT CPU 16.78 → 16.83 ms.

All 2,800 measured samples are in [dense-cutoff-action-observations.csv](dense-cutoff-action-observations.csv); `variant` is `main` or `cutoff90`.

### Per-position cost by occupancy

A private microbenchmark (not in the repository) timed `capture` with a shared buffer and `restoreInto` on 8,192 distinct 19×19 positions per occupancy level, with `-Xms6g -Xmx6g -Xmn3g`, eight runs per side, medians of collection-free rounds, in nanoseconds per position:

| Occupied cells | Storage, main → change | Capture | Restore |
| ---: | --- | ---: | ---: |
| 7 | sparse → sparse | 332 → 335 | 345 → 343 |
| 100 | sparse → sparse | 964 → 955 | 458 → 455 |
| 200 | sparse → sparse | 1,465 → 1,391 | 618 → 616 |
| 239 | sparse → sparse | 1,270 → 1,190 | 685 → 682 |
| 240 | dense → sparse | 1,305 → 1,184 | 236 → 684 |
| 280 | dense → sparse | 965 → 982 | 237 → 747 |
| 320 | dense → sparse | 711 → 793 | 237 → 813 |
| 340 | dense → dense | 595 → 704 | 236 → 236 |
| 361 | dense → dense | 508 → 663 | 237 → 234 |

- **Restore is the cost.** A dense restore is two array clones, about 236 ns at any occupancy. A sparse restore writes each cell: 2.9 times as long at 240 cells and 3.4 times at 320.
- **Capture moves both ways.** Removing the in-loop exit makes packing 4–9% faster from 200 to 260 cells. From 280 cells up capture is slower, because main stopped packing at the old bound and the change scans the whole board: 12% at 320 cells, and 18–31% for boards that end up dense anyway.

All twelve levels, plus a variant that repeats one position, are in [dense-cutoff-position-benchmark.csv](dense-cutoff-position-benchmark.csv).

### Limits

- How often real records pass two thirds occupancy was not measured. It needs at least 240 stones on a 19×19 board; the 205-move game record in the test resources peaks at 194.
- The absolute sizes are small in the measured record: under 1 MiB saved, under 1 ms added per action.
- The slowdown of trial return (+0.80 ms) is larger than that of undoing a deletion (+0.58 ms) for the same number of restored positions. The reason was not isolated.
- The action probe measured production classes whose `BoardPositionSnapshot` differs from the committed source only in a comment; the two compile to identical bytecode (`javap -c` output compared).
- One machine, one heap configuration, G1. Windows desktop latency was not measured.

## Long-PV capture cost

**No production code was changed for this part.** The capture-local string reuse stays as merged.

### The cost on this machine

`dense-analysis-long-pv-visits` (151 nodes, 618,496 PV/visit strings), six runs and 60 samples per revision, pooled wall medians in milliseconds. #179 is `5c0728e6`.

| Action | #179 | main |
| --- | ---: | ---: |
| Delete | 1.18 | 6.46 |
| Undo deletion | 0.42 | 0.47 |
| Redo deletion | 0.68 | 2.53 |
| Enter trial | 1.30 | 6.64 |
| Return from trial | 0.30 | 0.30 |

Only capturing actions pay. A collection fell inside at most 2 of 60 samples per cell, and wall and EDT CPU medians agree within 0.3 ms.

### Where the time goes

Three throwaway variants of `reuseAnalysisStrings` replaced the table lookup, three runs of 20 samples each:

| Variant | Delete | Redo deletion |
| --- | ---: | ---: |
| #179 (lists copied, no reuse pass) | 1.26 | 0.82 |
| main | 6.47 | 2.76 |
| Reuse pass removed | 1.27 | 0.84 |
| `values.set(i, values.get(i))` only | 3.82 | 3.68 |
| Plus `hashCode()` of each string, no table | 4.91 | 3.76 |

About half of the added 5.2 ms on a first deletion is reading each list element and storing a reference back; computing hashes of freshly parsed strings adds about 1.1 ms more; the table probe and equality check account for the remaining 1.6 ms. A JFR profile of a private capture microbenchmark puts 64% of samples in `RestoreStrings.reuse` itself and 12% in `String.hashCode`. Rewriting 618,496 list slots and hashing freshly parsed strings is about 70% of the added cost, and any design that shares tokens at capture time has to do both.

### Candidates measured

Both stay inside the private restore representation. Six runs and 60 samples per side, against main:

| Candidate | Delete | Redo deletion | Enter trial |
| --- | ---: | ---: | ---: |
| Skip the store when the table returns the same reference | 6.46 → 6.68 (+3.5%, p = 0.004) | 2.53 → 2.11 (−16.8%, p = 0.002) | 6.64 → 6.83 (+2.9%, p = 0.002) |
| Reuse while copying each list (one pass instead of copy then rewrite) | 6.46 → 6.39 (p = 0.342) | 2.53 → 2.58 (p = 0.076) | 6.64 → 6.64 (p = 0.853) |

- **Skipping identical stores** helps only when most strings are already shared, as on a redo. A first capture, the case that carries the cost, gets slower. On the ownership fixture its only established effect is redo deletion 0.76 → 0.73 ms.
- **The fused copy** shows no established difference on the long-PV fixture and allocates slightly more (3.836 → 3.910 MiB per deletion).

Five further table designs were tried only in the private microbenchmark (single process, median of timed passes, 5.99 ms for the production algorithm): hand-written short-string equality 7.14 ms, a parallel hash array 6.59 ms, both together 7.06 ms, skipped identical stores 6.65 ms, and a simplified probe 5.95 ms. None is faster beyond noise.

All 3,780 samples for this fixture are in [long-pv-capture-observations.csv](long-pv-capture-observations.csv); `experiment` separates the candidate comparison from the decomposition.

### What remains

Sharing tokens when analysis is parsed or attached, rather than at capture, is the one direction left. In the same microbenchmark, capture over a tree whose tokens are already shared takes 3.64 ms against 6.31 ms, and a one-time in-place pass over the live tree takes 5.85 ms (parsing the fixture takes 20.7 ms). That changes live analysis data outside the restore representation, was not implemented, and its effect on real actions is an estimate from the microbenchmark only.

## Verification

- Dense cutoff: `BoardPositionSnapshotTest`, `BoardEditUndoTest` and the related snapshot tests, **99 tests executed and passed** on the committed source. New cases cover positions just below, at and above the bound, number-only occupied cells, small-board rounding, a full board, buffer reuse after a full board, and a history restore across the bound.
- Every action-probe process passed the probe's restoration and cursor checks. One candidate run exited without a summary on its first attempt and was rerun; no failed run is included.
- A full `mvn verify` was not run locally for this change. All 18 CI jobs passed on PR head `79259456` ([run](https://github.com/22nsuk/KG-next/actions/runs/43673715889)) and on the merge commit `1d8874ef` on `main` ([run](https://github.com/22nsuk/KG-next/actions/runs/43676762769)).
- The private microbenchmarks, the occupancy census and the candidate sources are not in the repository. Their results above are reported as measured and cannot be reproduced from this tree alone; the action-probe and footprint results can, with the commands in the [compaction record](../2026-11-14-edit-snapshot-compaction/README.md#reproduction) plus `-Dprobe.fixture=NAME`.
