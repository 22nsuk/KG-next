package featurecat.lizzie.rules;

import static java.util.Arrays.asList;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.enginegame.EngineGameParticipantDescriptor;
import featurecat.lizzie.enginegame.EngineGamePresentation;
import featurecat.lizzie.enginegame.EngineGameRecord;
import featurecat.lizzie.enginegame.EngineGameRecordContext;
import featurecat.lizzie.enginegame.EngineGameSaveSnapshot;
import featurecat.lizzie.enginegame.MatchRulesSnapshot;
import featurecat.lizzie.enginegame.MatchRulesTexts;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.logging.SgfObservation;
import featurecat.lizzie.logging.SgfPhase;
import featurecat.lizzie.util.AtomicSgfFileWriter;
import featurecat.lizzie.util.EncodingDetector;
import featurecat.lizzie.util.SgfFileReader;
import featurecat.lizzie.util.Utils;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.Stack;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class SGFParser {
  // The same ASCII whitespace set as the legacy regex \s, including vertical tab.
  private static final String SGF_WHITESPACE = " \t\n\r\f\u000B";
  private static final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final String[] markupProps = new String[] {"LB", "CR", "SQ", "MA", "TR"};

  private static boolean isExtraMode2 = false;

  // static boolean oriEmpty = false;

  public static boolean load(String filename, boolean showHint) throws IOException {
    return load(filename, showHint, true);
  }

  public static boolean load(String filename, boolean showHint, boolean syncPrimaryEngine)
      throws IOException {
    if (!java.nio.file.Files.isReadable(Path.of(filename))) {
      SgfObservation.record("open", "unreadable", filename, null);
      return false;
    }
    return loadFileContent(SgfFileReader.read(Path.of(filename)), showHint, syncPrimaryEngine);
  }

  /** Prepares immutable file syntax without reading import preferences or changing the live board. */
  public static SgfDocument prepareFileContent(String value) {
    try (var phase = new SgfPhase("parse-syntax")) {
      if (value.isEmpty()) {
        SgfObservation.record("open", "empty", null, null);
        return null;
      }
      value = normalizeSgfInput(value);
      SgfDocument document = value == null ? null : parseLoadDocument(value);
      if (document == null) SgfObservation.record("open", "failed", null, null);
      return document;
    } catch (RuntimeException invalidSgf) {
      SgfObservation.record("open", "failed", null, invalidSgf);
      return null;
    }
  }

  /** Applies already decoded file content with the same navigation/hint contract as load(). */
  public static boolean loadFileContent(
      String value, boolean showHint, boolean syncPrimaryEngine) {
    return loadFileDocument(prepareFileContent(value), showHint, syncPrimaryEngine);
  }

  /** The live board owner applies prepared syntax; no file I/O or syntax parsing is repeated. */
  public static boolean loadFileDocument(
      SgfDocument document, boolean showHint, boolean syncPrimaryEngine) {
    if (document == null) return false;
    isExtraMode2 = false;
    Board.ClearStateSnapshot rollbackState = Lizzie.board.captureClearState();
    boolean finalizerScheduled = false;
    Lizzie.board.isLoadingFile = true;
    try {
      try (var phase = new SgfPhase("board-clear")) {
        if (syncPrimaryEngine) {
          Lizzie.board.clear(false);
        } else {
          Lizzie.board.clearWithoutPrimaryEngineForwarding();
        }
      }
      boolean returnValue;
      try {
        returnValue = parse(document);
      } catch (RuntimeException invalidSgf) {
        SgfObservation.record("open", "failed", null, invalidSgf);
        return false;
      }
      if (!returnValue) {
        return false;
      }
      Board loadedBoard = Lizzie.board;
      BoardHistoryList loadedHistory = currentHistory();
      if (syncPrimaryEngine) {
        adoptCurrentHistoryExternalRules();
      }
      applySgfKomiForSetupGameWhenReadKomiDisabled();

      SwingUtilities.invokeLater(
          new Runnable() {
            public void run() {
              if (Lizzie.board != loadedBoard || loadedBoard.getHistory() != loadedHistory) {
                return;
              }
              try (var phase = new SgfPhase("board-finalize")) {
                if (Lizzie.config.loadSgfLast)
                  while (Lizzie.board.nextMove(false))
                    ;
                if (syncPrimaryEngine) syncPrimaryEngineAfterSgfLoad();
                Lizzie.board.clearAfterMove();
                if (isExtraMode2
                    && !Lizzie.config.isDoubleEngineMode()
                    && !Lizzie.config.isAutoAna
                    && showHint) {
                  int ret =
                      JOptionPane.showConfirmDialog(
                          Lizzie.frame,
                          Lizzie.resourceBundle.getString("SGFParse.doubleEngineHint"),
                          Lizzie.resourceBundle.getString("SGFParse.doubleEngineHintTitle"),
                          JOptionPane.OK_CANCEL_OPTION);
                  if (ret == JOptionPane.OK_OPTION) {
                    Lizzie.config.toggleExtraMode(2);
                  }
                }
              } finally {
                if (Lizzie.board == loadedBoard && loadedBoard.getHistory() == loadedHistory) {
                  loadedBoard.isLoadingFile = false;
                }
              }
            }
          });
      finalizerScheduled = true;
      return true;
    } finally {
      if (!finalizerScheduled) {
        if (rollbackState != null) {
          Lizzie.board.restoreClearState(rollbackState);
          if (syncPrimaryEngine) {
            syncPrimaryEngineAfterSgfLoad();
          }
        }
        Lizzie.board.isLoadingFile = false;
      }
    }
  }

  public static boolean loadFromString(String sgfString) {
    return loadFromString(sgfString, true);
  }

  public static boolean loadFromString(String sgfString, boolean syncPrimaryEngine) {
    sgfString = normalizeSgfInput(sgfString);
    if (sgfString == null) {
      SgfObservation.record("import", "failed", null, null);
      return false;
    }
    isExtraMode2 = false;
    Board.ClearStateSnapshot rollbackState = Lizzie.board.captureClearState();
    boolean result = false;
    boolean committed = false;
    Lizzie.board.isLoadingFile = true;
    try {
      if (syncPrimaryEngine) {
        Lizzie.board.clear(false);
      } else {
        Lizzie.board.clearWithoutPrimaryEngineForwarding();
      }
      try {
        result = parse(sgfString);
      } catch (RuntimeException invalidSgf) {
        SgfObservation.record("import", "failed", null, invalidSgf);
        return false;
      }
      SgfObservation.record("import", result ? "ok" : "failed", null, null);
      if (!result) {
        return false;
      }
      if (syncPrimaryEngine) {
        adoptCurrentHistoryExternalRules();
      }
      applySgfKomiForSetupGameWhenReadKomiDisabled();
      if (Lizzie.config.loadSgfLast)
        while (Lizzie.board.nextMove(false))
          ;
      if (syncPrimaryEngine) syncPrimaryEngineAfterSgfLoad();
      Lizzie.board.isLoadingFile = false;
      committed = true;
      if (isExtraMode2 && !Lizzie.config.isDoubleEngineMode() && !Lizzie.config.isAutoAna) {
        int ret =
            JOptionPane.showConfirmDialog(
                Lizzie.frame,
                Lizzie.resourceBundle.getString("SGFParse.doubleEngineHint"),
                Lizzie.resourceBundle.getString("SGFParse.doubleEngineHintTitle"),
                JOptionPane.OK_CANCEL_OPTION);
        if (ret == JOptionPane.OK_OPTION) {
          Lizzie.config.toggleExtraMode(2);
        }
      }
      return true;
    } finally {
      if (!committed && rollbackState != null) {
        Lizzie.board.restoreClearState(rollbackState);
        if (syncPrimaryEngine) {
          syncPrimaryEngineAfterSgfLoad();
        }
      }
      Lizzie.board.isLoadingFile = false;
    }
  }

  private static void syncPrimaryEngineAfterSgfLoad() {
    if (Lizzie.board == null) {
      return;
    }
    if (Lizzie.frame != null) {
      Lizzie.frame.synchronizeImportedSgfAfterParserLoad();
      return;
    }
    Lizzie.board.resendCurrentPositionToPrimaryEngine();
  }

  private static void applySgfKomiForSetupGameWhenReadKomiDisabled() {
    if (Lizzie.config == null || Lizzie.config.readKomi) {
      return;
    }
    BoardHistoryList history = currentHistory();
    if (history == null || !isSetupOrHandicapGame(history)) {
      return;
    }
    parsedRootKomi(history.getStart()).ifPresent(komi -> history.getGameInfo().setKomi(komi));
  }

  private static BoardHistoryList currentHistory() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    BoardHistoryList history = Lizzie.board.getHistory();
    return history.getGameInfo() == null ? null : history;
  }

  public static BoardHistoryList.SessionRulesTarget adoptCurrentHistoryExternalRules() {
    BoardHistoryList history = currentHistory();
    if (history == null || history.getStart() == null || history.getStart().getData() == null) {
      throw new IllegalStateException("Cannot adopt rules without a parsed SGF history");
    }
    Map<String, List<String>> properties = history.getStart().getData().getProperties();
    String rawRules =
        properties.containsKey("RU") ? history.getStart().getData().getProperty("RU") : null;
    return history.publishExternalRules(rawRules);
  }

  private static boolean isSetupOrHandicapGame(BoardHistoryList history) {
    if (history == null || history.getStart() == null || history.getStart().getData() == null) {
      return false;
    }
    int handicap = history.getGameInfo().getHandicap();
    if (handicap <= 0) {
      handicap = parseHandicapProperty(history.getStart().getData().getProperty("HA"));
    }
    return handicap > 0 || hasRootSetupStones(history.getStart());
  }

  private static boolean hasRootSetupStones(BoardHistoryNode start) {
    if (start == null || start.getData() == null) {
      return false;
    }
    return start.getData().getProperties().containsKey("AB")
        || start.getData().getProperties().containsKey("AW")
        || start.getData().getProperties().containsKey("AE");
  }

  private static Optional<Double> parsedRootKomi(BoardHistoryNode start) {
    if (start == null || start.getData() == null) {
      return Optional.empty();
    }
    String rawKomi = start.getData().getProperty("KM");
    if (Utils.isBlank(rawKomi)) {
      rawKomi = start.getData().getProperty("KO");
    }
    return parseSgfKomi(rawKomi, start.getData().getProperties());
  }

  private static Optional<Double> parseSgfKomi(String rawKomi) {
    return parseSgfKomi(rawKomi, null);
  }

  private static Optional<Double> parseSgfKomi(String rawKomi, Map<String, List<String>> rootProperties) {
    if (Utils.isBlank(rawKomi)) {
      return Optional.empty();
    }
    try {
      Double komi = Double.parseDouble(rawKomi.trim());
      return normalizeSgfKomi(komi, rootProperties);
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private static Optional<Double> normalizeSgfKomi(Double komi) {
    return normalizeSgfKomi(komi, null);
  }

  private static Optional<Double> normalizeSgfKomi(
      Double komi, Map<String, List<String>> rootProperties) {
    return normalizeSgfKomi(komi, rootProperties, Board.boardWidth * Board.boardHeight);
  }

  private static Optional<Double> normalizeSgfKomi(
      Double komi, Map<String, List<String>> rootProperties, int area) {
    if (komi == null) {
      return Optional.empty();
    }
    komi =
        isFoxSgf(rootProperties)
            ? normalizeFoxSgfKomi(komi, rootProperties)
            : normalizeLegacySgfKomi(komi);
    return Math.abs(komi) < area
        ? Optional.of(komi)
        : Optional.empty();
  }

  private static double normalizeLegacySgfKomi(double komi) {
    if (komi >= 200) {
      komi = komi / 100;
      if (komi <= 4 && komi >= -4) {
        komi = komi * 2;
      }
    }
    String komiText = Double.toString(komi);
    if (komiText.endsWith(".75") || komiText.endsWith(".25")) {
      komi = komi * 2;
    }
    return komi;
  }

  private static double normalizeFoxSgfKomi(double komi, Map<String, List<String>> rootProperties) {
    if (Math.abs(komi) >= 50) {
      komi = komi / 100;
      if (isChineseRules(rootProperties) && komi <= 4 && komi >= -4) {
        komi = komi * 2;
      }
    }
    return komi;
  }

  private static boolean isFoxSgf(Map<String, List<String>> rootProperties) {
    return rootPropertyContains(rootProperties, "AP", "foxwq");
  }

  private static boolean isChineseRules(Map<String, List<String>> rootProperties) {
    return rootPropertyContains(rootProperties, "RU", "chinese");
  }

  private static boolean rootPropertyContains(
      Map<String, List<String>> rootProperties, String propertyName, String needle) {
    if (rootProperties == null) {
      return false;
    }
    String value = getOrDefault(rootProperties, propertyName, null);
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
  }

  public static boolean loadFromStringforedit(String sgfString) {
    sgfString = normalizeSgfInput(sgfString);
    if (sgfString == null) {
      SgfObservation.record("import", "failed", null, null);
      return false;
    }
    BoardHistoryList.SessionRulesTarget sessionRules =
        Lizzie.board.getHistory().captureSessionRules();
    Lizzie.board.clearforedit();
    Lizzie.board.hasStartStone = false;
    Lizzie.board.startStonelist = new ArrayList<Movelist>();
    Lizzie.board.isLoadingFile = true;
    try {
      return parse(sgfString);
    } finally {
      Lizzie.board.isLoadingFile = false;
      Lizzie.board.getHistory().restoreSessionRulesTarget(sessionRules);
    }
  }

  public static String passPos() {
    return "";
    //    (Board.boardWidth <= 51 && Board.boardHeight <= 51)
    //        ? String.format(
    //            "%c%c", alphabet.charAt(Board.boardWidth), alphabet.charAt(Board.boardHeight))
    //        : "";
  }

  public static boolean isPassPos(String pos) {
    // TODO
    String passPos = passPos();
    return pos.isEmpty() || passPos.equals(pos);
  }

  public static int[] convertSgfPosToCoord(String pos) {
    return convertSgfPosToCoord(pos, Board.boardWidth, Board.boardHeight);
  }

  public static boolean isSGF(String value) {
    final Pattern SGF_PATTERN = Pattern.compile("(?s).*?(\\(\\s*;{0,1}.*\\))(?s).*?");
    Matcher sgfMatcher = SGF_PATTERN.matcher(value);
    if (!sgfMatcher.matches()) {
      value = "(;" + value.substring(1);
      Matcher sgfMatcher2 = SGF_PATTERN.matcher(value);
      if (!sgfMatcher2.matches()) {
        return false;
      }
    }
    return true;
  }

  private static String normalizeSgfInput(String value) { return SgfDocument.normalizeInput(value); }

  private static String normalizePropertyName(String name) { return SgfDocument.normalizePropertyName(name); }

  private static String normalizeSetupRanges(String value, int width, int height) {
    return SgfDocument.normalizeSetupRanges(value, width, height);
  }

  private static int[] parseBoardSizeTagValue(String value) { return SgfDocument.parseBoardSizeTagValue(value); }

  private static int[] convertSgfPosToCoord(String pos, int width, int height) {
    return SgfDocument.convertSgfPosToCoord(pos, width, height);
  }

  private static boolean parse(String value) {
    return parse(parseLoadDocument(value));
  }

  private static boolean parse(SgfDocument document) {
    boolean oriPlaySound = Lizzie.config.playSound;
    try (var phase = new SgfPhase("parse-build-tree")) {
      Lizzie.config.playSound = false;
      return parseIntoCurrentBoard(document);
    } finally {
      Lizzie.config.playSound = oriPlaySound;
    }
  }

  private static SgfDocument parseLoadDocument(String value) {
    SgfDocument document = SgfDocument.parseNormalized(value);
    if (document == null && !value.isEmpty()) {
      // Preserve the live loader's legacy leading-node fragment tolerance.
      document = SgfDocument.parseNormalized("(;" + value.substring(1));
    }
    return document;
  }

  // Syntax is detached first; the interactive owner applies the document to its live history.
  private static boolean parseIntoCurrentBoard(SgfDocument document) {
    if (document == null) return false;
    Lizzie.board.reopen(document.width(), document.height());

    int subTreeDepth = 0;
    // Save the variation step count
    Map<Integer, BoardHistoryNode> subTreeStepMap = new HashMap<Integer, BoardHistoryNode>();
    // Game properties
    Map<String, List<String>> gameProperties = new HashMap<String, List<String>>();
    Map<String, List<String>> pendingProps = new HashMap<String, List<String>>();
    AnalysisImport analysisImport = new AnalysisImport();
    PendingNodeMetadata pendingNodeMetadata = new PendingNodeMetadata(analysisImport);
    boolean isMultiGo = false,
        moveStart = false,
        currentNodeHasMove = false,
        addPassForMove = true,
        currentNodeHasSetupSnapshot = false;
    int topLevelNodeCount = 0;
    boolean allowRootSetupOnCurrentNode = false;

    // MultiGo 's branch: (Main Branch (Main Branch) (Branch) )
    // Other 's branch: (Main Branch (Branch) Main Branch)
    isMultiGo = document.multiGo();

    String blackPlayer = "", whitePlayer = "";
    String result = "";
    // boolean isChineseRule = true;
    // boolean hasHandicap = false;
    Double komi = Lizzie.board.getHistory().getGameInfo().getKomi();
    boolean parsedKomiTag = false;
    // Support unicode characters (UTF-8)
    boolean currentNodeHasDummy = false;
    for (SgfDocument.Event event : document.events()) {
      switch (event.kind()) {
        case '(':
          flushPendingNodeMetadataToCurrentNode(Lizzie.board.getHistory(), pendingNodeMetadata);
          currentNodeHasDummy = false;
          subTreeDepth += 1;
          // Initialize the step count
          subTreeStepMap.put(subTreeDepth, Lizzie.board.getHistory().getCurrentHistoryNode());
          addPassForMove = true;
          currentNodeHasSetupSnapshot = false;
          currentNodeHasMove = false;
          pendingProps = new HashMap<String, List<String>>();
          break;
        case ')':
          flushPendingNodeMetadataToCurrentNode(Lizzie.board.getHistory(), pendingNodeMetadata);
          currentNodeHasDummy = false;
          if (isMultiGo) {
            // Restore to the variation node
            // int varStep = subTreeStepMap.get(subTreeDepth);
            BoardHistoryNode node = subTreeStepMap.get(subTreeDepth);
            //  for (int s = 0; s < varStep; s++)
            while (Lizzie.board.getHistory().getCurrentHistoryNode() != node) {
              if (!Lizzie.board.getHistory().previous().isPresent()) break;
            }
            //  System.out.println(subTreeDepth+" | "+varStep);
          }
          subTreeDepth -= 1;
          break;
        case 'P':
          if (subTreeDepth > 1 && !isMultiGo) {
            break;
          }
          String tag = event.name();
          String tagContent = event.legacyValue();
          // We got tag, we can parse this tag now.
          if (tag.equals("DD") && "true".equals(tagContent)) {
            appendDummyPlaceholder(Lizzie.board.getHistory());
            currentNodeHasDummy = true;
          } else if (tag.equals("B") || tag.equals("W")) {
            if (currentNodeHasDummy) {
              Lizzie.board.getHistory().previous();
              currentNodeHasDummy = false;
            }
            moveStart = true;
            addPassForMove = !currentNodeHasSetupSnapshot;
            int[] move = convertSgfPosToCoord(tagContent);
            // Save the step count
            //  subTreeStepMap.put(subTreeDepth, subTreeStepMap.get(subTreeDepth) + 1);
            Stone color = tag.equals("B") ? Stone.BLACK : Stone.WHITE;
            BoardHistoryNode parent = Lizzie.board.getHistory().getCurrentHistoryNode();
            boolean newBranch =
                parent.hasVariations()
                    || parent.next(true).map(BoardHistoryNode::isEndDummay).orElse(false);
            if (move == null) {
              Lizzie.board.getHistory().pass(color, newBranch, false);
            } else {
              Lizzie.board.getHistory().place(move[0], move[1], color, newBranch);
            }
            currentNodeHasMove = true;
            if (newBranch) {
              processPendingPros(Lizzie.board.getHistory(), pendingProps);
            }
            flushPendingNodeMetadataToCurrentNode(Lizzie.board.getHistory(), pendingNodeMetadata);
          } else if (tag.equals("C")) {
            // Support comment
            if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
              bufferNodeMetadata(pendingNodeMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(
                      Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
              target.getData().comment = tagContent;
            }
          } else if (isAnalysisTag(tag)) {
            if (isSecondaryEngineAnalysisTag(tag)) {
              isExtraMode2 = true;
            }
            if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
              bufferNodeAnalysis(pendingNodeMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(
                      Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
              analysisImport.applyTag(target.getData(), tag, tagContent);
            }
          } else if (tag.equals("AB") || tag.equals("AW")) {
            int[] move = convertSgfPosToCoord(tagContent);
            Stone color = tag.equals("AB") ? Stone.BLACK : Stone.WHITE;
            if (isRootSetupContext(
                Lizzie.board.getHistory(), moveStart, allowRootSetupOnCurrentNode)) {
              applyRootSetupProperty(Lizzie.board.getHistory(), tag, tagContent, move, color);
            } else {
              if (addPassForMove) {
                boolean newBranch =
                    Lizzie.board.getHistory().getCurrentHistoryNode().hasVariations();
                appendSetupSnapshot(
                    Lizzie.board.getHistory(), newBranch, pendingProps,
                    currentNodeHasMove, analysisImport);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(
                      Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
              movePendingNodeMetadataToTarget(
                  Lizzie.board.getHistory(), target, pendingNodeMetadata);
              addNodePropertyOnNode(Lizzie.board.getHistory(), target, tag, tagContent);
              if (move != null) {
                addExtraStoneOnNode(Lizzie.board.getHistory(), target, move[0], move[1], color);
              }
            }
          } else if (tag.equals("PL")) {
            if (isRootSetupContext(
                Lizzie.board.getHistory(), moveStart, allowRootSetupOnCurrentNode)) {
              applyRootPlayerProperty(Lizzie.board.getHistory(), tagContent);
            } else {
              if (addPassForMove) {
                boolean newBranch =
                    Lizzie.board.getHistory().getCurrentHistoryNode().hasVariations();
                appendSetupSnapshot(
                    Lizzie.board.getHistory(), newBranch, pendingProps,
                    currentNodeHasMove, analysisImport);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(
                      Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
              movePendingNodeMetadataToTarget(
                  Lizzie.board.getHistory(), target, pendingNodeMetadata);
              applyCurrentPlayerPropertyOnNode(Lizzie.board.getHistory(), target, tagContent);
            }
          } else if (tag.equals("PB")) {
            blackPlayer = decodeGameInfoText(tagContent);
          } else if (tag.equals("PW")) {
            whitePlayer = decodeGameInfoText(tagContent);
          } else if (tag.equals("RE")) {
            result = decodeGameInfoText(tagContent);
          } else if (tag.equals("DZ")) {
            if (tagContent.equals("Y")) {
              Lizzie.board.isPkBoard = true;
            }
            if (tagContent.equals("KB")) {
              Lizzie.board.isPkBoard = true;
              Lizzie.board.isKataBoard = true;
              Lizzie.board.isPkBoardKataB = true;
            }
            if (tagContent.equals("KW")) {
              Lizzie.board.isPkBoard = true;
              Lizzie.board.isKataBoard = true;
              Lizzie.board.isPkBoardKataW = true;
            }
            if (tagContent.equals("G")) {
              Lizzie.board.isKataBoard = true;
            }
          } else if (tag.equals("KM")
              || tag.equals("KO")) { // Cyberoro and some site uses komi tag as KO.
            try {
              if (!tagContent.trim().isEmpty()) {
                komi = Double.parseDouble(tagContent);
                parsedKomiTag = true;
              }
            } catch (NumberFormatException e) {
              e.printStackTrace();
            }
          }
          //          else if (tag.equals("HA")) {
          //            try {
          //              if (!tagContent.trim().isEmpty()) {
          //                if (Integer.parseInt(tagContent.trim()) > 0) hasHandicap = true;
          //              }
          //            } catch (NumberFormatException e) {
          //              e.printStackTrace();
          //            }
          //          }
          //          else if (tag.equals("RU")) {
          //            if (!tagContent.trim().isEmpty()) {
          //              String rules = tagContent.toLowerCase();
          //              if (rules.contains("japanese")
          //                  || rules.contains("jp")
          //                  || rules.contains("korean")
          //                  || rules.contains("kr")) isChineseRule = false;
          //            }
          //          }
          else {
            if (allowRootSetupOnCurrentNode && isRootGameProperty(tag)) {
              addProperty(gameProperties, tag, tagContent);
            } else if (moveStart) {
              // Other SGF node properties
              if ("AE".equals(tag)) {
                // remove a stone
                if (addPassForMove) {
                  boolean newBranch =
                      Lizzie.board.getHistory().getCurrentHistoryNode().hasVariations();
                  appendSetupSnapshot(
                      Lizzie.board.getHistory(), newBranch, pendingProps,
                      currentNodeHasMove, analysisImport);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(
                        Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
                movePendingNodeMetadataToTarget(
                    Lizzie.board.getHistory(), target, pendingNodeMetadata);
                addNodePropertyOnNode(Lizzie.board.getHistory(), target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent);
                if (move != null) {
                  removeStoneOnNode(
                      Lizzie.board.getHistory(), target, move[0], move[1], Stone.EMPTY);
                }
              } else if (currentNodeHasSetupSnapshot) {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(
                        Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(Lizzie.board.getHistory(), target, tag, tagContent);
              } else if (!"FIT".equals(tag)
                  && shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
                bufferNodeMetadata(pendingNodeMetadata, tag, tagContent);
              } else if (!"FIT".equals(tag)) {
                boolean firstProp =
                    Lizzie.board.getHistory().getCurrentHistoryNode().hasVariations();
                // (subTreeStepMap.get(subTreeDepth).hasVariations());
                if (firstProp) {
                  addProperty(pendingProps, tag, tagContent);
                } else {
                  addNodePropertyOnNode(
                      Lizzie.board.getHistory(),
                      Lizzie.board.getHistory().getCurrentHistoryNode(),
                      tag,
                      tagContent);
                }
              }
            } else {
              boolean rootSetupContext =
                  isRootSetupContext(
                      Lizzie.board.getHistory(), moveStart, allowRootSetupOnCurrentNode);
              if ("AE".equals(tag) && rootSetupContext) {
                int[] move = convertSgfPosToCoord(tagContent);
                applyRootSetupProperty(
                    Lizzie.board.getHistory(), tag, tagContent, move, Stone.EMPTY);
              } else if ("AE".equals(tag)) {
                if (addPassForMove) {
                  boolean newBranch =
                      Lizzie.board.getHistory().getCurrentHistoryNode().hasVariations();
                  appendSetupSnapshot(
                      Lizzie.board.getHistory(), newBranch, pendingProps,
                      currentNodeHasMove, analysisImport);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(
                        Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
                movePendingNodeMetadataToTarget(
                    Lizzie.board.getHistory(), target, pendingNodeMetadata);
                addNodePropertyOnNode(Lizzie.board.getHistory(), target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent);
                if (move != null) {
                  removeStoneOnNode(
                      Lizzie.board.getHistory(), target, move[0], move[1], Stone.EMPTY);
                }
              } else if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
                bufferNodeMetadata(pendingNodeMetadata, tag, tagContent);
              } else {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(
                        Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(Lizzie.board.getHistory(), target, tag, tagContent);
              }
            }
          }
          break;
        case ';':
          if (!(subTreeDepth > 1 && !isMultiGo)) {
            flushPendingNodeMetadataToCurrentNode(Lizzie.board.getHistory(), pendingNodeMetadata);
            currentNodeHasDummy = false;
            topLevelNodeCount += 1;
            allowRootSetupOnCurrentNode = topLevelNodeCount == 1;
            addPassForMove = true;
            currentNodeHasSetupSnapshot = false;
            currentNodeHasMove = false;
          }
          break;
        default:
          throw new IllegalArgumentException("Unknown SGF event: " + event.kind());
      }
    }
    flushPendingNodeMetadataToCurrentNode(Lizzie.board.getHistory(), pendingNodeMetadata);
    Optional<Double> parsedKomi =
        parsedKomiTag ? normalizeSgfKomi(komi, gameProperties) : Optional.empty();
    if (parsedKomi.isPresent()
        && (Lizzie.config.readKomi
            || isSetupOrHandicapGame(Lizzie.board.getHistory())
            || parseHandicapProperty(getOrDefault(gameProperties, "HA", null)) > 0)) {
      //      if (!hasHandicap && komi == 0) {
      //        if (isChineseRule) komi = 7.5;
      //        else komi = 6.5;
      //      }
      Lizzie.board.getHistory().getGameInfo().setKomi(parsedKomi.get());
    }
    Lizzie.frame.setPlayers(whitePlayer, blackPlayer);
    Lizzie.frame.setResult(result);
    GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
    gameInfo.setPlayerBlack(blackPlayer);
    gameInfo.setPlayerWhite(whitePlayer);
    gameInfo.setResult(result);
    gameInfo.setDate(decodeGameInfoText(getOrDefault(gameProperties, "DT", null)));
    gameInfo.setHandicap(parseHandicapProperty(getOrDefault(gameProperties, "HA", null)));
    // Rewind to game start
    while (Lizzie.board.previousMove(false))
      ;
    if (gameProperties.size() > 0) {
      Lizzie.board.addNodeProperties(gameProperties);
    }
    stabilizeRootSetupSideToPlay(Lizzie.board.getHistory());
    return true;
  }

  public static String saveToString(boolean forUpload) throws IOException {
    try {
      Board board = Lizzie.board;
      String result = serialize(board, board.getHistory(), forUpload, false, false, false, false);
      if (forUpload) {
        SgfObservation.record("export", "ok", null, null);
      }
      return result;
    } catch (IOException e) {
      if (forUpload) {
        SgfObservation.record("export", "failed", null, e);
      }
      throw e;
    }
  }

  /** Synchronous compatibility export; file-save callers use {@link #captureSnapshot}. */
  public static String saveSnapshot(Board board, boolean raw, boolean comments, boolean currentBranch)
      throws IOException {
    return captureSnapshot(board, raw, comments, currentBranch).serialize();
  }

  /** Freezes the tree and export settings on the EDT, without formatting SGF. */
  public static SaveSnapshot captureSnapshot(
      Board board, boolean raw, boolean comments, boolean currentBranch) throws IOException {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("SGF save snapshots must be captured on the event thread");
    }
    return prepareSave(
        board, board.getHistory(), false, false, currentBranch, currentBranch, true, raw, comments);
  }

  /**
   * Exclusively owns a detached tree and the settings needed to render it. File-save workers
   * serialize once; retries reuse the completed text. Neither the tree nor live owners escape.
   */
  public static final class SaveSnapshot {
    private BoardHistoryList history;
    private final SaveContext context;
    private final boolean forUpload;
    private final boolean fromAutoSave;
    private final boolean mainTrunkOnly;
    private final boolean stripRootMetadata;
    private final boolean captureBranchOnly;
    private String serialized;
    private Exception failure;

    private SaveSnapshot(
        BoardHistoryList history, SaveContext context, boolean forUpload, boolean fromAutoSave,
        boolean mainTrunkOnly, boolean stripRootMetadata, boolean captureBranchOnly) {
      this.history = history;
      this.context = context;
      this.forUpload = forUpload;
      this.fromAutoSave = fromAutoSave;
      this.mainTrunkOnly = mainTrunkOnly;
      this.stripRootMetadata = stripRootMetadata;
      this.captureBranchOnly = captureBranchOnly;
    }

    public synchronized String serialize() throws IOException {
      if (serialized != null) return serialized;
      if (failure instanceof IOException io) throw io;
      if (failure instanceof RuntimeException runtime) throw runtime;
      try {
        serialized = SGFParser.serialize(
            history, context, forUpload, fromAutoSave, mainTrunkOnly,
            stripRootMetadata, captureBranchOnly);
        return serialized;
      } catch (IOException | RuntimeException error) {
        // Rendering generates comments on the private tree. Never retry a partially rendered
        // tree or recapture a game which may have changed since this request was accepted.
        failure = error;
        throw error;
      } finally {
        history = null;
      }
    }
  }

  public static String saveMainTrunkRawToString() throws IOException {
    boolean originalSavingRaw = LizzieFrame.isSavingRaw;
    boolean originalSavingRawComment = LizzieFrame.isSavingRawComment;
    GameInfo originalGameInfo = copyGameInfo(Lizzie.board.getHistory().getGameInfo());
    int startMoveNumber = 0;
    boolean blackToPlay = Lizzie.board.getHistory().getStart().getData().blackToPlay;
    if (Lizzie.board.hasStartStone) {
      startMoveNumber += Lizzie.board.startStonelist.size();
    }
    try {
      Lizzie.board.saveListForEdit();
      Lizzie.board.clearforedit();
      Lizzie.board.getHistory().setGameInfo(originalGameInfo);
      Lizzie.board.setMoveListWithFlatten(
          Lizzie.board.tempallmovelist, startMoveNumber, blackToPlay);
      Lizzie.board.getHistory().getStart().getData().comment = "";
      LizzieFrame.isSavingRaw = true;
      LizzieFrame.isSavingRawComment = false;
      return saveToString(false);
    } finally {
      LizzieFrame.isSavingRaw = originalSavingRaw;
      LizzieFrame.isSavingRawComment = originalSavingRawComment;
      Lizzie.board.clearEditStuff();
    }
  }

  private static GameInfo copyGameInfo(GameInfo original) {
    return original == null ? new GameInfo() : original.copyForSave();
  }

  private static BoardHistoryList captureSaveHistory(
      BoardHistoryList source, boolean currentBranchOnly) {
    try (var phase = new SgfPhase("save-capture")) {
      return copySaveHistory(source, currentBranchOnly);
    }
  }

  private static BoardHistoryList copySaveHistory(
      BoardHistoryList source, boolean currentBranchOnly) {
    BoardHistoryList copy;
    BoardHistoryNode current;
    Map<BoardHistoryNode, List<BoardHistoryNode>> topology = new IdentityHashMap<>();
    // Engine-game responses commit structure on their reader thread under this exact history
    // monitor. A shallow copy has a different monitor and cannot protect the live child lists.
    // Freeze only references/metadata here: payload commits may acquire other owners' locks, so
    // never acquire a BoardData monitor while holding the history monitor.
    synchronized (source) {
      // A save owns data, not the live history's engine-authorization references.
      copy = new BoardHistoryList(null);
      current = source.getCurrentHistoryNode();
      copy.setGameInfo(copyGameInfo(source.getGameInfo()));
      if (currentBranchOnly) {
        BoardHistoryNode selected = current;
        while (selected.previous().isPresent()) {
          BoardHistoryNode parent = selected.previous().get();
          topology.put(parent, List.of(selected));
          selected = parent;
        }
        // Resolve terminal dummy nodes against the live topology once. Pruning can turn a
        // retained nonterminal dummy into a leaf, but it must still appear in the saved path.
        for (BoardHistoryNode node = current; node != null; ) {
          BoardHistoryNode next = node.next().orElse(null);
          topology.put(node, next == null ? List.of() : List.of(next));
          node = next;
        }
      } else {
        ArrayDeque<BoardHistoryNode> pending = new ArrayDeque<>();
        pending.add(source.getStart());
        while (!pending.isEmpty()) {
          BoardHistoryNode original = pending.removeFirst();
          List<BoardHistoryNode> children = List.copyOf(original.getVariations());
          topology.put(original, children);
          pending.addAll(children);
        }
      }
    }
    Map<BoardHistoryNode, BoardHistoryNode> nodes = new IdentityHashMap<>();
    for (BoardHistoryNode original : topology.keySet()) {
      nodes.put(original, original.copyForSave());
    }
    for (Map.Entry<BoardHistoryNode, List<BoardHistoryNode>> entry : topology.entrySet()) {
      BoardHistoryNode parent = nodes.get(entry.getKey());
      for (BoardHistoryNode child : entry.getValue()) {
        nodes.get(child).reparentAsLastVariationOf(parent);
      }
    }
    copy.setHead(nodes.get(current));
    return copy;
  }

  private static GameInfo gameInfoForSave(Board board) {
    if (board != null && board.getHistory() != null) {
      return board.getHistory().getGameInfo();
    }
    if (Lizzie.board != null && Lizzie.board.getHistory() != null) {
      return Lizzie.board.getHistory().getGameInfo();
    }
    return null;
  }

  private static boolean isEngineGameHistory(Board board) {
    GameInfo info = gameInfoForSave(board);
    return info != null && info.hasEngineGameHistory();
  }

  private static boolean isEngineGameHistory() {
    return isEngineGameHistory(Lizzie.board);
  }

  private static boolean isCurrentSaveHistory(Board board, BoardHistoryList history) {
    return board != null && board == Lizzie.board && board.getHistory() == history;
  }

  /** Resource bundles are fixed per language; switching languages replaces the global reference. */
  private record SaveOptions(
      boolean raw, boolean comments, boolean appendWinrate, boolean doubleEngine,
      boolean winrateAlwaysBlack, boolean scoreLeadWithKomi, ResourceBundle bundle,
      String appVersion) {
    static SaveOptions capture(boolean raw, boolean comments) {
      var config = Lizzie.config;
      return new SaveOptions(
          raw, comments, config != null && config.appendWinrateToComment,
          config != null && config.isDoubleEngineMode(),
          config != null && config.winrateAlwaysBlack,
          config != null && config.showKataGoScoreLeadWithKomi,
          Lizzie.resourceBundle, Lizzie.nextVersion);
    }
  }

  /** Serialization decisions belong to the captured history, not to a newly opened game. */
  private record SaveContext(
      Board board,
      BoardHistoryList sourceHistory,
      boolean engineGameHistory,
      boolean pkBoard,
      boolean gameBoard,
      boolean blackToPlay,
      double komi,
      String dataType,
      int boardWidth,
      int boardHeight,
      SaveOptions options,
      String rules) {
    static SaveContext current() {
      Board board = Lizzie.board;
      BoardHistoryList history = board.getHistory();
      return capture(board, history, history);
    }

    static SaveContext capture(
        Board board, BoardHistoryList sourceHistory, BoardHistoryList snapshot) {
      return capture(board, sourceHistory, snapshot,
          SaveOptions.capture(LizzieFrame.isSavingRaw, LizzieFrame.isSavingRawComment));
    }

    static SaveContext capture(
        Board board, BoardHistoryList sourceHistory, BoardHistoryList snapshot, SaveOptions options) {
      boolean current = isCurrentSaveHistory(board, sourceHistory);
      GameInfo info = snapshot.getGameInfo();
      EngineGameRecordContext engineContext = engineGameContext(info);
      boolean engineGame = info != null && info.hasEngineGameHistory();
      String savedType = snapshot.getStart().getData().getProperty("DZ");
      boolean pk =
          current
              ? board.isPkBoard
              : "Y".equalsIgnoreCase(savedType)
                  || "KB".equalsIgnoreCase(savedType)
                  || "KW".equalsIgnoreCase(savedType);
      String dataType;
      if (engineGame || pk) {
        EngineGameParticipantDescriptor white =
            engineContext == null ? null : engineContext.white();
        EngineGameParticipantDescriptor black =
            engineContext == null ? null : engineContext.black();
        boolean whiteKata =
            engineContext != null
                ? white != null && (white.katago() || white.sai())
                : current ? board.isPkBoardKataW : "KW".equalsIgnoreCase(savedType);
        boolean blackKata =
            engineContext != null
                ? black != null && (black.katago() || black.sai())
                : current ? board.isPkBoardKataB : "KB".equalsIgnoreCase(savedType);
        dataType = whiteKata ? "KW" : blackKata ? "KB" : "Y";
      } else {
        boolean kata =
            current
                ? (Lizzie.leelaz != null && Lizzie.leelaz.isKatago) || board.isKataBoard
                : "G".equalsIgnoreCase(savedType);
        dataType = kata ? "G" : "";
      }
      int[] boardSize = resolveHistoryBoardSize(snapshot);
      return new SaveContext(
          board, sourceHistory, engineGame, pk, current && board.isGameBoard,
          snapshot.isBlacksTurn(), info.getKomi(), dataType, boardSize[0], boardSize[1], options,
          current && Lizzie.leelaz != null && Lizzie.leelaz.isKatago
              ? rulesLabel(Lizzie.leelaz.rulesSummary, options.bundle()) : "");
    }

    SaveContext withBoardSize(int[] boardSize) {
      return new SaveContext(
          board, sourceHistory, engineGameHistory, pkBoard, gameBoard,
          blackToPlay, komi, dataType, boardSize[0], boardSize[1], options, rules);
    }

    SaveContext detached() {
      return new SaveContext(
          null, null, engineGameHistory, pkBoard, gameBoard, blackToPlay, komi,
          dataType, boardWidth, boardHeight, options, rules);
    }

    double winrate(BoardData data, boolean secondary) {
      double value = secondary ? data.winrate2 : data.winrate;
      return options.winrateAlwaysBlack() && data.analysisBlackToPlay(secondary)
          ? 100 - value : value;
    }

    boolean ownsCurrentHistory() {
      return isCurrentSaveHistory(board, sourceHistory);
    }

    double komiFor(BoardData data) {
      return data.komi == -999 ? komi : data.komi;
    }

    String asCoord(int index) {
      return SGFParser.asCoord(index / boardHeight, index % boardHeight, boardWidth, boardHeight);
    }

    String asCoord(int[] coord) {
      return SGFParser.asCoord(coord[0], coord[1], boardWidth, boardHeight);
    }

    int[] parseCoord(String value) {
      return convertSgfPosToCoord(value, boardWidth, boardHeight);
    }

    boolean isOnBoard(int[] coord) {
      return coord[0] >= 0 && coord[0] < boardWidth && coord[1] >= 0 && coord[1] < boardHeight;
    }

    void clearLoadingAfterSerializationFailure() {
      if (ownsCurrentHistory()) board.isLoadingFile = false;
    }
  }

  private static EngineGameRecordContext engineGameContext(GameInfo info) {
    if (info == null) {
      return null;
    }
    if (info.engineGameRecord() != null) {
      return info.engineGameRecord().context();
    }
    if (info.engineGameSaveSnapshot() != null) {
      return info.engineGameSaveSnapshot().context();
    }
    return info.engineGameRecordContext();
  }

  private static void maybeCaptureEngineGameSaveSnapshot(
      Board board, BoardHistoryList sourceHistory) {
    GameInfo info = sourceHistory.getGameInfo();
    if (info == null || info.engineGameRecord() != null || info.engineGameRecordContext() == null) {
      return;
    }
    if (isCurrentSaveHistory(board, sourceHistory) && Lizzie.engineGame != null) {
      Lizzie.engineGame.captureSaveSnapshot();
    }
  }

  public static void appendGameTimeAndPlayouts() {
    appendGameTimeAndPlayouts(Lizzie.board == null ? null : Lizzie.board.getHistory());
  }

  /** Appends completed-game totals to the supplied history, even after another game is opened. */
  public static void appendGameTimeAndPlayouts(BoardHistoryList history) {
    if (history == null) {
      return;
    }
    GameInfo info = history.getGameInfo();
    BoardHistoryNode node = history.getStart();
    long blackPlayouts;
    long whitePlayouts;
    long blackTimeMs;
    long whiteTimeMs;
    int openingIndex = -1;
    EngineGameRecord record = info == null ? null : info.engineGameRecord();
    EngineGameSaveSnapshot snapshot = info == null ? null : info.engineGameSaveSnapshot();
    if (record != null) {
      blackPlayouts = record.blackVisits();
      whitePlayouts = record.whiteVisits();
      blackTimeMs = record.blackTimeMs();
      whiteTimeMs = record.whiteTimeMs();
      openingIndex = record.openingIndex();
    } else if (snapshot != null) {
      blackPlayouts = snapshot.blackVisits();
      whitePlayouts = snapshot.whiteVisits();
      blackTimeMs = snapshot.blackTimeMs();
      whiteTimeMs = snapshot.whiteTimeMs();
      openingIndex = snapshot.context().openingIndex();
    } else {
      long[] visits = historySideVisits(node);
      blackPlayouts = visits[0];
      whitePlayouts = visits[1];
      blackTimeMs = 0L;
      whiteTimeMs = 0L;
      EngineGameRecordContext context = engineGameContext(info);
      openingIndex = context == null ? -1 : context.openingIndex();
    }
    node = history.getStart();
    if (Lizzie.config != null && Lizzie.config.chkEngineSgfStart && openingIndex >= 0) {
      node.getData().comment =
          node.getData().comment
              + Lizzie.resourceBundle.getString("SGFParse.startGameSgf")
              + openingIndex
              + "\n";
    }
    if (Lizzie.resourceBundle == null) {
      return;
    }
    node.getData().comment +=
        Lizzie.resourceBundle.getString("SGFParse.blackTotalTime")
            + blackTimeMs / (float) 1000
            + Lizzie.resourceBundle.getString("SGFParse.seconds")
            + "\n"
            + Lizzie.resourceBundle.getString("SGFParse.totalVisits")
            + blackPlayouts;
    node.getData().comment +=
        "\n"
            + Lizzie.resourceBundle.getString("SGFParse.whiteTotalTime")
            + whiteTimeMs / (float) 1000
            + Lizzie.resourceBundle.getString("SGFParse.seconds")
            + "\n"
            + Lizzie.resourceBundle.getString("SGFParse.totalVisits")
            + whitePlayouts;
  }

  private static long[] historySideVisits(BoardHistoryNode node) {
    long blackPlayouts = 0;
    long whitePlayouts = 0;
    if (node == null) {
      return new long[] {0L, 0L};
    }
    while (node.next().isPresent()) {
      if (node.getData().isHistoryActionNode()
          && node.getData().lastMoveColor.equals(Stone.WHITE)) {
        blackPlayouts += node.getData().getPlayouts();
      }
      if (node.getData().isHistoryActionNode()
          && node.getData().lastMoveColor.equals(Stone.BLACK)) {
        whitePlayouts += node.getData().getPlayouts();
      }
      node = node.next().get();
    }
    if (node.getData().isHistoryActionNode() && node.getData().lastMoveColor.equals(Stone.WHITE)) {
      blackPlayouts += node.getData().getPlayouts();
    }
    if (node.getData().isHistoryActionNode() && node.getData().lastMoveColor.equals(Stone.BLACK)) {
      whitePlayouts += node.getData().getPlayouts();
    }
    return new long[] {blackPlayouts, whitePlayouts};
  }

  private static void appendMatchRulesComment(
      BoardHistoryList history, GameInfo gameInfo, EngineGameRecordContext engineContext,
      ResourceBundle bundle) {
    MatchRulesSnapshot snapshot = EngineGamePresentation.historyMatchRules(gameInfo);
    if (snapshot != null && bundle != null) {
      BoardHistoryNode start = history.getStart();
      start.getData().comment =
          MatchRulesTexts.replaceSgfComment(
              start.getData().comment, snapshot, bundle);
      return;
    }
    if (engineContext != null) {
      appendEngineGameSideRules(history, engineContext.black(), "SGFParse.blackRules", bundle);
      appendEngineGameSideRules(history, engineContext.white(), "SGFParse.whiteRules", bundle);
    }
  }

  private static void appendEngineGameSideRules(
      BoardHistoryList history, EngineGameParticipantDescriptor side, String resourceKey,
      ResourceBundle bundle) {
    if (side == null || !side.katago() || bundle == null) {
      return;
    }
    String rules = rulesLabel(side.rulesSummary(), bundle);
    if (rules.isEmpty()) {
      return;
    }
    BoardHistoryNode start = history.getStart();
    if (start.getData().comment.equals("")) {
      start.getData().comment += bundle.getString(resourceKey) + rules;
    } else {
      start.getData().comment += "\n" + bundle.getString(resourceKey) + rules;
    }
  }

  private static String rulesLabel(KataGoRules.Summary summary, ResourceBundle bundle) {
    if (summary == null || bundle == null) {
      return "";
    }
    return bundle.getString(summary.labelKey());
  }

  public static void appendAiScoreBlunder() {
    int analyzedBlack = 0;
    int analyzedWhite = 0;
    double blackValue = 0;
    double whiteValue = 0;
    List<BlunderMoves> blackDiffWinrate = new ArrayList<BlunderMoves>();
    List<BlunderMoves> whiteDiffWinrate = new ArrayList<BlunderMoves>();
    BoardHistoryNode node = Lizzie.board.getHistory().getEnd();
    while (node.previous().isPresent()) {
      node = node.previous().get();
      NodeInfo nodeInfo = node.nodeInfoMain;
      if (nodeInfo.analyzed) {
        if (nodeInfo.isBlack) {
          blackValue = blackValue + nodeInfo.percentsMatch;
          // + Math.pow(nodeInfo.percentsMatch, (double) 1 / Lizzie.config.matchAiTemperature);
          analyzedBlack = analyzedBlack + 1;
          blackDiffWinrate.add(new BlunderMoves(nodeInfo.moveNum, nodeInfo.diffWinrate));
        } else {
          whiteValue = whiteValue + nodeInfo.percentsMatch;
          // + Math.pow(nodeInfo.percentsMatch, (double) 1 / Lizzie.config.matchAiTemperature);
          analyzedWhite = analyzedWhite + 1;
          whiteDiffWinrate.add(new BlunderMoves(nodeInfo.moveNum, nodeInfo.diffWinrate));
        }
      }
    }

    BoardHistoryNode startNode = Lizzie.board.getHistory().getStart();
    if (analyzedBlack >= 10) {
      String bAiScore = String.format(Locale.ENGLISH, "%.1f", blackValue * 100 / analyzedBlack);
      String infoString =
          Lizzie.resourceBundle.getString("SGFParse.blackAiScore")
              + bAiScore
              + "\n"
              + Lizzie.resourceBundle.getString("SGFParse.blackTop10");
      Collections.sort(blackDiffWinrate);
      for (int i = 0; i < 10; i++) {
        if (i < 9) infoString = infoString + " " + blackDiffWinrate.get(i).toString() + ",";
        else infoString = infoString + " " + blackDiffWinrate.get(i).toString();
      }
      if (startNode.getData().comment.equals("")) startNode.getData().comment = infoString;
      else startNode.getData().comment += "\n" + infoString;
    }
    if (analyzedWhite >= 10) {
      String wAiScore = String.format(Locale.ENGLISH, "%.1f", whiteValue * 100 / analyzedWhite);
      String infoString =
          Lizzie.resourceBundle.getString("SGFParse.whiteAiScore")
              + wAiScore
              + "\n"
              + Lizzie.resourceBundle.getString("SGFParse.whiteTop10");
      Collections.sort(whiteDiffWinrate);
      for (int i = 0; i < 10; i++) {
        if (i < 9) infoString = infoString + " " + whiteDiffWinrate.get(i).toString() + ",";
        else infoString = infoString + " " + whiteDiffWinrate.get(i).toString();
      }
      if (startNode.getData().comment.equals("")) startNode.getData().comment = infoString;
      else startNode.getData().comment += "\n" + infoString;
    }
  }

  //
  //  public static void appendTimeAndPlayouts() {
  //    appendGameTime();
  //    appendGamePlayouts();
  //  }

  public static void save(Board board, String filename) throws IOException {
    save(board, filename, false);
  }

  public static void save(Board board, String filename, boolean isAutoSave) throws IOException {
    String snapshot;
    try {
      snapshot = captureSave(board, isAutoSave);
    } catch (IOException | RuntimeException failure) {
      SgfObservation.record("save", "failed", filename, failure);
      throw failure;
    }
    AtomicSgfFileWriter.write(Path.of(filename), snapshot);
  }

  /** Freezes an ordinary save so a failed file write can retry without rereading the live game. */
  public static String captureSave(Board board, boolean isAutoSave) throws IOException {
    return captureSave(board, board.getHistory(), isAutoSave);
  }

  /** Freezes the exact source history without installing it as the board's current history. */
  public static String captureSave(
      Board board, BoardHistoryList sourceHistory, boolean isAutoSave) throws IOException {
    return captureSaveSnapshot(board, sourceHistory, isAutoSave).serialize();
  }

  /**
   * Freezes an exact history on its owner thread. Completed engine games also call this from
   * their terminal owner before retirement; ordinary UI saves capture on the EDT.
   */
  public static SaveSnapshot captureSaveSnapshot(
      Board board, BoardHistoryList sourceHistory, boolean isAutoSave) throws IOException {
    return prepareSave(
        board, sourceHistory, false, isAutoSave, false, false, true,
        LizzieFrame.isSavingRaw, LizzieFrame.isSavingRawComment);
  }

  private static String serialize(
      Board board,
      BoardHistoryList sourceHistory,
      boolean forUpload,
      boolean fromAutoSave,
      boolean mainTrunkOnly,
      boolean stripRootMetadata,
      boolean captureSnapshot)
      throws IOException {
    return prepareSave(
        board, sourceHistory, forUpload, fromAutoSave, mainTrunkOnly, stripRootMetadata,
        captureSnapshot, LizzieFrame.isSavingRaw, LizzieFrame.isSavingRawComment).serialize();
  }

  private static SaveSnapshot prepareSave(
      Board board, BoardHistoryList sourceHistory, boolean forUpload, boolean fromAutoSave,
      boolean mainTrunkOnly, boolean stripRootMetadata, boolean captureSnapshot,
      boolean raw, boolean comments) throws IOException {
    maybeCaptureEngineGameSaveSnapshot(board, sourceHistory);
    if (captureSnapshot
        && !raw
        && sourceHistory.getGameInfo().hasEngineGameHistory()
        && sourceHistory.getGameInfo().engineGameRecord() == null
        && isCurrentSaveHistory(board, sourceHistory)) {
      // Preserve save-time analysis comments before detaching; do not call back into the live
      // game while holding the structural monitor or after the payload has already been copied.
      board.updateWinrate();
    }
    // Standard saves may generate comments on a node's original first child. Keep their full
    // topology; raw branch exports only need the selected ancestors and first-child continuation.
    boolean captureBranchOnly = captureSnapshot && mainTrunkOnly && raw;
    BoardHistoryList history =
        captureSnapshot
            ? captureSaveHistory(sourceHistory, captureBranchOnly)
            : sourceHistory.shallowCopy();
    SaveContext context = SaveContext.capture(
        board, sourceHistory, history, SaveOptions.capture(raw, comments));
    if (!captureSnapshot && !raw && context.engineGameHistory() && context.ownsCurrentHistory()) {
      board.updateWinrate();
    }
    return new SaveSnapshot(
        history, captureSnapshot ? context.detached() : context, forUpload, fromAutoSave,
        mainTrunkOnly, stripRootMetadata, captureBranchOnly);
  }

  private static String serialize(
      BoardHistoryList history, SaveContext saveContext, boolean forUpload, boolean fromAutoSave,
      boolean mainTrunkOnly, boolean stripRootMetadata, boolean captureBranchOnly) throws IOException {
    try (var phase = new SgfPhase("save-serialize")) {
      String boardSizeTag = formatBoardSizeTag(saveContext.boardWidth(), saveContext.boardHeight());

      // collect game info
      GameInfo gameInfo = history.getGameInfo();
      EngineGameRecordContext engineContext = engineGameContext(gameInfo);
      boolean engineGameHistory = gameInfo != null && gameInfo.hasEngineGameHistory();
      String playerB = gameInfo.getPlayerBlack();
      String playerW = gameInfo.getPlayerWhite();
      String result = gameInfo.getResult();
      Double komi = gameInfo.getKomi();
      Integer handicap = gameInfo.getHandicap();
      String date = gameInfo.getDate();

      // add SGF header
      StringBuilder builder = new StringBuilder("(;");
      if (!saveContext.options().raw()) {
        if (engineGameHistory) {
          appendTime(gameInfo, history, saveContext.options().bundle());
          appendMatchRulesComment(history, gameInfo, engineContext, saveContext.options().bundle());
        } else {
          if (!fromAutoSave) {
            String rules = saveContext.rules();
            if (!rules.isEmpty()) {
              if (history.getStart().getData().comment.equals(""))
                history.getStart().getData().comment +=
                    saveContext.options().bundle().getString("SGFParse.rules") + rules;
              else if (!history
                  .getStart()
                  .getData()
                  .comment
                  .contains(saveContext.options().bundle().getString("SGFParse.rules")))
                history.getStart().getData().comment =
                    saveContext.options().bundle().getString("SGFParse.rules")
                        + rules
                        + "\n\n"
                        + history.getStart().getData().comment;
              else {
                String oldComment = history.getStart().getData().comment;
                int leftIndex =
                    oldComment.indexOf(
                        "\n",
                        oldComment.indexOf(saveContext.options().bundle().getString("SGFParse.rules")));
                history.getStart().getData().comment =
                    oldComment.substring(
                            0,
                            oldComment.indexOf(saveContext.options().bundle().getString("SGFParse.rules")))
                        + saveContext.options().bundle().getString("SGFParse.rules")
                        + rules
                        + (leftIndex > 0 ? oldComment.substring(leftIndex) : "");
              }
            }
          }
        }
      }
      // To append the winrate to the comment of sgf we might need to update the
      // Winrate
      // if (Lizzie.config.appendWinrateToComment) {
      // Lizzie.board.updateWinrate();
      // }

      // move to the first move
      Map<BoardHistoryNode, BoardHistoryNode> selectedBranch = new IdentityHashMap<>();
      if (mainTrunkOnly && !captureBranchOnly) {
        BoardHistoryNode selected = history.getCurrentHistoryNode();
        while (selected.previous().isPresent()) {
          BoardHistoryNode parent = selected.previous().get();
          selectedBranch.put(parent, selected);
          selected = parent;
        }
      }
      history.toStart();

      // Game properties
      BoardData rootData = history.getData().clone();
      // Property values are SGF-encoded, while GameInfo holds plain text. Encode directly
      // into the detached root so delimiters in metadata can never be parsed as properties.
      if (handicap != 0) rootData.setProperty("HA", handicap.toString());
      rootData.setProperty("KM", komi.toString());
      rootData.setProperty("PW", Escaping(playerW));
      rootData.setProperty("PB", Escaping(playerB));
      if (date == null) {
        rootData.getProperties().remove("DT");
      } else {
        rootData.setProperty("DT", Escaping(date));
      }
      if (!saveContext.options().raw()) {
        if (!saveContext.dataType().isEmpty()) {
          rootData.setProperty("DZ", saveContext.dataType());
        }
        rootData.setProperty("AP", Escaping("KG-next: " + saveContext.options().appVersion()));
      }
      rootData.setProperty("RE", Escaping(result));
      rootData.setProperty("SZ", boardSizeTag);
      rootData.setProperty("CA", "UTF-8");
      if (rootData.isSnapshotNode()) {
        builder.append(materializedRootSnapshotProperties(rootData, history.getStones(), saveContext));
      } else {
        builder.append(rootData.propertiesString());
        if (handicap != 0) {
          builder.append("AB");
          Stone[] stones = history.getStones();
          for (int i = 0; i < stones.length; i++) {
            Stone stone = stones[i];
            if (stone.isBlack()) {
              // i = x * Board.BOARD_SIZE + y;
              builder.append('[').append(saveContext.asCoord(i)).append(']');
            }
          }
        } else {
          // Process the AW/AB stone
          Stone[] stones = history.getStones();
          StringBuilder abStone = new StringBuilder();
          StringBuilder awStone = new StringBuilder();
          for (int i = 0; i < stones.length; i++) {
            Stone stone = stones[i];
            if (stone.isBlack() || stone.isWhite()) {
              if (stone.isBlack()) {
                abStone.append('[').append(saveContext.asCoord(i)).append(']');
              } else {
                awStone.append('[').append(saveContext.asCoord(i)).append(']');
              }
            }
          }
          if (abStone.length() > 0) {
            builder.append("AB").append(abStone);
          }
          if (awStone.length() > 0) {
            builder.append("AW").append(awStone);
          }
        }
      }

      // The AW/AB Comment
      if (!stripRootMetadata && !history.getData().comment.isEmpty()) {
        String coment = history.getData().comment;
        if (forUpload) {
          coment =
              removeWinrateComment(
                  history.getData().isKataData, history.getData().isSaiData, coment,
                  saveContext.options().bundle());
        }
        builder.append("C[").append(Escaping(coment)).append(']');
      }
      BoardHistoryNode curNode = history.getCurrentHistoryNode();
      if (!stripRootMetadata) {
        try {
          appendSerializedAnalysis(builder, curNode, saveContext, "LZOP", "LZOP2");
          if (!saveContext.engineGameHistory() && !saveContext.pkBoard()) {
            BoardData data = curNode.getData();
            if (saveContext.gameBoard()) {
              updateGameComment(curNode, data, saveContext);
            }
          }
        } catch (Exception e) {
          saveContext.clearLoadingAfterSerializationFailure();
        }
      }
      // replay moves, and convert them to tags.
      // * format: ";B[xy]" or ";W[xy]"
      // * with 'xy' = coordinates ; or 'tt' for pass.

      // Write variation tree
      BoardHistoryNode makerBeg = new BoardHistoryNode(null);
      BoardHistoryNode makerEnd = new BoardHistoryNode(null);
      BoardHistoryNode rootNode = history.getStart();
      Stack<BoardHistoryNode> stack = new Stack<>();
      stack.push(curNode);
      while (!stack.isEmpty()) {
        BoardHistoryNode cur = stack.pop();
        if (cur == makerBeg) {
          builder.append("(");
          continue;
        }
        if (cur == makerEnd) {
          builder.append(")");
          continue;
        }
        if (cur != rootNode) {
          builder = generateNode(saveContext, cur, forUpload, builder);
        }
        if (mainTrunkOnly && !captureBranchOnly) {
          BoardHistoryNode next = selectedBranch.getOrDefault(cur, cur.next().orElse(null));
          if (next != null) stack.push(next);
        } else {
          boolean hasBrothers = (cur.numberOfChildren() > 1);
          if (cur.numberOfChildren() >= 1) {
            for (int i = cur.numberOfChildren() - 1; i >= 0; i--) {
              if (hasBrothers) stack.push(makerEnd);
              stack.push(cur.getVariations().get(i));
              if (hasBrothers) stack.push(makerBeg);
            }
          }
        }
      }
      builder.append(')');
      return builder.toString();
    }
  }

  /** Generate node with variations */
  public static void appendComment() {
    appendComment(false);
  }

  /** Generates a comment, optionally using the PK formatter for an exact retired game owner. */
  public static void appendComment(boolean forceEngineGame) {
    // if (!Lizzie.config.showComment) return;
    BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();
    BoardData data = node.getData();
    if (forceEngineGame || isEngineGameHistory()) {
      if (data.getPlayouts() > 0) {
        data.setGeneratedComment(
            () -> formatCommentPk(node));
      }
    } else if (data.getPlayouts() > 0) {
      if (Lizzie.board.isGameBoard) {
        data.setGeneratedComment(
            () -> mergeAnalysisComment(data.comment, formatCommentForGame(node)));
      } else {
        data.setGeneratedComment(
            () -> formatComment(node));
      }
    }
  }

  public static void appendTime() {
    appendTime(gameInfoForSave(Lizzie.board));
  }

  private static void appendTime(GameInfo info) {
    appendTime(info, Lizzie.board == null ? null : Lizzie.board.getHistory(), Lizzie.resourceBundle);
  }

  private static void appendTime(GameInfo info, BoardHistoryList history, ResourceBundle bundle) {
    if (history == null || bundle == null) {
      return;
    }
    BoardHistoryNode node = history.getCurrentHistoryNode();
    if (node.getData().moveNumber < 3 || node.getData().getPlayouts() <= 0) {
      return;
    }
    EngineGameSaveSnapshot snapshot = info == null ? null : info.engineGameSaveSnapshot();
    long moveTimeMs = 0L;
    if (snapshot != null) {
      moveTimeMs =
          node.getData().blackToPlay ? snapshot.blackMoveTimeMs() : snapshot.whiteMoveTimeMs();
    }
    if (moveTimeMs <= 0) {
      return;
    }
    node.getData().comment +=
        "\n"
            + bundle.getString("SGFParse.moveTime")
            + moveTimeMs / 1000f
            + bundle.getString("SGFParse.seconds");
  }

  //
  //  public static void appendCommentForPk() {
  //	  appendComment();
  //  }

  private static double getMatchValue(BoardHistoryNode node) {
    if (!node.isMainTrunk()) return 0;
    boolean isBlack = node.getData().blackToPlay;
    double matchValue = 0;
    int analyzed = 0;
    while (node.previous().isPresent()) {
      node = node.previous().get();
      NodeInfo nodeInfo = node.nodeInfoMain;
      if (nodeInfo.analyzed) {
        if (node.getData().blackToPlay && isBlack) {
          matchValue = matchValue + nodeInfo.percentsMatch;
          analyzed++;
        } else if (!node.getData().blackToPlay && !isBlack) {
          matchValue = matchValue + nodeInfo.percentsMatch;
          analyzed++;
        }
      }
    }
    if (analyzed == 0) return 0;
    return matchValue * 100 / analyzed;
  }

  private static StringBuilder generateNode(
      Board board, BoardHistoryNode node, boolean forUpload, StringBuilder builder)
      throws IOException {
    if (node == null) return builder;
    Board sourceBoard = board == null ? Lizzie.board : board;
    BoardHistoryList history = sourceBoard == null ? null : sourceBoard.getHistory();
    int[] boardSize = resolveNodeBoardSize(node);
    SaveContext saveContext;
    if (history != null) {
      saveContext = SaveContext.capture(sourceBoard, history, history).withBoardSize(boardSize);
    } else {
      // The legacy node exporter also accepts detached nodes without an installed live history.
      BoardData data = node.getData();
      saveContext =
          new SaveContext(
              sourceBoard, null, false,
              sourceBoard != null && sourceBoard.isPkBoard,
              sourceBoard != null && sourceBoard.isGameBoard,
              data.blackToPlay, data.komi == -999 ? GameInfo.DEFAULT_KOMI : data.komi,
              "", boardSize[0], boardSize[1],
              SaveOptions.capture(LizzieFrame.isSavingRaw, LizzieFrame.isSavingRawComment), "");
    }
    return generateNode(saveContext, node, forUpload, builder);
  }

  private static StringBuilder generateNode(
      SaveContext saveContext, BoardHistoryNode node, boolean forUpload, StringBuilder builder)
      throws IOException {
    if (node == null) {
      return builder;
    }
    BoardData data = node.getData();
    if (data.isSnapshotNode() && node.previous().isPresent()) {
      builder.append(";");
      builder.append(materializedSetupProperties(node, saveContext));
      appendSerializedComment(builder, node, forUpload, true, saveContext);
      return builder;
    }
    if (shouldSerializeSetupSnapshot(data)) {
      builder.append(";");
      builder.append(data.propertiesString());
      appendSerializedComment(builder, node, forUpload, true, saveContext);
      return builder;
    }
    if (!data.isHistoryActionNode()) {
      return builder;
    }
    String stone = "";
    if (Stone.BLACK.equals(data.lastMoveColor) || Stone.WHITE.equals(data.lastMoveColor)) {

      if (Stone.BLACK.equals(data.lastMoveColor)) stone = "B";
      else if (Stone.WHITE.equals(data.lastMoveColor)) stone = "W";

      builder.append(";");

      if (data.dummy) {
        builder.append("DD[true]");
      } else {
        String sgfMove = data.isPassNode() ? passPos() : saveContext.asCoord(data.lastMove.get());
        builder.append(stone).append('[').append(sgfMove).append(']');
      }

      // Node properties
      builder.append(data.propertiesString());

      if (forUpload) {
        builder.append(String.format(Locale.ENGLISH, "FIT[%s]", getMatchValue(node)));
      }

      appendSerializedComment(builder, node, forUpload, true, saveContext);
    }
    return builder;
  }

  private static boolean shouldSerializeSetupSnapshot(BoardData data) {
    if (!data.isSnapshotNode()) {
      return false;
    }
    Map<String, List<String>> properties = data.getProperties();
    return properties.containsKey("AB")
        || properties.containsKey("AW")
        || properties.containsKey("AE");
  }

  private static String materializedRootSnapshotProperties(
      BoardData rootData, Stone[] stones, SaveContext saveContext) {
    Map<String, List<String>> properties = new LinkedHashMap<String, List<String>>();
    Map<String, List<String>> rootSetup =
        collectCurrentRootSetup(stones, rootData.blackToPlay, saveContext);
    boolean setupInserted = false;
    for (Map.Entry<String, List<String>> entry : rootData.getProperties().entrySet()) {
      String key = entry.getKey();
      if (isRootSetupSerializationProperty(key)) {
        if (!setupInserted) {
          addProperties(properties, rootSetup);
          setupInserted = true;
        }
        continue;
      }
      addPropertyValues(properties, key, entry.getValue());
    }
    if (!setupInserted) {
      addProperties(properties, rootSetup);
    }
    return propertiesString(properties);
  }

  private static Map<String, List<String>> collectCurrentRootSetup(
      Stone[] stones, boolean blackToPlay, SaveContext saveContext) {
    Map<String, List<String>> setup = new LinkedHashMap<String, List<String>>();
    for (int index = 0; index < stones.length; index++) {
      Stone stone = stones[index];
      if (stone.isBlack()) {
        addSetupProperty(setup, "AB", saveContext.asCoord(index));
      } else if (stone.isWhite()) {
        addSetupProperty(setup, "AW", saveContext.asCoord(index));
      }
    }
    addProperty(setup, "PL", blackToPlay ? "B" : "W");
    return setup;
  }

  private static boolean isRootSetupSerializationProperty(String key) {
    return "AB".equals(key) || "AW".equals(key) || "AE".equals(key) || "PL".equals(key);
  }

  private static String materializedSetupProperties(BoardHistoryNode node, SaveContext saveContext) {
    Map<String, List<String>> properties = new LinkedHashMap<String, List<String>>();
    BoardData data = node.getData();
    addProperty(properties, "PL", data.blackToPlay ? "B" : "W");
    appendSetupStoneDelta(properties, node, saveContext);
    data.getProperties()
        .forEach(
            (key, value) -> {
              if ("PL".equals(key)) {
                return;
              }
              if (isSetupStoneProperty(key)) {
                addSemanticallyMatchingSetupValues(properties, node, key, value, saveContext);
                return;
              }
              addPropertyValues(properties, key, value);
            });
    return propertiesString(properties);
  }

  private static void appendSetupStoneDelta(
      Map<String, List<String>> properties, BoardHistoryNode node, SaveContext saveContext) {
    Stone[] currentStones = node.getData().stones;
    Stone[] previousStones = node.previous().get().getData().stones;
    for (int index = 0; index < currentStones.length; index++) {
      Stone currentStone = currentStones[index];
      Stone previousStone = previousStones[index];
      if (currentStone == previousStone) {
        continue;
      }
      if (!previousStone.isEmpty()) {
        addSetupProperty(properties, "AE", saveContext.asCoord(index));
      }
      if (currentStone.isBlack()) {
        addSetupProperty(properties, "AB", saveContext.asCoord(index));
      } else if (currentStone.isWhite()) {
        addSetupProperty(properties, "AW", saveContext.asCoord(index));
      }
    }
  }

  private static void addSemanticallyMatchingSetupValues(
      Map<String, List<String>> properties, BoardHistoryNode node, String key, List<String> rawValues,
      SaveContext saveContext) {
    if (rawValues == null || rawValues.isEmpty()) {
      return;
    }
    Stone[] currentStones = node.getData().stones;
    Stone[] previousStones = node.previous().get().getData().stones;
    boolean keepExplicitRemovedStoneMetadata = node.hasRemovedStone();
    for (String value : rawValues) {
      String normalizedValue = value.trim();
      int[] coord = saveContext.parseCoord(normalizedValue);
      if (!matchesMaterializedSetupSemantics(
          key, coord, currentStones, previousStones, keepExplicitRemovedStoneMetadata, saveContext)) {
        continue;
      }
      addSetupProperty(properties, key, normalizedValue);
    }
  }

  private static boolean matchesMaterializedSetupSemantics(
      String key,
      int[] coord,
      Stone[] currentStones,
      Stone[] previousStones,
      boolean keepExplicitRemovedStoneMetadata,
      SaveContext saveContext) {
    if (coord == null || !saveContext.isOnBoard(coord)) {
      return false;
    }
    int index = coord[0] * saveContext.boardHeight() + coord[1];
    Stone currentStone = currentStones[index];
    Stone previousStone = previousStones[index];
    if ("AB".equals(key)) {
      return currentStone != previousStone && currentStone.isBlack();
    }
    if ("AW".equals(key)) {
      return currentStone != previousStone && currentStone.isWhite();
    }
    if (!"AE".equals(key) || !currentStone.isEmpty()) {
      return false;
    }
    if (currentStone != previousStone) {
      return true;
    }
    return keepExplicitRemovedStoneMetadata;
  }

  private static void addSetupProperty(Map<String, List<String>> properties, String key, String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    if (!properties.getOrDefault(key, List.of()).contains(value)) {
      addProperty(properties, key, value);
    }
  }

  private static boolean isSetupStoneProperty(String key) {
    return "AB".equals(key) || "AW".equals(key) || "AE".equals(key);
  }

  private static void appendSerializedComment(
      StringBuilder builder, BoardHistoryNode node, boolean forUpload, boolean includeAnalysisData,
      SaveContext saveContext)
      throws IOException {
    if (!saveContext.options().raw()) {
      appendStandardSerializedComment(builder, node, forUpload, includeAnalysisData, saveContext);
      return;
    }
    if (saveContext.options().comments()) {
      appendRawSerializedComment(builder, node, forUpload, saveContext);
    }
  }

  private static void appendStandardSerializedComment(
      StringBuilder builder, BoardHistoryNode node, boolean forUpload, boolean includeAnalysisData,
      SaveContext saveContext)
      throws IOException {
    prepareCommentForSave(node, saveContext);
    String curComment = getSerializedComment(node, saveContext);
    if (shouldFormatSnapshotCommentForSave(node, saveContext)) {
      curComment = formatSnapshotCommentForExport(node, saveContext);
    }
    if (forUpload) {
      curComment =
          removeWinrateComment(node.getData().isKataData, node.getData().isSaiData, curComment,
              saveContext.options().bundle());
    }
    if (!curComment.isEmpty()) {
      builder.append("C[").append(Escaping(curComment)).append(']');
    }
    if (includeAnalysisData) {
      appendSerializedAnalysis(builder, node, saveContext);
    }
  }

  private static void prepareCommentForSave(BoardHistoryNode node, SaveContext saveContext)
      throws IOException {
    BoardData data = node.getData();
    if (saveContext.options().appendWinrate()) {
      if (!saveContext.engineGameHistory() && !saveContext.pkBoard()) {
        if (saveContext.gameBoard()) {
          updateGameComment(node, data, saveContext);
        } else {
          if (data.isSnapshotNode()) {
            return;
          }
          if (data.getPlayouts() > 0) data.setGeneratedComment(() -> formatComment(node, saveContext));
          if (saveContext.options().doubleEngine() && data.getPlayouts2() > 0) {
            data.setGeneratedComment(() -> formatComment2(node, saveContext));
          }
        }
      }
      return;
    }
    if (!saveContext.engineGameHistory() && !saveContext.pkBoard() && saveContext.gameBoard()) {
      updateGameComment(node, data, saveContext);
    }
  }

  private static void updateGameComment(
      BoardHistoryNode node, BoardData data, SaveContext saveContext) throws IOException {
    if (node.next().isEmpty()) return;
    BoardData destination = node.next().get().getData();
    destination.setGeneratedComment(() -> mergeAnalysisComment(
        destination.comment, data.getPlayouts() > 0 ? formatCommentForGame(node, saveContext) : "",
        saveContext.options().bundle()));
  }

  private static String getSerializedComment(BoardHistoryNode node, SaveContext saveContext)
      throws IOException {
    if (!saveContext.engineGameHistory() || !node.previous().isPresent()) {
      return node.getData().comment;
    }
    if (node.getData().isSnapshotNode()) {
      return node.getData().comment;
    }
    BoardHistoryNode previous = node.previous().get();
    String source = previous.previous().isEmpty()
        ? formatCommentPk(previous, saveContext) : previous.getData().comment;
    return mergeAnalysisComment(node.getData().comment, splitWinrateComment(source, saveContext.options().bundle()).matchInfo,
        saveContext.options().bundle());
  }

  private static boolean shouldFormatSnapshotCommentForSave(
      BoardHistoryNode node, SaveContext saveContext) {
    return saveContext.options().appendWinrate()
        && node.getData().isSnapshotNode()
        && !saveContext.engineGameHistory()
        && !saveContext.pkBoard()
        && !saveContext.gameBoard();
  }

  private static String formatSnapshotCommentForExport(
      BoardHistoryNode node, SaveContext saveContext) throws IOException {
    BoardData data = node.getData();
    String curComment = data.comment;
    if (data.getPlayouts() > 0) {
      curComment = formatComment(node, saveContext);
    }
    if (saveContext.options().doubleEngine() && data.getPlayouts2() > 0) {
      curComment = formatComment2(node, saveContext);
    }
    return curComment;
  }

  private static void appendRawSerializedComment(
      StringBuilder builder, BoardHistoryNode node, boolean forUpload, SaveContext saveContext)
      throws IOException {
    BoardData data = node.getData();
    String curComment = data.comment;
    if (!saveContext.engineGameHistory() && !saveContext.pkBoard()) {
      if (data.isSnapshotNode()) {
        curComment = formatSnapshotCommentForExport(node, saveContext);
      } else {
        if (data.getPlayouts() > 0) data.setGeneratedComment(() -> formatComment(node, saveContext));
        if (saveContext.options().doubleEngine() && data.getPlayouts2() > 0) {
          data.setGeneratedComment(() -> formatComment2(node, saveContext));
        }
        curComment = data.comment;
      }
    }
    if (forUpload) {
      curComment =
          removeWinrateComment(node.getData().isKataData, node.getData().isSaiData, curComment,
              saveContext.options().bundle());
    }
    if (!curComment.isEmpty()) {
      builder.append("C[").append(Escaping(curComment)).append(']');
    }
  }

  private static void appendSerializedAnalysis(
      StringBuilder builder, BoardHistoryNode node, SaveContext saveContext)
      throws IOException {
    appendSerializedAnalysis(builder, node, saveContext, "LZ", "LZ2");
  }

  private static void appendSerializedAnalysis(
      StringBuilder builder, BoardHistoryNode node, SaveContext saveContext,
      String primaryTag, String secondaryTag)
      throws IOException {
    try {
      BoardData data = node.getData();
      synchronized (data) {
        String primary = hasPrimaryAnalysisPayload(data) ? formatNodeData(node) : null;
        String secondary = hasSecondaryAnalysisPayload(data) ? formatNodeData2(node) : null;
        if (primary != null) {
          builder.append(primaryTag).append('[').append(Escaping(primary)).append(']');
        }
        if (secondary != null) {
          builder.append(secondaryTag).append('[').append(Escaping(secondary)).append(']');
        }
        appendAnalysisMetadata(builder, data, primary, secondary);
      }
    } catch (Exception e) {
      saveContext.clearLoadingAfterSerializationFailure();
    }
  }

  private static String removeWinrateComment(
      boolean isKataData, boolean isSaiData, String curComment, ResourceBundle bundle) {
    return curComment.replaceAll(winrateCommentRegex(isKataData, isSaiData, bundle), "");
  }

  /** Personal commentary plus the current-language formatComment match-info block. */
  public static final class WinrateCommentSplit {
    public final String personalComment;
    public final String matchInfo;

    private WinrateCommentSplit(String personalComment, String matchInfo) {
      this.personalComment = personalComment;
      this.matchInfo = matchInfo;
    }
  }

  /**
   * Splits a stored ordinary-analysis comment into personal text and the writer match-info block
   * using the same language-dependent pattern the writer already uses.
   */
  public static WinrateCommentSplit splitWinrateComment(String comment) {
    return splitWinrateComment(comment, Lizzie.resourceBundle);
  }

  private static WinrateCommentSplit splitWinrateComment(String comment, ResourceBundle bundle) {
    if (comment == null || comment.isEmpty() || bundle == null) {
      return new WinrateCommentSplit(comment == null ? "" : comment, "");
    }
    boolean[][] variants = {{true, false}, {true, true}, {false, false}};
    for (boolean[] variant : variants) {
      String wp = winrateCommentRegex(variant[0], variant[1], bundle);
      Matcher matcher = Pattern.compile(wp).matcher(comment);
      if (matcher.find()) {
        String matchInfo = matcher.group().trim();
        return new WinrateCommentSplit(matcher.replaceAll("").trim(), matchInfo);
      }
    }
    return new WinrateCommentSplit(comment, "");
  }

  private static String winrateCommentRegex(
      boolean isKataData, boolean isSaiData, ResourceBundle bundle) {
    // Stored summaries retain their score label when the display preference changes.
    String lead =
        "(?:" + Pattern.quote(bundle.getString("SGFParse.leadWithKomi"))
            + "|" + Pattern.quote(bundle.getString("SGFParse.leadJustScore")) + ")";
    if (!isKataData) {
      return "("
          + bundle.getString("SGFParse.black")
          + " |"
          + bundle.getString("SGFParse.white")
          + " )"
          + bundle.getString("SGFParse.winrate")
          + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n\\("
          + ".*"
          + " / [0-9\\.]*[kmKM]* "
          + bundle.getString("SGFParse.playouts")
          + "\\)\\n"
          + bundle.getString("SGFParse.komi")
          + ".*";
    }
    if (isSaiData) {
      return "("
          + bundle.getString("SGFParse.black")
          + " |"
          + bundle.getString("SGFParse.white")
          + " )"
          + bundle.getString("SGFParse.winrate")
          + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n"
          + lead
          + " [0-9\\.\\-+]* \\(*[0-9.\\-+]*\\)*\n\\("
          + ".*"
          + " / [0-9\\.]*[kmKM]* "
          + bundle.getString("SGFParse.playouts")
          + "\\)\\n"
          + bundle.getString("SGFParse.komi")
          + ".*";
    }
    return "("
        + bundle.getString("SGFParse.black")
        + " |"
        + bundle.getString("SGFParse.white")
        + " )"
        + bundle.getString("SGFParse.winrate")
        + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n"
        + lead
        + " [0-9\\.\\-+]* \\(*[0-9.\\-+]*\\)* "
        + bundle.getString("SGFParse.stdev")
        + " [0-9\\.\\-+]*\n\\("
        + ".*"
        + " / [0-9\\.]*[kmKM]* "
        + bundle.getString("SGFParse.playouts")
        + "\\)\\n"
        + bundle.getString("SGFParse.komi")
        + ".*";
  }

  private static String mergeAnalysisComment(String comment, String summary) {
    return mergeAnalysisComment(comment, summary, Lizzie.resourceBundle);
  }

  private static String mergeAnalysisComment(
      String comment, String summary, ResourceBundle bundle) {
    WinrateCommentSplit split = splitWinrateComment(comment, bundle);
    if (!split.matchInfo.isEmpty()) return comment.replace(split.matchInfo, summary);
    if (summary.isEmpty()) return comment;
    return comment.isEmpty() ? summary : comment + "\n\n" + summary;
  }

  /**
   * Format Comment with following format: Move <Move number> <Winrate> (<Last Move Rate
   * Difference>) (<Weight name> / <Playouts>)
   */
  public static String formatComment(BoardHistoryNode node) {
    return formatComment(node, SaveContext.current());
  }

  private static String formatComment(BoardHistoryNode node, SaveContext saveContext) {
    //    if (node.getData().commented) return node.getData().comment;
    //    node.getData().commented = true;
    BoardData data = node.getData();
    String engine = node.getData().engineName;
    engine = engine.replaceAll(" ", "");
    String playouts = Utils.getPlayoutsString(data.getPlayouts());

    // Last winrate
    Optional<BoardData> lastNode = node.previous().flatMap(n -> Optional.of(n.getData()));
    boolean validLastWinrate = lastNode.isPresent();

    double lastWR = validLastWinrate ? saveContext.winrate(lastNode.get(), false) : 50;
    // Current winrate
    boolean validWinrate = (data.getPlayouts() > 0);
    double curWR;
    if (saveContext.options().winrateAlwaysBlack()) {
      curWR = validWinrate ? saveContext.winrate(data, false) : lastWR;
    } else {
      curWR = validWinrate ? saveContext.winrate(data, false) : 100 - lastWR;
    }

    // Last move difference winrate
    String lastMoveDiff = "";
    if (validLastWinrate && validWinrate) {
      //      if (Lizzie.config.handicapInsteadOfWinrate) {
      //        double currHandicapedWR = Leelaz.winrateToHandicap(100 - curWR);
      //        double lastHandicapedWR = Leelaz.winrateToHandicap(lastWR);
      //        lastMoveDiff = String.format(Locale.ENGLISH,": %.2f", currHandicapedWR -
      // lastHandicapedWR);
      //      } else {
      double diff;
      if (saveContext.options().winrateAlwaysBlack()) {
        diff = lastWR - curWR;
      } else {
        diff = 100 - lastWR - curWR;
      }
      lastMoveDiff =
          String.format(Locale.ENGLISH, "(%s%.1f%%)", diff > 0 ? "+" : "-", Math.abs(diff));
      // }
    }
    // if (Lizzie.engineManager.isEngineGame && node.moveNumberOfNode() <= 3) {
    // lastMoveDiff = "";
    // }
    //    String wf = "%s棋 胜率: %s %s\n(%s / %s 计算量)";
    boolean blackWinrate = !data.analysisBlackToPlay(false) || saveContext.options().winrateAlwaysBlack();
    String nc = "";
    //        String.format(
    //            wf,
    //           blackWinrate ? "黑" : "白",
    //            String.format(Locale.ENGLISH,"%.1f%%", 100 - curWR),
    //            lastMoveDiff,
    //            engine,
    //            playouts);

    if (data.isKataData) {
      String diffScore = "";
      if (validLastWinrate && validWinrate) {
        if (node.previous().get().getData().getPlayouts() > 0) {
          double diff = -data.scoreMean - node.previous().get().getData().scoreMean;
          if (saveContext.options().winrateAlwaysBlack() && data.analysisBlackToPlay(false))
            diff = -diff;
          diffScore =
              String.format(Locale.ENGLISH, "(%s%.1f)", diff > 0 ? "+" : "-", Math.abs(diff));
        }
      }
      if (data.isSaiData) {
        double score = -node.getData().scoreMean;
        if (saveContext.options().scoreLeadWithKomi()) {
          if (data.analysisBlackToPlay(false)) {
            score = score - saveContext.komi();
            // else score = -score;
          } else {
            score = score + saveContext.komi();
            //   else score = -score;
          }
          if (saveContext.options().winrateAlwaysBlack() && data.analysisBlackToPlay(false)) score = -score;
        } else if (saveContext.options().winrateAlwaysBlack() && data.analysisBlackToPlay(false)) score = -score;
        String wf =
            "%s "
                + saveContext.options().bundle().getString("SGFParse.winrate")
                + " %s %s\n"
                + (saveContext.options().scoreLeadWithKomi()
                    ? saveContext.options().bundle().getString("SGFParse.leadWithKomi")
                    : saveContext.options().bundle().getString("SGFParse.leadJustScore"))
                + " %s %s\n(%s / %s "
                + saveContext.options().bundle().getString("SGFParse.playouts")
                + ")\n"
                + saveContext.options().bundle().getString("SGFParse.komi")
                + " %s";
        nc =
            String.format(
                wf,
                blackWinrate
                    ? saveContext.options().bundle().getString("SGFParse.black")
                    : saveContext.options().bundle().getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data)));
      } else {
        double score = -node.getData().scoreMean;
        if (saveContext.options().scoreLeadWithKomi()) {
          if (data.analysisBlackToPlay(false)) {
            score = score - saveContext.komi();
            // else score = -score;
          } else {
            score = score + saveContext.komi();
            //   else score = -score;
          }
          if (saveContext.options().winrateAlwaysBlack() && data.analysisBlackToPlay(false)) score = -score;
        } else if (saveContext.options().winrateAlwaysBlack() && data.analysisBlackToPlay(false)) score = -score;
        String wf =
            "%s "
                + saveContext.options().bundle().getString("SGFParse.winrate")
                + " %s %s\n"
                + (saveContext.options().scoreLeadWithKomi()
                    ? saveContext.options().bundle().getString("SGFParse.leadWithKomi")
                    : saveContext.options().bundle().getString("SGFParse.leadJustScore"))
                + " %s %s "
                + saveContext.options().bundle().getString("SGFParse.stdev")
                + " %s\n(%s / %s "
                + saveContext.options().bundle().getString("SGFParse.playouts")
                + ")\n"
                + saveContext.options().bundle().getString("SGFParse.komi")
                + " %s";
        double scoreStdev = node.getData().scoreStdev;

        nc =
            String.format(
                wf,
                blackWinrate
                    ? saveContext.options().bundle().getString("SGFParse.black")
                    : saveContext.options().bundle().getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                String.format(Locale.ENGLISH, "%.1f", scoreStdev),
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data))
                    + getPdaWrnString(data.pda, data.primaryAnalysisProvenance(), saveContext.options().bundle()));
      }
    } else {
      String wf =
          "%s "
              + saveContext.options().bundle().getString("SGFParse.winrate")
              + " %s %s\n(%s / %s "
              + saveContext.options().bundle().getString("SGFParse.playouts")
              + ")\n"
              + saveContext.options().bundle().getString("SGFParse.komi")
              + " %s";

      nc =
          String.format(
              wf,
              blackWinrate
                  ? saveContext.options().bundle().getString("SGFParse.black")
                  : saveContext.options().bundle().getString("SGFParse.white"),
              String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
              lastMoveDiff,
              engine,
              playouts,
              String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data)));
    }

    return mergeAnalysisComment(data.comment, nc, saveContext.options().bundle());
  }

  private static String formatCommentForGame(BoardHistoryNode node) {
    return formatCommentForGame(node, SaveContext.current());
  }

  private static String formatCommentForGame(BoardHistoryNode node, SaveContext saveContext) {
    //    if (node.getData().commented) return node.getData().comment;
    //    node.getData().commented = true;

    BoardData data = node.getData();
    String engine = node.getData().engineName;
    engine = engine.replaceAll(" ", "");
    String playouts = Utils.getPlayoutsString(data.getPlayouts());

    // Last winrate
    Optional<BoardData> lastNode = node.previous().flatMap(n -> Optional.of(n.getData()));
    boolean validLastWinrate = lastNode.isPresent();

    double lastWR = validLastWinrate ? lastNode.get().winrate : 50;
    // Current winrate
    boolean validWinrate = (data.getPlayouts() > 0);
    double curWR;
    //  if (saveContext.options().winrateAlwaysBlack()) {
    curWR = validWinrate ? data.winrate : lastWR;
    //  } else {
    //    curWR = validWinrate ? saveContext.winrate(data, false) : 100 - lastWR;
    //  }

    // Last move difference winrate
    String lastMoveDiff = "";
    if (validLastWinrate && validWinrate) {
      //      if (Lizzie.config.handicapInsteadOfWinrate) {
      //        double currHandicapedWR = Leelaz.winrateToHandicap(100 - curWR);
      //        double lastHandicapedWR = Leelaz.winrateToHandicap(lastWR);
      //        lastMoveDiff = String.format(Locale.ENGLISH,": %.2f", currHandicapedWR -
      // lastHandicapedWR);
      //      } else {
      double diff;
      //  if (saveContext.options().winrateAlwaysBlack()) {
      //      diff = lastWR - curWR;
      //   } else {
      diff = 100 - lastWR - curWR;
      //   }
      lastMoveDiff =
          String.format(Locale.ENGLISH, "(%s%.1f%%)", diff < 0 ? "+" : "-", Math.abs(diff));
      // }
    }
    // if (Lizzie.engineManager.isEngineGame && node.moveNumberOfNode() <= 3) {
    // lastMoveDiff = "";
    // }
    //    String wf = "%s棋 胜率: %s %s\n(%s / %s 计算量)";
    boolean blackWinrate = !data.blackToPlay;
    String nc = "";
    //        String.format(
    //            wf,
    //           blackWinrate ? "黑" : "白",
    //            String.format(Locale.ENGLISH,"%.1f%%", 100 - curWR),
    //            lastMoveDiff,
    //            engine,
    //            playouts);

    if (data.isKataData) {
      String diffScore = "";
      // if (validLastWinrate && validWinrate) {
      if (node.previous().isPresent()) {
        if (node.previous().get().getData().getPlayouts() > 0) {
          double diff = -data.scoreMean - node.previous().get().getData().scoreMean;
          if (saveContext.options().winrateAlwaysBlack() && saveContext.blackToPlay())
            diff = -diff;
          // if (!blackWinrate) diff = -diff;
          diffScore =
              String.format(Locale.ENGLISH, "(%s%.1f)", diff > 0 ? "+" : "-", Math.abs(diff));
        }
        //  }
        else if (node.previous().get().previous().isPresent()
            && node.previous().get().previous().get().getData().getPlayouts() > 0) {
          double diff =
              -data.scoreMean + node.previous().get().previous().get().getData().scoreMean;
          if (!blackWinrate) diff = -diff;
          diffScore =
              String.format(Locale.ENGLISH, "(%s%.1f)", diff > 0 ? "+" : "-", Math.abs(diff));
        }
      }
      if (data.isSaiData) {
        double score = data.scoreMean;
        if (saveContext.options().scoreLeadWithKomi()) {
          if (data.blackToPlay) {
            score = score + saveContext.komi();
            // else score = -score;
          } else {
            score = score - saveContext.komi();
            //   else score = -score;
          }
        }
        String wf =
            "%s "
                + saveContext.options().bundle().getString("SGFParse.winrate")
                + " %s %s\n"
                + (saveContext.options().scoreLeadWithKomi()
                    ? saveContext.options().bundle().getString("SGFParse.leadWithKomi")
                    : saveContext.options().bundle().getString("SGFParse.leadJustScore"))
                + " %s %s\n(%s / %s "
                + saveContext.options().bundle().getString("SGFParse.playouts")
                + ")\n"
                + saveContext.options().bundle().getString("SGFParse.komi")
                + " %s";
        nc =
            String.format(
                wf,
                blackWinrate
                    ? saveContext.options().bundle().getString("SGFParse.white")
                    : saveContext.options().bundle().getString("SGFParse.black"),
                String.format(Locale.ENGLISH, "%.1f%%", curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data)));
      } else {
        double score = node.getData().scoreMean;
        if (saveContext.options().scoreLeadWithKomi()) {
          if (data.blackToPlay) {
            score = score + saveContext.komi();
            // else score = -score;
          } else {
            score = score - saveContext.komi();
            //   else score = -score;
          }
        }
        String wf =
            "%s "
                + saveContext.options().bundle().getString("SGFParse.winrate")
                + " %s %s\n"
                + (saveContext.options().scoreLeadWithKomi()
                    ? saveContext.options().bundle().getString("SGFParse.leadWithKomi")
                    : saveContext.options().bundle().getString("SGFParse.leadJustScore"))
                + " %s %s "
                + saveContext.options().bundle().getString("SGFParse.stdev")
                + " %s\n(%s / %s "
                + saveContext.options().bundle().getString("SGFParse.playouts")
                + ")\n"
                + saveContext.options().bundle().getString("SGFParse.komi")
                + " %s";
        double scoreStdev = node.getData().scoreStdev;
        nc =
            String.format(
                wf,
                blackWinrate
                    ? saveContext.options().bundle().getString("SGFParse.white")
                    : saveContext.options().bundle().getString("SGFParse.black"),
                String.format(Locale.ENGLISH, "%.1f%%", curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                String.format(Locale.ENGLISH, "%.1f", scoreStdev),
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data))
                    + getPdaWrnString(data.pda, data.primaryAnalysisProvenance(), saveContext.options().bundle()));
      }
    } else {
      String wf =
          "%s "
              + saveContext.options().bundle().getString("SGFParse.winrate")
              + " %s %s\n(%s / %s "
              + saveContext.options().bundle().getString("SGFParse.playouts")
              + ")\n"
              + saveContext.options().bundle().getString("SGFParse.komi")
              + " %s";

      nc =
          String.format(
              wf,
              blackWinrate
                  ? saveContext.options().bundle().getString("SGFParse.white")
                  : saveContext.options().bundle().getString("SGFParse.black"),
              String.format(Locale.ENGLISH, "%.1f%%", curWR),
              lastMoveDiff,
              engine,
              playouts,
              String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data)));
    }
    return nc;
  }

  public static String formatCommentPk(BoardHistoryNode node) {
    return formatCommentPk(node, SaveContext.current());
  }

  private static String formatCommentPk(BoardHistoryNode node, SaveContext saveContext) {
    if (!saveContext.engineGameHistory() && !node.previous().isPresent()) return "";
    BoardData data = node.getData();
    String engine = node.getData().engineName;
    engine = engine.replaceAll(" ", "");
    String playouts = Utils.getPlayoutsString(data.getPlayouts());
    // Last winrate
    Optional<BoardData> lastNode;
    if (node.previous().isPresent())
      lastNode = node.previous().get().previous().flatMap(n -> Optional.of(n.getData()));
    else lastNode = Optional.empty();
    boolean validLastWinrate = lastNode.isPresent();
    double lastWR = validLastWinrate ? lastNode.get().winrate : 50;
    boolean validWinrate = (data.getPlayouts() > 0);
    double curWR;
    curWR = validWinrate ? data.winrate : 100 - lastWR;
    String lastMoveDiff = "";
    if (validLastWinrate && validWinrate) {
      double diff = curWR - lastWR;
      lastMoveDiff =
          String.format(Locale.ENGLISH, "(%s%.1f%%)", diff > 0 ? "+" : "-", Math.abs(diff));
    }
    boolean blackWinrate = data.blackToPlay;
    String nc = "";
    if (data.isKataData) {
      String diffScore = "";
      if (validLastWinrate && validWinrate) {
        if (node.previous().get().previous().get().getData().getPlayouts() > 0) {
          double diff = data.scoreMean - node.previous().get().previous().get().getData().scoreMean;
          diffScore =
              String.format(Locale.ENGLISH, "(%s%.1f)", diff > 0 ? "+" : "-", Math.abs(diff));
        }
      }
      if (data.isSaiData) {
        double score = data.scoreMean;
        if (saveContext.options().scoreLeadWithKomi()) {
          if (data.blackToPlay) {
            score = score + saveContext.komi();
            // else score = -score;
          } else {
            score = score - saveContext.komi();
            //   else score = -score;
          }
        }
        String wf =
            "%s "
                + saveContext.options().bundle().getString("SGFParse.winrate")
                + " %s %s\n"
                + (saveContext.options().scoreLeadWithKomi()
                    ? saveContext.options().bundle().getString("SGFParse.leadWithKomi")
                    : saveContext.options().bundle().getString("SGFParse.leadJustScore"))
                + " %s %s\n(%s / %s "
                + saveContext.options().bundle().getString("SGFParse.playouts")
                + ")\n"
                + saveContext.options().bundle().getString("SGFParse.komi")
                + " %s";
        nc =
            String.format(
                wf,
                blackWinrate
                    ? saveContext.options().bundle().getString("SGFParse.black")
                    : saveContext.options().bundle().getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data)));
      } else {
        double score = node.getData().scoreMean;
        if (saveContext.options().scoreLeadWithKomi()) {
          if (data.blackToPlay) {
            score = score + saveContext.komi();
            // else score = -score;
          } else {
            score = score - saveContext.komi();
            //   else score = -score;
          }
        }
        String wf =
            "%s "
                + saveContext.options().bundle().getString("SGFParse.winrate")
                + " %s %s\n"
                + (saveContext.options().scoreLeadWithKomi()
                    ? saveContext.options().bundle().getString("SGFParse.leadWithKomi")
                    : saveContext.options().bundle().getString("SGFParse.leadJustScore"))
                + " %s %s "
                + saveContext.options().bundle().getString("SGFParse.stdev")
                + " %s\n(%s / %s "
                + saveContext.options().bundle().getString("SGFParse.playouts")
                + ")\n"
                + saveContext.options().bundle().getString("SGFParse.komi")
                + " %s";
        double scoreStdev = node.getData().scoreStdev;
        nc =
            String.format(
                wf,
                blackWinrate
                    ? saveContext.options().bundle().getString("SGFParse.black")
                    : saveContext.options().bundle().getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                String.format(Locale.ENGLISH, "%.1f", scoreStdev),
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data))
                    + getPdaWrnString(data.pda, data.primaryAnalysisProvenance(), saveContext.options().bundle()));
      }
    } else {
      String wf =
          "%s "
              + saveContext.options().bundle().getString("SGFParse.winrate")
              + " %s %s\n(%s / %s "
              + saveContext.options().bundle().getString("SGFParse.playouts")
              + ")\n"
              + saveContext.options().bundle().getString("SGFParse.komi")
              + " %s";

      nc =
          String.format(
              wf,
              blackWinrate
                  ? saveContext.options().bundle().getString("SGFParse.black")
                  : saveContext.options().bundle().getString("SGFParse.white"),
              String.format(Locale.ENGLISH, "%.1f%%", curWR),
              lastMoveDiff,
              engine,
              playouts,
              String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data)));
    }

    return mergeAnalysisComment(data.comment, nc, saveContext.options().bundle());
  }

  private static String getPdaWrnString(double pda, AnalysisProvenance provenance, ResourceBundle bundle) {
    String line = pda != 0 ? " " + bundle.getString("SGFParse.pda") + pda : "";
    Double wrn = provenance == null ? null : provenance.wrn();
    line += wrn != null ? " " + bundle.getString("SGFParse.wrn") + wrn : "";
    return line;
  }

  private static String formatComment2(BoardHistoryNode node, SaveContext saveContext) {
    //    if (node.getData().commented2) return node.getData().comment;
    //    node.getData().commented2 = true;
    BoardData data = node.getData();
    String engine = node.getData().engineName2;
    engine = engine.replaceAll(" ", "");
    String playouts = Utils.getPlayoutsString(data.getPlayouts2());

    Optional<BoardData> lastNode = node.previous().flatMap(n -> Optional.of(n.getData()));
    boolean validLastWinrate = lastNode.isPresent();

    double lastWR = validLastWinrate ? saveContext.winrate(lastNode.get(), true) : 50;
    if (saveContext.engineGameHistory() && node.moveNumberOfNode() > 2) {
      lastWR = 100 - lastWR;
    }
    boolean validWinrate = (data.getPlayouts2() > 0);
    double curWR;
    if (saveContext.options().winrateAlwaysBlack()) {
      curWR = validWinrate ? saveContext.winrate(data, true) : lastWR;
    } else {
      curWR = validWinrate ? saveContext.winrate(data, true) : 100 - lastWR;
    }

    String lastMoveDiff = "";
    if (validLastWinrate && validWinrate) {
      //	      if (Lizzie.config.handicapInsteadOfWinrate) {
      //	        double currHandicapedWR = Leelaz.winrateToHandicap(100 - curWR);
      //	        double lastHandicapedWR = Leelaz.winrateToHandicap(lastWR);
      //	        lastMoveDiff = String.format(Locale.ENGLISH,": %.2f", currHandicapedWR -
      // lastHandicapedWR);
      //	      } else {
      double diff;
      if (saveContext.options().winrateAlwaysBlack()) {
        diff = lastWR - curWR;
      } else {
        diff = 100 - lastWR - curWR;
      }
      lastMoveDiff =
          String.format(Locale.ENGLISH, "(%s%.1f%%)", diff > 0 ? "+" : "-", Math.abs(diff));
      //  }
    }
    boolean blackWinrate = !data.analysisBlackToPlay(true) || saveContext.options().winrateAlwaysBlack();
    String nc = "";

    if (data.isKataData2) {
      String diffScore = "";
      if (validLastWinrate && validWinrate) {
        if (node.previous().get().getData().getPlayouts() > 0) {
          double diff = data.scoreMean + node.previous().get().getData().scoreMean;
          if (saveContext.options().winrateAlwaysBlack() && data.analysisBlackToPlay(true))
            diff = -diff;
          diffScore =
              String.format(Locale.ENGLISH, "(%s%.1f)", diff > 0 ? "+" : "-", Math.abs(diff));
        }
      }
      if (data.isSaiData2) {
        double score = -node.getData().scoreMean;
        if (saveContext.options().scoreLeadWithKomi()) {
          if (data.analysisBlackToPlay(true)) {
            score = score - saveContext.komi();
            // else score = -score;
          } else {
            score = score + saveContext.komi();
            //   else score = -score;
          }
          if (saveContext.options().winrateAlwaysBlack() && data.analysisBlackToPlay(true)) score = -score;
        } else if (saveContext.options().winrateAlwaysBlack() && data.analysisBlackToPlay(true)) score = -score;
        String wf =
            "%s "
                + saveContext.options().bundle().getString("SGFParse.winrate")
                + " %s %s\n"
                + (saveContext.options().scoreLeadWithKomi()
                    ? saveContext.options().bundle().getString("SGFParse.leadWithKomi")
                    : saveContext.options().bundle().getString("SGFParse.leadJustScore"))
                + " %s %s\n(%s / %s "
                + saveContext.options().bundle().getString("SGFParse.playouts")
                + ")\n"
                + saveContext.options().bundle().getString("SGFParse.komi")
                + " %s";
        nc =
            String.format(
                wf,
                blackWinrate
                    ? saveContext.options().bundle().getString("SGFParse.black")
                    : saveContext.options().bundle().getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data)));
      } else {
        double score = -node.getData().scoreMean;
        if (saveContext.options().scoreLeadWithKomi()) {
          if (data.analysisBlackToPlay(true)) {
            score = score - saveContext.komi();
            // else score = -score;
          } else {
            score = score + saveContext.komi();
            //   else score = -score;
          }
          if (saveContext.options().winrateAlwaysBlack() && data.analysisBlackToPlay(true)) score = -score;
        } else if (saveContext.options().winrateAlwaysBlack() && data.analysisBlackToPlay(true)) score = -score;
        String wf =
            "%s "
                + saveContext.options().bundle().getString("SGFParse.winrate")
                + " %s %s\n"
                + (saveContext.options().scoreLeadWithKomi()
                    ? saveContext.options().bundle().getString("SGFParse.leadWithKomi")
                    : saveContext.options().bundle().getString("SGFParse.leadJustScore"))
                + " %s %s "
                + saveContext.options().bundle().getString("SGFParse.stdev")
                + " %s\n(%s / %s "
                + saveContext.options().bundle().getString("SGFParse.playouts")
                + ")\n"
                + saveContext.options().bundle().getString("SGFParse.komi")
                + " %s";
        double scoreStdev = node.getData().scoreStdev2;
        nc =
            String.format(
                wf,
                blackWinrate
                    ? saveContext.options().bundle().getString("SGFParse.black")
                    : saveContext.options().bundle().getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                String.format(Locale.ENGLISH, "%.1f", scoreStdev),
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data))
                    + getPdaWrnString(
                        data.pda2, data.secondaryAnalysisProvenance(), saveContext.options().bundle()));
      }
    } else {
      String wf =
          "%s "
              + saveContext.options().bundle().getString("SGFParse.winrate")
              + " %s %s\n(%s / %s "
              + saveContext.options().bundle().getString("SGFParse.playouts")
              + ")\n"
              + saveContext.options().bundle().getString("SGFParse.komi")
              + " %s";

      nc =
          String.format(
              wf,
              blackWinrate
                  ? saveContext.options().bundle().getString("SGFParse.black")
                  : saveContext.options().bundle().getString("SGFParse.white"),
              String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
              lastMoveDiff,
              engine,
              playouts,
              String.format(Locale.ENGLISH, "%.1f", saveContext.komiFor(data)));
    }

    return mergeAnalysisComment(data.comment, nc, saveContext.options().bundle());
  }

  /** Format Comment with following format: <Winrate> <Playouts> */
  private static String formatNodeData(BoardHistoryNode node) {
    BoardData data = node.getData();

    // Playouts
    String playouts = Utils.getPlayoutsString(data.getPlayouts());

    // Last winrate
    Optional<BoardData> lastNode = node.previous().flatMap(n -> Optional.of(n.getData()));
    boolean validLastWinrate = lastNode.map(SGFParser::hasPrimaryAnalysisPayload).orElse(false);
    double lastWR = validLastWinrate ? lastNode.get().winrate : 50;

    // Current winrate
    boolean validWinrate = hasPrimaryAnalysisPayload(data);
    double curWR = validWinrate ? data.winrate : 100 - lastWR;
    String curWinrate = "";
    curWinrate = String.format(Locale.ENGLISH, "%.1f", 100 - curWR);

    StringBuilder header = formatAnalysisHeader(
        data.engineName,
        curWinrate,
        playouts,
        data.isKataData,
        data.analysisHeaderSlots,
        data.scoreMean,
        data.scoreStdev,
        data.pda,
        data.rootVisits, data.primaryAnalysisProvenance(),
        data.analysisBlackToPlay(false) == data.blackToPlay ? null : data.analysisBlackToPlay(false));
    return formatAnalysisPayload(header, data, false);
  }

  private static String formatNodeData2(BoardHistoryNode node) {
    BoardData data = node.getData();

    // Playouts
    String playouts = Utils.getPlayoutsString(data.getPlayouts2());

    // Last winrate
    Optional<BoardData> lastNode = node.previous().flatMap(n -> Optional.of(n.getData()));
    boolean validLastWinrate = lastNode.map(SGFParser::hasSecondaryAnalysisPayload).orElse(false);
    double lastWR = validLastWinrate ? lastNode.get().winrate2 : 50;

    // Current winrate
    boolean validWinrate = hasSecondaryAnalysisPayload(data);
    double curWR = validWinrate ? data.winrate2 : 100 - lastWR;
    String curWinrate = "";
    curWinrate = String.format(Locale.ENGLISH, "%.1f", 100 - curWR);

    StringBuilder header = formatAnalysisHeader(
        data.engineName2,
        curWinrate,
        playouts,
        data.isKataData2,
        data.analysisHeaderSlots2,
        data.scoreMean2,
        data.scoreStdev2,
        data.pda2,
        data.rootVisits2, data.secondaryAnalysisProvenance(),
        data.analysisBlackToPlay(true) == data.blackToPlay ? null : data.analysisBlackToPlay(true));
    return formatAnalysisPayload(header, data, true);
  }

  private static StringBuilder formatAnalysisHeader(
      String engineName,
      String curWinrate,
      String playouts,
      boolean kataData,
      int headerSlots,
      double scoreMean,
      double scoreStdev,
      double pda,
      int rootVisits, AnalysisProvenance provenance, Boolean analysisTurn) {
    Double wrn = provenance == null ? null : provenance.wrn();
    boolean includeWrn = isHeaderWrn(wrn);
    StringBuilder header = new StringBuilder();
    header.append(engineName).append(" ").append(curWinrate).append(" ").append(playouts);
    if (kataData || rootVisits >= 0 || includeWrn) {
      // Named extensions follow all six numeric/label slots, including in short legacy headers.
      boolean hasExplicitHeaderSlots = rootVisits < 0 && !includeWrn && headerSlots > 0;
      boolean includeScoreStdev = !hasExplicitHeaderSlots || headerSlots >= 5;
      boolean includePda = rootVisits >= 0 || includeWrn
          || (hasExplicitHeaderSlots ? headerSlots >= 6 : Double.compare(pda, 0) != 0);
      header.append(" ").append(formatAnalysisScalar(scoreMean));
      if (includeScoreStdev) {
        header.append(" ").append(formatAnalysisScalar(scoreStdev));
      }
      if (includePda) {
        header.append(" ").append(formatAnalysisScalar(pda));
      }
    }
    if (rootVisits >= 0) header.append(" rootVisits=").append(rootVisits);
    if (includeWrn) header.append(" wrn=").append(wrn);
    if (analysisTurn != null) header.append(" analysisTurn=").append(analysisTurn ? "B" : "W");
    return header;
  }

  private static String formatAnalysisPayload(
      StringBuilder header, BoardData data, boolean secondary) {
    int candidateCount = (secondary ? data.bestMoves2 : data.bestMoves).size();
    boolean hasBestMoves = candidateCount > 0;
    List<Double> estimateArray = secondary ? data.estimateArray2 : data.estimateArray;
    boolean hasOwnership = estimateArray != null && !estimateArray.isEmpty();
    if (!hasBestMoves && !hasOwnership) {
      return header.toString();
    }
    // Reserve a short-PV allowance per candidate without formatting or scanning its tokens twice.
    // Cap speculative reservation for imported lists; long PVs and ownership grow normally.
    long estimatedLength = header.length() + 1L + 128L * candidateCount
        + (hasOwnership ? 10L + 8L * estimateArray.size() : 0);
    header.ensureCapacity((int) Math.min(estimatedLength, 64 * 1024));
    // Candidates write into the canonical payload; KGP still hashes its completed unescaped text.
    header.append('\n');
    if (hasBestMoves) {
      if (secondary) data.appendBestMoves2(header);
      else data.appendBestMoves(header);
    }
    if (hasOwnership) {
      header.append(" ownership");
      for (Double value : estimateArray) {
        header.append(' ');
        if (value == null) header.append("null");
        else header.append(value.doubleValue());
      }
    }
    return header.toString();
  }

  private static String formatAnalysisScalar(double value) {
    return Double.toString(value);
  }

  // KGP leaves the legacy LZ grammar intact. Each slot is bound to its original LZ text so an
  // older editor that replaces only LZ cannot silently attach an obsolete source to new analysis.
  // Four records allow 10,752 UTF-16 text units each, at most six JSON bytes per unit.
  // With 2 KiB for keys, digests and numbers, Base64 needs at most 346,796 characters.
  private static final int MAX_PROVENANCE_PROPERTY_LENGTH = 393216;
  private static final Set<String> PROVENANCE_FIELDS = Set.of(
      "engineName", "engineVersion", "modelName", "modelSha256", "configSha256", "rules",
      "komi", "pda", "wrn", "humanSLProfile");

  private record AnalysisMetadataSlot(
      String payloadSha256, AnalysisProvenance analysis, AnalysisProvenance ownership,
      boolean invalidated) {}

  private record AnalysisMetadata(AnalysisMetadataSlot primary, AnalysisMetadataSlot secondary) {}

  private static void appendAnalysisMetadata(
      StringBuilder builder, BoardData data, String primaryPayload, String secondaryPayload) {
    if (primaryPayload == null && secondaryPayload == null && data.getProperty("KGP") == null) {
      return;
    }
    boolean invalidated = (primaryPayload != null && data.isChanged)
        || (secondaryPayload != null && data.isChanged2);
    // Keep valid-only files readable by clients that understand version 1 provenance.
    JSONObject metadata = new JSONObject().put("app", "KG-next")
        .put("version", invalidated ? 2 : 1);
    addAnalysisMetadataSlot(metadata, "primary", primaryPayload, data.primaryAnalysisProvenance(),
        data.estimateArray == null || data.estimateArray.isEmpty()
            ? null : data.primaryOwnershipProvenance(), data.isChanged);
    addAnalysisMetadataSlot(metadata, "secondary", secondaryPayload, data.secondaryAnalysisProvenance(),
        data.estimateArray2 == null || data.estimateArray2.isEmpty()
            ? null : data.secondaryOwnershipProvenance(), data.isChanged2);
    String property = data.getProperty("KGP");
    if (metadata.length() > 2) {
      property = Base64.getUrlEncoder().withoutPadding()
          .encodeToString(metadata.toString().getBytes(StandardCharsets.UTF_8));
    } else if (readAnalysisMetadata(property) != null) {
      // A rejected digest must not match again merely because saving normalizes the LZ text.
      property = null;
    }
    // Preserve opaque or future metadata when no current state supersedes it, without trusting it.
    if (property != null) builder.append("KGP[").append(Escaping(property)).append(']');
  }

  private static void addAnalysisMetadataSlot(
      JSONObject metadata, String key, String payload,
      AnalysisProvenance analysis, AnalysisProvenance ownership, boolean invalidated) {
    // A header-only source needs no new KGP. Preserve opaque metadata unless richer source or
    // invalidation state supersedes it. Slots with ownership/invalidation still carry their source.
    if (payload == null || (ownership == null && !invalidated
        && (analysis == null || analysis.equals(headerWrnProvenance(analysis.wrn()))))) return;
    JSONObject slot = new JSONObject().put("payloadSha256", analysisPayloadDigest(payload));
    if (analysis != null) slot.put("analysis", provenanceJson(analysis));
    if (ownership != null) slot.put("ownership", provenanceJson(ownership));
    if (invalidated) slot.put("invalidated", true);
    metadata.put(key, slot);
  }

  private static JSONObject provenanceJson(AnalysisProvenance provenance) {
    return new JSONObject()
        .put("engineName", provenance.engineName())
        .put("engineVersion", provenance.engineVersion())
        .put("modelName", provenance.modelName())
        .put("modelSha256", provenance.modelSha256())
        .put("configSha256", provenance.configSha256())
        .put("rules", provenance.rules())
        .put("komi", provenance.komi())
        .put("pda", provenance.pda())
        .put("wrn", provenance.wrn())
        .put("humanSLProfile", provenance.humanSLProfile());
  }

  private static void restoreAnalysisMetadata(
      BoardData data, boolean restorePrimaryInvalidation, boolean restoreSecondaryInvalidation) {
    data.setPrimaryAnalysisProvenance(headerWrnProvenance(data.importedAnalysisWrn(false)));
    data.setPrimaryOwnershipProvenance(null);
    data.setSecondaryAnalysisProvenance(headerWrnProvenance(data.importedAnalysisWrn(true)));
    data.setSecondaryOwnershipProvenance(null);
    // A branch import can replace one engine's payload on an already invalidated live node.
    if (restorePrimaryInvalidation) data.isChanged = false;
    if (restoreSecondaryInvalidation) data.isChanged2 = false;
    AnalysisMetadata metadata = readAnalysisMetadata(data.getProperty("KGP"));
    if (metadata == null) return;
    AnalysisMetadataSlot primary = metadata.primary();
    AnalysisMetadataSlot secondary = metadata.secondary();
    if (primary != null && primary.payloadSha256().equals(data.importedAnalysisDigest(false))) {
      data.setPrimaryAnalysisProvenance(primary.analysis());
      if (restorePrimaryInvalidation) data.isChanged = primary.invalidated();
      if (data.estimateArray != null && !data.estimateArray.isEmpty()) {
        data.setPrimaryOwnershipProvenance(primary.ownership());
      }
    }
    if (secondary != null && secondary.payloadSha256().equals(data.importedAnalysisDigest(true))) {
      data.setSecondaryAnalysisProvenance(secondary.analysis());
      if (restoreSecondaryInvalidation) data.isChanged2 = secondary.invalidated();
      if (data.estimateArray2 != null && !data.estimateArray2.isEmpty()) {
        data.setSecondaryOwnershipProvenance(secondary.ownership());
      }
    }
  }

  private static AnalysisMetadata readAnalysisMetadata(String property) {
    if (property == null || property.length() > MAX_PROVENANCE_PROPERTY_LENGTH) return null;
    try {
      String json = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(property))).toString();
      JSONTokener tokener = new JSONTokener(json);
      JSONObject metadata = new JSONObject(tokener);
      if (tokener.nextClean() != 0) return null;
      requireProvenanceKeys(metadata, Set.of("app", "version", "primary", "secondary"));
      boolean supportsInvalidation = Integer.valueOf(2).equals(metadata.opt("version"));
      if (!"KG-next".equals(metadata.opt("app"))
          || (!supportsInvalidation && !Integer.valueOf(1).equals(metadata.opt("version")))) {
        return null;
      }
      return new AnalysisMetadata(
          readAnalysisMetadataSlot(metadata, "primary", supportsInvalidation),
          readAnalysisMetadataSlot(metadata, "secondary", supportsInvalidation));
    } catch (IllegalArgumentException | JSONException | CharacterCodingException ignored) {
      // Optional metadata must never prevent legacy analysis from loading. The raw property stays.
      return null;
    }
  }

  private static AnalysisMetadataSlot readAnalysisMetadataSlot(
      JSONObject metadata, String key, boolean supportsInvalidation) {
    if (!metadata.has(key) || metadata.isNull(key)) return null;
    JSONObject slot = metadata.getJSONObject(key);
    requireProvenanceKeys(slot, supportsInvalidation
        ? Set.of("payloadSha256", "analysis", "ownership", "invalidated")
        : Set.of("payloadSha256", "analysis", "ownership"));
    String digest = slot.getString("payloadSha256");
    if (!digest.matches("[a-fA-F0-9]{64}")) throw new JSONException("Invalid payload digest");
    if (slot.has("invalidated") && !(slot.get("invalidated") instanceof Boolean)) {
      throw new JSONException("Invalid analysis invalidation flag");
    }
    return new AnalysisMetadataSlot(digest.toLowerCase(Locale.ROOT),
        readProvenance(slot, "analysis"), readProvenance(slot, "ownership"),
        Boolean.TRUE.equals(slot.opt("invalidated")));
  }

  private static AnalysisProvenance readProvenance(JSONObject slot, String key) {
    if (!slot.has(key) || slot.isNull(key)) return null;
    JSONObject value = slot.getJSONObject(key);
    requireProvenanceKeys(value, PROVENANCE_FIELDS);
    AnalysisProvenance provenance = new AnalysisProvenance(
        provenanceText(value, "engineName"), provenanceText(value, "engineVersion"),
        provenanceText(value, "modelName"), provenanceText(value, "modelSha256"),
        provenanceText(value, "configSha256"), provenanceText(value, "rules"),
        provenanceNumber(value, "komi"), provenanceNumber(value, "pda"),
        provenanceNumber(value, "wrn"), provenanceText(value, "humanSLProfile"));
    JSONObject normalized = provenanceJson(provenance);
    // Live collection drops invalid optional fields; import rejects a malformed source as a whole.
    // This avoids showing a partially trusted source from an unsupported or corrupt schema.
    for (String field : PROVENANCE_FIELDS) {
      if (field.equals("komi") || field.equals("pda") || field.equals("wrn")) continue;
      String original = provenanceText(value, field).strip();
      if (field.endsWith("Sha256")) original = original.toLowerCase(Locale.ROOT);
      if (!original.equals(normalized.getString(field))) {
        throw new JSONException("Invalid provenance text");
      }
    }
    return provenance;
  }

  private static String provenanceText(JSONObject value, String key) {
    return !value.has(key) || value.isNull(key) ? "" : value.getString(key);
  }

  private static Double provenanceNumber(JSONObject value, String key) {
    if (!value.has(key) || value.isNull(key)) return null;
    Object raw = value.get(key);
    if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
      throw new JSONException("Invalid provenance number");
    }
    return number.doubleValue();
  }

  private static void requireProvenanceKeys(JSONObject value, Set<String> allowed) {
    if (!allowed.containsAll(value.keySet())) throw new JSONException("Unknown provenance field");
  }

  private static String analysisPayloadDigest(String payload) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  public static boolean isMarkupProperty(String key) {
    return asList(markupProps).contains(key);
  }

  /**
   * Get a value with key, or the default if there is no such key
   *
   * @param key
   * @param defaultValue
   * @return
   */
  public static String getOrDefault(Map<String, List<String>> props, String key, String defaultValue) {
    List<String> values = props.get(key);
    return values == null || values.isEmpty() ? defaultValue : values.get(values.size() - 1);
  }

  /**
   * Add a key and value to the props
   *
   * @param key
   * @param value
   */
  public static void addProperty(Map<String, List<String>> props, String key, String value) {
    addPropertyValues(props, key, List.of(value));
  }

  private static void addPropertyValues(
      Map<String, List<String>> props, String key, List<String> values) {
    List<String> previous = props.get(key);
    if (previous == null) {
      props.put(key, List.copyOf(values));
    } else {
      List<String> combined = new ArrayList<>(previous.size() + values.size());
      combined.addAll(previous);
      combined.addAll(values);
      props.put(key, List.copyOf(combined));
    }
  }

  /**
   * Add the properties by mutating the props
   *
   * @return
   */
  public static void addProperties(Map<String, List<String>> props, Map<String, List<String>> addProps) {
    addProps.forEach((key, values) -> addPropertyValues(props, key, values));
  }

  /**
   * Add the properties from string
   *
   * @return
   */
  public static void addProperties(Map<String, List<String>> props, String propsStr) {
    boolean inTag = false, escaping = false;
    String tag = "";
    StringBuilder tagBuilder = new StringBuilder();
    StringBuilder tagContentBuilder = new StringBuilder();

    for (int i = 0; i < propsStr.length(); i++) {
      char c = propsStr.charAt(i);
      if (escaping) {
        tagContentBuilder.append(c);
        escaping = false;
        continue;
      }
      switch (c) {
        case '(':
          if (inTag) {
            if (i > 0) {
              tagContentBuilder.append(c);
            }
          }
          break;
        case ';':
        case ')':
          if (inTag) {
            tagContentBuilder.append(c);
          }
          break;
        case '[':
          inTag = true;
          String tagTemp = tagBuilder.toString();
          if (!tagTemp.isEmpty()) {
            tag = normalizePropertyName(tagTemp);
          }
          tagContentBuilder = new StringBuilder();
          break;
        case ']':
          inTag = false;
          tagBuilder = new StringBuilder();
          addProperty(props, tag, tagContentBuilder.toString());
          break;
        default:
          if (inTag) {
            if (c == '\\') {
              escaping = true;
              if (!tag.equals("C")) tagContentBuilder.append(c);
              continue;
            }
            tagContentBuilder.append(c);
          } else {
            if (c != '\n' && c != '\r' && c != '\t' && c != ' ') {
              tagBuilder.append(c);
            }
          }
      }
    }
  }

  public static String getResult(String filename) {
    File file = new File(filename);
    if (!file.exists() || !file.canRead()) {
      return "";
    }
    String encoding = EncodingDetector.detect(filename);
    try (FileInputStream fp = new FileInputStream(file);
        InputStreamReader reader =
            new InputStreamReader(fp, encoding.equals("WINDOWS-1252") ? "GB18030" : encoding)) {
      StringBuilder builder = new StringBuilder();
      while (reader.ready()) {
        builder.append((char) reader.read());
      }
      String value = builder.toString();
      if (value.isEmpty()) {
        Lizzie.board.isLoadingFile = false;
        return "";
      }
      return parseResult(value);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  private static String parseResult(String value) {
    int tempIndex = value.indexOf("RE[") + 3;
    String temp = value.substring(tempIndex, Math.min(value.length(), tempIndex + 20));
    return temp.substring(0, temp.indexOf("]"));
  }

  /**
   * Get properties string by the props
   *
   * @return
   */
  public static String propertiesString(Map<String, List<String>> props) {
    StringBuilder sb = new StringBuilder();
    if (props.containsKey("CA")) {
      sb.append(nodeString("CA", props.get("CA")));
    }
    props.forEach(
        (key, value) -> {
          // KGP is emitted together with the exact LZ payload it describes.
          if (!"CA".equals(key) && !"KGP".equals(key)) {
            sb.append(nodeString(key, value));
          }
        });
    return sb.toString();
  }

  /**
   * Get node string by the key and its separate raw values
   *
   * @param key
   * @param values
   * @return
   */
  public static String nodeString(String key, List<String> values) {
    StringBuilder sb = new StringBuilder(key);
    for (String value : values) sb.append('[').append(value).append(']');
    return sb.toString();
  }

  private static void processPendingPros(BoardHistoryList history, Map<String, List<String>> props) {
    props.forEach((key, values) -> values.forEach(value -> history.addNodeProperty(key, value)));
  }

  private static final class PendingNodeMetadata {
    private final AnalysisImport analysisImport;
    private String comment;
    private final Map<String, List<String>> properties = new LinkedHashMap<String, List<String>>();
    private final List<PendingAnalysisPayload> analysisPayloads =
        new ArrayList<PendingAnalysisPayload>();

    private PendingNodeMetadata(AnalysisImport analysisImport) {
      this.analysisImport = analysisImport;
    }

    private boolean isEmpty() {
      return comment == null && properties.isEmpty() && analysisPayloads.isEmpty();
    }

    private void clear() {
      comment = null;
      properties.clear();
      analysisPayloads.clear();
    }
  }

  private static final class PendingAnalysisPayload {
    private final String tag;
    private final String content;

    private PendingAnalysisPayload(String tag, String content) {
      this.tag = tag;
      this.content = content;
    }
  }

  /** Root/game information is distinct from the position metadata of the first SGF node. */
  private static boolean isRootGameProperty(String tag) {
    // FF[4] root/game-info properties, plus the existing DZ/KO game extensions.
    return switch (tag) {
      case "AP", "CA", "FF", "GM", "ST", "SZ",
          "AN", "BR", "BT", "CP", "DT", "EV", "GN", "GC", "ON", "OT", "PB", "PC",
          "PW", "RE", "RO", "RU", "SO", "TM", "US", "WR", "WT", "HA", "KM",
          "DZ", "KO" -> true;
      default -> false;
    };
  }

  private static boolean shouldBufferNodeMetadata(
      boolean currentNodeHasMove, boolean currentNodeHasSetupSnapshot) {
    // The first node may also produce a move, pass or dummy. Without an action, the node
    // boundary flushes metadata to the current position, including root setup.
    return !currentNodeHasMove && !currentNodeHasSetupSnapshot;
  }

  private static void bufferNodeMetadata(
      PendingNodeMetadata pendingNodeMetadata, String tag, String tagContent) {
    if ("C".equals(tag)) {
      pendingNodeMetadata.comment = tagContent;
      return;
    }
    addProperty(pendingNodeMetadata.properties, tag, tagContent);
  }

  private static void bufferNodeAnalysis(
      PendingNodeMetadata pendingNodeMetadata, String tag, String tagContent) {
    pendingNodeMetadata.analysisPayloads.add(new PendingAnalysisPayload(tag, tagContent));
  }

  private static void applyPendingAnalysisPayloads(
      BoardData target, PendingNodeMetadata pendingNodeMetadata) {
    if (target == null || pendingNodeMetadata.analysisPayloads.isEmpty()) {
      return;
    }
    pendingNodeMetadata.analysisPayloads.forEach(
        payload -> pendingNodeMetadata.analysisImport.applyTag(target, payload.tag, payload.content));
  }

  private static void flushPendingNodeMetadataToCurrentNode(
      BoardHistoryList history, PendingNodeMetadata pendingNodeMetadata) {
    movePendingNodeMetadataToTarget(history, history.getCurrentHistoryNode(), pendingNodeMetadata);
  }

  private static void movePendingNodeMetadataToTarget(
      BoardHistoryList history,
      BoardHistoryNode target,
      PendingNodeMetadata pendingNodeMetadata) {
    if (pendingNodeMetadata == null || pendingNodeMetadata.isEmpty()) {
      return;
    }
    if (pendingNodeMetadata.comment != null) {
      target.getData().comment = pendingNodeMetadata.comment;
    }
    pendingNodeMetadata.properties.forEach(
        (key, values) -> values.forEach(value -> addNodePropertyOnNode(history, target, key, value)));
    applyPendingAnalysisPayloads(target.getData(), pendingNodeMetadata);
    pendingNodeMetadata.clear();
  }

  private static void moveLeadingSetupMetadataToSnapshot(
      BoardHistoryList history, AnalysisImport analysisImport) {
    BoardHistoryNode snapshotNode = history.getCurrentHistoryNode();
    if (!snapshotNode.getData().isSnapshotNode() || !snapshotNode.previous().isPresent()) {
      return;
    }
    BoardHistoryNode moveNode = snapshotNode.previous().get();
    BoardData moveData = moveNode.getData();
    BoardData snapshotData = snapshotNode.getData();
    if (!moveData.comment.isEmpty()) {
      snapshotData.comment = moveData.comment;
      moveData.comment = "";
    }
    moveLeadingAnalysisPayloadToSnapshot(moveData, snapshotData);
    analysisImport.moveToSnapshot(moveData, snapshotData);
    if (moveData.getProperties().isEmpty()) {
      return;
    }
    Map<String, List<String>> properties =
        new LinkedHashMap<String, List<String>>(moveData.getProperties());
    properties.forEach((key, values) -> values.forEach(value -> snapshotData.addProperty(key, value)));
    moveData.setProperties(new LinkedHashMap<String, List<String>>());
    if (properties.containsKey("PL")) {
      applyCurrentPlayerProperty(snapshotData, getOrDefault(properties, "PL", null));
    }
    if (properties.containsKey("MN")) {
      rollbackMoveNodeMnState(moveNode);
    }
  }

  private static void moveLeadingAnalysisPayloadToSnapshot(
      BoardData moveData, BoardData snapshotData) {
    movePrimaryAnalysisPayloadToSnapshot(moveData, snapshotData);
    moveSecondaryAnalysisPayloadToSnapshot(moveData, snapshotData);
  }

  private static void movePrimaryAnalysisPayloadToSnapshot(
      BoardData moveData, BoardData snapshotData) {
    if (!hasPrimaryAnalysisPayload(moveData)) {
      return;
    }
    snapshotData.engineName = moveData.engineName;
    snapshotData.winrate = moveData.winrate;
    snapshotData.setAnalysisBlackToPlay(false, moveData.analysisBlackToPlay(false));
    snapshotData.setPlayouts(moveData.getPlayouts());
    snapshotData.rootVisits = moveData.rootVisits;
    snapshotData.bestMoves = copyMoveDataListAllowNull(moveData.bestMoves);
    snapshotData.bestMovesOutOfRange = copyMoveDataListAllowNull(moveData.bestMovesOutOfRange);
    snapshotData.estimateArray = copyEstimateArray(moveData.estimateArray);
    snapshotData.isSaiData = moveData.isSaiData;
    snapshotData.isKataData = moveData.isKataData;
    snapshotData.isChanged = moveData.isChanged;
    snapshotData.playoutsChanged = moveData.playoutsChanged;
    snapshotData.analysisHeaderSlots = moveData.analysisHeaderSlots;
    snapshotData.scoreMean = moveData.scoreMean;
    snapshotData.scoreStdev = moveData.scoreStdev;
    snapshotData.pda = moveData.pda;
    snapshotData.setPrimaryAnalysisProvenance(moveData.primaryAnalysisProvenance());
    snapshotData.setPrimaryOwnershipProvenance(moveData.primaryOwnershipProvenance());
    snapshotData.copyImportedAnalysisFrom(moveData, false);
    snapshotData.markPrimaryAnalysisRun(null);
    clearPrimaryAnalysisPayload(moveData);
  }

  private static void moveSecondaryAnalysisPayloadToSnapshot(
      BoardData moveData, BoardData snapshotData) {
    if (!hasSecondaryAnalysisPayload(moveData)) {
      return;
    }
    snapshotData.engineName2 = moveData.engineName2;
    snapshotData.winrate2 = moveData.winrate2;
    snapshotData.setAnalysisBlackToPlay(true, moveData.analysisBlackToPlay(true));
    snapshotData.setPlayouts2(moveData.getPlayouts2());
    snapshotData.rootVisits2 = moveData.rootVisits2;
    snapshotData.bestMoves2 = copyMoveDataListAllowNull(moveData.bestMoves2);
    snapshotData.bestMoves2OutOfRange = copyMoveDataListAllowNull(moveData.bestMoves2OutOfRange);
    snapshotData.estimateArray2 = copyEstimateArray(moveData.estimateArray2);
    snapshotData.isSaiData2 = moveData.isSaiData2;
    snapshotData.isKataData2 = moveData.isKataData2;
    snapshotData.isChanged2 = moveData.isChanged2;
    snapshotData.analysisHeaderSlots2 = moveData.analysisHeaderSlots2;
    snapshotData.scoreMean2 = moveData.scoreMean2;
    snapshotData.scoreStdev2 = moveData.scoreStdev2;
    snapshotData.pda2 = moveData.pda2;
    snapshotData.setSecondaryAnalysisProvenance(moveData.secondaryAnalysisProvenance());
    snapshotData.setSecondaryOwnershipProvenance(moveData.secondaryOwnershipProvenance());
    snapshotData.copyImportedAnalysisFrom(moveData, true);
    clearSecondaryAnalysisPayload(moveData);
  }

  private static boolean hasPrimaryAnalysisPayload(BoardData data) {
    return data.getPlayouts() > 0
        || !Utils.isBlank(data.engineName)
        || (data.bestMoves != null && !data.bestMoves.isEmpty())
        || data.isKataData
        || (data.estimateArray != null && !data.estimateArray.isEmpty());
  }

  private static boolean hasSecondaryAnalysisPayload(BoardData data) {
    return data.getPlayouts2() > 0
        || !Utils.isBlank(data.engineName2)
        || (data.bestMoves2 != null && !data.bestMoves2.isEmpty())
        || data.isKataData2
        || (data.estimateArray2 != null && !data.estimateArray2.isEmpty());
  }

  private static void clearPrimaryAnalysisPayload(BoardData data) {
    data.setAnalysisBlackToPlay(false, null);
    data.engineName = "";
    data.winrate = 50;
    data.setPlayouts(0);
    data.bestMoves = new ArrayList<MoveData>();
    data.bestMovesOutOfRange = new ArrayList<MoveData>();
    data.estimateArray = null;
    data.isSaiData = false;
    data.isKataData = false;
    data.isChanged = false;
    data.playoutsChanged = false;
    data.analysisHeaderSlots = 0;
    data.scoreMean = 0;
    data.scoreStdev = 0;
    data.pda = 0;
    data.setPrimaryAnalysisProvenance(null);
    data.setPrimaryOwnershipProvenance(null);
    data.setImportedAnalysis(false, null, null);
    data.markPrimaryAnalysisRun(null);
  }

  private static void clearSecondaryAnalysisPayload(BoardData data) {
    data.setAnalysisBlackToPlay(true, null);
    data.engineName2 = "";
    data.winrate2 = 50;
    data.setPlayouts2(0);
    data.bestMoves2 = new ArrayList<MoveData>();
    data.bestMoves2OutOfRange = new ArrayList<MoveData>();
    data.estimateArray2 = null;
    data.isSaiData2 = false;
    data.isKataData2 = false;
    data.isChanged2 = false;
    data.analysisHeaderSlots2 = 0;
    data.scoreMean2 = 0;
    data.scoreStdev2 = 0;
    data.pda2 = 0;
    data.setSecondaryAnalysisProvenance(null);
    data.setSecondaryOwnershipProvenance(null);
    data.setImportedAnalysis(true, null, null);
  }

  private static List<MoveData> copyMoveDataListAllowNull(List<MoveData> values) {
    return values == null ? null : new ArrayList<MoveData>(values);
  }

  private static List<Double> copyEstimateArray(List<Double> values) {
    return BoardData.compactEstimateArray(values);
  }

  private static void rollbackMoveNodeMnState(BoardHistoryNode moveNode) {
    BoardData moveData = moveNode.getData();
    if (moveData.isMoveNode()) {
      moveNode.resetMoveNumberList(moveData.moveNumber);
    }
    moveData.moveMNNumber = -1;
  }

  private static boolean isRootSetupContext(
      BoardHistoryList history, boolean moveStart, boolean allowRootSetupOnCurrentNode) {
    return allowRootSetupOnCurrentNode
        && !moveStart
        && history != null
        && !history.getCurrentHistoryNode().previous().isPresent();
  }

  private static boolean isRootSetupContext(BoardHistoryList history, boolean moveStart) {
    return isRootSetupContext(history, moveStart, true);
  }

  private static BoardHistoryNode getSetupPropertyTargetNode(
      BoardHistoryList history, boolean currentNodeHasSetupSnapshot) {
    BoardHistoryNode current = history.getCurrentHistoryNode();
    if (!currentNodeHasSetupSnapshot || !current.getData().isHistoryActionNode()) {
      return current;
    }
    if (!current.previous().isPresent()) {
      return current;
    }
    BoardHistoryNode previous = current.previous().get();
    if (!previous.getData().isSnapshotNode()) {
      return current;
    }
    return previous;
  }

  private static void runOnNode(
      BoardHistoryList history, BoardHistoryNode target, Runnable action) {
    BoardHistoryNode original = history.getCurrentHistoryNode();
    if (original == target) {
      action.run();
      return;
    }
    history.setHead(target);
    try {
      action.run();
    } finally {
      history.setHead(original);
    }
  }

  private static void addNodePropertyOnNode(
      BoardHistoryList history, BoardHistoryNode target, String key, String value) {
    runOnNode(history, target, () -> history.addNodeProperty(key, value));
  }

  private static void addExtraStoneOnNode(
      BoardHistoryList history, BoardHistoryNode target, int x, int y, Stone color) {
    runOnNode(history, target, () -> history.addExtraStone(x, y, color));
  }

  private static void removeStoneOnNode(
      BoardHistoryList history, BoardHistoryNode target, int x, int y, Stone color) {
    removeStoneOnNode(history, target, x, y, color, true);
  }

  private static void removeStoneOnNode(
      BoardHistoryList history,
      BoardHistoryNode target,
      int x,
      int y,
      Stone color,
      boolean refreshUi) {
    runOnNode(history, target, () -> removeStone(history, x, y, color, refreshUi));
  }

  private static void removeStone(
      BoardHistoryList history, int x, int y, Stone color, boolean refreshUi) {
    if (refreshUi) {
      history.removeStone(x, y, color);
      return;
    }
    removeStoneWithoutRefresh(history, x, y);
  }

  private static void removeStoneWithoutRefresh(BoardHistoryList history, int x, int y) {
    synchronized (history) {
      if (!history.isValid(x, y)) {
        return;
      }
      int index = history.getIndex(x, y);
      Stone[] stones = history.getStones();
      if (stones[index] == Stone.EMPTY) {
        return;
      }
      BoardData data = history.getData();
      Stone oriColor = stones[index];
      stones[index] = Stone.EMPTY;
      data.zobrist.toggleStone(x, y, oriColor);
      data.moveNumberList[index] = 0;
      history.getCurrentHistoryNode().setRemovedStone();
    }
  }

  private static void applyCurrentPlayerPropertyOnNode(
      BoardHistoryList history, BoardHistoryNode target, String tagContent) {
    runOnNode(history, target, () -> applyCurrentPlayerProperty(history, tagContent));
  }

  private static void clearStartStoneState(BoardHistoryList targetHistory) {
    if (!isCurrentBoardHistory(targetHistory)) {
      return;
    }
    Lizzie.board.hasStartStone = false;
    if (Lizzie.board.startStonelist == null) {
      Lizzie.board.startStonelist = new ArrayList<Movelist>();
      return;
    }
    Lizzie.board.startStonelist.clear();
  }

  private static boolean isCurrentBoardHistory(BoardHistoryList history) {
    return Lizzie.board != null && history != null && Lizzie.board.getHistory() == history;
  }

  private static void applyRootSetupProperty(
      BoardHistoryList history, String tag, String tagContent, int[] move, Stone color) {
    applyRootSetupProperty(history, tag, tagContent, move, color, isCurrentBoardHistory(history));
  }

  private static void applyRootSetupProperty(
      BoardHistoryList history,
      String tag,
      String tagContent,
      int[] move,
      Stone color,
      boolean refreshUi) {
    clearStartStoneState(history);
    history.addNodeProperty(tag, tagContent);
    if (move == null) {
      return;
    }
    if ("AE".equals(tag)) {
      removeStone(history, move[0], move[1], color, refreshUi);
      return;
    }
    if (history.getStones()[history.getIndex(move[0], move[1])] == Stone.EMPTY) {
      history.addExtraStone(move[0], move[1], color);
    }
  }

  private static void applyRootPlayerProperty(BoardHistoryList history, String tagContent) {
    clearStartStoneState(history);
    applyCurrentPlayerProperty(history, tagContent);
  }

  private static void applyCurrentPlayerProperty(BoardHistoryList history, String tagContent) {
    history.addNodeProperty("PL", tagContent);
    applyCurrentPlayerProperty(history.getData(), tagContent);
  }

  private static void applyCurrentPlayerProperty(BoardData data, String tagContent) {
    data.blackToPlay = !"W".equalsIgnoreCase(tagContent);
  }

  private static void stabilizeRootSetupSideToPlay(BoardHistoryList history) {
    if (history == null || history.getStart() == null) {
      return;
    }
    int declaredHandicap =
        history.getGameInfo() == null ? 0 : history.getGameInfo().getHandicap();
    int[] boardSize = resolveHistoryBoardSize(history);
    stabilizeEligibleSetupSubtree(
        history.getStart(), null, declaredHandicap, boardSize[0], boardSize[1]);
  }

  private static void stabilizeEligibleSetupSubtree(
      BoardHistoryNode node, Boolean inheritedPlayer, int declaredHandicap, int width, int height) {
    if (node == null) {
      return;
    }
    BoardData data = node.getData();
    if (data == null || (data.isHistoryActionNode() && !data.dummy)) {
      return;
    }

    Boolean explicitPlayer = validPlayer(data.getProperty("PL"));
    Boolean effectivePlayer = explicitPlayer == null ? inheritedPlayer : explicitPlayer;
    if (data.isSnapshotNode()) {
      Boolean firstPlayer = firstInitialActionPlayer(node);
      data.blackToPlay = effectivePlayer != null
          ? effectivePlayer
          : firstPlayer != null
              ? firstPlayer
              : !hasCompletedInitialHandicap(node, declaredHandicap, width, height);
    }

    for (BoardHistoryNode child : node.getVariations()) {
      stabilizeEligibleSetupSubtree(child, effectivePlayer, declaredHandicap, width, height);
    }
  }

  // Turn defaults describe the completed position, not set_free_handicap restore eligibility.
  private static boolean hasCompletedInitialHandicap(
      BoardHistoryNode node, int declaredHandicap, int width, int height) {
    int handicap = declaredHandicap;
    for (BoardHistoryNode ancestor = node; ancestor != null;
        ancestor = ancestor.previous().orElse(null)) {
      BoardData data = ancestor.getData();
      if ((data.isHistoryActionNode() && !data.dummy)
          || data.getProperties().containsKey("AW") || data.getProperties().containsKey("AE")) {
        return false;
      }
      String declaration = data.getProperty("HA");
      if (declaration != null) {
        int value = parseHandicapProperty(declaration);
        if (value < 2 || (handicap != 0 && handicap != value)) return false;
        handicap = value;
      }
    }
    if (handicap < 2 || handicap >= width * height) return false;
    int blackStones = 0;
    for (Stone stone : node.getData().stones) {
      if (stone == Stone.BLACK) blackStones++;
      else if (stone != Stone.EMPTY) return false;
    }
    return blackStones == handicap;
  }

  private static Boolean validPlayer(String value) {
    if ("B".equalsIgnoreCase(value)) return true;
    if ("W".equalsIgnoreCase(value)) return false;
    return null;
  }

  private static Boolean firstInitialActionPlayer(BoardHistoryNode node) {
    BoardHistoryNode next = node.next().orElse(null);
    while (next != null) {
      BoardData data = next.getData();
      if (hasRootSetupStoneProperties(data) || validPlayer(data.getProperty("PL")) != null) {
        return null;
      }
      if (data.isHistoryActionNode() && !data.dummy) {
        return data.lastMoveColor.isBlack();
      }
      next = next.next().orElse(null);
    }
    return null;
  }

  private static void resolveImportedInitialBranches(
      BoardHistoryList history, BoardHistoryNode parent, int existingChildren) {
    Boolean inheritedPlayer = null;
    for (BoardHistoryNode ancestor = parent; ancestor != null;
        ancestor = ancestor.previous().orElse(null)) {
      BoardData data = ancestor.getData();
      if (data.isHistoryActionNode() && !data.dummy) return;
      if (inheritedPlayer == null) inheritedPlayer = validPlayer(data.getProperty("PL"));
    }
    int[] size = resolveHistoryBoardSize(history);
    for (int i = existingChildren; i < parent.numberOfChildren(); i++) {
      stabilizeEligibleSetupSubtree(parent.getVariations().get(i), inheritedPlayer,
          history.getGameInfo().getHandicap(), size[0], size[1]);
    }
  }

  private static boolean hasRootSetupStoneProperties(BoardData rootData) {
    Map<String, List<String>> properties = rootData.getProperties();
    return properties.containsKey("AB")
        || properties.containsKey("AW")
        || properties.containsKey("AE");
  }

  private static int parseHandicapProperty(String rawHandicap) {
    if (Utils.isBlank(rawHandicap)) {
      return 0;
    }
    try {
      return Integer.parseInt(rawHandicap.trim());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static void appendSetupSnapshot(
      BoardHistoryList history,
      boolean newBranch,
      Map<String, List<String>> pendingProps,
      boolean transferLeadingMoveMetadata,
      AnalysisImport analysisImport) {
    BoardData current = history.getData();
    int snapshotPlayouts = transferLeadingMoveMetadata ? current.getPlayouts() : 0;
    double snapshotWinrate = transferLeadingMoveMetadata ? current.winrate : 50;
    BoardData snapshot =
        BoardData.snapshot(
            current.stones.clone(),
            Optional.empty(),
            Stone.EMPTY,
            current.blackToPlay,
            current.zobrist.clone(),
            current.moveNumber,
            current.moveNumberList.clone(),
            current.blackCaptures,
            current.whiteCaptures,
            snapshotWinrate,
            snapshotPlayouts);
    if (!transferLeadingMoveMetadata) {
      clearPrimaryAnalysisPayload(snapshot);
      clearSecondaryAnalysisPayload(snapshot);
    }
    snapshot.moveMNNumber = current.moveMNNumber;
    history.addOrGoto(snapshot, newBranch);
    if (transferLeadingMoveMetadata) {
      moveLeadingSetupMetadataToSnapshot(history, analysisImport);
    }
    if (newBranch) {
      processPendingPros(history, pendingProps);
    }
  }

  public static String Escaping(String in) {
    return in.replace("\\", "\\\\").replace("]", "\\]");
  }

  private static boolean isGameInfoTextProperty(String tag) {
    return "PB".equals(tag) || "PW".equals(tag) || "RE".equals(tag) || "DT".equals(tag);
  }

  /** Decode GameInfo's SimpleText fields without changing the raw node-property representation. */
  private static String decodeGameInfoText(String value) {
    if (value == null) return null;
    StringBuilder text = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean escaped = c == '\\' && i + 1 < value.length();
      if (escaped) c = value.charAt(++i);
      if (c == '\r' || c == '\n') {
        if (i + 1 < value.length()
            && ((c == '\r' && value.charAt(i + 1) == '\n')
                || (c == '\n' && value.charAt(i + 1) == '\r'))) {
          i++;
        }
        if (!escaped) text.append(' ');
      } else {
        text.append(Character.isWhitespace(c) ? ' ' : c);
      }
    }
    return text.toString();
  }

  /** Options captured by the requester; a detached import never consults changing UI settings. */
  public record ParseOptions(
      boolean readKomi, boolean noCapture, boolean canSuicidal, boolean newMoveNumberInBranch,
      int limitBranchLength, int maxSuggestions, double defaultKomi) {
    public static ParseOptions capture() {
      var config = Lizzie.config;
      return new ParseOptions(
          config == null || config.readKomi, config != null && config.noCapture,
          Lizzie.leelaz != null && Lizzie.leelaz.canSuicidal,
          config != null && config.newMoveNumberInBranch,
          config == null ? 0 : config.limitBranchLength,
          config != null && config.limitMaxSuggestion > 0 && !config.showNoSuggCircle
              ? config.limitMaxSuggestion : 361,
          GameInfo.DEFAULT_KOMI);
    }
  }

  public static BoardHistoryList parseSgf(String value, boolean first) {
    return parseSgf(value, first, ParseOptions.capture());
  }

  /** Builds a private history without replacing the live board dimensions or coordinate hash table. */
  public static BoardHistoryList parseSgf(String value, boolean first, ParseOptions options) {
    SgfDocument document = SgfDocument.parse(value);
    return document == null ? null : buildHistory(document, first, options);
  }

  /** Applies captured import policy to immutable syntax; publishing the result is the caller's job. */
  public static BoardHistoryList buildHistory(SgfDocument document, boolean first, ParseOptions options) {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(document.width(), document.height()));
    history.getGameInfo().setKomiNoMenu(options.defaultKomi());
    BoardHistoryNode.runWithoutGlobalMoveSideEffects(options.newMoveNumberInBranch(),
        () -> parseValue(document, history, false, first, options));
    return history;
  }

  static int[] resolveHistoryBoardSize(BoardHistoryList history) {
    if (history == null) {
      return new int[] {Board.boardWidth, Board.boardHeight};
    }
    return resolveBoardSize(history.getStart().getData(), Board.boardWidth, Board.boardHeight);
  }

  private static int[] resolveNodeBoardSize(BoardHistoryNode node) {
    if (node == null) {
      return new int[] {Board.boardWidth, Board.boardHeight};
    }
    BoardHistoryNode root = node;
    while (root.previous().isPresent()) {
      root = root.previous().get();
    }
    return resolveBoardSize(root.getData(), Board.boardWidth, Board.boardHeight);
  }

  private static int[] resolveBoardSize(
      BoardData rootData, int fallbackBoardWidth, int fallbackBoardHeight) {
    if (rootData == null) {
      return new int[] {fallbackBoardWidth, fallbackBoardHeight};
    }
    int[] parsedFromSz = parseBoardSizeTagValue(rootData.getProperty("SZ"));
    if (parsedFromSz != null) {
      return parsedFromSz;
    }
    int boardArea = rootData.stones == null ? 0 : rootData.stones.length;
    if (boardArea <= 0) {
      return new int[] {fallbackBoardWidth, fallbackBoardHeight};
    }
    Zobrist geometry = rootData.zobrist;
    if (geometry != null && (long) geometry.width() * geometry.height() == boardArea) {
      return new int[] {geometry.width(), geometry.height()};
    }
    if (fallbackBoardWidth > 0
        && fallbackBoardHeight > 0
        && fallbackBoardWidth * fallbackBoardHeight == boardArea) {
      return new int[] {fallbackBoardWidth, fallbackBoardHeight};
    }
    if (fallbackBoardHeight > 0 && boardArea % fallbackBoardHeight == 0) {
      return new int[] {boardArea / fallbackBoardHeight, fallbackBoardHeight};
    }
    if (fallbackBoardWidth > 0 && boardArea % fallbackBoardWidth == 0) {
      return new int[] {fallbackBoardWidth, boardArea / fallbackBoardWidth};
    }
    int squareBoardSize = (int) Math.sqrt(boardArea);
    if (squareBoardSize * squareBoardSize == boardArea) {
      return new int[] {squareBoardSize, squareBoardSize};
    }
    return new int[] {boardArea, 1};
  }

  private static String formatBoardSizeTag(int boardWidth, int boardHeight) {
    return boardWidth + (boardWidth != boardHeight ? ":" + boardHeight : "");
  }

  // DD is a terminal placeholder, not a modifier on the next real B/W action.
  // It does not complete currentNodeHasMove: same-node payload stays buffered for a real action,
  // or is flushed to the dummy only when the SGF node ends without one.
  private static void appendDummyPlaceholder(BoardHistoryList history) {
    history.pass(history.isBlacksTurn() ? Stone.BLACK : Stone.WHITE, true, true);
  }

  private static BoardHistoryList parseValue(
      SgfDocument document, BoardHistoryList history, boolean isBranch, boolean firstTime, ParseOptions options) {

    BoardHistoryNode importParent = history.getCurrentHistoryNode();
    int existingChildren = importParent.numberOfChildren();
    int subTreeDepth = 0;
    // Save the variation step count
    Map<Integer, BoardHistoryNode> subTreeStepMap = new HashMap<Integer, BoardHistoryNode>();
    // Game properties
    Map<String, List<String>> gameProperties = new HashMap<String, List<String>>();
    Map<String, List<String>> pendingProps = new HashMap<String, List<String>>();
    AnalysisImport analysisImport = new AnalysisImport(options);
    PendingNodeMetadata pendingNodeMetadata = new PendingNodeMetadata(analysisImport);
    boolean isMultiGo = false,
        moveStart = false,
        currentNodeHasMove = false,
        addPassForMove = true,
        currentNodeHasSetupSnapshot = false;
    int topLevelNodeCount = 0;
    boolean allowRootSetupOnCurrentNode = false;
    // MultiGo 's branch: (Main Branch (Main Branch) (Branch) )
    // Other 's branch: (Main Branch (Branch) Main Branch)
    isMultiGo = document.multiGo();
    if (isBranch) {
      subTreeDepth += 1;
      // Initialize the step count
      subTreeStepMap.put(subTreeDepth, history.getCurrentHistoryNode());
    }

    String blackPlayer = "", whitePlayer = "";
    Double komi = null;
    boolean parsedKomiTag = false;
    boolean currentNodeHasDummy = false;
    // Support unicode characters (UTF-8)
    for (SgfDocument.Event event : document.events()) {
      switch (event.kind()) {
        case '(':
          flushPendingNodeMetadataToCurrentNode(history, pendingNodeMetadata);
          currentNodeHasDummy = false;
          subTreeDepth += 1;
          // Initialize the step count
          subTreeStepMap.put(subTreeDepth, history.getCurrentHistoryNode());
          addPassForMove = true;
          currentNodeHasSetupSnapshot = false;
          currentNodeHasMove = false;
          pendingProps = new HashMap<String, List<String>>();
          break;
        case ')':
          flushPendingNodeMetadataToCurrentNode(history, pendingNodeMetadata);
          currentNodeHasDummy = false;
          if (isMultiGo) {
            // Restore to the variation node
            // int varStep = subTreeStepMap.get(subTreeDepth);
            //   for (int s = 0; s < varStep; s++)
            while (history.getCurrentHistoryNode() != subTreeStepMap.get(subTreeDepth)) {
              if (!history.previous().isPresent()) break;
            }
          }
          subTreeDepth -= 1;
          break;
        case 'P':
          if (subTreeDepth > 1 && !isMultiGo) {
            break;
          }
          String tag = event.name();
          String tagContent = event.legacyValue();
          // We got tag, we can parse this tag now.
          if (tag.equals("DD") && "true".equals(tagContent)) {
            appendDummyPlaceholder(history);
            currentNodeHasDummy = true;
          } else if (tag.equals("B") || tag.equals("W")) {
            if (currentNodeHasDummy) {
              history.previous();
              currentNodeHasDummy = false;
            }
            moveStart = true;
            addPassForMove = !currentNodeHasSetupSnapshot;
            int[] move = convertSgfPosToCoord(tagContent, history.getData().zobrist.width(), history.getData().zobrist.height());
            // Save the step count
            //  subTreeStepMap.put(subTreeDepth, subTreeStepMap.get(subTreeDepth) + 1);
            Stone color = tag.equals("B") ? Stone.BLACK : Stone.WHITE;
            BoardHistoryNode parent = history.getCurrentHistoryNode();
            boolean newBranch =
                parent.hasVariations()
                    || parent.next(true).map(BoardHistoryNode::isEndDummay).orElse(false);
            if (move == null) {
              history.pass(color, newBranch, false);

            } else {
              history.place(move[0], move[1], color, newBranch, false, false, options.noCapture(), options.canSuicidal());
            }
            currentNodeHasMove = true;
            if (newBranch) {
              processPendingPros(history, pendingProps);
            }
            flushPendingNodeMetadataToCurrentNode(history, pendingNodeMetadata);
          } else if (tag.equals("C")) {
            // Support comment
            if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
              bufferNodeMetadata(pendingNodeMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              target.getData().comment = tagContent;
            }
          } else if (isAnalysisTag(tag)) {
            if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
              bufferNodeAnalysis(pendingNodeMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              analysisImport.applyTag(target.getData(), tag, tagContent);
            }
          } else if (tag.equals("AB") || tag.equals("AW")) {
            int[] move = convertSgfPosToCoord(tagContent, history.getData().zobrist.width(), history.getData().zobrist.height());
            Stone color = tag.equals("AB") ? Stone.BLACK : Stone.WHITE;
            if (isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode)) {
              applyRootSetupProperty(history, tag, tagContent, move, color);
            } else {
              if (addPassForMove) {
                boolean newBranch = history.getCurrentHistoryNode().hasVariations();
                appendSetupSnapshot(
                    history, newBranch, pendingProps, currentNodeHasMove, analysisImport);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              movePendingNodeMetadataToTarget(history, target, pendingNodeMetadata);
              addNodePropertyOnNode(history, target, tag, tagContent);
              if (move != null) {
                addExtraStoneOnNode(history, target, move[0], move[1], color);
              }
            }
          } else if (tag.equals("PL")) {
            if (isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode)) {
              applyRootPlayerProperty(history, tagContent);
            } else {
              if (addPassForMove) {
                boolean newBranch = history.getCurrentHistoryNode().hasVariations();
                appendSetupSnapshot(
                    history, newBranch, pendingProps, currentNodeHasMove, analysisImport);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              movePendingNodeMetadataToTarget(history, target, pendingNodeMetadata);
              applyCurrentPlayerPropertyOnNode(history, target, tagContent);
            }
          } else if (tag.equals("PB")) {
            blackPlayer = decodeGameInfoText(tagContent);
            history.getGameInfo().setPlayerBlack(blackPlayer);
          } else if (tag.equals("PW")) {
            whitePlayer = decodeGameInfoText(tagContent);
            history.getGameInfo().setPlayerWhite(whitePlayer);
          } else if (tag.equals("RE")) {
            history.getGameInfo().setResultNoUI(decodeGameInfoText(tagContent));
          } else if (tag.equals("KM")) {
            if (firstTime) {
              try {
                if (!tagContent.trim().isEmpty()) {
                  komi = Double.parseDouble(tagContent);
                  parsedKomiTag = true;
                }
              } catch (NumberFormatException e) {
                e.printStackTrace();
              }
            }
          } else if (tag.equals("HA")) {
            try {
              if (tagContent.trim().isEmpty()) {
                tagContent = "0";
              }
              int handicap = Integer.parseInt(tagContent);
              history.getGameInfo().setHandicap(handicap);
            } catch (NumberFormatException e) {
              e.printStackTrace();
            }
          } else {
            if (allowRootSetupOnCurrentNode && isRootGameProperty(tag)) {
              addProperty(gameProperties, tag, tagContent);
            } else if (moveStart) {
              // Other SGF node properties
              if ("AE".equals(tag)) {
                // remove a stone
                if (addPassForMove) {
                  boolean newBranch = history.getCurrentHistoryNode().hasVariations();
                  appendSetupSnapshot(
                      history, newBranch, pendingProps, currentNodeHasMove, analysisImport);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                movePendingNodeMetadataToTarget(history, target, pendingNodeMetadata);
                addNodePropertyOnNode(history, target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent, history.getData().zobrist.width(), history.getData().zobrist.height());
                if (move != null) {
                  removeStoneOnNode(history, target, move[0], move[1], Stone.EMPTY, false);
                }
              } else if (currentNodeHasSetupSnapshot) {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(history, target, tag, tagContent);
              } else if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
                bufferNodeMetadata(pendingNodeMetadata, tag, tagContent);
              } else {
                boolean firstProp = history.getCurrentHistoryNode().hasVariations();
                if (firstProp) {
                  addProperty(pendingProps, tag, tagContent);
                } else {
                  history.addNodeProperty(tag, tagContent);
                }
              }
            } else {
              boolean rootSetupContext =
                  isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode);
              if ("AE".equals(tag) && rootSetupContext) {
                int[] move = convertSgfPosToCoord(tagContent, history.getData().zobrist.width(), history.getData().zobrist.height());
                applyRootSetupProperty(history, tag, tagContent, move, Stone.EMPTY, false);
              } else if ("AE".equals(tag)) {
                if (addPassForMove) {
                  boolean newBranch = history.getCurrentHistoryNode().hasVariations();
                  appendSetupSnapshot(
                      history, newBranch, pendingProps, currentNodeHasMove, analysisImport);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                movePendingNodeMetadataToTarget(history, target, pendingNodeMetadata);
                addNodePropertyOnNode(history, target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent, history.getData().zobrist.width(), history.getData().zobrist.height());
                if (move != null) {
                  removeStoneOnNode(history, target, move[0], move[1], Stone.EMPTY, false);
                }
              } else if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
                bufferNodeMetadata(pendingNodeMetadata, tag, tagContent);
              } else {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(history, target, tag, tagContent);
              }
            }
          }
          break;
        case ';':
          if (!(subTreeDepth > 1 && !isMultiGo)) {
            flushPendingNodeMetadataToCurrentNode(history, pendingNodeMetadata);
            currentNodeHasDummy = false;
            topLevelNodeCount += 1;
            allowRootSetupOnCurrentNode = !isBranch && topLevelNodeCount == 1;
            addPassForMove = true;
            currentNodeHasSetupSnapshot = false;
            currentNodeHasMove = false;
          }
          break;
        default:
          throw new IllegalArgumentException("Unknown SGF event: " + event.kind());
      }
    }
    flushPendingNodeMetadataToCurrentNode(history, pendingNodeMetadata);

    if (isBranch) {
      resolveImportedInitialBranches(history, importParent, existingChildren);
      history.toBranchTop();
    } else {
      // Rewind to game start
      while (history.previous().isPresent())
        ;

      if (gameProperties.size() > 0) {
        history.getData().addProperties(gameProperties);
      }
      history.getGameInfo().setDate(decodeGameInfoText(getOrDefault(gameProperties, "DT", null)));
      stabilizeRootSetupSideToPlay(history);
      Optional<Double> parsedKomi =
          parsedKomiTag ? normalizeSgfKomi(komi, gameProperties, history.getData().stones.length) : Optional.empty();
      if (parsedKomi.isPresent()
          && (options.readKomi()
              || isSetupOrHandicapGame(history)
              || parseHandicapProperty(getOrDefault(gameProperties, "HA", null)) > 0)) {
        history.getGameInfo().setKomiNoMenu(parsedKomi.get());
      }
    }
    return history;
  }

  public static void addStartList(boolean isBlack, int x, int y) {
    Movelist move = new Movelist();
    move.x = x;
    move.y = y;
    move.ispass = false;
    move.isblack = isBlack;
    move.movenum = Lizzie.board.startStonelist.size() + 1;
    Lizzie.board.startStonelist.add(move);
  }

  public static int parseBranch(BoardHistoryList history, String value) {
    int[] size = resolveHistoryBoardSize(history);
    value = normalizeSetupRanges(value, size[0], size[1]);
    if (value == null) return -1;
    BoardHistoryNode importParent = history.getCurrentHistoryNode();
    int existingChildren = importParent.numberOfChildren();
    int subTreeDepth = 0;
    // Save the variation step count
    Map<Integer, Integer> subTreeStepMap = new HashMap<Integer, Integer>();
    // Game properties
    Map<String, List<String>> pendingProps = new HashMap<String, List<String>>();
    AnalysisImport analysisImport = new AnalysisImport();
    PendingNodeMetadata pendingNodeMetadata = new PendingNodeMetadata(analysisImport);
    boolean inTag = false,
        isMultiGo = false,
        escaping = false,
        moveStart = false,
        currentNodeHasMove = false,
        addPassForMove = true,
        currentNodeHasSetupSnapshot = false;
    boolean inProp = false;
    boolean currentNodeHasDummy = false;
    boolean allowRootSetupOnCurrentNode = false;
    String tag = "";
    StringBuilder tagBuilder = new StringBuilder();
    StringBuilder tagContentBuilder = new StringBuilder();
    // MultiGo 's branch: (Main Branch (Main Branch) (Branch) )
    // Other 's branch: (Main Branch (Branch) Main Branch)
    isMultiGo = hasMultiGoBranchSuffix(value);
    subTreeDepth += 1;
    // Initialize the step count
    subTreeStepMap.put(subTreeDepth, 0);

    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (escaping) {
        tagContentBuilder.append(c == 'n' && !isGameInfoTextProperty(tag) ? '\n' : c);
        escaping = false;
        continue;
      }
      switch (c) {
        case '(':
          if (!inTag) {
            flushPendingNodeMetadataToCurrentNode(history, pendingNodeMetadata);
            currentNodeHasDummy = false;
            subTreeDepth += 1;
            // Initialize the step count
            subTreeStepMap.put(subTreeDepth, 0);
            addPassForMove = true;
            currentNodeHasSetupSnapshot = false;
            currentNodeHasMove = false;
            pendingProps = new HashMap<String, List<String>>();
          } else {
            if (i > 0) {
              // Allow the comment tag includes '('
              tagContentBuilder.append(c);
            }
          }
          break;
        case ')':
          if (!inTag) {
            flushPendingNodeMetadataToCurrentNode(history, pendingNodeMetadata);
            currentNodeHasDummy = false;
            if (isMultiGo) {
              // Restore to the variation node
              int varStep = subTreeStepMap.get(subTreeDepth);
              for (int s = 0; s < varStep; s++) {
                history.previous();
              }
            }
            subTreeDepth -= 1;
          } else {
            // Allow the comment tag includes '('
            tagContentBuilder.append(c);
          }
          break;
        case '[':
          if (!inProp) {
            inProp = true;
            if (subTreeDepth > 1 && !isMultiGo) {
              break;
            }
            inTag = true;
            String tagTemp = tagBuilder.toString();
            if (!tagTemp.isEmpty()) {
              tag = normalizePropertyName(tagTemp);
            }
            tagContentBuilder = new StringBuilder();
          } else {
            tagContentBuilder.append(c);
          }
          break;
        case ']':
          if (subTreeDepth > 1 && !isMultiGo) {
            break;
          }
          inTag = false;
          inProp = false;
          tagBuilder = new StringBuilder();
          String tagContent = tagContentBuilder.toString();
          // We got tag, we can parse this tag now.
          if (tag.equals("DD") && "true".equals(tagContent)) {
            appendDummyPlaceholder(history);
            subTreeStepMap.put(subTreeDepth, subTreeStepMap.get(subTreeDepth) + 1);
            currentNodeHasDummy = true;
          } else if (tag.equals("B") || tag.equals("W")) {
            if (currentNodeHasDummy) {
              history.previous();
              subTreeStepMap.put(subTreeDepth, subTreeStepMap.get(subTreeDepth) - 1);
              currentNodeHasDummy = false;
            }
            moveStart = true;
            addPassForMove = !currentNodeHasSetupSnapshot;
            int[] move = convertSgfPosToCoord(tagContent);
            // Save the step count
            subTreeStepMap.put(subTreeDepth, subTreeStepMap.get(subTreeDepth) + 1);
            Stone color = tag.equals("B") ? Stone.BLACK : Stone.WHITE;
            boolean newBranch = (subTreeStepMap.get(subTreeDepth) == 1);
            if (move == null) {
              history.pass(color, newBranch, false);
            } else {
              history.place(move[0], move[1], color, newBranch);
            }
            currentNodeHasMove = true;
            if (newBranch) {
              processPendingPros(history, pendingProps);
            }
            flushPendingNodeMetadataToCurrentNode(history, pendingNodeMetadata);
          } else if (tag.equals("C")) {
            // Support comment
            if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
              bufferNodeMetadata(pendingNodeMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              target.getData().comment = tagContent;
            }
          } else if (isAnalysisTag(tag)) {
            if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
              bufferNodeAnalysis(pendingNodeMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              analysisImport.applyTag(target.getData(), tag, tagContent);
            }
          } else if (tag.equals("AB") || tag.equals("AW")) {
            int[] move = convertSgfPosToCoord(tagContent);
            Stone color = tag.equals("AB") ? Stone.BLACK : Stone.WHITE;
            if (isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode)) {
              applyRootSetupProperty(history, tag, tagContent, move, color);
            } else {
              if (addPassForMove) {
                boolean newBranch = (subTreeStepMap.get(subTreeDepth) <= 1);
                appendSetupSnapshot(
                    history, newBranch, pendingProps, currentNodeHasMove, analysisImport);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              movePendingNodeMetadataToTarget(history, target, pendingNodeMetadata);
              addNodePropertyOnNode(history, target, tag, tagContent);
              if (move != null) {
                addExtraStoneOnNode(history, target, move[0], move[1], color);
              }
            }
          } else if (tag.equals("PL")) {
            if (isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode)) {
              applyRootPlayerProperty(history, tagContent);
            } else {
              if (addPassForMove) {
                boolean newBranch = (subTreeStepMap.get(subTreeDepth) <= 1);
                appendSetupSnapshot(
                    history, newBranch, pendingProps, currentNodeHasMove, analysisImport);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              movePendingNodeMetadataToTarget(history, target, pendingNodeMetadata);
              applyCurrentPlayerPropertyOnNode(history, target, tagContent);
            }
          } else if (tag.equals("PB")) {
          } else if (tag.equals("PW")) {
          } else if (tag.equals("KM") && Lizzie.config.readKomi) {
            try {
              if (!tagContent.trim().isEmpty()) {
                Double komi = Double.parseDouble(tagContent);
                if (komi >= 200) {
                  komi = komi / 100;
                  if (komi <= 4 && komi >= -4) komi = komi * 2;
                }
                if (komi.toString().endsWith(".75") || komi.toString().endsWith(".25"))
                  komi = komi * 2;
                if (Math.abs(komi) < Board.boardWidth * Board.boardHeight) {
                  history.getGameInfo().setKomi(komi);
                }
              }
            } catch (NumberFormatException e) {
              e.printStackTrace();
            }
          } else {
            if (moveStart) {
              // Other SGF node properties
              if ("AE".equals(tag)) {
                // remove a stone
                if (addPassForMove) {
                  boolean newBranch = (subTreeStepMap.get(subTreeDepth) <= 1);
                  appendSetupSnapshot(
                      history, newBranch, pendingProps, currentNodeHasMove, analysisImport);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                movePendingNodeMetadataToTarget(history, target, pendingNodeMetadata);
                addNodePropertyOnNode(history, target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent);
                if (move != null) {
                  removeStoneOnNode(history, target, move[0], move[1], Stone.EMPTY);
                }
              } else if (currentNodeHasSetupSnapshot) {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(history, target, tag, tagContent);
              } else if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
                bufferNodeMetadata(pendingNodeMetadata, tag, tagContent);
              } else {
                boolean firstProp = (subTreeStepMap.get(subTreeDepth) == 0);
                if (firstProp) {
                  addProperty(pendingProps, tag, tagContent);
                } else {
                  history.addNodeProperty(tag, tagContent);
                }
              }
            } else {
              boolean rootSetupContext =
                  isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode);
              if ("AE".equals(tag) && rootSetupContext) {
                int[] move = convertSgfPosToCoord(tagContent);
                applyRootSetupProperty(history, tag, tagContent, move, Stone.EMPTY);
              } else if ("AE".equals(tag)) {
                if (addPassForMove) {
                  boolean newBranch = (subTreeStepMap.get(subTreeDepth) <= 1);
                  appendSetupSnapshot(
                      history, newBranch, pendingProps, currentNodeHasMove, analysisImport);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                movePendingNodeMetadataToTarget(history, target, pendingNodeMetadata);
                addNodePropertyOnNode(history, target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent);
                if (move != null) {
                  removeStoneOnNode(history, target, move[0], move[1], Stone.EMPTY);
                }
              } else if (shouldBufferNodeMetadata(currentNodeHasMove, currentNodeHasSetupSnapshot)) {
                bufferNodeMetadata(pendingNodeMetadata, tag, tagContent);
              } else {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(history, target, tag, tagContent);
              }
            }
          }
          break;
        case ';':
          if (inProp) {
            // support C[a;b;c;]
            tagContentBuilder.append(c);
          } else if (!(subTreeDepth > 1 && !isMultiGo)) {
            flushPendingNodeMetadataToCurrentNode(history, pendingNodeMetadata);
            currentNodeHasDummy = false;
            addPassForMove = true;
            currentNodeHasSetupSnapshot = false;
            currentNodeHasMove = false;
          }
          break;
        default:
          if (subTreeDepth > 1 && !isMultiGo) {
            break;
          }
          if (inTag) {
            if (c == '\\') {
              escaping = true;
              if (!tag.equals("C") && !isAnalysisPayloadTag(tag)) tagContentBuilder.append(c);
              continue;
            }
            tagContentBuilder.append(c);
          } else {
            if (c != '\n' && c != '\r' && c != '\t' && c != ' ') {
              tagBuilder.append(c);
            }
          }
      }
    }
    flushPendingNodeMetadataToCurrentNode(history, pendingNodeMetadata);
    resolveImportedInitialBranches(history, importParent, existingChildren);
    history.toBranchTop();
    return history.getCurrentHistoryNode().numberOfChildren() - 1;
  }

  private static boolean isAnalysisTag(String tag) {
    return isAnalysisPayloadTag(tag) || "KGP".equals(tag);
  }

  // Decode LZ text once while reading it: KGP hashes the unescaped payload, before parsing or
  // suggestion limits. Keep the existing raw escape handling for opaque KGP and other properties.
  private static boolean isAnalysisPayloadTag(String tag) {
    return "LZ".equals(tag) || "LZOP".equals(tag) || "LZ2".equals(tag) || "LZOP2".equals(tag);
  }

  /** The existing branch-format heuristic only needs the final two non-whitespace characters. */
  private static boolean hasMultiGoBranchSuffix(String value) {
    return SgfDocument.hasMultiGoBranchSuffix(value);
  }

  private static String[] analysisFields(String value) {
    StringTokenizer tokenizer = new StringTokenizer(value.trim(), SGF_WHITESPACE);
    String[] fields = new String[tokenizer.countTokens()];
    for (int i = 0; i < fields.length; i++) fields[i] = tokenizer.nextToken();
    return fields;
  }

  private static boolean isSecondaryEngineAnalysisTag(String tag) {
    return "LZ2".equals(tag) || "LZOP2".equals(tag);
  }

  private static final class AnalysisImport {
    private final ParseOptions options;

    private AnalysisImport() { this(ParseOptions.capture()); }

    private AnalysisImport(ParseOptions options) { this.options = options; }

    // A matching digest can belong to an earlier import. Only payloads replaced by this parser
    // call permit KGP to restore invalidation; metadata alone cannot validate live analysis.
    private final Set<BoardData> primaryReplacements =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BoardData> secondaryReplacements =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private void moveToSnapshot(BoardData source, BoardData snapshot) {
      if (primaryReplacements.remove(source)) primaryReplacements.add(snapshot);
      if (secondaryReplacements.remove(source)) secondaryReplacements.add(snapshot);
    }

    private void applyTag(BoardData target, String tag, String tagContent) {
      if (target == null) {
        return;
      }
      if ("KGP".equals(tag)) {
        target.addProperty(tag, tagContent);
        restoreAnalysisMetadata(target,
            primaryReplacements.contains(target), secondaryReplacements.contains(target));
        return;
      }
      if (Utils.isBlank(tagContent)) return;
      int detailStart = tagContent.indexOf('\n');
      String[] line1 = analysisFields(
          detailStart < 0 ? tagContent : tagContent.substring(0, detailStart));
      if (line1.length < 3 || Utils.isBlank(line1[0])) {
        return;
      }
      boolean secondaryEngine = isSecondaryEngineAnalysisTag(tag);
      if (secondaryEngine) clearSecondaryAnalysisPayload(target);
      else clearPrimaryAnalysisPayload(target);
      applyAnalysisHeader(target, line1, secondaryEngine);
      int numPlayouts = secondaryEngine ? target.getPlayouts2() : target.getPlayouts();
      if (numPlayouts > 0 && detailStart >= 0) {
        double stdev = extractAnalysisScoreStdev(line1);
        ParsedAnalysisInfo parsed = parseAnalysisInfoForDetachedNode(
            tagContent, detailStart + 1, stdev, secondaryEngine, options);
        applyParsedAnalysisInfo(target, parsed, secondaryEngine);
      }
      target.setImportedAnalysis(secondaryEngine, analysisPayloadDigest(tagContent), parseHeaderWrn(line1));
      (secondaryEngine ? secondaryReplacements : primaryReplacements).add(target);
      restoreAnalysisMetadata(target, !secondaryEngine, secondaryEngine);
    }
  }

  private static boolean isHeaderWrn(Double value) {
    // KataGo's engine range (0..5), not the more restrictive GUI editing range (0..2).
    // KGP retains its existing finite-number contract independently of this named extension.
    return value != null && Double.isFinite(value) && value >= 0 && value <= 5;
  }

  private static AnalysisProvenance headerWrnProvenance(Double wrn) {
    return isHeaderWrn(wrn)
        ? new AnalysisProvenance("", "", "", "", "", "", null, null, wrn, "") : null;
  }

  private static Double parseHeaderWrn(String[] header) {
    Double wrn = null;
    boolean seen = false;
    for (int i = 6; i < header.length; i++) {
      if (!header[i].startsWith("wrn=")) continue;
      // Conflicting or repeated values are ambiguous, even if one occurrence is valid.
      if (seen) return null;
      seen = true;
      double parsed = parseDoubleOrDefault(header[i].substring("wrn=".length()), Double.NaN);
      if (isHeaderWrn(parsed)) wrn = parsed;
    }
    return wrn;
  }

  private static void applyAnalysisHeader(
      BoardData target, String[] line1, boolean secondaryEngineTag) {
    // Turn metadata must not pad a short legacy header with fabricated score fields.
    Boolean analysisTurn = null;
    boolean hasTurnField = false;
    for (int i = 3; i < line1.length; i++) {
      String field = line1[i];
      hasTurnField |= field.startsWith("analysisTurn=");
      if (field.equals("analysisTurn=B")) analysisTurn = true;
      else if (field.equals("analysisTurn=W")) analysisTurn = false;
    }
    if (hasTurnField) {
      List<String> fields = new ArrayList<>(asList(line1));
      fields.subList(3, fields.size()).removeIf(field -> field.startsWith("analysisTurn="));
      line1 = fields.toArray(new String[0]);
    }
    int headerPlayouts = parseAnalysisPlayouts(line1[2]);
    int headerSlots = Math.min(6, line1.length);
    int rootVisits = -1;
    for (int i = 6; i < line1.length; i++) {
      if (line1[i].startsWith("rootVisits=")) {
        try {
          rootVisits = Integer.parseInt(line1[i].substring("rootVisits=".length()));
        } catch (NumberFormatException ignored) {
          rootVisits = -1;
        }
      }
    }
    target.setAnalysisBlackToPlay(secondaryEngineTag, analysisTurn);
    if (rootVisits >= 0) headerPlayouts = rootVisits;
    if (secondaryEngineTag) {
      target.engineName2 = line1[0];
      target.winrate2 = 100 - parseDoubleOrDefault(line1[1], 50);
      target.setPlayouts2(headerPlayouts);
      target.rootVisits2 = rootVisits;
      target.isKataData2 = false;
      target.analysisHeaderSlots2 = headerSlots;
      target.scoreMean2 = 0;
      target.scoreStdev2 = 0;
      target.pda2 = 0;
      if (line1.length >= 4) {
        target.scoreMean2 = parseDoubleOrDefault(line1[3], 0);
        target.isKataData2 = true;
      }
      if (line1.length >= 5) {
        target.scoreStdev2 = parseDoubleOrDefault(line1[4], 0);
      }
      if (line1.length >= 6) {
        target.pda2 = parseDoubleOrDefault(line1[5], 0);
      }
      return;
    }
    target.engineName = line1[0];
    target.winrate = 100 - parseDoubleOrDefault(line1[1], 50);
    target.setPlayouts(headerPlayouts);
    target.rootVisits = rootVisits;
    target.isKataData = false;
    target.analysisHeaderSlots = headerSlots;
    target.scoreMean = 0;
    target.scoreStdev = 0;
    target.pda = 0;
    if (line1.length >= 4) {
      target.scoreMean = parseDoubleOrDefault(line1[3], 0);
      target.isKataData = true;
    }
    if (line1.length >= 5) {
      target.scoreStdev = parseDoubleOrDefault(line1[4], 0);
    }
    if (line1.length >= 6) {
      target.pda = parseDoubleOrDefault(line1[5], 0);
    }
  }

  private static int parseAnalysisPlayouts(String rawValue) {
    if (Utils.isBlank(rawValue)) {
      return 0;
    }
    // Saved candidates normally contain plain nonnegative integers. Leave abbreviations,
    // decimals, signs and overflow to the existing tolerant import path below.
    int integer = 0;
    int cursor = 0;
    while (cursor < rawValue.length()) {
      int digit = rawValue.charAt(cursor) - '0';
      if (digit < 0 || digit > 9 || integer > (Integer.MAX_VALUE - digit) / 10) break;
      integer = integer * 10 + digit;
      cursor++;
    }
    if (cursor == rawValue.length()) return integer;
    String normalized = rawValue.trim().toLowerCase(Locale.ENGLISH).replace(",", "");
    double multiplier = 1;
    if (normalized.endsWith("m")) {
      multiplier = 1_000_000;
      normalized = normalized.substring(0, normalized.length() - 1);
    } else if (normalized.endsWith("k")) {
      multiplier = 1_000;
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    try {
      double value = Double.parseDouble(normalized.replaceAll("[^0-9.\\-]", ""));
      return value <= 0 ? 0 : (int) Math.round(value * multiplier);
    } catch (NumberFormatException ex) {
      String digits = normalized.replaceAll("[^0-9]", "");
      if (digits.isEmpty()) {
        return 0;
      }
      try {
        return Integer.parseInt(digits);
      } catch (NumberFormatException nestedEx) {
        return 0;
      }
    }
  }

  private static double extractAnalysisScoreStdev(String[] line1) {
    if (line1.length < 5) {
      return 0;
    }
    return parseDoubleOrDefault(line1[4], 0);
  }

  private static double parseDoubleOrDefault(String rawValue, double defaultValue) {
    try {
      return Double.parseDouble(rawValue);
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private static ParsedAnalysisInfo parseAnalysisInfoForDetachedNode(
      String line, int start, double stdev, boolean secondaryEngineTag, ParseOptions options) {
    // Keep the legacy literal separators, including ownership occurring inside a token.
    int ownership = line.indexOf("ownership", start);
    int analysisEnd = ownership < 0 ? line.length() : ownership;
    List<MoveData> bestMoves = new ArrayList<>();
    int maxSuggestions =
        secondaryEngineTag
            ? 361
            : options.maxSuggestions();
    while (start < analysisEnd && maxSuggestions > 0) {
      int separator = line.indexOf(" info ", start);
      if (separator < 0 || separator + " info ".length() > analysisEnd) separator = analysisEnd;
      AnalysisTokens tokens = new AnalysisTokens(line, start, separator);
      if (tokens.advance()) {
        bestMoves.add(parseMoveDataFromAnalysis(tokens, bestMoves.size(), options.limitBranchLength()));
        maxSuggestions--;
      }
      start = separator + " info ".length();
    }
    if (!bestMoves.isEmpty()) {
      bestMoves.get(0).scoreStdev = stdev;
    }
    return new ParsedAnalysisInfo(bestMoves,
        ownership < 0 ? null : parseOwnershipValues(line, ownership + "ownership".length()));
  }

  /** A trimmed region with the same six ASCII delimiters as analysisFields, read only once. */
  private static final class AnalysisTokens {
    private final String text;
    private final int end;
    private int position;
    private int tokenStart;
    private int tokenEnd;

    AnalysisTokens(String text, int start, int end) {
      this.text = text;
      // String.trim also removes other control characters, but only at the region's edges.
      while (start < end && text.charAt(start) <= ' ') start++;
      while (start < end && text.charAt(end - 1) <= ' ') end--;
      this.position = start;
      this.end = end;
    }

    boolean advance() {
      while (position < end && SGF_WHITESPACE.indexOf(text.charAt(position)) >= 0) position++;
      tokenStart = position;
      while (position < end && SGF_WHITESPACE.indexOf(text.charAt(position)) < 0) position++;
      tokenEnd = position;
      return tokenStart < tokenEnd;
    }

    String value() {
      return text.substring(tokenStart, tokenEnd);
    }

    boolean is(String expected) {
      return is(tokenStart, tokenEnd, expected);
    }

    boolean is(int start, int end, String expected) {
      return end - start == expected.length()
          && text.regionMatches(start, expected, 0, expected.length());
    }
  }

  private static MoveData parseMoveDataFromAnalysis(AnalysisTokens tokens, int order, int limit) {
    MoveData result = new MoveData();
    result.order = order;
    do {
      int keyStart = tokens.tokenStart;
      int keyEnd = tokens.tokenEnd;
      if (!tokens.advance()) break;
      if (tokens.is(keyStart, keyEnd, "pv")) {
        captureVariationTokens(result, tokens, limit);
        break;
      }
      applyMoveDataToken(result, tokens, keyStart, keyEnd);
    } while (tokens.advance());
    if ((result.variation == null || result.variation.isEmpty())
        && !Utils.isBlank(result.coordinate)) {
      result.variation = new ArrayList<>();
      result.variation.add(result.coordinate);
    }
    return result;
  }

  private static void captureVariationTokens(MoveData target, AnalysisTokens tokens, int limit) {
    target.variation = new ArrayList<>();
    do {
      if (tokens.is("pvVisits")) {
        target.pvVisits = new ArrayList<>();
        while (tokens.advance()) target.pvVisits.add(tokens.value());
        return;
      }
      if (limit <= 0 || target.variation.size() < limit) target.variation.add(tokens.value());
    } while (tokens.advance());
  }

  private static void applyMoveDataToken(
      MoveData target, AnalysisTokens tokens, int keyStart, int keyEnd) {
    if (tokens.is(keyStart, keyEnd, "move")) {
      target.coordinate = tokens.value();
    } else if (tokens.is(keyStart, keyEnd, "visits")) {
      target.playouts = parseAnalysisPlayouts(tokens.value());
    } else if (tokens.is(keyStart, keyEnd, "order")) {
      target.order = Integer.parseInt(tokens.value());
    } else if (tokens.is(keyStart, keyEnd, "edgeVisits")) {
      target.edgeVisits = Integer.parseInt(tokens.value());
    } else if (tokens.is(keyStart, keyEnd, "winrate")) {
      target.winrate = parseAnalysisPlayouts(tokens.value()) / 100.0;
      target.lcb = target.winrate;
    } else if (tokens.is(keyStart, keyEnd, "prior")) {
      target.policy = parsePriorValue(tokens.value());
    } else if (tokens.is(keyStart, keyEnd, "scoreMean")) {
      target.scoreMean = parseDoubleOrDefault(tokens.value(), 0);
      target.isKataData = true;
    } else if (tokens.is(keyStart, keyEnd, "scoreStdev")) {
      target.scoreStdev = parseDoubleOrDefault(tokens.value(), 0);
      target.isKataData = true;
    }
  }

  private static double parsePriorValue(String rawValue) {
    try {
      return Integer.parseInt(rawValue) / 100.0;
    } catch (NumberFormatException ex) {
      return parseDoubleOrDefault(rawValue, 0);
    }
  }

  private static List<Double> parseOwnershipValues(String line, int start) {
    AnalysisTokens tokens = new AnalysisTokens(line, start, line.length());
    double[] values = new double[32];
    int size = 0;
    while (tokens.advance()) {
      double value;
      try {
        value = Double.parseDouble(tokens.value());
      } catch (NumberFormatException ex) {
        break;
      }
      if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
      values[size++] = value;
    }
    return BoardData.compactEstimateArray(values, size);
  }

  private static void applyParsedAnalysisInfo(
      BoardData target, ParsedAnalysisInfo parsed, boolean secondaryEngineTag) {
    if (parsed.bestMoves.isEmpty()) {
      return;
    }
    MoveData bestMove = parsed.bestMoves.get(0);
    if (secondaryEngineTag) {
      boolean headerHasKata = target.isKataData2;
      target.bestMoves2 = parsed.bestMoves;
      target.estimateArray2 = parsed.estimateArray;
      target.isSaiData2 = target.isSaiData2 || bestMove.isSaiData;
      target.isKataData2 = target.isKataData2 || bestMove.isKataData;
      if (!headerHasKata && bestMove.isKataData) {
        if (shouldApplyBestMoveScore(target.scoreMean2, bestMove.scoreMean)) {
          target.scoreMean2 = bestMove.scoreMean;
        }
        target.scoreStdev2 = bestMove.scoreStdev;
      }
      return;
    }
    boolean headerHasKata = target.isKataData;
    target.bestMoves = parsed.bestMoves;
    target.estimateArray = parsed.estimateArray;
    target.playoutsChanged = true;
    target.isSaiData = target.isSaiData || bestMove.isSaiData;
    target.isKataData = target.isKataData || bestMove.isKataData;
    if (!headerHasKata && bestMove.isKataData) {
      if (shouldApplyBestMoveScore(target.scoreMean, bestMove.scoreMean)) {
        target.scoreMean = bestMove.scoreMean;
      }
      target.scoreStdev = bestMove.scoreStdev;
    }
  }

  private static boolean shouldApplyBestMoveScore(
      double headerScoreMean, double bestMoveScoreMean) {
    return Double.compare(bestMoveScoreMean, 0) != 0 || Double.compare(headerScoreMean, 0) == 0;
  }

  private static final class ParsedAnalysisInfo {
    private final List<MoveData> bestMoves;
    // Built once by parseOwnershipValues as an immutable, detached primitive-backed list.
    private final List<Double> estimateArray;

    private ParsedAnalysisInfo(List<MoveData> bestMoves, List<Double> estimateArray) {
      this.bestMoves = bestMoves;
      this.estimateArray = estimateArray;
    }
  }

  private static List<MoveData> parseInfofromfile(String line, double stdev) {
    boolean hasOwnership = false;
    String[] lineInfo = null;
    if (line.contains("ownership")) {
      hasOwnership = true;
      lineInfo = line.split("ownership");
      line = lineInfo[0];
    }
    List<MoveData> bestMoves = new ArrayList<>();
    String[] variations = line.split(" info ");
    int k =
        (Lizzie.config.limitMaxSuggestion > 0 && !Lizzie.config.showNoSuggCircle
            ? Lizzie.config.limitMaxSuggestion
            : 361);
    for (String var : variations) {
      if (!var.trim().isEmpty()) {
        bestMoves.add(MoveData.fromInfofromfile(var, bestMoves));
        k = k - 1;
        if (k < 1) break;
      }
    }
    if (bestMoves.size() > 0) bestMoves.get(0).scoreStdev = stdev;
    ArrayList<Double> estimateArray = new ArrayList<Double>();
    if (hasOwnership && lineInfo != null && lineInfo.length > 1) {
      String[] params2 = lineInfo[1].trim().split(" ");
      for (int i = 0; i < params2.length; i++) {
        try {
          estimateArray.add(Double.parseDouble(params2[i]));
        } catch (NumberFormatException e) {
          break;
        }
      }
    } else estimateArray = null;
    Lizzie.board
        .getData()
        .tryToSetBestMoves(
            bestMoves,
            Lizzie.board.getData().engineName,
            false,
            MoveData.getPlayouts(bestMoves),
            estimateArray);

    return bestMoves;
  }

  private static List<MoveData> parseInfofromfile2(String line, double stdev) {
    boolean hasOwnership = false;
    String[] lineInfo = null;
    if (line.contains("ownership")) {
      hasOwnership = true;
      lineInfo = line.split("ownership");
      line = lineInfo[0];
    }
    List<MoveData> bestMoves = new ArrayList<>();
    String[] variations = line.split(" info ");
    // int k =
    // Lizzie.config.config.getJSONObject("leelaz").getInt("max-suggestion-moves");
    for (String var : variations) {
      if (!var.trim().isEmpty()) {
        bestMoves.add(MoveData.fromInfofromfile(var, bestMoves));
        // k = k - 1;
        // if (k < 1) break;
      }
    }
    if (bestMoves.size() > 0) bestMoves.get(0).scoreStdev = stdev;
    ArrayList<Double> estimateArray = new ArrayList<Double>();
    if (hasOwnership && lineInfo != null && lineInfo.length > 1) {
      String[] params2 = lineInfo[1].trim().split(" ");
      for (int i = 0; i < params2.length; i++) {
        try {
          estimateArray.add(Double.parseDouble(params2[i]));
        } catch (NumberFormatException e) {
          break;
        }
      }
    } else estimateArray = null;
    Lizzie.board
        .getData()
        .tryToSetBestMoves2(
            bestMoves,
            Lizzie.board.getData().engineName2,
            false,
            MoveData.getPlayouts(bestMoves),
            estimateArray);
    return bestMoves;
  }

  public static String asCoord(int[] c) {
    return asCoord(c[0], c[1], Board.boardWidth, Board.boardHeight);
  }

  private static String asCoord(int x, int y, int boardWidth, int boardHeight) {
    if (boardHeight >= 52 || boardWidth >= 52) {
      return x + "_" + y;
    } else {
      return new String(new char[] {alphabet.charAt(x), alphabet.charAt(y)});
    }
  }
}

class BlunderMoves implements Comparable<BlunderMoves> {

  public int moveNumber;
  public Double diffWinrate;

  public BlunderMoves(int moveNumber, Double diffWinrate) {
    this.moveNumber = moveNumber;
    this.diffWinrate = diffWinrate;
  }

  @Override
  public String toString() {
    return moveNumber
        + "("
        + (diffWinrate > 0 ? "+" : "-")
        + String.format(Locale.ENGLISH, "%.1f", Math.abs(diffWinrate))
        + "%)";
  }

  @Override
  public int compareTo(BlunderMoves move) {
    if (Math.abs(move.diffWinrate) > Math.abs(this.diffWinrate)) return 1;
    else return -1;
  }
}
