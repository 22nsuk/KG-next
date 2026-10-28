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
Manifest failure preserves in-memory metadata and leaves the complete SGF for
manual recovery. A missing or failed optional preview does not undo a committed
record; its obsolete preview is removed. Deletion commits the manifest before
cleanup, so cleanup failure leaves an orphan rather than reviving the record.

Startup recovery uses the same queue for reading/promoting the periodic slot and
committing its metadata. SGF validation remains on the event thread. Autosave is
gated until startup selection has completed, and a failed recovery retains the
source slot for retry. Engine preload follows the startup completion future.

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
- `PlayedGameSaveSafetyTest` / `ContributeSaveTest`: directory/reservation work
  stays off the event thread, unique played-game files, contribution batches
  larger than the queue, version-aware publication and failed-save retry.
- `LizzieShutdownTest`: repeated close requests share the final save, configuration
  and resource cleanup wait for completion, and failure leaves shutdown retryable.
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
