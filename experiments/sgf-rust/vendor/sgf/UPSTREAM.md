# Vendored upstream source

Source: <https://github.com/wimi321/lizzieyzy-next-v2>, commit
`c1718bbefe4c2149f545db5b7bb191c7b37b7b27`, `crates/sgf`.
The root upstream GPL-3.0-only `LICENSE` is included here. Cargo package values
and lints expand the original inherited workspace values without changing them.

Local changes add a separate `parse_kg_raw` API for KG's normalized UTF-8 event
contract, with raw values that cannot be passed to the upstream writer. The
normal `parse`/`serialize` paths and upstream test assertions are retained.
Raw mode has explicit experiment budgets: depth 256, one million events, and
64 MiB of encoded event bytes (including repeated identifier bytes). Budget
exhaustion is an error for the Java caller's explicit fallback path.
Upstream SGF fixtures are copied without modification into `fixtures/sgf`;
the upstream test fixture path is adjusted for this standalone vendored crate.

The SGF fixtures are marked binary in Git because their original encodings,
CR/LF conventions and whitespace are test inputs, not source formatting.
