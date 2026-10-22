# Development Guide

This guide is for people who want to change code, fix packaging, improve docs, or continue maintaining this fork.

If you only want to use the app, start with the [Installation Guide](INSTALL_EN.md), [Troubleshooting](TROUBLESHOOTING_EN.md), and [Package Overview](PACKAGES_EN.md).

## Important Context First

- This is a maintained LizzieYzy fork, not a one-off patch repository.
- The most important user flow is: install the app, launch it, fetch public Fox games through **Fox nickname**, and analyze normally.
- The repository has a broad deterministic JUnit suite plus focused Python and shell checks. Native packaging, desktop, Gatekeeper, installer-upgrade, and real-GPU acceptance remain separate platform gates.

## Building Locally

### Option 1: Use your system Java and Maven

If you already have a working Java + Maven environment, you can build directly:

```bash
mvn -B -DskipTests package
```

The source/API baseline and standalone shaded-JAR runtime target are Java 17. Maintained release workflows build on JDK 21, and final platform packages include a Java 21 runtime. Use JDK 21 for normal builds. Final-product acceptance consumes the supplied release artifacts and their build provenance as described in [Specialized Acceptance](SPECIALIZED_ACCEPTANCE.md#成品交接与验收).

### Option 2: Use the bundled tool cache in the repo

Maintainers often use the toolchain already stored in this repository:

- JDK: `.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home`
- Maven: `.tools/apache-maven-3.9.10/bin/mvn`

Example:

```bash
export JAVA_HOME="$PWD/.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home"
export PATH="$PWD/.tools/apache-maven-3.9.10/bin:$JAVA_HOME/bin:$PATH"
mvn -B -DskipTests package
```

Common build outputs include:

- `target/lizzie-yzy2.5.3.jar`
- `target/lizzie-yzy2.5.3-shaded.jar`

## Recommended Local Checks

Before you open a PR, at minimum run:

```bash
python3 scripts/check_markdown_links.py
git diff --check
```

If you changed Java code, run another build:

```bash
mvn -B -DskipTests package
```

If you changed packaging, engine paths, first-launch behavior, or the Fox fetch flow, do the relevant manual verification too.

Desktop smoke tests include board editing and SGF import after engine startup failure.
On a Windows test desktop whose Chinese IME is in composition mode, the ASCII
physical-key search probe can be run with `LIZZIE_TEST_ASCII_IME=shift`. This
explicit opt-in toggles Shift once per probe after focusing the search field;
only use it when Shift switches that IME to ASCII. It does not change production
input handling and is not a test of Chinese composition. Leave it unset on
ordinary ASCII-input desktops and hosted CI.

### AI commentary correctness

`TeacherEvidence` freezes the board, KataGo candidates, recorded continuation, and optional
knowledge before an API request. `TeacherBoardContext` uses a detached history to replay only
valid line prefixes with the existing capture, suicide, and simple-ko rules. It does not prove
superko legality, best replies, life and death, or forced play. Point losses and winrate losses
have separate units; an unevaluated recorded move has an unknown loss.

Range and whole-game requests select at most 40 positions and keep their complete evidence
within a 200,000-character initial message budget. If needed, lower-priority positions are
omitted while both ends remain; the selected evidence is shared by the prompt, verifier, and
follow-up. The transport's 250,000-character cap leaves room for ordinary follow-ups and one
correction, but arbitrarily large questions or replies can still exceed that cap.

`GroundedCommentaryClient` checks a completed reply against that same evidence and permits
at most one correction request for unsupported coordinates or impossible percentages. It
does not expose rejected drafts. Interrupted streams fail without a correction retry. Numeric
consistency and overly certain wording remain nonblocking notes; these checks do not establish
the truth of every natural-language claim.

Commentary transport has a five-minute deadline per `stream()` call, covering header wait,
body reads, and the single Chat Completions to Responses fallback allowed on HTTP 404/405.
After headers, 60 seconds without received body bytes fails the request. Partial lines and
SSE heartbeat bytes refresh that idle timer, but cannot extend the total deadline. Java's
[`ofInputStream()` handler](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html#ofInputStream())
can return before the body is complete, so the request timeout alone is insufficient.

Each call also limits received body data to 8 MiB, each line to 65,536 UTF-16 code units,
each SSE event envelope to 262,144 units (all nonempty fields/comments plus one normalized
separator per line, reset by a blank line), and accumulated explanation text to 131,072 units.
Limits are checked before unbounded line growth or publishing excess text. HTTP error bodies
use the same deadlines and retain only the existing 4,096-byte diagnostic excerpt, with secrets
redacted. Terminal-event validation, endpoint fallback rules and grounding remain unchanged.

Timeouts and size overruns close the response and fail without an automatic transport retry or
grounded correction; they do not mark the user cancellation token. Explicit cancellation also
aborts a pending header request. Every exit removes that call's deadline task and resource
ownership, so a manual retry or the existing single grounding correction gets a fresh budget.
The correction is a separate call (at most two five-minute calls for one grounded explanation).
Only a completed, verified explanation reaches the dialog's SGF/follow-up persistence path.
These limits cover commentary responses; model-list requests retain their separate behavior.

`TeacherLlmStreamLimitsTest` uses local HTTP servers with shortened limits to cover idle and
absolute timeouts, error bodies, both endpoints, Unicode, exact size boundaries, cancellation,
replacement and grounding. `TeacherResponseBudgetTest` controls future/body handoffs to verify
late-response cleanup and completion-versus-deadline races without relying on network timing.

The required `desktop` group runs every `TeacherDialogRequestOwnershipTest` case against a
local HTTP/SSE server through the real API-key client and Swing dialog. It covers original-node
SGF storage after cursor navigation, frozen follow-up context, correction, cancellation, game
and recorded-line edits, settings-dialog reentrancy, and callbacks already queued on the EDT.
The gate rejects missing or skipped cases. Screenshots are retained under
`target/desktop-smoke/probes/teacher/`; no paid model or real GPU is used by these tests.

## Repository Map

### Code directories

- `src/main/java/featurecat/lizzie/gui`
  - main UI, dialogs, windows, interaction logic
- `src/main/java/featurecat/lizzie/analysis`
  - engine integration, analysis flow, remote connections, Fox fetch related logic
  - includes files such as `GetFoxRequest.java` for Fox request handling
- `src/main/java/featurecat/lizzie/rules`
  - board state, move logic, SGF / GIB parsing, game data structures
- `src/main/java/featurecat/lizzie/util`
  - utility helpers
- `src/main/java/featurecat/lizzie/theme`
  - theme-related logic

### Resource directories

- `src/main/resources/l10n`
  - localization files such as `DisplayStrings_zh_CN.properties`
- `src/main/resources/assets`
  - bundled assets, helper resources, embedded tools
- `theme/`
  - theme asset files

### Packaging and release directories

- `scripts/`
  - packaging and helper scripts
- `runtime/`
  - bundled runtime content used for releases
- `engines/`
  - bundled engine files
- `weights/`
  - default weight files
- `dist/`
  - release output related directory

### Documentation and repo config

- `docs/`
  - installation, troubleshooting, maintenance, release, and development docs
- `.github/`
  - CI, issue templates, PR template, release-note config
- `assets/`
  - visual assets for the GitHub project page

## Where To Start For Common Changes

### 1. Fox sync or Fox nickname workflow changes

Start with:

- `src/main/java/featurecat/lizzie/analysis/`
- `src/main/java/featurecat/lizzie/gui/`
- `src/main/resources/l10n/`

Also verify:

- the UI still says `Fox nickname`
- README and install docs do not fall back to old Fox nickname wording
- you perform at least one real Fox fetch check

### 2. UI wording, localization, and menu entries

Start with:

- `src/main/resources/l10n/DisplayStrings*.properties`
- `src/main/java/featurecat/lizzie/gui/`

Also check:

- terminology stays consistent across Chinese and English
- `with-katago`, `nvidia`, and `without.engine` spelling remains unchanged
- user-visible changes are reflected in README or install docs if needed

### 3. Packaging, bundled engine, or release asset changes

Start with:

- `scripts/prepare_bundled_runtime.sh`
- `scripts/prepare_bundled_katago.sh`
- `scripts/package_release.sh`
- `scripts/package_macos_dmg.sh`
- [Release Checklist](RELEASE_CHECKLIST.md)

These changes usually also require updates to:

- `README.md`
- `docs/PACKAGES.md`
- `docs/TESTED_PLATFORMS.md`
- GitHub release notes

## Packaging Script Roles

- `scripts/prepare_bundled_runtime.sh`
  - prepares bundled runtime assets
- `scripts/prepare_bundled_katago.sh`
  - prepares bundled KataGo plus default weight
- `scripts/package_release.sh`
  - builds the three Linux release archives
- `scripts/package_macos_dmg.sh`
  - builds macOS `.dmg` packages
- `scripts/validate_release_assets.sh`
  - checks that `dist/release/` only contains the public-facing main assets
- `scripts/check_markdown_links.py`
  - validates local markdown links

## Final Pre-PR Checklist

- Did you affect the main user flow: install, launch, Fox nickname fetch, and analysis?
- Did any wording drift back to old Fox nickname terminology?
- Do package names, README guidance, and docs still agree?
- If packaging changed, did you update the related docs and verification records?
- If the UI changed, should you attach a screenshot?

## Recommended Reading

- [Contributing Guide](../CONTRIBUTING.md)
- [Maintenance Notes](MAINTENANCE_EN.md)
- [Release Checklist](RELEASE_CHECKLIST.md)
- [Package Overview](PACKAGES_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
