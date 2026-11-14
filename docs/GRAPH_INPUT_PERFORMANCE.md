# Graph input path reuse

`WinrateGraph.resolveMoveTargetNode` still rejects coordinates outside the graph
and the padded overview before reading history. A possible hit still validates
the rendered parameters, main-trunk end, graph mode, anchors, navigation targets,
and overview on every lookup.

The validation now owns one short-lived `GraphPath`. It computes the selected
path's end, main-trunk/variation-top classification, and visible node list only
when needed. Navigation validation and the overview consume the same list.
Navigation validation compares the rendered targets directly with that list,
including node identity, order, current x coordinate, and both length limits;
it no longer creates a temporary target object for every node. Drawing and
validation share the same target-eligibility predicate. No
`GraphPath` is kept in a graph field or reused by a later pointer event.

## Preserved behavior

The normal graph still walks anchors backwards, switches from the variation's
fork to the main-trunk end, and carries its fallback winrate across that switch.
Engine-game/PK anchors still use the current node and the node two moves back,
including the existing duplicate anchors. These are different policies and do
not become one forward traversal.

The path is based on `renderedCurrentGraphNode`, preserving the second drag or
hover before the next draw. It does not substitute the live current node or the
main-trunk end for that base. In particular, terminal dummy handling still uses
the original `next()` and `isMainTrunk()` rules. If an earlier freshness comparison
fails, later path lists and overview data are not constructed.

