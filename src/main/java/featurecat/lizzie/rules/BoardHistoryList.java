package featurecat.lizzie.rules;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;

/** Linked list data structure to store board history */
public class BoardHistoryList {
  public enum SessionRulesKind {
    UNSPECIFIED,
    VALID,
    INVALID
  }

  public enum SessionRulesSource {
    NONE,
    EXTERNAL_IMPORT,
    MANUAL_SELECTION
  }

  /** Immutable rules target adopted by this history and its revision. */
  public static final class SessionRulesTarget {
    private final SessionRulesKind kind;
    private final String rawDeclaration;
    private final KataGoRules parsedRules;
    private final SessionRulesSource source;
    private final long revision;

    private SessionRulesTarget(
        SessionRulesKind kind,
        String rawDeclaration,
        KataGoRules parsedRules,
        SessionRulesSource source,
        long revision) {
      this.kind = Objects.requireNonNull(kind, "kind");
      this.rawDeclaration = rawDeclaration;
      this.parsedRules = parsedRules;
      this.source = Objects.requireNonNull(source, "source");
      this.revision = revision;
    }

    public SessionRulesKind kind() {
      return kind;
    }

    public String rawDeclaration() {
      return rawDeclaration;
    }

    public Optional<KataGoRules> parsedRules() {
      return Optional.ofNullable(parsedRules);
    }

    public SessionRulesSource source() {
      return source;
    }

    public long revision() {
      return revision;
    }
  }

  /** Opaque claim for one manual rules selection against this history. */
  public static final class ManualRulesIntent {
    private final SessionRulesTarget previousTarget;
    private final long generation;
    private final BooleanSupplier selectionIsCurrent;

    private ManualRulesIntent(
        SessionRulesTarget previousTarget, long generation, BooleanSupplier selectionIsCurrent) {
      this.previousTarget = previousTarget;
      this.generation = generation;
      this.selectionIsCurrent = selectionIsCurrent;
    }

    public SessionRulesTarget previousTarget() {
      return previousTarget;
    }
  }

  private volatile GameInfo gameInfo;
  private BoardHistoryNode head;
  private SessionRulesTarget sessionRulesTarget;
  private SessionRulesTarget analysisOverrideTarget;
  private Object analysisOverridePrimary;
  private Object analysisOverrideMirror;
  private long analysisOverridePrimaryGeneration = -1L;
  private long manualRulesIntentGeneration;
  private volatile ManualRulesIntent pendingManualRulesIntent;

  /**
   * Initialize a new board history list, whose first node is data
   *
   * @param data the data to be stored for the first entry
   */
  public BoardHistoryList(BoardData data) {
    head = new BoardHistoryNode(data);
    gameInfo = new GameInfo();
    sessionRulesTarget =
        new SessionRulesTarget(
            SessionRulesKind.UNSPECIFIED, null, null, SessionRulesSource.NONE, 0L);
  }

  /** Atomically returns the immutable rules target currently adopted by this history. */
  public synchronized SessionRulesTarget captureSessionRules() {
    return sessionRulesTarget;
  }

  public synchronized ManualRulesIntent beginManualRulesIntent() {
    return beginManualRulesIntent(() -> true);
  }

  /** The admission predicate must read only nonblocking selection snapshots. */
  public synchronized ManualRulesIntent beginManualRulesIntent(BooleanSupplier selectionIsCurrent) {
    pendingManualRulesIntent =
        new ManualRulesIntent(sessionRulesTarget, ++manualRulesIntentGeneration,
            Objects.requireNonNull(selectionIsCurrent, "selectionIsCurrent"));
    return pendingManualRulesIntent;
  }

  public boolean hasPendingManualRulesIntent() {
    ManualRulesIntent pending = pendingManualRulesIntent;
    return pending != null && pending.selectionIsCurrent.getAsBoolean();
  }

  public boolean ownsPendingManualRulesIntent(ManualRulesIntent intent) {
    return intent != null && pendingManualRulesIntent == intent;
  }

  public synchronized void finishManualRulesIntent(ManualRulesIntent intent) {
    if (pendingManualRulesIntent == intent) pendingManualRulesIntent = null;
  }

  public synchronized boolean isLatestManualRulesIntent(ManualRulesIntent intent) {
    return intent != null && intent.generation == manualRulesIntentGeneration;
  }

  /** Rebinds an internally restored history to the exact pre-restore session target. */
  public synchronized void restoreSessionRulesTarget(SessionRulesTarget target) {
    sessionRulesTarget = Objects.requireNonNull(target, "target");
    manualRulesIntentGeneration++;
    pendingManualRulesIntent = null;
    clearAnalysisOverride();
  }

  public synchronized void authorizeAnalysisWithCurrentRules(
      SessionRulesTarget target, Object primary, long primaryGeneration, Object mirror) {
    if (target == null
        || target != sessionRulesTarget
        || primary == null
        || primaryGeneration < 0L) {
      throw new IllegalArgumentException("Analysis override does not match current session rules");
    }
    analysisOverrideTarget = target;
    analysisOverridePrimary = primary;
    analysisOverridePrimaryGeneration = primaryGeneration;
    analysisOverrideMirror = mirror;
  }

  public synchronized boolean permitsAnalysisWithCurrentRules(
      SessionRulesTarget target, Object primary, long primaryGeneration, Object mirror) {
    return target != null
        && target == sessionRulesTarget
        && analysisOverrideTarget == target
        && analysisOverridePrimary == primary
        && analysisOverridePrimaryGeneration == primaryGeneration
        && analysisOverrideMirror == mirror;
  }

  /** Retires any explicit continue decision for the captured target without changing the target. */
  public synchronized void revokeAnalysisOverride(SessionRulesTarget target) {
    if (target != null && target == sessionRulesTarget && analysisOverrideTarget == target) {
      clearAnalysisOverride();
    }
  }

