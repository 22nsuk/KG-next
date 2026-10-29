# SGF save coordinator

The four Save As modes use the same chooser, owned by the main window. Existing
extension normalization and overwrite cancellation are retained. The filename
field uses the normal chooser behavior; no hidden frame or delayed focus thread
is created. Saving over the current original file uses the same write pipeline.
Batch analysis, single-file automatic analysis, played games, contribution games,
periodic/exit saves and saved-list edits also use that worker.

## Snapshot and publication

The event thread freezes node relationships, the selected node and game metadata
under the original history's structural lock, which also guards engine-game move
commits on reader threads. It releases that lock before copying node payloads or
serializing SGF, avoiding a nested history-to-payload lock order. A subsequent
engine move cannot appear halfway through the captured tree. Each node is copied
under the short lock also used
by primary, secondary and whole-game payload commits. Engine publication can
continue after each node copy. No engine lock is held while serializing the
whole tree or writing to disk. The resulting immutable SGF string is the only
board content given to the background writer.

Current-branch export follows the selected node's ancestors, then its main
continuation. It retains setup nodes, passes and game metadata without clearing,
replaying, navigating or pruning the live tree. Other export modes retain every
variation. Temporary raw/comment flags are restored even when serialization
fails.

A single worker writes accepted requests in order (one active request and at most
16 waiting requests). Each request includes event-thread preparation, worker I/O
and event-thread publication. The next request cannot prepare its metadata until
the previous publication finishes. Rejection and write failure complete the
request exceptionally and do not stop subsequent requests.

SGF replacement flushes a temporary UTF-8 file in the destination directory,
then atomically replaces the destination. There is deliberately no destructive fallback on a
filesystem that cannot perform atomic replacement. A failed save reports the
existing save error and retains the original file.

Successful completion updates the current filename and recent directory only
if the original board, history and root still own the application. Continued
editing does not alter an already captured save. An export does not rename the
current document. Engine pause/resume is confined to the chooser/capture phase
and never resumes an engine that was replaced while the dialog was open.

Shutdown defers through completion futures, including event-thread publication,
before persisting configuration and exiting. Final autosave runs once; the
continuation drains any later accepted writes without starting another final
autosave. A failed write cancels shutdown and leaves it retryable. No shutdown
continuation waits synchronously for disk work on the event thread.

Shutdown and delayed startup recovery compare the game's explicit edit state,
not an SGF export. The state includes the original board/history/cursor, tree
structure, setup stones, game information, node properties and exact authored
comments. Normal engine visits, candidates, ownership, provenance and generated
analysis comments may continue changing without being mistaken for a user edit.
Explicit primary/secondary invalidation and information-clear actions advance a
live node's analysis-edit revision. A subsequent engine response cannot erase
that action merely by making the result valid again. These revisions and comment
ownership are runtime state; they do not change the SGF format or restore live
permissions from imported analysis.

Live authored comment writes and generated comment formatting share the node's
payload monitor. A generated comment supplies its formatter to that owner, so
reading the authored origin, formatting the displayed text and publishing both
cannot straddle an editor commit. Reapplying the unchanged displayed comment is
a no-op and retains its generated origin. This also protects a pending save when
the user commits a comment while a whole-game response is generating its text.

If an edit arrives after the final autosave capture, shutdown is cancelled and
the current game stays open. A fresh close saves that retained state and can
then exit. An ordinary analysis response alone does not require another close or
another final autosave. Capturing edit state follows the save snapshot's lock
ordering: freeze topology under the history monitor, release it, then read each
node's small state under its payload monitor.

## Automatic records and batches

The requesting event captures SGF, cursor metadata, name, destination and a
detached preview image. Directory creation, unused-ID lookup/reservation, atomic
SGF and manifest replacement, preview encoding/replacement and file cleanup run
on the worker. A new saved record cannot claim an orphaned SGF/preview or a
dangling link. Failed played-game saves remove only their own empty reservation.

Saved-list edits and autosaves share one manifest. Their preparation reads the
latest published metadata when each request reaches the front of the queue;
capturing the entire manifest at click time would lose intervening renames,
deletions or additions. Disk success publishes the detached manifest on the
event thread. The clicked list button stays disabled while its operation is
pending, and only the original, still-open panel is refreshed after success.

SGF, manifest and preview are separate atomic replacements, not a multi-file
crash transaction. SGF failure preserves the previous metadata and preview.
Manifest failure preserves in-memory metadata and leaves the complete SGF.
Automatic slots additionally retain the recovery information described below;
ordinary saved-list records still require manual recovery after a manifest
failure. A missing or failed optional preview does not undo a committed record;
its obsolete preview is removed where possible. Deletion commits the manifest
before cleanup, so cleanup failure leaves an orphan rather than reviving the record.

Startup recovery uses the same queue for reading/promoting the periodic slot and
committing its metadata. SGF validation remains on the event thread. Autosave is
gated until startup selection has completed, and a failed recovery retains the
source slot for retry. Engine preload follows the startup completion future.

### Automatic save interruption recovery

Forced-process-stop tests exposed two overwrite sequences: a first periodic SGF
written before its manifest marker was ignored on restart and overwritten by the
next autosave of the older resumed game; an exit SGF written before clearing an
older periodic marker was replaced by that older periodic record during recovery.

Before replacing either automatic slot, the shared worker atomically writes
`save/automatic-save-intent.json`. Version 1 records only the slot, intended UTF-8
SGF SHA-256, time, move number and cursor path. The manifest and preview work
follow the existing order. The intent is removed after completion. It contains
no SGF copy or full manifest snapshot and cannot select an arbitrary file path.

