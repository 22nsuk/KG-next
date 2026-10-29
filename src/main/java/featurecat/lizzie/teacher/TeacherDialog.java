package featurecat.lizzie.teacher;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.BoardNodeKind;
import featurecat.lizzie.rules.Stone;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/** Non-modal AI commentary window backed only by existing KataGo analysis evidence. */
public final class TeacherDialog extends JDialog {
  private static TeacherDialog activeDialog;

  private final TeacherSettings settings;
  private final Supplier<BoardHistoryNode> currentNodeProvider;
  private final Supplier<BoardHistoryNode> rootNodeProvider;
  private final TeacherRequestController requests = new TeacherRequestController();
  private final Timer gameGuardTimer;

  private final TeacherDialogView view = new TeacherDialogView();
  private final JEditorPane output = view.output();
  private final JToggleButton explainNext = view.explainNext();
  private final JToggleButton explainRange = view.explainRange();
  private final JToggleButton explainWhole = view.explainWhole();
  private final JButton stop = view.stop();
  private final JButton settingsButton = view.settingsButton();
  private final JButton ask = view.ask();
  private final JCheckBox writeToSgf = view.writeToSgf();
  private final JTextField followUp = view.followUp();
  private final JSpinner rangeStart = view.rangeStart();
  private final JSpinner rangeEnd = view.rangeEnd();

  private BoardHistoryNode displayedGame;
  private CommentaryContext lastCommentary;
  private Request activeRequest;
  private long uiGeneration;
  private boolean requestRunning;
  private boolean settingsLoaded;
  private boolean settingsUsable;

  public static void show(Window owner) {
    if (activeDialog != null && activeDialog.isDisplayable()) {
      activeDialog.refreshFromBoard();
      activeDialog.setVisible(true);
      activeDialog.toFront();
      SwingUtilities.invokeLater(activeDialog::focusPrimaryControl);
      return;
    }
    activeDialog = new TeacherDialog(owner);
    activeDialog.setVisible(true);
    SwingUtilities.invokeLater(activeDialog::focusPrimaryControl);
  }

  private void focusPrimaryControl() {
    if (isDisplayable() && explainNext.isEnabled()) {
      explainNext.requestFocusInWindow();
    }
  }

  private TeacherDialog(Window owner) {
    this(
        owner,
        TeacherSettings.createDefault(),
        () ->
            Lizzie.board == null || Lizzie.board.getHistory() == null
                ? null
                : Lizzie.board.getHistory().getCurrentHistoryNode(),
        () ->
            Lizzie.board == null || Lizzie.board.getHistory() == null
                ? null
                : Lizzie.board.getHistory().getStart());
  }