  /**
   * Publishes the root SGF RU declaration as this history's external target.
   *
   * <p>A null declaration means RU is missing and is distinct from an invalid or blank declaration.
   * Every invocation publishes a newer revision, including missing and invalid declarations.
   */
  public synchronized SessionRulesTarget publishExternalRules(String rawDeclaration) {
    manualRulesIntentGeneration++;
    pendingManualRulesIntent = null;
    clearAnalysisOverride();
    long revision = sessionRulesTarget.revision() + 1L;
    if (rawDeclaration == null) {
      sessionRulesTarget =
          new SessionRulesTarget(
              SessionRulesKind.UNSPECIFIED,
              null,
              null,
              SessionRulesSource.EXTERNAL_IMPORT,
              revision);
    } else {
      Optional<KataGoRules> parsed = parseDeclaredRules(rawDeclaration);
      if (parsed.isPresent()) {
        sessionRulesTarget =
            new SessionRulesTarget(
                SessionRulesKind.VALID,
                rawDeclaration,
                parsed.get(),
                SessionRulesSource.EXTERNAL_IMPORT,
                revision);
      } else {
        sessionRulesTarget =
            new SessionRulesTarget(
                SessionRulesKind.INVALID,
                rawDeclaration,
                null,
                SessionRulesSource.EXTERNAL_IMPORT,
                revision);
      }
    }
    return sessionRulesTarget;
  }

  static Optional<KataGoRules> parseDeclaredRules(String declaration) {
    return KataGoRules.parse(declaration).filter(KataGoRules::hasRequiredFields);
  }

  /** Publishes a successful manual selection as a valid target at a newer revision. */
  public synchronized SessionRulesTarget publishManualRules(KataGoRules rules) {
    manualRulesIntentGeneration++;
    pendingManualRulesIntent = null;
    return publishManualRulesUnchecked(rules);
  }

  /** Publishes a manual selection only while its history-owned intent is still latest. */
  public synchronized Optional<SessionRulesTarget> publishManualRulesIfCurrent(
      ManualRulesIntent intent, KataGoRules rules) {
    if (intent == null
        || intent.generation != manualRulesIntentGeneration
        || sessionRulesTarget != intent.previousTarget) {
      return Optional.empty();
    }
    SessionRulesTarget published = publishManualRulesUnchecked(rules);
    finishManualRulesIntent(intent);
    return Optional.of(published);
  }

  private SessionRulesTarget publishManualRulesUnchecked(KataGoRules rules) {
    clearAnalysisOverride();
    Objects.requireNonNull(rules, "rules");
    if (!rules.hasRequiredFields()) {
      throw new IllegalArgumentException("Manual session rules must contain all required fields");
    }
    sessionRulesTarget =
        new SessionRulesTarget(
            SessionRulesKind.VALID,
            null,
            rules,
            SessionRulesSource.MANUAL_SELECTION,
            sessionRulesTarget.revision() + 1L);
    return sessionRulesTarget;
  }

  private void clearAnalysisOverride() {
    analysisOverrideTarget = null;
    analysisOverridePrimary = null;
    analysisOverridePrimaryGeneration = -1L;
    analysisOverrideMirror = null;
  }

  public GameInfo getGameInfo() {
    return gameInfo;
  }

  public synchronized void setGameInfo(GameInfo gameInfo) {
    this.gameInfo = gameInfo;
  }

  public void addNodeProperty(String key, String value) {
    synchronized (this) {
      this.getData().addProperty(key, value);
      if ("MN".equals(key)) {
        moveNumber(Integer.parseInt(value));
      }
    }
  }

  public void moveNumber(int moveNumber) {
    synchronized (this) {
      BoardData data = this.getData();
      if (data.isMoveNode()) {
        int[] moveNumberList = this.getMoveNumberList();
        moveNumberList[getIndex(data.lastMove.get()[0], data.lastMove.get()[1])] = moveNumber;
        Optional<BoardHistoryNode> node = this.getCurrentHistoryNode().previous();
        while (node.isPresent() && node.get().numberOfChildren() <= 1) {
          BoardData nodeData = node.get().getData();
          if (nodeData.isMoveNode() && nodeData.moveNumber >= moveNumber) {
            moveNumber = (moveNumber > 1) ? moveNumber - 1 : 0;
            moveNumberList[getIndex(nodeData.lastMove.get()[0], nodeData.lastMove.get()[1])] =
                moveNumber;
          }
          node = node.get().previous();
        }
      }
    }
  }

  public void toBranchTop() {
    BoardHistoryNode start = head;
    while (start.previous().isPresent()) {
      BoardHistoryNode pre = start.previous().get();
      if (pre.next(true).isPresent() && pre.next(true).get() != start) {
        previous();
        break;
      }
      previous();
      start = pre;
    }
  }

  public synchronized BoardHistoryList shallowCopy() {
    BoardHistoryList copy = new BoardHistoryList(null);
    copy.head = head;
    copy.gameInfo = gameInfo;
    copy.sessionRulesTarget = sessionRulesTarget;
    copy.analysisOverrideTarget = analysisOverrideTarget;
    copy.analysisOverridePrimary = analysisOverridePrimary;
    copy.analysisOverrideMirror = analysisOverrideMirror;
    copy.analysisOverridePrimaryGeneration = analysisOverridePrimaryGeneration;
    return copy;
  }

  private Optional<KataGoRules> rulesForReplay() {
    SessionRulesTarget target = captureSessionRules();
    // Detached and unsynchronized imports can retain RU without publishing a session target.
    Optional<KataGoRules> declared = target.source() == SessionRulesSource.NONE
        ? parseDeclaredRules(getStart().getData().getProperty("RU")) : target.parsedRules();
    // An unspecified target leaves the primary engine's current rules in force during restore.
    if (declared.isPresent()) return declared;
    var engine = Lizzie.leelaz;
    return Optional.ofNullable(engine == null ? null : engine.currentConfirmedRules());
  }

  boolean allowsSuicideForReplay() {
    return allowsSuicideForReplay(rulesForReplay());
  }