Startup and failed-save retry settle this record before legacy periodic-slot
promotion. A matching SGF is validated on the EDT; its slot metadata is merged
into the latest manifest without reverting unrelated saved-list edits. An exit
intent clears the older periodic marker. A nonmatching/absent SGF means the
intended content is not present; the old files are left in place. Malformed or
unreadable evidence, metadata failure and cleanup failure retain recovery state
and suspend automatic writes. A validated intended slot remains the resume
choice even if its metadata repair or subsequent promotion fails. Previews are
optional and may be discarded after repair.

If unreadable evidence caused startup to display an older fallback, retry must
not overwrite the recovered record with a previously captured fallback. The
original configuration and explicit-edit snapshot identify that case. Recovery
reloads only an unchanged fallback and retires every already captured fallback
request, including concurrent ones; a fresh request captures the recovered game.
User edits and newly opened games retain their normal ownership. Failure to
capture that protective state does not enable automatic saving.

No migration is needed when the intent is absent. A background existence check
keeps legacy startup from waiting behind unrelated queued saves; operations
which can repair files still use the shared save queue. This evidence does not
identify the age of orphaned files created by older versions. An older KG-next
version ignores a pending intent and does not provide this recovery guarantee.
The tests cover abrupt JVM termination between replacements, not power loss,
filesystem crash durability, or a transaction across all record files.

Batch analysis retains one frozen SGF through a failed-save retry and advances
only after disk success. Duplicate starts/retries during a pending write are
ignored; stop, game replacement or frame replacement prevents a late completion
from advancing the current batch. Contribution batches capture the games and
restore the watched position before I/O, then submit one write at a time so a
large batch cannot exhaust the bounded queue. A completed contribution game is
marked saved only if the written version still matches it.

Swing callers use completion futures. The legacy boolean saved-game entry points
wait for actual completion only when called off the event thread; they reject
EDT calls rather than returning a misleading queued-as-success value. Background
waiters retain interruption status after receiving the actual result.

## Validation

- `AtomicSgfFileWriterTest`: complete UTF-8 replacement, Chinese paths, uppercase
  extensions, locked/rejected replacement, unsupported atomic moves, missing
  parent directories and temporary-file cleanup.
- `SgfSaveQueueTest`: blocked disk I/O leaves the event thread responsive,
  snapshot-only worker input, retired-board completion, ordered repeated saves,
  fresh-metadata preparation after publication, bounded queue rejection and
  recovery after preparation/write failures.
- `BatchAnalysisSaveTest` / `QuickBatchFlowTest`: frozen batch retry, no early
  progress, stop during a queued save and stale completion isolation.
- `AutomaticSaveSafetyTest` / `AutomaticSaveRecoveryTest`: record and manifest
  failure, ordered saved-list edits, frozen captures, optional previews and
  recovery-slot preservation.
- `AutomaticSaveInterruptionTest` / `AutomaticSaveIntentTest`: abrupt child-JVM
  termination at actual replacement boundaries, both overwrite regressions,
  metadata/preview state, restart, unreadable evidence, failure/retry, concurrent
  stale captures, authored edits and configuration ownership. Ordinary saved-list
  orphan/cursor behavior is characterized without claiming automatic repair.
- `PlayedGameSaveSafetyTest` / `ContributeSaveTest`: directory/reservation work
  stays off the event thread, unique played-game files, contribution batches
  larger than the queue, version-aware publication and failed-save retry.
- `LizzieShutdownTest`: repeated close requests share the final save, configuration
  and resource cleanup wait for completion, and failure leaves shutdown retryable.
- `ShutdownSaveRaceDesktopTest`: the real application retains edits and explicit
  invalidation received during the final write, including invalidation followed
  by a fresh engine result. Primary/secondary updates at root and move positions,
  including replacement of an already-invalid result, permit normal shutdown.
  An editor commit blocked behind a generated comment retains its text and
  cancels shutdown; a fresh close persists that exact edited record.
- `GameEditSnapshotTest`: authored state and generated analysis comments have
  separate ownership; direct comment edits, topology, metadata and explicit
  analysis-edit revisions are retained without copying analysis payloads.
- `SavedGameSaveDesktopTest`: the actual Korean saved-list new-record action
  remains responsive while the shared worker is blocked, then publishes the SGF,
  preview and manifest and refreshes the list after release.
- `SGFSaveSnapshotTest`: selected branches, setup/pass round trips, mode/flag
  restoration, continued edits, a deliberately interrupted analysis commit, and
  concurrent engine moves during both structure capture and payload copying.
- `OfflineBoardAcceptanceTest`: existing cancellation, overwrite protection and
  engine replacement checks, plus all four successful chooser flows, actual
  main-window ownership, Chinese filenames and preservation of the live tree.

Current execution results belong to the PR's CI run. Windows/Linux desktop
acceptance and native filename typing are separate from headless coverage;
programmatic chooser approval is not evidence of real keyboard input.

The first Linux SGF/real-CPU acceptance run on 2026-09-23 passed the real engine
case, but exposed a stale test dependency: `SgfUiAcceptanceIT` still waited for
the removed custom save dialog's static `LizzieFrame.text`. Its filename lookup
now locates the current chooser's visible, enabled, editable preset filename,
and rejects missing or ambiguous candidates. Robot clicks, clipboard typing,
Enter approval, overwrite cancellation and semantic round-trip assertions are
unchanged. `SgfUiFilenameFieldTest` checks the lookup without a desktop; the
full Linux keyboard flow must still pass separately. No production behavior was
changed to accommodate the probe.

No throughput improvement is claimed. Disk I/O has moved off the event thread;
tree capture and serialization remain on it to preserve the existing serializer's
board, rules and formatting context.