The selected v2 reference is the separation of path structure from analysis
validity and current values. v2's
[`GraphView::from_document_with_branch`](https://github.com/wimi321/lizzieyzy-next-v2/blob/175664fd03c23bd8c74c1ea181797baecbde92de/crates/app-core/src/graph.rs)
builds a path and its branch information together. Its
[`graph_analysis` state](https://github.com/wimi321/lizzieyzy-next-v2/blob/175664fd03c23bd8c74c1ea181797baecbde92de/crates/app-core/src/actor/graph_analysis.rs)
uses content revision for stored-analysis validity, not for freezing all graph
values. KG retains its own per-lookup comparisons instead of adopting that
revision as a general graph cache key.

## Correctness checks

The existing 120 graph tests passed before the change. With the new tests and
the baseline production classes, 124 tests ran: 122 passed and only the two new
path-sharing cases failed. They observed two selected-end lookups without the
overview and three with it, rather than the required one.

The updated implementation passes all 124 cases, without failures, errors, or
skips. The new tests check one
end/trunk/fork calculation per lookup, rejection after an in-place analysis
update, navigation after redraw, and addition and removal of a variation
continuation while both the current node and main-trunk end retain their
identities. These structure checks run with the winrate line visible and hidden,
with the overview disabled, so navigation validation must work independently of
anchors. A click after redraw selects the variation node rather than the
main-trunk node with the same move number. Existing checks cover snapshot
boundaries, dense columns, overview
occlusion and padding, engine-game/PK mode, and drag before redraw.

## Comparison protocol

Baseline: main `922e57f43bcaba4f9e22c563cab57fece07996c1`.
The archived
[`GraphHitPairedProbe.java`](performance/2026-11-14/GraphHitPairedProbe.java)
runs the baseline and candidate together in each JVM, against the same test fixture,
board objects, and dependencies. The baseline source changes only its public
class declaration to `BaselineWinrateGraph`; reversing that single substitution
reproduces the exact baseline bytes. The probe
uses the existing graph tests' isolated configuration, board and frame setup.
It builds synthetic 3x3 history metadata, then uses the real graph renderer and
`resolveMoveTargetNode` on the Swing event thread. These are graph workloads, not
legal-game generation or engine-search benchmarks.

There are 400-move and 2,000-move main lines. A third tree has a 2,000-move main
line with 128 variations at move 1,000: 127 have 64 moves and the selected one has
2,000 moves, for 12,129 nodes including the root. Its visible path contains 4,001
nodes; hidden sibling branches are retained in the fixture but are not a claim
that every sibling is traversed on input. The rendered base is halfway along the
selected continuation. Each tree runs ordinary and PK modes with overview off
and on, at 1200x260 pixels. Four in-bounds coordinates rotate through the main
graph and the overview region.

Each case measures both fresh input and rejection after changing the same
current node's winrate. Rendering and fixture creation happen outside the timed
region. ThreadMXBean counters measure EDT CPU and allocated bytes around batches
of lookups; wall time is also retained. These are transient allocations, not
retained heap, peak process memory, or application-wide CPU.

Each version receives 15,000 warm-up lookups in the first case and 1,500 in each
remaining case. Twelve adjacent batch pairs per case alternate baseline first
(AB) and candidate first (BA), six of each order. Each batch has 200 lookups.
The unchanged protocol runs in two JVMs. All 576 pairs are retained, including
outliers. Before timing, the probe checks
exact returned-node identity at all four coordinates and requires stale input
to return null. During timing, direct method calls accumulate a node checksum;
paired checksums must match. Fixture reflection, rendering, and setup are
outside the measured loop.

CPU change is the median of all 24 adjacent candidate/baseline ratios per case
across the two JVMs;
allocation change compares the two versions' median bytes per lookup. The raw
data also records separate AB/BA and per-run summaries, so execution-order and
run sensitivity are visible. These are descriptive measurements, not statistical
confidence intervals. Preliminary separate-JVM timings
were variable, so adjacent comparisons were chosen to reduce environmental
drift. The first final-source run still had large order differences and two
long-main-line cases without CPU improvement. The second run repeats the same
protocol to check that uncertainty. Neither run or individual sample is excluded.

The environment is
Linux x86-64, Temurin 21.0.13+9, nine available logical CPUs, a fixed 512MiB heap,
`-XX:-UsePerfData`, and `java.awt.headless=true`. The host is shared; other user
work was not paused. No native engine or physical display is part of this probe.

## Results

Final measurements and all batch observations are in
[`graph-input.json`](performance/2026-11-14/graph-input.json), together with source,
compiled-class, fixture, and dependency fingerprints. The fingerprints are
checked again after measurement.

The following table describes the final two runs. Positive CPU percentages
mean a lower paired median. Allocations are median bytes per lookup.

| History | Mode | Overview | Baseline bytes | Candidate bytes | Allocation reduction | Observed CPU reduction |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 2,000 main | Normal | Off | 498,024 | 352,488 | 29.2% | 28.9% |
| 2,000 main | Normal | On | 1,265,950 | 1,022,846 | 19.2% | 6.8% |
| 2,000 main | PK | Off | 559,088 | 445,552 | 20.3% | 16.0% |
| 2,000 main | PK | On | 1,319,898 | 1,108,794 | 16.0% | 2.7% |
| 2,000 main + 128 variations | Normal | Off | 850,392 | 576,172 | 32.2% | 22.6% |
| 2,000 main + 128 variations | Normal | On | 2,388,082 | 1,919,626 | 19.6% | 11.0% |
| 2,000 main + 128 variations | PK | Off | 821,912 | 643,720 | 21.7% | 5.6% |
| 2,000 main + 128 variations | PK | On | 2,352,946 | 1,980,514 | 15.8% | 10.7% |

All eight long-history conditions allocate less; the measured reductions are
15.8–32.2%. The repeated path traversals also fall from two or three to one in
the regression checks. These are the strongest evidence for keeping this
bounded change.

The pooled CPU medians are 2.7–28.9% lower, but this is not a stable improvement
guarantee for every condition. For example, PK with a long main line and
overview is 12.2% slower in the first run and 16.0% faster in the second; its
pooled reduction is only 2.7%. Some variation cases also change direction
between runs. The stale-input control has large order and run differences as
well, despite exiting before navigation and overview work. CPU scheduling,
hardware state, JIT optimization, or other shared-host effects are not isolated
by this probe; the data cannot assign the variation to one cause. Exact CPU
improvement rates and uniform per-condition CPU reduction remain unestablished.

Stale-input allocation does not benefit from shared navigation or overview
work because the anchor comparison rejects it first. On the long histories,
the pooled medians add 20–32 bytes per lookup (less than 0.02%). All stale
identity checks and paired checksums pass. The 400-move cases, stale cases,
per-run summaries, and all observations are included in the JSON.

The workload measures synchronous graph input validation and hit resolution.
It does not measure painting, native-engine analysis, overall application CPU,
retained memory, Windows interaction latency, or physical-display FPS.

## Reproduction

From the candidate checkout, build the application and test fixture with JDK 21.
Generate the baseline's renamed class directly from the fixed Git revision.
Both implementations then use the same classpath and node objects.

```sh
mvn -B -ntp -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/graph-probe-classpath.txt -Dmdep.includeScope=test

graph_probe_cp="target/test-classes:target/classes:$(cat target/graph-probe-classpath.txt)"
mkdir -p target/graph-probe
python3 - <<'PY'
from pathlib import Path
import subprocess

ref = "922e57f43bcaba4f9e22c563cab57fece07996c1"
path = "src/main/java/featurecat/lizzie/gui/WinrateGraph.java"
source = subprocess.check_output(["git", "show", f"{ref}:{path}"])
declaration = b"public class WinrateGraph {"
assert source.count(declaration) == 1
renamed = source.replace(declaration, b"public class BaselineWinrateGraph {", 1)
Path("target/graph-probe/BaselineWinrateGraph.java").write_bytes(renamed)
PY
javac --release 17 -cp "$graph_probe_cp" -d target/graph-probe \
  target/graph-probe/BaselineWinrateGraph.java
javac --release 21 -cp "target/graph-probe:$graph_probe_cp" -d target/graph-probe \
  docs/performance/2026-11-14/GraphHitPairedProbe.java
java -XX:-UsePerfData -Xms512m -Xmx512m -Djava.awt.headless=true \
  -cp "target/graph-probe:$graph_probe_cp" featurecat.lizzie.gui.GraphHitPairedProbe \
  > target/graph-input.csv
```

Numeric CSV columns are main-line moves, variation count, PK mode, overview,
fresh/stale state, pair index, AB/BA order, baseline/candidate CPU nanoseconds per
lookup, baseline/candidate allocated bytes per lookup, baseline/candidate
elapsed nanoseconds per lookup, and the common selected-node checksum. Other
application log lines are not observations. Timing is not a CI threshold.