  private static boolean allowsSuicideForReplay(Optional<KataGoRules> rules) {
    var engine = Lizzie.leelaz;
    return rules.map(rule -> rule.bool(KataGoRules.SUICIDE))
        .orElse(engine != null && engine.canSuicidal);
  }

  /** Walk the selected variation back to the setup declaration or move that owns this stone. */
  BoardHistoryNode setupStoneOwner(int x, int y) {
    int index = getIndex(x, y);
    Stone stone = head.getData().stones[index];
    int[] point = {x, y};
    if (stone != Stone.BLACK && stone != Stone.WHITE) return null;
    for (BoardHistoryNode node = head; node != null; node = node.previous().orElse(null)) {
      BoardData data = node.getData();
      if (data.stones[index] != stone) return null;
      BoardData parent = node.previous().map(BoardHistoryNode::getData).orElse(null);
      if (data.isSnapshotNode() && SnapshotSetup.points(data, parent).get(index)) return node;
      if (data.isMoveNode() && data.isSameCoord(point)) return null;
    }
    return null;
  }

  private record SetupEditNode(BoardHistoryNode source, BoardHistoryNode parent, boolean affected) {}

  /** Replays a setup edit on a private tree; an illegal continuation rejects the whole edit. */
  BoardHistoryList editSetupStone(
      BoardHistoryNode owner, int x, int y, Stone replacement, TrialNumbering trialNumbering) {
    BoardHistoryNode root = getStart();
    if (!owner.getData().isSnapshotNode()
        || x < 0 || y < 0
        || x >= root.getData().zobrist.width() || y >= root.getData().zobrist.height()
        || (replacement != Stone.EMPTY && replacement != Stone.BLACK && replacement != Stone.WHITE)) {
      return null;
    }
    Optional<KataGoRules> rules = rulesForReplay();
    boolean canSuicidal = allowsSuicideForReplay(rules);
    SetupReplayHistory replayHistory = new SetupReplayHistory(rules.orElse(null));
    if (trialNumbering != null && !trialNumbering.appliesTo(root)) trialNumbering = null;

    BoardHistoryList rebuilt = new BoardHistoryList(null);
    rebuilt.gameInfo = gameInfo;
    rebuilt.sessionRulesTarget = sessionRulesTarget;
    BoardHistoryNode copiedOwner = null;
    BoardHistoryNode selected = null;
    ArrayDeque<SetupEditNode> pending = new ArrayDeque<>();
    pending.add(new SetupEditNode(root, null, root == owner));
    while (!pending.isEmpty()) {
      SetupEditNode entry = pending.removeFirst();
      BoardHistoryNode source = entry.source();
      BoardHistoryNode parent = entry.parent();
      BoardData oldParent = source.previous().map(BoardHistoryNode::getData).orElse(null);
      BoardData newParent = parent == null ? null : parent.getData();
      BoardData original = source.getData();
      BoardData data = original.clone();
      BitSet setup = null;
      if (entry.affected() && original.isSnapshotNode()) {
        setup = SnapshotSetup.points(original, oldParent);
        if (source == owner) {
          int index = data.zobrist.index(x, y);
          data.replaceSetupStone(index, replacement);
          if (oldParent != null && replacement == oldParent.stones[index]) {
            data.inheritMoveNumber(oldParent, index);
          }
          // Removing a setup on an already-empty parent also removes its declaration.
          setup.set(index, replacement != Stone.EMPTY
              || (oldParent != null && oldParent.stones[index] != Stone.EMPTY));
        } else {
          data.stones = newParent.stones.clone();
          data.zobrist = newParent.zobrist.clone();
          if (data.hidesInheritedMoveNumbers) {
            data.clearInheritedMoveNumbers();
          } else {
            System.arraycopy(newParent.moveNumberList, 0, data.moveNumberList, 0, data.stones.length);
          }
          data.blackCaptures += newParent.blackCaptures - oldParent.blackCaptures;
          data.whiteCaptures += newParent.whiteCaptures - oldParent.whiteCaptures;
          for (int index = setup.nextSetBit(0); index >= 0; index = setup.nextSetBit(index + 1)) {
            data.replaceSetupStone(index, original.stones[index]);
          }
        }
      } else if (entry.affected()
          && !replayEditedAction(source, data, oldParent, parent, canSuicidal, replayHistory)) {
        return null;
      }
      if (trialNumbering != null) {
        trialNumbering.normalizeMoveNumber(source, data,
            newParent, oldParent == null ? 0 : oldParent.moveNumber);
        if (parent != null) {
          TrialNumbering.normalizeMoveNumberGrid(
              data, newParent, original.moveNumber, index -> oldParent.moveNumberList[index]);
        }
      }
      if (data.isMoveNode()) {
        int[] move = data.lastMove.orElseThrow();
        int index = data.zobrist.index(move[0], move[1]);
        if (data.stones[index] == data.lastMoveColor
            && data.stones[index] != original.stones[index]) {
          // A formerly suicidal move can survive after the setup edit; it owns its new label.
          data.moveNumberList[index] = data.moveMNNumber >= 0 ? data.moveMNNumber : data.moveNumber;
        }
      }
      BoardHistoryNode copied = source.copyForRestore(data);
      if (setup != null) copied.updateSetupMetadata(newParent, setup);
      if (!entry.affected()) copied.materializeRestoreNodeInfo();
      if (parent != null) copied.reparentAsLastVariationOf(parent);
      if (source == owner) copiedOwner = copied;
      if (source == head) selected = copied;
      for (BoardHistoryNode child : source.getVariations()) {
        pending.addLast(new SetupEditNode(child, copied, entry.affected() || child == owner));
      }
    }
    if (copiedOwner == null
        || !replayHistory.validateContinuations(owner, copiedOwner, gameInfo.getHandicap())) {
      return null;
    }
    rebuilt.head = selected;
    // Future opens of this edited tree must replay even a mixture of Go and no-capture moves.
    rebuilt.getStart().getData().setProperty(SGFParser.RECORDED_CAPTURE_MODES, "1");
    return rebuilt;
  }

