# Single-step edit undo and trial restoration

Measured on 2026-11-14 KST (2026-11-13 UTC). This change removes the complete-SGF
write/reparse path from edit undo/redo and desktop trial entry/return. It retains
one pending undo or redo; it does not add a multi-step history.

## Design decision

| Approach | Capture/restore cost | Implementation and semantic burden |
| --- | --- | --- |
| Independent in-memory snapshot | Still visits the whole tree; no SGF formatting, escaping, analysis-text encoding, or reparsing | Fits the existing whole-state, single-step contract; must detach mutable payloads and restore actual executable positions |
| Reversible edit changes | Can approach the changed subtree/property size | Needs command ownership for every edit and its inverse, including later moves, branch order, cursor, metadata and analysis invalidation |

The [v2 edit model](https://github.com/wimi321/lizzieyzy-next-v2/blob/175664fd03c23bd8c74c1ea181797baecbde92de/crates/document/src/edit.rs)
is a useful reference for explicit add/delete/property/promote changes. Importing
that architecture would require routing KG's existing edit paths through a shared
command layer. This PR takes the independent snapshot approach and keeps that
larger migration separate from the current single-step behavior.

A direct clone of every playable stones/grid array was rejected during exploratory
measurement: allocation and capture latency increased on the wide tree. The final
snapshot therefore has a dedicated restore representation:

- `BoardPositionSnapshot` independently packs occupied or numbered cells. Dense
  positions use exact array copies. One capture-local buffer avoids scanning sparse
  arrays twice; no buffer or mutable live array is retained by a snapshot.
- `BoardHistorySnapshot` owns detached node metadata, full candidate payloads,
  ordered parent indices and the selected cursor. Default node information and
  empty mutable containers are materialized when restoring, preserving their values.
- Restore recreates every stones/grid array and writable container **before** the
  tree is published. This type is distinct from a lightweight SGF export snapshot;
  it retains hashes, actual positions and non-SGF analysis/edit state.
- Trial return and its initial truncation undo share one single-use snapshot. Either
  action clears the other owner. Insertion's internal deletion reuses the enclosing
  edit capture instead of taking the same whole-tree snapshot twice.

Capture and restoration remain proportional to the tree and position data. This is
not an O(edited-node-count) command log, and memory use is not guaranteed to match
the old SGF string. The measured tradeoffs are recorded below.

## Restoration contract

The tests cover ordered branches and the exact cursor, repeated unknown SGF
properties, markup, setup/removed stones, rectangular geometry, hash tables,
move-number grids, comments and extra-stone state. All four candidate lists retain
their mutable nested payloads independently, including out-of-range candidates,
PV visit arrays and ownership. Analysis provenance, perspective, root visits and
invalidation flags are retained; previous live source/run permissions and edit
revisions are not granted to a new live tree.

Undo/redo preserves the **current** GameInfo and session rules, matching the edit
UI's prior policy. Trial return restores the pre-trial game metadata and rules.
These choices are applied before scheduling engine synchronization. Analysis only
resumes after the existing coordinator confirms the exact position; an idle or
paused edit stays idle/paused, including a pause requested while confirmation is
pending. File-load automatic analysis is not started by an edit restore.

Undo can exit trial play, and redo can then keep trial moves as ordinary moves.
Only those private edit snapshots normalize trial numbering. `MN`, zeroed branch
labels, setup nodes and passes remain distinct. The pre-trial root count and dummy
increments preserve synchronized roots with nonzero counts and the web trial's
uncounted placeholders. A numbering context cannot affect another root.

## Measurement contract

- Baseline production: `922e57f43bcaba4f9e22c563cab57fece07996c1`.
- Same probe source/classes, fixture SHA-256 values and dependency set in both
  revisions; separate production application processes and owned Xvfb desktops.
- Java 21.0.13, Linux amd64, `-Xmx768m -XX:ActiveProcessorCount=2`.
- Five complete warmup samples excluded, followed by ten samples per fixture.
  Both variants run sequentially. These are repeated, unpaired observations
  within one warm JVM per variant, not statistically independent samples or a
  guarantee for all machines.
- Production `deleteMoveNoHintAfter`, `cleanedit`, `reedit`, `insertMove` and
  `tryPlay` execute on Swing's event dispatch thread (EDT). The wide-tree deletion
  removes the first root variation; capture still covers the entire record.
- Each scenario starts with a completed file open at the root. Candidate/PV export
  limits are disabled and winrate comments are not appended. An independent SGF
  semantic oracle and cursor check run after undo and trial return in every sample.
- Wall time brackets only the synchronous action. Calling-EDT CPU time and allocated
  bytes also include the same probe row-bookkeeping overhead in both variants.
  Repaint, asynchronous move-list metadata refresh and native engine I/O are
  excluded; metadata work is settled between actions. The benchmark is offline.
  Controlled GTP tests separately validate engine confirmation, stale output and
  pause/resume behavior.
- Retained undo heap is a separate untimed observation: after a deletion and an
  explicit `System.gc()` request, record used heap, discard undo, request GC again,
  and subtract. Three passes per fixture include auxiliary edit lists. These are approximate JVM used-heap
  differences, not peak RAM, RSS, exact object-retention sizes or allocation totals.

## Results

Baseline production is `922e57f43bcaba4f9e22c563cab57fece07996c1`. The candidate
was measured at local commit `0fb71c1d388400bd2c46be029f9c0739fff31171`; its
[published source commit](https://github.com/22nsuk/KG-next/commit/69ee78630ac143af86ab3114005a121516c76f6b)
has the identical Git tree `4ebc579141d7a5f3588a5e55f9f557525cb6d669`. GitHub API
publication changed commit metadata only, not the measured files. All four fixtures
and every restore check passed in both runs. Probe source, probe class bytes, dependencies, runtime,
fixture hashes and sample counts match; each run kept its artifacts unchanged.

**The result is not a uniform latency improvement.** The wide tree's first deletion
rose from **35.70 to 42.62 ms** (19.4%, 6.92 ms). Its deletion undo fell from
**116.57 to 64.21 ms**, and its insertion, insertion undo, redo and trial actions
also had lower wall medians. The other 21 fixture/action combinations had lower
wall medians too. The initial wide-tree capture still visits and copies all node
state, even when the actual deletion is small. This remaining regression is part
of the tradeoff, not a passed "every edit gets faster" acceptance condition.

Analysis-heavy and long-comment records benefit most from removing text conversion.
Insertion still replays the retained continuation: the snapshot change lowers its
measured cost but does not remove the roughly 522–1,033 MiB of EDT allocations
in these three insertion cases. These are transient allocations, not retained RAM.

### Fixtures

| Fixture | Nodes | Forks | Candidates/node | PV length | Ownership/node | SGF MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Wide variations | 21,845 | 5,461 | 0 | 0 | 0 | 1.48 |
| Dense analysis and ownership | 301 | 0 | 32 | 8 | 361 | 1.53 |
| Long multilingual comments | 201 | 0 | 0 | 0 | 0 | 4.60 |
| Dense analysis with long PV and visits | 151 | 0 | 32 | 64 | 361 | 2.90 |

These are deterministic synthetic SGFs. The wide record has 32 comment characters
per node; the long-comment record has 12,800. The long-PV fixture includes 64 PV
visit entries per candidate. Exact fixture hashes and raw observations are in
[before.json](before.json) and [after.json](after.json).

### Synchronous action medians

Ten measured observations per cell, after five warmups. Negative change means a
lower wall median. CPU and allocated bytes include the fixed probe bookkeeping
described above. One MiB is 1,048,576 bytes.

#### Wide variations

| Action | Before wall ms | After wall ms | Change | CPU ms before → after | Allocated MiB before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Delete | 35.70 | 42.62 | +19.4% | 30.52 → 41.04 | 19.03 → 14.61 |
| Undo deletion | 116.57 | 64.21 | -44.9% | 86.43 → 62.76 | 120.67 → 80.93 |
| Redo deletion | 94.47 | 90.71 | -4.0% | 75.80 → 69.92 | 99.77 → 67.11 |
| Insert | 97.95 | 80.41 | -17.9% | 97.36 → 71.99 | 149.46 → 126.00 |
| Undo insertion | 210.80 | 106.42 | -49.5% | 90.35 → 63.74 | 120.68 → 80.94 |
| Enter trial | 66.55 | 50.95 | -23.4% | 62.73 → 50.53 | 38.06 → 14.85 |
| Return from trial | 122.10 | 70.08 | -42.6% | 58.37 → 33.17 | 107.86 → 70.01 |

#### Dense analysis and ownership

| Action | Before wall ms | After wall ms | Change | CPU ms before → after | Allocated MiB before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Delete | 30.02 | 2.74 | -90.9% | 30.06 → 2.76 | 32.79 → 2.84 |
| Undo deletion | 54.77 | 1.89 | -96.5% | 54.74 → 1.91 | 28.59 → 0.98 |
| Redo deletion | 26.19 | 1.79 | -93.2% | 26.11 → 1.78 | 32.88 → 2.82 |
| Insert | 387.62 | 249.12 | -35.7% | 331.75 → 196.30 | 1096.00 → 1033.31 |
| Undo insertion | 57.85 | 2.82 | -95.1% | 57.86 → 2.74 | 28.63 → 1.16 |
| Enter trial | 51.52 | 3.18 | -93.8% | 51.43 → 3.20 | 65.56 → 2.84 |
| Return from trial | 57.46 | 1.49 | -97.4% | 57.28 → 1.46 | 28.48 → 0.97 |

#### Long multilingual comments

| Action | Before wall ms | After wall ms | Change | CPU ms before → after | Allocated MiB before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Delete | 15.33 | 0.67 | -95.6% | 13.92 → 0.68 | 30.80 → 0.13 |
| Undo deletion | 29.44 | 0.79 | -97.3% | 29.46 → 0.80 | 21.60 → 0.65 |
| Redo deletion | 14.16 | 0.69 | -95.1% | 14.17 → 0.70 | 30.91 → 0.12 |
| Insert | 212.32 | 171.35 | -19.3% | 179.88 → 145.91 | 750.39 → 688.97 |
| Undo insertion | 35.23 | 1.62 | -95.4% | 34.82 → 1.64 | 21.63 → 0.77 |
| Enter trial | 36.78 | 1.03 | -97.2% | 34.12 → 1.05 | 61.59 → 0.13 |
| Return from trial | 34.77 | 1.27 | -96.3% | 32.48 → 1.25 | 21.42 → 0.65 |

#### Dense analysis with long PV and visits

| Action | Before wall ms | After wall ms | Change | CPU ms before → after | Allocated MiB before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Delete | 34.32 | 3.18 | -90.7% | 34.05 → 3.19 | 43.36 → 3.82 |
| Undo deletion | 104.85 | 1.08 | -99.0% | 104.81 → 1.12 | 53.72 → 0.52 |
| Redo deletion | 33.81 | 1.66 | -95.1% | 33.82 → 1.60 | 43.72 → 3.82 |
| Insert | 266.23 | 202.30 | -24.0% | 177.64 → 98.14 | 604.72 → 521.85 |
| Undo insertion | 103.70 | 2.82 | -97.3% | 103.70 → 2.63 | 53.74 → 0.61 |
| Enter trial | 74.91 | 3.65 | -95.1% | 68.90 → 3.66 | 86.70 → 3.82 |
| Return from trial | 114.96 | 0.86 | -99.3% | 105.55 → 0.80 | 53.44 → 0.49 |

### Retained undo heap observations

Median of three separate used-heap differences, with the full observed range.
An independent restore snapshot retains more object state than an SGF string;
the wide and long-PV fixtures therefore keep substantially more heap. GC timing
and other application objects make the small-sample heap differences approximate.
These figures must not be used as a peak-memory limit.

| Fixture | Before median MiB | After median MiB | Before observed range MiB | After observed range MiB |
| --- | ---: | ---: | ---: | ---: |
| Wide variations | 2.00 | 13.76 | 1.00–2.00 | 13.76–13.76 |
| Dense analysis and ownership | 4.01 | 6.85 | 4.01–4.01 | 2.83–6.85 |
| Long multilingual comments | 6.00 | 4.99 | 6.00–6.00 | 0.10–4.99 |
| Dense analysis with long PV and visits | 6.01 | 32.20 | 6.00–6.01 | 32.19–32.21 |

[comparison.json](comparison.json) contains the derived medians.
[Before artifact identity](before-identity.json) and
[after artifact identity](after-identity.json) record the hashes checked around
each application process. [verification.json](verification.json) records the
test counts, source revisions, log digests and negative-control result.

## Verification

- Fresh-output Maven `verify` passed with Java 21.0.13, headless mode, and
  `-Dfmt.skip=true -Djacoco.skip=true`: 8,292 unit-test invocations, zero failures
  or errors, 192 environment skips (8,100 executed); 19 integration-test
  invocations, zero failures or errors, 18 environment skips (the shaded-logging
  smoke test executed). This is not a coverage-gate or native-engine result.
- After fixing test teardown to drain the rules fixture's owned move-list worker,
  all rules tests were rebuilt and rerun: 1,730 invocations, zero failures/errors,
  eight environment skips (1,722 executed), with no uncaught background exception.
  This follow-up overlaps the full suite; its count is not additional distinct tests.
- `BoardEditUndoTest`, `BoardPositionSnapshotTest`,
  `PositionConfirmedRollbackTest` and `HumanSlGameControllerIntegrationTest`
  cover the restore contract above, stale analysis permissions, delayed engine
  confirmation, paused/running/idle states and failed mode handoff.
- A negative control ran the same preservation test against the frozen baseline
  production classes. It failed at the explicit assertion that undo must retain
  extra-stone state with no SGF representation; the candidate passed. This checks
  that the regression test can detect an actual old-path loss, not only round-trip
  equality of already-exported SGF.
- The portable repository checks and `git diff --check` passed; final checks include
  the raw measurement files and this document.

An earlier incremental build attempt had missing auxiliary class files and failed
`QuickBatchFlowTest`. Rebuilding into an empty output directory produced all 3,242
classes in the compiler manifest and resolved that error. The same earlier run
also saw one teardown-count failure in the unchanged `HumanSlAnalysisRunnerTest`;
all 75 tests in that class passed in the full fresh run. Its existing fake process
returns an exit value even while alive, so it can race asynchronous cleanup; that
fixture was not changed in this PR. No failing test was excluded to obtain the
fresh-run result.

The native Windows desktop and a real KataGo process were not benchmarked here.
The offline production-window measurements and controlled protocol tests have
different scopes; neither is presented as native-engine end-to-end latency.

## Reproducing the application probe

Use the same two test-only probe sources from this PR with each production
revision. The baseline needs only their package visibility changes and the new
manual entry point; do not apply the production changes to the baseline checkout.

```bash
mvn -Dfmt.skip=true -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/edit-probe-classpath.txt

xvfb-run -a java -Xmx768m -XX:ActiveProcessorCount=2 \
  -Djava.awt.headless=false \
  -cp "target/test-classes:target/classes:$(cat target/edit-probe-classpath.txt)" \
  featurecat.lizzie.gui.EditUndoPerformanceProbe target/edit-undo-probe 10 5
```

Use a fresh output directory for each run. The probe creates an isolated application
configuration and deterministic SGFs there. It records all warmups and observations
in `summary.json`; no timing assertion is added to CI. Compare only results whose
fixture, probe, dependency and runtime controls match.
