# Edit snapshot storage and capture tradeoffs

Measured on 2026-11-14 UTC. This is a separate follow-up to [PR #179](https://github.com/22nsuk/KG-next/pull/179), based on its `5c0728e6` head. The original single-step undo/trial design and restoration contract are described [here](../2026-11-14-edit-undo/README.md).

## Result and remaining tradeoffs

This change reduces snapshot representation size; **it does not eliminate every latency or memory tradeoff**. Against #179, the wide record's deletion wall median is **37.35 → 32.44 ms**. Its snapshot graph shrinks from **15.915 to 14.493 MiB**, and capture allocation falls from **14.606 to 13.184 MiB**.

The long-PV snapshot graph shrinks from **32.383 to 3.888 MiB (88.0%)**. Reusing those tokens costs CPU: deletion changes from **2.43 to 13.36 ms**. The fresh SGF reference measures 25.39 ms for that action. The wide record's SGF deletion reference is **25.42 ms**, so its original first-deletion gap remains. Full action results, including increases and variable restoration wall times, appear below. No uniform speedup or all-issues-resolved claim is made.

The wide record's trial return has a higher observed wall median, **71.68 → 84.60 ms (+18.0%)**, despite lower EDT CPU time (**29.69 → 24.32 ms**). Per-run wall medians are 71.68/78.00 ms before and 92.03/79.03 ms after. The repeated comparison does not establish the cause of the wall-time increase, and it remains a limitation of these results.

The graph figures are sums of reachable object sizes, **not exclusive retained heap, total JVM memory, or peak RAM**. The original GC-difference probe varies substantially and is reported separately without treating it as an exact storage measure.

## Changes and design choice

- Sparse positions now use one `long[]` instead of three arrays. Each cell retains a 29-bit index, all seven Stone values plus null, and the exact 32-bit move number. Dense arrays retain their previous clone path and cutoff. Oversized positions or a future larger Stone enum fall back to dense storage. Restoration still creates independent mutable arrays before publishing the tree.
- A capture-local cache reuses equal coordinate/PV/visit strings. Candidates and their mutable lists remain independently copied. Its 4,096 slots are allocated lazily, tokens over 64 UTF-16 code units bypass it, and lookup probes at most four slots. Every match checks full equality. Collisions or capacity misses preserve the original value; entries are never evicted. The cache itself is not retained by a snapshot and is never global.
- All four candidate lists, null values, raw numeric spelling, markup/unknown SGF properties, analysis provenance/invalidation, trial numbering, cursor and branch order keep their existing contracts. No edit-command model or multiple-step undo is introduced.

An initial bounded HashMap implementation reduced the graph by the same amount but measured **18.35 ms** for long-PV deletion. A fixed-cache prototype measured **14.87 ms**; final runs are the primary comparison below. Their complete observations are retained alongside the final runs. On all four fixtures, Map, cache prototype, and final implementation have exactly equal graph-size results. The cache does not promise that equivalence for arbitrary inputs: collisions may reduce reuse, while values remain lossless.

Other approaches were considered. Sharing the known immutable ownership representation is safe at an appropriate boundary but does not address the dominant short-string retention here. General live-list or live-node sharing would compromise snapshot independence. Deferred/background compaction or packed string decoding would add handoff/materialization work and new lifecycle behavior. Per-edit inverses would require a wider edit-ownership migration. The chosen changes stay within the private restore representation. Whole-tree metadata copying, board-cell scans, playable-array reconstruction, and continuation replay on insertion remain.

## Measurement conditions and evidence

- Primary baseline: `5c0728e6a8e4b5e21b4395fcd7ddf6308c466ed0` (#179).
- Published final source: [`11f2076168ec5c1a726230383ae593c104cf020e`](https://github.com/22nsuk/KG-next/commit/11f2076168ec5c1a726230383ae593c104cf020e). Its tree `1dea0dc7138138f0adbf01c12fdd8743c2c7a119` is identical to measured local `c5a75a45`; API publication changed commit metadata only.
- The Map source is published as [`7cc5140054bca6822a3f8c3c15f904b92f414a34`](https://github.com/22nsuk/KG-next/commit/7cc5140054bca6822a3f8c3c15f904b92f414a34), tree-equivalent to local `dc1b9fe0`. The earlier fixed-cache prototype has the same algorithm; the final source links the capacity and hash shift through one bit-count constant and strengthens the tests.
- The original SGF reference is `922e57f43bcaba4f9e22c563cab57fece07996c1`. Its frozen production class hashes match the prior measurement record. It is a historical reference, not the parent of this PR, and it does not preserve all non-SGF state required of the new restore snapshot.
- Java 21.0.13, Linux amd64, `-Xmx768m -XX:ActiveProcessorCount=2`, release-17 production classes, the same probe bytecode, dependency hashes, fixture hashes and options for every run. Each process owns an Xorg dummy display (1600×1200×24, authenticated TCP via localhost, no window manager). No other heavy build/probe JVM runs concurrently.
- Each run has five excluded warmups then ten measured samples per fixture/action. Primary baseline and final each have two fresh JVM runs, with the second comparison in reverse order. The chronology is recorded in [runs.json](runs.json). The primary tables pool 20 observations per cell and retain per-run medians in [comparison.json](comparison.json). Samples within a JVM are not independent, execution is not randomized, and no statistical speed guarantee is inferred.
- [EditUndoPerformanceProbe](../../../src/test/java/featurecat/lizzie/gui/EditUndoPerformanceProbe.java) invokes the actual production delete/undo/redo/insert/trial methods on the EDT in an offline window. It verifies restored SGF semantics and the root cursor, and settles asynchronous move-list refreshes between actions. Repaint, deferred metadata refresh and native engine I/O are outside the timed action. CPU/allocation also include the same small result-row bookkeeping.
- [observations.csv](observations.csv) preserves all 2,940 action rows, including warmups and exploratory candidates. [runs.json](runs.json) preserves runtime, fixture, raw GC observations and per-run artifact identities. [footprints.json](footprints.json) includes class-level graph counts and its shared agent/probe identity. [verification.json](verification.json) distinguishes source revisions, local checks and final CI.

## Primary action results

Wall/CPU values are milliseconds. Allocation is MiB (1,048,576 bytes). Changes compare final with #179; positive percentages mean slower observed wall medians. All 28 action/fixture combinations are included.

### Wide variations (21,845 nodes)

| Action | #179 wall | Final wall | Change | EDT CPU before → after | Allocated MiB before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Delete | 37.35 | 32.44 | -13.1% | 37.04 → 30.79 | 14.606 → 13.184 |
| Undo deletion | 76.60 | 66.98 | -12.6% | 57.57 → 49.77 | 80.929 → 79.862 |
| Redo deletion | 66.81 | 57.74 | -13.6% | 56.74 → 49.02 | 67.111 → 65.688 |
| Insert | 73.50 | 57.83 | -21.3% | 62.40 → 55.26 | 132.615 → 131.193 |
| Undo insertion | 102.25 | 87.99 | -13.9% | 56.01 → 51.73 | 80.939 → 79.871 |
| Enter trial | 39.50 | 31.74 | -19.6% | 37.70 → 31.78 | 14.853 → 13.431 |
| Return from trial | 71.68 | 84.60 | +18.0% | 29.69 → 24.32 | 70.005 → 70.005 |

### Dense analysis and ownership (301 nodes)

| Action | #179 wall | Final wall | Change | EDT CPU before → after | Allocated MiB before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Delete | 2.27 | 3.08 | +36.1% | 2.27 → 3.11 | 2.839 → 2.855 |
| Undo deletion | 1.19 | 0.99 | -16.5% | 1.24 → 1.00 | 0.981 → 0.997 |
| Redo deletion | 1.53 | 1.91 | +24.7% | 1.57 → 1.94 | 2.824 → 2.840 |
| Insert | 209.11 | 203.72 | -2.6% | 157.93 → 154.92 | 1095.695 → 1095.697 |
| Undo insertion | 2.18 | 1.94 | -11.2% | 2.08 → 1.94 | 1.163 → 1.164 |
| Enter trial | 2.74 | 3.16 | +15.2% | 2.76 → 3.18 | 2.840 → 2.856 |
| Return from trial | 0.87 | 0.83 | -4.4% | 0.88 → 0.84 | 0.970 → 0.970 |

### Long multilingual comments (201 nodes)

| Action | #179 wall | Final wall | Change | EDT CPU before → after | Allocated MiB before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Delete | 0.44 | 0.45 | +2.5% | 0.46 → 0.47 | 0.125 → 0.125 |
| Undo deletion | 0.55 | 0.54 | -2.1% | 0.56 → 0.55 | 0.651 → 0.651 |
| Redo deletion | 0.57 | 0.62 | +8.8% | 0.58 → 0.63 | 0.118 → 0.118 |
| Insert | 131.23 | 136.58 | +4.1% | 107.09 → 114.45 | 730.693 → 730.687 |
| Undo insertion | 1.52 | 1.66 | +9.7% | 1.55 → 1.67 | 0.773 → 0.764 |
| Enter trial | 0.67 | 0.62 | -6.8% | 0.69 → 0.64 | 0.126 → 0.126 |
| Return from trial | 0.69 | 0.69 | -1.4% | 0.70 → 0.66 | 0.650 → 0.650 |

### Long PV and visits (151 nodes)

| Action | #179 wall | Final wall | Change | EDT CPU before → after | Allocated MiB before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Delete | 2.43 | 13.36 | +450.5% | 2.45 → 13.18 | 3.821 → 3.837 |
| Undo deletion | 0.84 | 0.87 | +4.0% | 0.85 → 0.88 | 0.516 → 0.532 |
| Redo deletion | 1.67 | 6.01 | +259.4% | 1.68 → 5.71 | 3.818 → 3.833 |
| Insert | 197.89 | 161.05 | -18.6% | 80.45 → 89.77 | 553.250 → 553.257 |
| Undo insertion | 2.04 | 1.84 | -9.8% | 2.03 → 1.83 | 0.607 → 0.615 |
| Enter trial | 2.98 | 14.11 | +373.3% | 2.90 → 14.12 | 3.822 → 3.838 |
| Return from trial | 0.61 | 0.54 | -11.5% | 0.61 → 0.55 | 0.490 → 0.490 |

## Fresh SGF reference

These are separate ten-sample reference runs, compared with the final pooled twenty samples. They are not a substitute for the #179 comparison above.

| Fixture | SGF delete → final | SGF undo deletion → final | SGF trial return → final |
| --- | ---: | ---: | ---: |
| Wide variations (21,845 nodes) | 25.42 → 32.44 | 67.72 → 66.98 | 87.05 → 84.60 |
| Dense analysis and ownership (301 nodes) | 18.36 → 3.08 | 40.34 → 0.99 | 41.29 → 0.83 |
| Long multilingual comments (201 nodes) | 11.23 → 0.45 | 22.84 → 0.54 | 23.36 → 0.69 |
| Long PV and visits (151 nodes) | 25.39 → 13.36 | 73.32 → 0.87 | 73.76 → 0.54 |

## Snapshot object graphs

[EditUndoFootprintProbe](../../../src/test/java/featurecat/lizzie/rules/EditUndoFootprintProbe.java) uses a Java instrumentation agent to sum each identity-distinct reachable object once, including String backing arrays. It measures the pending undo after the same root deletion, with move-list refresh settled before and after. The headless rules harness replaces UI/engine callbacks; no latency is measured.

It excludes static fields, enum constants and the snapshot’s Board owner back-reference. The second metric additionally subtracts objects reachable from the remaining live history. Other GUI/task/JVM roots are not subtracted. Therefore neither metric is exclusive retained heap. Inaccessible fields fail the probe instead of being silently skipped.

| Fixture | Graph MiB before → final | Objects outside live-history graph, MiB before → final | Reachable String objects before → final |
| --- | ---: | ---: | ---: |
| Wide variations (21,845 nodes) | 15.915 → 14.493 | 14.284 → 12.862 | 21,856 → 21,856 |
| Dense analysis and ownership (301 nodes) | 6.858 → 2.892 | 6.839 → 2.884 | 87,602 → 953 |
| Long multilingual comments (201 nodes) | 5.022 → 5.022 | 4.992 → 4.992 | 212 → 212 |
| Long PV and visits (151 nodes) | 32.383 → 3.888 | 32.188 → 3.843 | 623,792 → 1,298 |

The long-PV input has 618,496 PV/visit elements but only 361 distinct PV coordinate values and 473 visit-count values. Copying lists alone previously kept the many parsed String objects alive. Token reuse removes that duplicated object representation without trimming PVs, visit data, ownership or analysis metadata. Wide records have no candidate tokens; their reduction comes from position arrays, while per-node metadata remains.

## GC-difference observations

The original UI probe requests GC with a pending undo, then discards undo and requests GC again. Three untimed passes per JVM include auxiliary edit lists. The primary comparison therefore has six differences per revision. These observations depend on other object lifetimes and GC behavior; they are not an exact retained-object or peak-memory limit.

| Fixture | Before median MiB (range) | Final median MiB (range) |
| --- | ---: | ---: |
| Wide variations (21,845 nodes) | 13.758 (13.757–13.761) | 12.334 (12.293–12.336) |
| Dense analysis and ownership (301 nodes) | 6.850 (2.820–16.487) | 2.886 (2.809–2.904) |
| Long multilingual comments (201 nodes) | 4.994 (4.993–4.999) | 2.548 (0.071–19.209) |
| Long PV and visits (151 nodes) | 18.012 (3.814–32.203) | 3.856 (3.814–3.857) |

## Verification

- Final source: eight relevant classes, **346 tests executed and passed**, zero failures, errors, skips or aborts. They cover full editable restoration, all Stone/number cases, exact analysis/provenance, SGF semantics, delayed position confirmation and HumanSL rollback. Same-hash strings beyond four slots, over-capacity input, 64/65-character boundaries, negative hashes and independently writable restored lists are exercised.
- The initial Map candidate had a full headless Maven `verify`: **8,332 unit invocations, 8,138 executed/passed, 194 environment skips**, plus 19 integration invocations with the shaded-logging smoke executed/passed and 18 environment skips. No failures/errors. This result belongs to the intermediate implementation; it is not presented as a full final-cache suite pass.
- For the final local checks/measurements, both changed production source files were recompiled with the same JDK and `--release 17 -g` onto the fully verified production set. The class-set audit accounts for the one new nested cache class (3,247 total), verifies no missing/extra classes, and limits replaced class families to the recompiled sources. Changed test/probe classes were also recompiled. The PR checks will provide the separate full rebuild validation of the final published revision; their status is recorded on the PR.
- Every latency process passed the restoration/cursor oracle and its before/after artifact identity check. Every footprint run passed, and Map/prototype/final graph results match exactly on these fixtures.
- One-step undo ownership, trial return, GameInfo/rules policy and engine synchronization remain covered by the existing production paths and the final focused tests. No source data or assertions were dropped to improve memory results.

## Reproduction

Build production revisions separately and use one compiled probe/test-class set and dependency classpath against each production class directory. Use fresh output directories, the same owned display, JDK and JVM options, and run processes sequentially.

```sh
mvn -Dfmt.skip=true -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/edit-probe-classpath.txt

java -Xmx768m -XX:ActiveProcessorCount=2 -Djava.awt.headless=false \
  -cp "$PROBE_TEST_CLASSES:$PRODUCTION_CLASSES:$DEPENDENCY_CP" \
  featurecat.lizzie.gui.EditUndoPerformanceProbe "$FRESH_OUTPUT_DIRECTORY" 10 5
```

The recorded runs use Xorg dummy displays. Xvfb can supply a different isolated display environment for reproduction, but use the same environment for both revisions and do not pool those results with the recorded runs.

For the graph measurement, compile the final footprint probe once, package its main class in a small agent JAR with manifest entry `Premain-Class: featurecat.lizzie.rules.EditUndoFootprintProbe`, and reuse that agent/probe bytecode for both revisions. The normal test classpath supplies its nested helper and rules harness. `FIXTURE_DIRECTORY` points to the SGFs generated by the latency probe.

```sh
java -Xmx768m -XX:ActiveProcessorCount=2 -Djava.awt.headless=true \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  -javaagent:"$FOOTPRINT_AGENT_JAR" \
  -cp "$PROBE_TEST_CLASSES:$PRODUCTION_CLASSES:$DEPENDENCY_CP" \
  featurecat.lizzie.rules.EditUndoFootprintProbe \
  "$FIXTURE_DIRECTORY" "$NEW_OUTPUT_JSON"
```

The baseline graph probe requires #179's in-memory snapshot fields; it is not used against the pre-#179 SGF implementation. These measurements do not establish Windows desktop latency, native KataGo I/O performance or an RSS ceiling. All source revisions, hashes and complete raw observations needed to distinguish exploratory and final results are retained above.