  /** An accepted self-capture can survive a rules change only with the same removed chain. */
  private static boolean sameRecordedSelfCapture(
      int move, BoardData oldParent, BoardData recorded, BoardData newParent, BoardData played) {
    if (recorded.stones[move] != Stone.EMPTY) return false;
    Stone color = played.lastMoveColor;
    for (int i = 0; i < played.stones.length; i++) {
      boolean wasRemoved = oldParent.stones[i] == color && recorded.stones[i] == Stone.EMPTY;
      boolean nowRemoved = newParent.stones[i] == color && played.stones[i] == Stone.EMPTY;
      if (wasRemoved != nowRemoved) return false;
    }
    return true;
  }

  /** Replay into the unpublished copy, without adding or removing children on either tree. */
  private static boolean replayEditedAction(
      BoardHistoryNode source, BoardData edited, BoardData oldParent, BoardHistoryNode parent,
      boolean canSuicidal, SetupReplayHistory replayHistory) {
    BoardData original = source.getData();
    BoardData newParent = parent.getData();
    if (!applyRecordedAction(edited, newParent)) return false;

    BoardData recorded = original;
    if (source.hasSetupEdits()) {
      // Separate the recorded action from any legacy in-place setup applied after it.
      recorded = original.clone();
      if (!applyRecordedAction(recorded, oldParent)) return false;
      replayHistory.recordActionBeforeSetup(source, recorded, edited);
    }
    if (!canSuicidal && edited.isMoveNode()) {
      int[] move = edited.lastMove.orElseThrow();
      int point = edited.zobrist.index(move[0], move[1]);
      if (edited.stones[point] == Stone.EMPTY
          && !sameRecordedSelfCapture(point, oldParent, recorded, newParent, edited)) return false;
    }
    for (int i = 0; i < edited.stones.length; i++) {
      if (edited.stones[i] == Stone.EMPTY) {
        edited.moveNumberList[i] = 0;
      } else if (edited.stones[i] != original.stones[i]
          || (original.stones[i] == oldParent.stones[i]
              && original.moveNumberList[i] == oldParent.moveNumberList[i]
              && newParent.moveNumberList[i] != oldParent.moveNumberList[i])) {
        // Follow changed inherited labels through actions, including branch-number resets.
        edited.inheritMoveNumber(newParent, i);
      }
      if (original.stones[i] != recorded.stones[i]) edited.replaceSetupStone(i, original.stones[i]);
    }
    return true;
  }

  private static boolean applyRecordedAction(BoardData action, BoardData parent) {
    System.arraycopy(parent.stones, 0, action.stones, 0, action.stones.length);
    action.zobrist = parent.zobrist.clone();
    action.blackCaptures = parent.blackCaptures;
    action.whiteCaptures = parent.whiteCaptures;
    if (!action.isMoveNode()) return true;
    int[] move = action.lastMove.orElseThrow();
    if (parent.stones[parent.zobrist.index(move[0], move[1])] != Stone.EMPTY) return false;
    return action.applyMoveToPosition(action.playedWithoutCaptures) != 1;
  }

  /** Numbering conventions that cannot be inferred after the trial anchor is reset to zero. */
  static final class TrialNumbering {
    private final WeakReference<BoardHistoryNode> root;
    private final int rootMoveNumber;
    // This metadata must not keep discarded live branches alive alongside the independent copy.
    private final Map<BoardHistoryNode, Integer> dummyIncrements = new WeakHashMap<>();

    TrialNumbering(BoardHistoryList history) {
      synchronized (history) {
        BoardHistoryNode start = history.getStart();
        root = new WeakReference<>(start);
        rootMoveNumber = start.getData().moveNumber;
        ArrayDeque<BoardHistoryNode> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
          BoardHistoryNode node = pending.removeFirst();
          for (BoardHistoryNode child : node.getVariations()) {
            if (child.getData().dummy) dummyIncrements.put(child,
                child.getData().moveNumber - node.getData().moveNumber);
            pending.add(child);
          }
        }
      }
    }

    boolean appliesTo(BoardHistoryNode candidateRoot) {
      return root.get() == candidateRoot;
    }

    void normalizeMoveNumber(
        BoardHistoryNode original, BoardData data, BoardData parent, int oldParentNumber) {
      if (parent == null) {
        data.moveNumber = rootMoveNumber;
        return;
      }
      int increment = data.isHistoryActionNode() ? 1 : 0;
      if (data.dummy) {
        increment = dummyIncrements.getOrDefault(original, data.moveNumber == oldParentNumber ? 0 : 1);
      }
      data.moveNumber = parent.moveNumber + increment;
    }