  TeacherDialog(
      Window owner,
      TeacherSettings settings,
      Supplier<BoardHistoryNode> currentNodeProvider,
      Supplier<BoardHistoryNode> rootNodeProvider) {
    super(owner, TeacherStrings.get("Teacher.title", "AI commentary"), ModalityType.MODELESS);
    this.settings = settings;
    this.currentNodeProvider = currentNodeProvider;
    this.rootNodeProvider = rootNodeProvider;
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setContentPane(view);
    bindActions();
    setMinimumSize(new Dimension(760, 540));
    setSize(new Dimension(900, 680));
    setLocationRelativeTo(owner);
    getRootPane()
        .registerKeyboardAction(
            event -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

    gameGuardTimer = new Timer(200, event -> guardGameContext());
    gameGuardTimer.start();

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            uiGeneration++;
            activeRequest = null;
            requests.close();
            gameGuardTimer.stop();
            if (activeDialog == TeacherDialog.this) {
              activeDialog = null;
            }
          }
        });
    refreshFromBoard();
    setRunning(false);
    refreshSettingsStatus();
  }

  private void bindActions() {
    explainNext.addActionListener(
        event -> {
          view.selectMode(TeacherDialogView.Mode.NEXT);
          explainNextMove();
        });
    explainRange.addActionListener(
        event -> {
          view.selectMode(TeacherDialogView.Mode.RANGE);
          explainRange();
        });
    explainWhole.addActionListener(
        event -> {
          view.selectMode(TeacherDialogView.Mode.WHOLE);
          explainWholeGame();
        });
    stop.addActionListener(event -> stopRequest());
    settingsButton.addActionListener(
        event -> {
          if (TeacherSettingsDialog.show(this, settings)) {
            refreshSettingsStatus();
          }
        });
    ask.addActionListener(event -> askFollowUp());
    followUp.addActionListener(event -> askFollowUp());
  }

  private void guardGameContext() {
    BoardHistoryNode root = rootNodeProvider.get();
    if (displayedGame != root
        || (activeRequest != null && !activeRequest.context.owner.targetIsCurrent(root))
        || (lastCommentary != null && !lastCommentary.owner.targetIsCurrent(root))) {
      discardChangedContext();
    } else if (!requestRunning && lastCommentary == null) {
      BoardHistoryNode current = currentNodeProvider.get();
      view.setCurrentMove(current == null ? 0 : current.getData().moveNumber);
    }
  }

  private void discardChangedContext() {
    uiGeneration++;
    activeRequest = null;
    requests.cancel();
    lastCommentary = null;
    followUp.setText("");
    setRunning(false);
    clearOutputForEmptyState();
    refreshFromBoard();
    setStatus(
        TeacherStrings.get(
            "Teacher.status.gameChanged",
            "The game or commentary position changed. Generate a new commentary."),
        TeacherDialogView.StatusTone.WARNING);
  }

  private void refreshFromBoard() {
    BoardHistoryNode root = rootNodeProvider.get();
    if (displayedGame != root) {
      uiGeneration++;
      activeRequest = null;
      requests.cancel();
      lastCommentary = null;
      followUp.setText("");
      setRunning(false);
    }
    displayedGame = root;
    BoardHistoryNode current = currentNodeProvider.get();
    if (root == null || current == null || current.getData() == null) {
      clearOutputForEmptyState();
      view.setCurrentMove(0);
      setStatus(
          TeacherStrings.get("Teacher.status.noGame", "No game is loaded."),
          TeacherDialogView.StatusTone.WARNING);
      return;
    }
    view.setCurrentMove(current.getData().moveNumber);
    // Reopening the same dialog or moving the cursor does not replace an existing discussion.
    if (requestRunning || (lastCommentary != null && lastCommentary.owner.isCurrent(root))) {
      return;
    }
    lastCommentary = null;
    int lastMove = Math.max(1, root.getLast().getData().moveNumber);
    rangeStart.setModel(new SpinnerNumberModel(1, 1, lastMove, 1));
    rangeEnd.setModel(new SpinnerNumberModel(lastMove, 1, lastMove, 1));
    TeacherDialogStyle.styleSpinner(rangeStart);
    TeacherDialogStyle.styleSpinner(rangeEnd);
    Optional<String> saved = TeacherCommentCodec.extract(current.getData().comment);
    if (saved.isPresent()) {
      TargetSnapshot owner = TargetSnapshot.capture(root, current);
      if (owner != null) {
        // SGF commentary has no persisted evidence snapshot. Capture it only if a question is sent.
        lastCommentary = new CommentaryContext(owner, List.of(), List.of(), saved.get());
        showCommentary(saved.get());
        setStatus(
            TeacherStrings.get(
                "Teacher.status.savedLoaded", "Loaded saved commentary from this SGF node."),
            TeacherDialogView.StatusTone.SUCCESS);
        return;
      }
    }
    clearOutputForEmptyState();
    boolean hasEvidence = TeacherEvidence.position(current).isPresent();
    setStatus(
        evidenceStatus(current),
        hasEvidence
            ? TeacherDialogView.StatusTone.NEUTRAL
            : TeacherDialogView.StatusTone.WARNING);
  }

  private void clearOutputForEmptyState() {
    output.setText("<html><body></body></html>");
    view.resetEmptyTitle();
    view.showEmpty();
  }

  private void showCommentary(String commentary) {
    output.setText(markdownToHtml(commentary));
    output.setCaretPosition(0);
    view.showOutput();
  }

  private void refreshSettingsStatus() {
    settingsLoaded = false;
    settingsUsable = false;
    updateControlState();
    view.setModelStatus(
        TeacherStrings.get("Teacher.status.loadingSettings", "Loading secure settings..."));
    new SwingWorker<TeacherSettings.Snapshot, Void>() {
      @Override
      protected TeacherSettings.Snapshot doInBackground() throws Exception {
        return settings.load();
      }

      @Override
      protected void done() {
        settingsLoaded = true;
        try {
          TeacherSettings.Snapshot snapshot = get();
          settingsUsable = true;
          view.setModelStatus(
              snapshot.hasApiKey
                  ? TeacherStrings.format("Teacher.status.modelReady", "Model: {0}", snapshot.model)
                  : TeacherStrings.get(
                      "Teacher.status.needsKey", "Configure an API key before use"));
        } catch (Exception error) {
          settingsUsable = false;
          view.setModelStatus(localError(error));
        }
        updateControlState();
        SwingUtilities.invokeLater(TeacherDialog.this::focusPrimaryControl);
      }
    }.execute();
  }

  private void explainNextMove() {
    if (requestRunning) {
      return;
    }
    BoardHistoryNode current = currentNode();
    TargetSnapshot owner = TargetSnapshot.capture(rootNodeProvider.get(), current);
    if (owner == null) {
      return;
    }
    Optional<TeacherEvidence.Position> position = TeacherEvidence.current(current);
    if (position.isEmpty()) {
      needsAnalysis();
      return;
    }
    List<TeacherLlmClient.Message> context =
        TeacherPromptBuilder.forPosition(
            position.get(), TeacherStrings.locale(), settings.snapshot());
    startRequest(
        context,
        new CommentaryContext(owner, context, List.of(position.get()), ""),
        requestingStatus(),
        "");
  }

  private void explainRange() {
    if (requestRunning) {
      return;
    }
    BoardHistoryNode root = rootNode();
    TargetSnapshot owner = TargetSnapshot.capture(root, currentNodeProvider.get(), true);
    if (owner == null) {
      return;
    }
    int first = ((Number) rangeStart.getValue()).intValue();
    int last = ((Number) rangeEnd.getValue()).intValue();
    TeacherEvidence.Range evidence =
        TeacherEvidence.mainLine(root, Math.min(first, last), Math.max(first, last));
    explainRange(owner, evidence, TeacherPromptBuilder.Mode.RANGE);
  }

  private void explainWholeGame() {
    if (requestRunning) {
      return;
    }
    BoardHistoryNode root = rootNode();
    TargetSnapshot owner = TargetSnapshot.capture(root, root, true);
    if (owner == null) {
      return;
    }
    explainRange(owner, TeacherEvidence.wholeGame(root), TeacherPromptBuilder.Mode.WHOLE_GAME);
  }

  private void explainRange(
      TargetSnapshot owner, TeacherEvidence.Range evidence, TeacherPromptBuilder.Mode mode) {
    if (evidence.isEmpty()) {
      setStatus(
          TeacherStrings.get(
              "Teacher.status.rangeNeedsAnalysis",
              "No analyzed positions were found in this range."),
          TeacherDialogView.StatusTone.WARNING);
      return;
    }
    TeacherPromptBuilder.PreparedRange prepared;
    try {
      prepared =
          TeacherPromptBuilder.prepareRange(
              evidence, mode, TeacherStrings.locale(), settings.snapshot());
    } catch (IllegalArgumentException tooLarge) {
      setStatus(
          TeacherStrings.format(
              "Teacher.status.failed", "Commentary failed: {0}", localError(tooLarge)),
          TeacherDialogView.StatusTone.ERROR);
      return;
    }
    TeacherEvidence.Range selected = prepared.evidence();
    List<TeacherLlmClient.Message> context = prepared.messages();
    startRequest(
        context,
        new CommentaryContext(owner, context, selected.positions, ""),
        TeacherStrings.format(
            "Teacher.status.evidenceReady",
            "{0} key positions selected ({1} analyzed, {2} omitted). Generating commentary...",
            selected.positions.size(),
            selected.analyzedPositions,
            selected.omittedPositions),
        "");
  }

  private void askFollowUp() {
    if (requestRunning || !settingsLoaded || !settingsUsable) {
      return;
    }
    if (displayedGame != rootNodeProvider.get()
        || (lastCommentary != null && !lastCommentary.owner.isCurrent(rootNodeProvider.get()))) {
      discardChangedContext();
      return;
    }
    String question = followUp.getText().trim();
    if (question.isEmpty()) {
      return;
    }
    CommentaryContext context = lastCommentary;
    if (context == null || context.messages.isEmpty()) {
      // A loaded SGF answer keeps its original target even after the user moves the cursor.
      BoardHistoryNode target = context == null ? currentNode() : context.owner.target;
      TargetSnapshot owner =
          context == null ? TargetSnapshot.capture(rootNodeProvider.get(), target) : context.owner;
      if (owner == null) {
        return;
      }
      Optional<TeacherEvidence.Position> position = TeacherEvidence.current(target);
      if (position.isEmpty()) {
        needsAnalysis();
        return;
      }
      context =
          new CommentaryContext(
              owner,
              TeacherPromptBuilder.forPosition(
                  position.get(), TeacherStrings.locale(), settings.snapshot()),
              List.of(position.get()),
              context == null ? "" : context.answer);
    }
    if (startRequest(
        TeacherPromptBuilder.forFollowUp(
            context.messages,
            context.answer,
            question,
            TeacherStrings.locale(),
            settings.snapshot()),
        context,
        requestingStatus(),
        question)) {
      followUp.setText("");
    }
  }

  private void needsAnalysis() {
    setStatus(
        TeacherStrings.get(
            "Teacher.status.needsAnalysis",
            "This position has no KataGo candidates yet. Analyze it first."),
        TeacherDialogView.StatusTone.WARNING);
  }

  private String requestingStatus() {
    return TeacherStrings.get("Teacher.status.requesting", "Generating commentary...");
  }

  private boolean startRequest(
      List<TeacherLlmClient.Message> messages,
      CommentaryContext context,
      String runningStatus,
      String question) {
    if (requestRunning || !context.owner.isCurrent(rootNodeProvider.get())) {
      return false;
    }
    long preparationGeneration = uiGeneration;
    CommentaryClient client = configuredClient();
    if (client == null) {
      return false;
    }
    // The settings dialog has a nested event loop: the game may change while it is open.
    if (preparationGeneration != uiGeneration
        || !context.owner.isCurrent(rootNodeProvider.get())) {
      discardChangedContext();
      return false;
    }
    Request request =
        new Request(++uiGeneration, context, settings.snapshot().model, question);
    activeRequest = request;
    displayedGame = context.owner.root;
    if (question.isEmpty()) {
      lastCommentary = null;
    }
    output.setText("<html><body></body></html>");
    view.showLoading(runningStatus);
    setRunning(true);
    setStatus(runningStatus, TeacherDialogView.StatusTone.RUNNING);
    requests.start(
        new GroundedCommentaryClient(client, context.positions),
        messages,
        new TeacherRequestController.Listener() {
          @Override
          public void onText(String text) {
            // Display only the verified, complete answer; partial drafts have no SGF ownership.
          }

          @Override
          public void onComplete(String fullText) {
            SwingUtilities.invokeLater(
                () -> {
                  if (acceptCallback(request)) completeRequest(request, fullText);
                });
          }

          @Override
          public void onFailure(Throwable error) {
            SwingUtilities.invokeLater(
                () -> {
                  if (acceptCallback(request)) failRequest(request, error);
                });
          }

          @Override
          public void onCancelled() {
            SwingUtilities.invokeLater(
                () -> {
                  if (acceptCallback(request)) cancelledRequest(request);
                });
          }
        });
    return true;
  }

  private boolean acceptCallback(Request request) {
    if (!isDisplayable() || activeRequest != request || uiGeneration != request.generation) {
      return false;
    }
    if (!request.context.owner.isCurrent(rootNodeProvider.get())) {
      discardChangedContext();
      return false;
    }
    return true;
  }

  private CommentaryClient configuredClient() {
    try {
      TeacherSettings.Snapshot snapshot = settings.load();
      if (!snapshot.hasApiKey) {
        if (!TeacherSettingsDialog.show(this, settings)) {
          return null;
        }
        snapshot = settings.snapshot();
      }
      Optional<String> apiKey = settings.apiKey();
      if (apiKey.isEmpty()) {
        setStatus(
            TeacherStrings.get("Teacher.status.needsKey", "Configure an API key before use"),
            TeacherDialogView.StatusTone.WARNING);
        return null;
      }
      return new TeacherLlmClient(snapshot.baseUrl, apiKey.get(), snapshot.model);
    } catch (Exception error) {
      setStatus(localError(error));
      return null;
    }
  }

  private void completeRequest(Request request, String fullText) {
    String result = fullText == null ? "" : fullText.trim();
    if (result.isEmpty()) {
      failRequest(request, new IllegalStateException("AI service returned an empty response."));
      return;
    }
    CommentaryContext context = request.context;
    String rendered = withVerifierNotes(result, context.positions);
    if (writeToSgf.isSelected()) {
      BoardData target = context.owner.target.getData();
      try {
        synchronized (target) {
          target.setAuthoredComment(
              TeacherCommentCodec.upsert(target.comment, result, request.model));
        }
      } catch (IllegalArgumentException invalidComment) {
        failRequest(request, invalidComment);
        return;
      }
      if (Lizzie.frame != null) {
        Lizzie.frame.refresh();
      }
      setStatus(
          TeacherStrings.get(
              "Teacher.status.completedSaved",
              "Commentary completed and added to the SGF comment."),
          TeacherDialogView.StatusTone.SUCCESS);
    } else {
      setStatus(
          TeacherStrings.get("Teacher.status.completed", "Commentary completed."),
          TeacherDialogView.StatusTone.SUCCESS);
    }
    lastCommentary =
        new CommentaryContext(context.owner, context.messages, context.positions, result);
    showCommentary(rendered);
    activeRequest = null;
    setRunning(false);
  }

  private static String withVerifierNotes(
      String result, List<TeacherEvidence.Position> requestPositions) {
    // These are the same frozen positions sent to the provider, never rebuilt from the live node.
    TeacherVerifier.Result verification = TeacherVerifier.verify(result, requestPositions);
    ArrayList<String> notes = new ArrayList<>(verification.violations);
    notes.addAll(verification.warnings);
    if (notes.isEmpty()) {
      return result;
    }
    return result
        + "\n\n> "
        + TeacherStrings.get("Teacher.verify.note", "Verifier notes")
        + ": "
        + String.join("; ", notes.subList(0, Math.min(4, notes.size())));
  }

  private void failRequest(Request request, Throwable error) {
    finishUnsuccessfulRequest(request);
    setStatus(
        TeacherStrings.format("Teacher.status.failed", "Commentary failed: {0}", localError(error)),
        TeacherDialogView.StatusTone.ERROR);
  }

  private void cancelledRequest(Request request) {
    finishUnsuccessfulRequest(request);
    setStatus(
        TeacherStrings.get("Teacher.status.cancelled", "Commentary stopped."),
        TeacherDialogView.StatusTone.WARNING);
  }

  private void finishUnsuccessfulRequest(Request request) {
    activeRequest = null;
    if (lastCommentary == null) {
      clearOutputForEmptyState();
    } else {
      showCommentary(withVerifierNotes(lastCommentary.answer, lastCommentary.positions));
    }
    if (followUp.getText().isBlank() && request.context.owner.isCurrent(rootNodeProvider.get())) {
      followUp.setText(request.question);
    }
    setRunning(false);
  }

  private void stopRequest() {
    // The worker may already be finished while its completion is still queued on the EDT.
    if (!requestRunning || activeRequest == null) {
      return;
    }
    Request cancelled = activeRequest;
    uiGeneration++;
    requests.cancel();
    cancelledRequest(cancelled);
  }

  private void setRunning(boolean running) {
    requestRunning = running;
    view.setRunning(running);
    updateControlState();
  }

  private void updateControlState() {
    boolean ready = settingsLoaded && settingsUsable && !requestRunning;
    explainNext.setEnabled(ready);
    explainRange.setEnabled(ready);
    explainWhole.setEnabled(ready);
    settingsButton.setEnabled(settingsLoaded && !requestRunning);
    ask.setEnabled(ready);
    followUp.setEnabled(ready);
    rangeStart.setEnabled(ready);
    rangeEnd.setEnabled(ready);
    stop.setEnabled(requestRunning);
  }

  private BoardHistoryNode currentNode() {
    BoardHistoryNode current = currentNodeProvider.get();
    if (current == null) {
      setStatus(TeacherStrings.get("Teacher.status.noGame", "No game is loaded."));
    }
    return current;
  }

  private BoardHistoryNode rootNode() {
    BoardHistoryNode root = rootNodeProvider.get();
    if (root == null) {
      setStatus(TeacherStrings.get("Teacher.status.noGame", "No game is loaded."));
    }
    return root;
  }

  private String evidenceStatus(BoardHistoryNode node) {
    Optional<TeacherEvidence.Position> position = TeacherEvidence.position(node);
    if (position.isEmpty()) {
      return TeacherStrings.get(
          "Teacher.status.needsAnalysis",
          "This position has no KataGo candidates yet. Analyze it first.");
    }
    return TeacherStrings.format(
        "Teacher.status.ready",
        "Ready: move {0}, {1} KataGo candidates.",
        position.get().moveNumber,
        position.get().candidates.size());
  }

  private record CommentaryContext(
      TargetSnapshot owner,
      List<TeacherLlmClient.Message> messages,
      List<TeacherEvidence.Position> positions,
      String answer) {
    private CommentaryContext {
      messages = List.copyOf(messages);
      positions = List.copyOf(positions);
    }
  }

  private record Request(
      long generation, CommentaryContext context, String model, String question) {}

  /** Saves may outlive cursor navigation, but never the target position or its game. */
  private static final class TargetSnapshot {
    final BoardHistoryNode root;
    final BoardHistoryNode target;
    private final NodeSnapshot position;
    private final List<BoardHistoryNode> ancestry;
    private final List<NodeSnapshot> recentHistory;
    private final List<NodeSnapshot> continuation;
    private final List<NodeSnapshot> mainLine;
    private final int width = Board.boardWidth;
    private final int height = Board.boardHeight;

    private TargetSnapshot(
        BoardHistoryNode root,
        BoardHistoryNode target,
        List<BoardHistoryNode> ancestry,
        boolean includeMainLine) {
      this.root = root;
      this.target = target;
      this.ancestry = List.copyOf(ancestry);
      position = new NodeSnapshot(target);
      recentHistory =
          ancestry.stream()
              .limit(TeacherEvidence.MAX_KNOWLEDGE_HISTORY)
              .map(NodeSnapshot::new)
              .toList();
      ArrayList<NodeSnapshot> nextMoves = new ArrayList<>();
      BoardHistoryNode next = target.next().orElse(null);
      while (next != null && nextMoves.size() < TeacherEvidence.MAX_RECORDED_CONTINUATION) {
        nextMoves.add(new NodeSnapshot(next));
        next = next.next().orElse(null);
      }
      continuation = List.copyOf(nextMoves);
      ArrayList<NodeSnapshot> line = new ArrayList<>();
      if (includeMainLine) {
        for (BoardHistoryNode node = root; node != null; node = node.next().orElse(null)) {
          line.add(new NodeSnapshot(node));
        }
      }
      mainLine = List.copyOf(line);
    }

    static TargetSnapshot capture(BoardHistoryNode root, BoardHistoryNode target) {
      return capture(root, target, false);
    }

    static TargetSnapshot capture(
        BoardHistoryNode root, BoardHistoryNode target, boolean includeMainLine) {
      if (root == null || target == null || target.getData() == null) {
        return null;
      }
      ArrayList<BoardHistoryNode> ancestry = new ArrayList<>();
      BoardHistoryNode node = target;
      while (node != root) {
        Optional<BoardHistoryNode> previous = node.previous();
        if (previous == null || previous.isEmpty() || !previous.get().getVariations().contains(node)) {
          return null;
        }
        node = previous.get();
        ancestry.add(node);
      }
      return new TargetSnapshot(root, target, ancestry, includeMainLine);
    }

    boolean targetIsCurrent(BoardHistoryNode currentRoot) {
      if (root != currentRoot || width != Board.boardWidth || height != Board.boardHeight
          || !position.matches(target)) {
        return false;
      }
      BoardHistoryNode child = target;
      for (BoardHistoryNode parent : ancestry) {
        Optional<BoardHistoryNode> previous = child.previous();
        if (previous == null || previous.orElse(null) != parent
            || !parent.getVariations().contains(child)) {
          return false;
        }
        child = parent;
      }
      return child == root && recentHistory.stream().allMatch(snapshot -> snapshot.matches(snapshot.node));
    }

    boolean isCurrent(BoardHistoryNode currentRoot) {
      if (!targetIsCurrent(currentRoot)) {
        return false;
      }
      BoardHistoryNode next = target.next().orElse(null);
      for (NodeSnapshot expected : continuation) {
        if (!expected.matches(next)) {
          return false;
        }
        next = next.next().orElse(null);
      }
      if (continuation.size() < TeacherEvidence.MAX_RECORDED_CONTINUATION && next != null) {
        return false;
      }
      if (mainLine.isEmpty()) {
        return true;
      }
      BoardHistoryNode current = root;
      for (NodeSnapshot expected : mainLine) {
        if (!expected.matches(current)) {
          return false;
        }
        current = current.next().orElse(null);
      }
      return current == null;
    }
  }

  /** Analysis and user comments may change independently; the actual position must still match. */
  private static final class NodeSnapshot {
    private final BoardHistoryNode node;
    private final BoardData data;
    private final BoardNodeKind kind;
    private final Stone[] stones;
    private final int[] lastMove;
    private final Stone lastMoveColor;
    private final int moveNumber;
    private final boolean blackToPlay;
    private final boolean dummy;
    private final boolean removedStone;
    private final boolean extraStones;
    private final int blackCaptures;
    private final int whiteCaptures;
    private final double komi;

    NodeSnapshot(BoardHistoryNode node) {
      this.node = node;
      data = node.getData();
      kind = data.getNodeKind();
      stones = data.stones == null ? null : data.stones.clone();
      lastMove = data.lastMove.map(int[]::clone).orElse(null);
      lastMoveColor = data.lastMoveColor;
      moveNumber = data.moveNumber;
      blackToPlay = data.blackToPlay;
      dummy = data.dummy;
      removedStone = node.hasRemovedStone();
      extraStones = node.extraStones != null && !node.extraStones.isEmpty();
      blackCaptures = data.blackCaptures;
      whiteCaptures = data.whiteCaptures;
      komi = data.komi;
    }

    boolean matches(BoardHistoryNode current) {
      return current == node
          && current.getData() == data
          && kind == data.getNodeKind()
          && Arrays.equals(stones, data.stones)
          && Arrays.equals(lastMove, data.lastMove.orElse(null))
          && lastMoveColor == data.lastMoveColor
          && moveNumber == data.moveNumber
          && blackToPlay == data.blackToPlay
          && dummy == data.dummy
          && removedStone == current.hasRemovedStone()
          && extraStones == (current.extraStones != null && !current.extraStones.isEmpty())
          && blackCaptures == data.blackCaptures
          && whiteCaptures == data.whiteCaptures
          && Double.compare(komi, data.komi) == 0;
    }
  }

  private void setStatus(String message) {
    setStatus(message, TeacherDialogView.StatusTone.NEUTRAL);
  }

  private void setStatus(String message, TeacherDialogView.StatusTone tone) {
    view.setStatus(message, tone);
  }

  private static String localError(Throwable error) {
    Throwable cause = error;
    while (cause != null && cause.getCause() != null) {
      cause = cause.getCause();
    }
    String message = cause == null ? "" : cause.getMessage();
    return message == null || message.isBlank()
        ? TeacherStrings.get("Teacher.error.generic", "The operation failed.")
        : message;
  }

  static String markdownToHtml(String markdown) {
    return SafeMarkdownRenderer.toHtml(markdown);
  }
}
