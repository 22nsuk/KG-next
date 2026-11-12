# Evaluation: 2026-11-12

This is the initial unadapted pipe experiment. See the [native follow-up](NATIVE_RESULTS.md)
for the subsequent raw compatibility API, real JNA binding, and Windows/Linux measurements.

**Keep the production parser in Java.** The pinned Rust library parses the compatible
large inputs faster internally, but full Java handoff/history construction has mixed results
and KG extension/escape compatibility is not yet sufficient for a replacement.

This is one development experiment, not a product benchmark or a compatibility percentage.
The corpus deliberately contains known edge cases. The [raw evidence](results/2026-11-12.json)
contains every result, warning, history digest, timing sample, fixture/source hash and tool version.
The [runner instructions](README.md) describe the measurement boundaries and reproduction command.

## Measured state

- KG base: `9d85a973ea2130923992bd7f83ca34f2124803ea`, with this PR's parser changes.
  The manifest records the exact measured source hashes and uncommitted state; it does not
  claim that the base commit alone contains the measured implementation.
- Rust library: `wimi321/lizzieyzy-next-v2` at `c1718bbefe4c2149f545db5b7bb191c7b37b7b27`.
- Linux amd64; OpenJDK `21.0.13+9-1-deb13u2-Debian`; Rust/Cargo `1.96.0`; Maven `3.9.9`.
- The JVM reported 9 logical processors and a 1.5 GiB maximum heap. Five warm samples follow
  two excluded warmups per fixture; no CPU pinning, GC isolation or cache eviction was used.
- Positive document/history controls passed; changed-value and missing-branch controls were
  detected. Valid and stale KGP source controls passed. The runner exited successfully.

## Compatibility findings

Of 37 cases, 15 matched both the adapted document and KG history, 17 differed (including
adapter/game-selection gaps), Rust rejected four, and Java rejected one. These categories
describe this corpus and bridge; they are not a general pass rate for either parser.

| Finding | Observed result and implication |
| --- | --- |
| Numbered analysis properties | `(;LZ2[x])` is rejected by Rust. After `SZ`, `LZ2`/`LZOP2` text can be absorbed into the preceding value, accompanied by `UnescapedClosingBracket` warnings. A valid secondary analysis fixture also loses its secondary slot through this bridge. This blocks production adoption. |
| Retained analysis and provenance | Ordinary `LZOP` + valid `KGP` preserves candidates, analysis/ownership sources and invalidation in both paths. A one-character payload edit rejects stale provenance in both. |
| Legacy newline conventions | KG's literal `\n` and escaped physical linebreak fixtures retain one candidate and valid sources. Rust interprets them differently; the adapted histories have no candidate or valid source. Syntax-only success would miss this impact. |
| Compressed setup ranges | The checked-in multilingual UI fixture and one diagnostic differ because KG expands `AB[aa:bb]` while the minimal Rust bridge retains the range. Rust preserves the syntax; a complete adapter must apply KG's existing setup normalization. |
| Escaped opaque values | The repeated-property/label fixture differs at `LB[aa:a\:b]`; KG retains an escape that Rust removes. Property order and repeated values are transferred, but KG's opaque value contract needs an explicit adapter decision. |
| Encodings | CA-declared Big5 and Shift_JIS examples decode to the intended text in Rust while KG's detector produces different text. Without CA, an EUC-KR example is correct in KG and different in Rust. UTF-16 BOM cases match. A decoder switch cannot be treated as universally better. |
| Collections and malformed inputs | Collection syntax is retained, but game selection is intentionally not implemented. Surrounding text/structural escapes, lowercase identifiers, malformed brackets and missing closure have different tolerance. Rust rejects the depth-260 case at its nesting limit. |

## Timing results

Median milliseconds from the final measured source state:

| Generated fixture | Java syntax | Rust internal parse | Rust full transport + document | Java total | Rust total |
| --- | ---: | ---: | ---: | ---: | ---: |
| 21,845 nodes / wide variations | 17.722 | 11.033 | 36.865 | 66.590 | 105.645 |
| 301 nodes / dense analysis + ownership | 23.500 | 4.312 | 12.837 | 41.231 | 32.995 |
| 201 nodes / long multilingual comments | 40.367 | 12.550 | 43.091 | 57.356 | 59.681 |
| 151 nodes / 32 candidates + 64-step PVs | 50.945 | 7.557 | 19.737 | 81.196 | 57.696 |

Java total is syntax plus detached history construction. Rust total includes UTF-8 encoding,
complete pipe transport, native response construction, Java response decoding/value adaptation,
document allocation and the same history builder. Neither total includes disk I/O, UI installation
or engine synchronization. Medians of separate stages do not add up to the median total.

The two analysis-heavy fixtures favor Rust's full path in this run. The wide tree favors Java,
and the long-comment totals are close enough that this small, noisy run should not rank them.
Cold process/document medians were 76.437, 19.107, 46.152 and 29.024 ms respectively; these exclude
history construction and shutdown. All raw ranges/samples, plus three small checked-in SGF
measurements, are retained in the JSON evidence. The fourth checked-in file is not timed because
of the range-adapter difference.

## Adoption decision

The Java separation is useful now: immutable syntax can be read without the application,
captured options build private histories, and independently parsed histories retain coherent
coordinate/hash semantics through resize and synchronization. It provides a boundary at which
a later native parser could be evaluated without replacing board rules or UI ownership.

Before connecting Rust to the product, fix or adapt numbered extension identifiers, define
legacy escaping and encoding policy, normalize setup ranges, and pass the retained-analysis
corpus. Then measure a real proposed binding, including all copies, cancellation and lifecycle
costs. JNI/FFM could change handoff costs, but this experiment does not measure either. Native
packaging, cross-platform operation, allocation/peak RSS and interactive responsiveness remain
unmeasured. There is no production native dependency or serializer change in this PR.