    static void normalizeMoveNumberGrid(
        BoardData data, BoardData parent, int originalMoveNumber, IntUnaryOperator oldParentNumberAt) {
      int moveIndex = data.isMoveNode()
          ? data.zobrist.index(data.lastMove.orElseThrow()[0], data.lastMove.get()[1]) : -1;
      for (int index = 0; index < data.moveNumberList.length; index++) {
        int number = data.moveNumberList[index];
        // Keep hidden labels and MN overrides; normalize only trial-relative own/inherited labels.
        if (number == 0) continue;
        if (index == moveIndex && (number < 0 || originalMoveNumber != data.moveNumber)) {
          data.moveNumberList[index] = data.moveMNNumber >= 0 ? data.moveMNNumber : data.moveNumber;
        } else if (number == oldParentNumberAt.applyAsInt(index)
            && data.stones[index] == parent.stones[index]) {
          data.moveNumberList[index] = parent.moveNumberList[index];
        }
      }
    }
  }

  private record RestoreStructure(BoardHistoryNode node, int parentIndex) {}

  /** Complete private restore data; arrays are materialized before the history can be published. */
  BoardHistorySnapshot copyForRestore(TrialNumbering trialNumbering) {
    BoardHistoryList copy = new BoardHistoryList(null);
    BoardHistoryNode current;
    BoardHistoryNode root;
    List<RestoreStructure> structure = new java.util.ArrayList<>();
    synchronized (this) {
      current = head;
      root = getStart();
      copy.gameInfo = gameInfo.copyForSave();
      copy.sessionRulesTarget = sessionRulesTarget;
      structure.add(new RestoreStructure(root, -1));
      for (int index = 0; index < structure.size(); index++) {
        List<BoardHistoryNode> children = structure.get(index).node().getVariations();
        for (int child = 0; child < children.size(); child++) {
          structure.add(new RestoreStructure(children.get(child), index));
        }
      }
    }
    boolean normalizeTrial = trialNumbering != null && trialNumbering.appliesTo(root);
    List<BoardHistorySnapshot.NodeSnapshot> nodes = new java.util.ArrayList<>(structure.size());
    BoardPositionSnapshot.CaptureBuffer buffer = new BoardPositionSnapshot.CaptureBuffer();
    BoardData.AnalysisStrings analysisStrings = new BoardData.AnalysisStrings();
    // Match save capture's lock order: never acquire payload locks under the history monitor,
    // and release a payload lock before acquiring the corresponding node metadata lock.
    for (RestoreStructure entry : structure) {
      BoardData original = entry.node().getData();
      BoardData metadata;
      BoardPositionSnapshot position;
      synchronized (original) {
        metadata = original.copyRestoreMetadata(analysisStrings);
        position = BoardPositionSnapshot.capture(original.stones, original.moveNumberList, buffer);
      }
      int originalMoveNumber = metadata.moveNumber;
      BoardHistoryNode node = entry.node().copyForRestore(metadata);
      if (entry.parentIndex() >= 0) {
        BoardHistoryNode parent = nodes.get(entry.parentIndex()).node();
        node.reparentAsLastVariationOf(parent);
        if (normalizeTrial) {
          trialNumbering.normalizeMoveNumber(entry.node(), metadata, parent.getData(),
              nodes.get(entry.parentIndex()).originalMoveNumber());
        }
      } else if (normalizeTrial) {
        trialNumbering.normalizeMoveNumber(entry.node(), metadata, null, 0);
      }
      nodes.add(new BoardHistorySnapshot.NodeSnapshot(
          node, position, entry.parentIndex(), originalMoveNumber));
      if (entry.node() == current) copy.head = node;
    }
    return new BoardHistorySnapshot(copy, nodes, normalizeTrial);
  }

  /** Clear history. */
  public void clear() {
    head.clear();
  }

  /**
   * Add new data after head. Overwrites any data that may have been stored after head.
   *
   * @param data the data to add
   */
  public void add(BoardData data) {
    head = head.add(new BoardHistoryNode(data));
  }

  public void addOrGoto(BoardData data) {
    addOrGoto(data, false);
  }

  public void addOrGoto(BoardData data, boolean newBranch) {
    head = head.addOrGoto(data, newBranch);
  }

  public void addOrGoto(BoardData data, boolean newBranch, boolean changeMove) {
    head = head.addOrGoto(data, newBranch, changeMove, false);
  }

  public void addOrGoto(BoardData data, boolean newBranch, boolean changeMove, boolean tsumego) {
    head = head.addOrGoto(data, newBranch, changeMove, tsumego);
  }

  Optional<BoardData> nextByMoveIdentity(BoardData data) {
    Optional<BoardHistoryNode> n = head.findDirectChildByMoveIdentity(data);
    if (n.isPresent()) {
      if (head.hasRemovedStone()) head.clearAndSyncBoard(true);
      if (Lizzie.leelaz != null) Lizzie.leelaz.clearBestMoves();
      head = n.get();
      head.placeExtraStones();
    }
    return n.map(BoardHistoryNode::getData);
  }

  /**
   * moves the pointer to the left, returns the data stored there
   *
   * @return data of previous node, Optional.empty if there is no previous node
   */
  public Optional<BoardData> previous() {
    if (!head.previous().isPresent()) return Optional.empty();
    else head = head.previous().get();
    return Optional.of(head.getData());
  }

  public void toStart() {
    while (previous().isPresent())
      ;
  }

  /**
   * moves the pointer to the right, returns the data stored there
   *
   * @return the data of next node, Optional.empty if there is no next node
   */
  public Optional<BoardData> next() {
    return next(false);
  }

  public Optional<BoardData> next(boolean includeDummay) {
    Optional<BoardHistoryNode> n = head.next(includeDummay);
    // n.ifPresent(x -> head = x);
    if (n.isPresent()) {
      if (!BoardHistoryNode.suppressGlobalMoveSideEffects() && Lizzie.leelaz != null) {
        Lizzie.leelaz.clearBestMoves();
      }
      head = n.get();
      // Lizzie.board.clearAfterMove();
    }
    return n.map(x -> x.getData());
  }

  /**
   * Moves the pointer to the variation number idx, returns the data stored there.
   *
   * @return the data of next node, Optional.empty if there is no variation with index.
   */
  public Optional<BoardData> nextVariation(int idx) {
    Optional<BoardHistoryNode> n = head.getVariation(idx);
    if (n.isPresent()) {
      if (head.hasRemovedStone()) head.clearAndSyncBoard(true);
      head = n.get();
      head.placeExtraStones();
    }
    return n.map(x -> x.getData());
  }

  Optional<BoardData> nextVariationWithoutEngineSync(int idx) {
    Optional<BoardHistoryNode> next = head.getVariation(idx);
    next.ifPresent(node -> head = node);
    return next.map(BoardHistoryNode::getData);
  }

  /**
   * Does not change the pointer position
   *
   * @return the data stored at the next index, if any, Optional.empty otherwise.
   */
  public Optional<BoardData> getNext() {
    return getNext(false);
  }

  public Optional<BoardData> getNext(boolean includeDummy) {
    return head.next(includeDummy).map(x -> x.getData());
  }

  /**
   * @return nexts for display
   */
  public List<BoardHistoryNode> getNexts() {
    return head.getVariations();
  }

  /**
   * Does not change the pointer position.
   *
   * @return the data stored at the previous index, if any, Optional.empty otherwise.
   */
  public Optional<BoardData> getPrevious() {
    return head.previous().map(p -> p.getData());
  }

  /**
   * @return the data of the current node
   */
  public BoardData getData() {
    return head.getData();
  }

  public void setStone(int[] coordinates, Stone stone) {
    if (!isValid(coordinates[0], coordinates[1])) return;
    int index = getIndex(coordinates[0], coordinates[1]);
    head.getData().stones[index] = stone;
    head.getData().zobrist.toggleStone(coordinates[0], coordinates[1], stone);
  }

  public Stone[] getStones() {
    return head.getData().stones;
  }

  public Optional<int[]> getLastMove() {
    return head.getData().lastMove;
  }

  public Optional<int[]> getNextMove() {
    return getNext().flatMap(n -> n.lastMove);
  }

  public Stone getLastMoveColor() {
    return head.getData().lastMoveColor;
  }

  public boolean isBlacksTurn() {
    return head.getData().blackToPlay;
  }

  int getIndex(int x, int y) { return getData().zobrist.index(x, y); }

  boolean isValid(int x, int y) { return getData().zobrist.isValid(x, y); }

  public Zobrist getZobrist() {
    return head.getData().zobrist.clone();
  }

  public int getMoveNumber() {
    return head.getData().moveNumber;
  }

  public int getMoveMNNumber() {
    return head.getData().moveMNNumber;
  }

  public int[] getMoveNumberList() {
    return head.getData().moveNumberList;
  }

  public BoardHistoryNode getCurrentHistoryNode() {
    return head;
  }

  public void setHead(BoardHistoryNode node) {
    head = node;
  }

  public boolean isEmptyBoard() {
    if (!head.previous().isPresent() && !head.next().isPresent()) return true;
    else return false;
  }

  public boolean noStoneBoard() {
    if (head.previous().isPresent() || Lizzie.board.hasStartStone) {
      return false;
    }
    for (Stone stone : head.getData().stones) {
      if (!stone.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  //  public BoardHistoryNode getRecentAnalyzedNode() {
  //    BoardHistoryNode node = head;
  //    if (!node.getData().bestMoves.isEmpty()) return node;
  //    while (node.next().isPresent()) {
  //      if (!node.getData().bestMoves.isEmpty()) return node;
  //      else node = node.next().get();
  //    }
  //    node = head;
  //    while (node.previous().isPresent()) {
  //      if (!node.getData().bestMoves.isEmpty()) return node;
  //      else node = node.previous().get();
  //    }
  //    return head;
  //  }
  //
  public BoardHistoryNode getFirstAnalyzedNode2() {
    BoardHistoryNode node = head;
    if (!node.getData().bestMoves2.isEmpty()) return node;
    while (node.next().isPresent()) {
      if (!node.getData().bestMoves2.isEmpty()) return node;
      else node = node.next().get();
    }
    node = head;
    while (node.previous().isPresent()) {
      if (!node.getData().bestMoves2.isEmpty()) return node;
      else node = node.previous().get();
    }
    return head;
  }

  /**
   * @param data the board position to check against superko
   * @return whether or not the given position violates the superko rule at the head's state
   */
  public boolean violatesSuperko(BoardData data) {
    BoardHistoryNode head = this.head;

    // check to see if this position has occurred before
    while (head.previous().isPresent()) {
      // if two zobrist hashes are equal, and it's the same player to coordinate, they
      // are the same
      // position
      if (data.zobrist.equals(head.getData().zobrist)
          && data.blackToPlay == head.getData().blackToPlay) return true;

      head = head.previous().get();
    }

    // no position matched this position, so it's valid
    return false;
  }

  public boolean violatesKoRule(BoardData data) {
    return violatesKoRule(head, data);
  }

  /** Test a detached candidate without moving the real history cursor. */
  public static boolean violatesKoRule(BoardHistoryNode parent, BoardData move) {
    while (parent.isRedundantPlayerSnapshot() || parent.isUnplayedPlaceholder()) {
      parent = parent.previous().orElseThrow();
    }
    BoardData last = parent.getData();
    if (!last.isMoveNode() || parent.hasSetupEdits() || last.lastMoveColor == move.lastMoveColor) {
      return false;
    }
    boolean changedTurn = (last.lastMoveColor.isBlack() ? "B" : "W")
        .equalsIgnoreCase(last.getProperty("PL"));
    return !changedTurn && parent.previous().map(node -> {
      BoardData prior = node.getData();
      return last.capturedSingleOpponentSince(prior) && move.zobrist.equals(prior.zobrist);
    }).orElse(false);
  }

  /**
   * Returns the root node
   *
   * @return root node
   */
  public BoardHistoryNode root() {
    BoardHistoryNode top = head;
    while (top.previous().isPresent()) {
      top = top.previous().get();
    }
    return top;
  }

  /**
   * Returns the length of current branch
   *
   * @return length of current branch
   */
  public int currentBranchLength() {
    return getMoveNumber() + head.getDepth();
  }

  /**
   * Returns the length of main trunk
   *
   * @return length of main trunk
   */
  public int mainTrunkLength() {
    return root().getDepth();
  }

  public BoardHistoryNode getStart() {
    BoardHistoryNode e = head;
    while (e.previous().isPresent()) {
      e = e.previous().get();
    }
    return e;
  }

  public BoardHistoryNode getEnd() {
    BoardHistoryNode e = head;
    while (e.next().isPresent()) {
      e = e.next().get();
    }
    return e;
  }

  public BoardHistoryNode getMainEnd() {
    BoardHistoryNode e = head;
    while (e.previous().isPresent()) {
      e = e.previous().get();
    }
    while (e.next().isPresent()) {
      e = e.next().get();
    }
    return e;
  }

  public BoardHistoryNode getCurOrMainEnd(boolean editMode) {
    if (editMode) return getCurrentHistoryNode();
    else return getMainEnd();
  }

  public BoardHistoryNode getMainEndWithPass() {
    BoardHistoryNode e = head;
    while (e.previous().isPresent()) {
      e = e.previous().get();
    }
    while (e.next(true).isPresent()) {
      e = e.next(true).get();
    }
    return e;
  }

  public void pass(Stone color) {
    pass(color, false, false, false);
  }

  public void pass(Stone color, boolean newBranch) {
    pass(color, newBranch, false, false);
  }

  public void pass(Stone color, boolean newBranch, boolean dummy) {
    pass(color, newBranch, dummy, false);
  }

  public void pass(Stone color, boolean newBranch, boolean dummy, boolean changeMove) {
    pass(color, newBranch, dummy, changeMove, false);
  }

  public void pass(
      Stone color, boolean newBranch, boolean dummy, boolean changeMove, boolean addLast) {
    pass(color, newBranch, dummy, changeMove, addLast, ActionMode.NORMAL, false);
  }

  void passForEngineGame(Stone color, boolean newMoveNumberInBranch) {
    pass(color, false, false, false, false, ActionMode.ENGINE_GAME, newMoveNumberInBranch);
  }

  void passFromRecord(Stone color, boolean newBranch) {
    pass(color, newBranch, false, false, false, ActionMode.RECORD, false);
  }

  private void pass(
      Stone color,
      boolean newBranch,
      boolean dummy,
      boolean changeMove,
      boolean addLast,
      ActionMode mode,
      boolean frozenNewMoveNumberInBranch) {
    synchronized (this) {
      boolean engineGamePure = mode == ActionMode.ENGINE_GAME;

      // check to see if this move is being replayed in history
      if (!engineGamePure
          && !addLast
          && this.getNext()
              .filter(next -> next.lastMoveColor == color)
              .map(BoardHistoryList::isReplayablePass)
              .orElse(false)
          && !newBranch) {
        // this is the next move in history. Just increment history so that we don't
        // erase the
        // redo's
        this.next();
        return;
      }
      BoardHistoryNode curNode = this.head;
      if (addLast) curNode = head.getLast();

      // BoardData.pass owns the copies; a pass does not modify the current position.
      Stone[] stones = curNode.getData().stones;
      Zobrist zobrist = curNode.getData().zobrist;

      int moveNumber = curNode.getData().moveNumber + 1;

      boolean restartNumbering = mode.restartsNumbering(newBranch);
      // build the new game state
      BoardData newState =
          BoardData.pass(
              stones,
              color,
              color.equals(Stone.WHITE),
              zobrist,
              moveNumber,
              curNode.getData().moveNumberList,
              curNode.getData().blackCaptures,
              curNode.getData().whiteCaptures,
              0,
              0);
      if (restartNumbering && curNode.next(true).isPresent()) newState.clearInheritedMoveNumbers();
      newState.dummy = dummy;
      newState.moveMNNumber = curNode.getData().nextMoveMNNumber(restartNumbering);

      // update history with pass
      if (addLast) {
        head.getLast().addAtLast(newState);
      } else if (engineGamePure) {
        this.head = this.head.addOrGotoForEngineGame(newState, frozenNewMoveNumberInBranch);
      } else {
        this.addOrGoto(newState, newBranch);
      }
    }
  }

  private static boolean isReplayablePass(BoardData data) {
    return data != null && data.isPassNode() && !data.dummy;
  }

  public void place(int x, int y, Stone color) {
    place(x, y, color, false);
  }

  public void place(int x, int y, Stone color, boolean newBranch) {
    place(x, y, color, newBranch, false, false);
  }

  public void place(int x, int y, Stone color, boolean newBranch, boolean changeMove) {
    place(x, y, color, newBranch, changeMove, false);
  }

  public void place(
      int x, int y, Stone color, boolean newBranch, boolean changeMove, boolean addLast) {
    place(
        x,
        y,
        color,
        newBranch,
        changeMove,
        addLast,
        Lizzie.config.noCapture,
        Lizzie.leelaz != null && Lizzie.leelaz.canSuicidal);
  }

  /** Places a move using rules frozen by an owning engine-game transaction. */
  public void place(
      int x,
      int y,
      Stone color,
      boolean newBranch,
      boolean changeMove,
      boolean addLast,
      boolean noCapture,
      boolean canSuicidal) {
    place(x, y, color, newBranch, changeMove, addLast, noCapture, canSuicidal, ActionMode.NORMAL, false);
  }

  void placeForEngineGame(
      int x,
      int y,
      Stone color,
      boolean noCapture,
      boolean canSuicidal,
      boolean newMoveNumberInBranch) {
    place(x, y, color, false, false, false, noCapture, canSuicidal, ActionMode.ENGINE_GAME, newMoveNumberInBranch);
  }

  void placeRecordedMove(
      int x, int y, Stone color, boolean newBranch, boolean noCapture, boolean canSuicidal) {
    place(x, y, color, newBranch, false, false, noCapture, canSuicidal, ActionMode.RECORD, false);
  }

  private enum ActionMode {
    NORMAL, ENGINE_GAME, RECORD;

    boolean restartsNumbering(boolean newBranch) {
      // Recorded siblings always append, while their numbering follows the import preference.
      return newBranch && (this != RECORD || BoardHistoryNode.startsBranchNumberingAtOne());
    }
  }

  private void place(
      int x,
      int y,
      Stone color,
      boolean newBranch,
      boolean changeMove,
      boolean addLast,
      boolean noCapture,
      boolean canSuicidal,
      ActionMode mode,
      boolean frozenNewMoveNumberInBranch) {
    synchronized (this) {
      boolean engineGamePure = mode == ActionMode.ENGINE_GAME;
      BoardHistoryNode curNode = this.head;
      if (addLast) curNode = head.getLast();
      if (!isValid(x, y)
          || ((mode == ActionMode.RECORD || !newBranch)
              && curNode.getData().stones[getIndex(x, y)] != Stone.EMPTY)) return;
      // Lizzie.board.modifyStart();
      double nextWinrate = -100;
      if (curNode.getData().winrate >= 0) nextWinrate = 100 - curNode.getData().winrate;

      // A replay must match both the coordinate and the requested color.
      if (!engineGamePure && !addLast) {
        Optional<int[]> nextLast =
            this.getNext().filter(next -> next.lastMoveColor == color).flatMap(n -> n.lastMove);
        if (nextLast.isPresent()
            && nextLast.get()[0] == x
            && nextLast.get()[1] == y
            && !newBranch
            && !changeMove) {
          // this is the next coordinate in history. Just increment history so that we
          // don't erase the
          // redo's
          this.next();
          //   Lizzie.board.modifyEnd();
          return;
        }
      }
      BoardData parent = curNode.getData();
      int moveNumber = parent.moveNumber + 1;
      boolean restartNumbering = mode.restartsNumbering(newBranch);
      int moveMNNumber = parent.nextMoveMNNumber(restartNumbering);
      // The factory detaches the parent buffers once. Mutate only this unpublished candidate;
      // rejected moves discard it without touching the parent or any existing variation.
      BoardData newState =
          BoardData.move(
              parent.stones,
              new int[] {x, y},
              color,
              color.equals(Stone.WHITE),
              parent.zobrist,
              moveNumber,
              parent.moveNumberList,
              parent.blackCaptures,
              parent.whiteCaptures,
              nextWinrate,
              0);
      newState.moveMNNumber = moveMNNumber;
      Stone[] stones = newState.stones;
      int[] moveNumberList = newState.moveNumberList;
      if (restartNumbering && curNode.next(true).isPresent()) {
        newState.clearInheritedMoveNumbers();
      }

      moveNumberList[getIndex(x, y)] = moveMNNumber > -1 ? moveMNNumber : moveNumber;

      int selfCaptured = newState.applyMoveToPosition(noCapture);
      for (int i = 0; i < getData().stones.length; i++) {
        if (stones[i].equals(Stone.EMPTY)) {
          moveNumberList[i] = 0;
        }
      }

      if (violatesKoRule(curNode, newState)) return;
      if (selfCaptured == 1 || (selfCaptured > 0 && !canSuicidal)) return;

      // update history with this coordinate
      if (addLast) {
        head.getLast().addAtLast(newState);
      } else if (engineGamePure) {
        this.head = this.head.addOrGotoForEngineGame(newState, frozenNewMoveNumberInBranch);
      } else {
        this.addOrGoto(newState, newBranch, changeMove);
      }
      //   Lizzie.board.modifyEnd();
      // Lizzie.frame.renderVarTree(0, 0, false, false);
    }
  }

  //  public void addStone(int x, int y, Stone color) {
  //    synchronized (this) {
  //      if (!Board.isValid(x, y) || this.getStones()[Board.getIndex(x, y)] != Stone.EMPTY) return;
  //
  //      Stone[] stones = this.getData().stones;
  //      Zobrist zobrist = this.getData().zobrist;
  //
  //      // set the stone at (x, y) to color
  //      stones[Board.getIndex(x, y)] = color;
  //      zobrist.toggleStone(x, y, color);
  //    }
  //  }

  public void addExtraStone(int x, int y, Stone color) {
    synchronized (this) {
      if (!isValid(x, y) || this.getStones()[getIndex(x, y)] != Stone.EMPTY) return;

      Stone[] stones = this.getData().stones;
      Zobrist zobrist = this.getData().zobrist;

      // set the stone at (x, y) to color
      stones[getIndex(x, y)] = color;
      zobrist.toggleStone(x, y, color);
      head.addExtraStones(x, y, color.isBlack());
    }
  }

  public void removeStone(int x, int y, Stone color) {
    synchronized (this) {
      if (!isValid(x, y) || this.getStones()[getIndex(x, y)] == Stone.EMPTY) return;

      BoardData data = this.getData();
      Stone[] stones = data.stones;
      Zobrist zobrist = data.zobrist;

      // set the stone at (x, y) to empty
      Stone oriColor = stones[getIndex(x, y)];
      stones[getIndex(x, y)] = Stone.EMPTY;
      zobrist.toggleStone(x, y, oriColor);
      data.moveNumberList[getIndex(x, y)] = 0;
      head.setRemovedStone();

      Lizzie.frame.refresh();
    }
  }

  public void flatten() {
    Stone[] stones = this.getStones();
    boolean blackToPlay = this.isBlacksTurn();
    Zobrist zobrist = this.getZobrist().clone();
    BoardHistoryList oldHistory = this;

    head =
        new BoardHistoryNode(
            BoardData.snapshot(
                stones,
                Optional.empty(),
                Stone.EMPTY,
                blackToPlay,
                zobrist,
                0,
                new int[getData().stones.length],
                0,
                0,
                0.0,
                0));
    this.setGameInfo(oldHistory.getGameInfo());
  }

  public boolean goToMoveNumber(int moveNumber, boolean withinBranch) {
    int delta = moveNumber - this.getMoveNumber();
    boolean moved = false;
    for (int i = 0; i < Math.abs(delta); i++) {
      if (withinBranch && delta < 0) {
        BoardHistoryNode currentNode = this.getCurrentHistoryNode();
        if (!currentNode.isFirstChild()) {
          break;
        }
      }
      if (!(delta > 0 ? next().isPresent() : previous().isPresent())) {
        break;
      }
      moved = true;
    }
    return moved;
  }

  public int sync(BoardHistoryList newList) {
    return getStart().syncSubtree(newList.getStart());
  }

  public String getCurrentTurnPlayerShortName() {
    // TODO Auto-generated method stub
    String name;
    if (isBlacksTurn()) {
      name = getGameInfo().getPlayerBlack();
    } else {
      name = getGameInfo().getPlayerWhite();
    }
    if (name.length() > 21) return name.substring(0, 20);
    else return name;
  }

  public void setRemovedStone() {
    head.setRemovedStone();
  }
}
