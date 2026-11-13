package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GameInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SGFKomiRoundTripTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(doubles = {0, 0.5, 6.5, 7.5, 7.25, -0.25, 3.75, 200, 360.5})
  void previousKgNextSavesKeepTheirActualKomi(double komi) throws Exception {
    try (var ignored = RulesLayerTestHarness.open(19)) {
      assertAllReaders("(;SZ[19]KM[" + komi
          + "]AP[KG-next: next-2026-07-13.2];B[aa])", komi);
    }
  }

  @ParameterizedTest
  @CsvSource({
      "'',3.75,7.5", "'',-0.25,-0.5", "'',200,4", "'',650,6.5",
      "AP[GNU Go:3.8],50,50", "AP[GNU Go:3.8],3.25,6.5",
      "AP[foxwq]RU[Japanese],50,0.5", "AP[foxwq]RU[Chinese],375,7.5",
      "AP[foxwq]RU[Japanese],650,6.5",
      "AP[other-KG-next:1],3.75,7.5", "AP[KG-next-other:1],3.75,7.5",
      "AP[Other:KG-next],3.75,7.5",
      "AP[KG-next:1]AP[Other:1],3.75,7.5",
      "AP[Other:1]AP[KG-next:1],3.75,3.75",
      "AP[KG-next:foxwq],200,200", "AP[KG-next],3.75,3.75"
  })
  void sourceIdentityPreservesLegacyConversionsAndUsesTheLastApplication(
      String properties, double stored, double expected) throws Exception {
    try (var ignored = RulesLayerTestHarness.open(19)) {
      assertAllReaders("(;SZ[19]" + properties + "KM[" + stored + "];B[aa])", expected);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "KM[7.25]KO[375]AP[KG-next:old]",
      "KO[375]AP[KG-next:old]KM[7.25]",
      "AP[KG-next:old]KM[7.25]KO[375]"
  })
  void savedRootKomiWinsOverALegacyAliasRegardlessOfHeaderOrder(String properties)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open(19)) {
      assertAllReaders("(;SZ[19]" + properties + ";B[aa])", 7.25);
    }
  }

  @ParameterizedTest
  @CsvSource({
      "KO[3.25],6.5", "AP[KG-next:old]KO[3.25],6.5",
      "KM[6.5]KO[375],7.5", "KO[375]KM[6.5],6.5"
  })
  void liveLegacyAliasAndTagOrderRemainCompatible(String properties, double expected)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open(19)) {
      String sgf = "(;SZ[19]" + properties + ";B[aa])";
      assertTrue(SGFParser.loadFromString(sgf, false));
      assertEquals(expected, Lizzie.board.getHistory().getGameInfo().getKomi());
      assertTrue(SGFParser.loadFromStringforedit(sgf));
      assertEquals(expected, Lizzie.board.getHistory().getGameInfo().getKomi());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "361", "-361", "400", "-400"})
  void ownApplicationDoesNotBypassKomiValidityChecks(String komi) throws Exception {
    try (var ignored = RulesLayerTestHarness.open(19)) {
      assertAllReaders("(;SZ[19]AP[KG-next:old]KM[" + komi + "];B[aa])",
          GameInfo.DEFAULT_KOMI);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "HA[2]AB[aa][ss]", "AW[ss]"})
  void readKomiDisabledStillDistinguishesOrdinaryAndSetupGames(String setup) throws Exception {
    try (var ignored = RulesLayerTestHarness.open(19)) {
      Lizzie.config.readKomi = false;
      assertAllReaders("(;SZ[19]AP[KG-next:old]" + setup + "KM[7.25];B[bb])",
          setup.isEmpty() ? GameInfo.DEFAULT_KOMI : 7.25);
    }
  }

  @Test
  void detachedLiteralKomiUsesItsOwnBoardArea() throws Exception {
    try (var ignored = RulesLayerTestHarness.open(5)) {
      var history = SGFParser.parseSgf("(;SZ[13:19]AP[KG-next:old]KM[200];B[aa])", true);
      assertEquals(200, history.getGameInfo().getKomi());
      assertEquals(5, Board.boardWidth);
    }
  }

  enum SaveMode {
    FILE, AUTO_FILE, STRING, UPLOAD, RAW, RAW_COMMENTS, BRANCH, RAW_BRANCH
  }

  @ParameterizedTest
  @EnumSource(SaveMode.class)
  void saveModesRecordTheCurrentWriterAndKeepEditedKomiOnRepeatedFileOpen(SaveMode mode)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open(19)) {
      var history = SGFParser.parseSgf(
          "(;SZ[19]AP[foxwq]RU[Chinese]KM[375]C[root];B[aa](;W[bb])(;W[cc]))", true);
      assertEquals(7.5, history.getGameInfo().getKomi());
      history.getGameInfo().setKomiNoMenu(7.25);
      history.setHead(history.getStart().next().orElseThrow().getVariation(1).orElseThrow());
      Lizzie.board.setHistory(history);
      for (int round = 0; round < 2; round++) {
        var live = Lizzie.board.getHistory();
        var selected = live.getCurrentHistoryNode();
        var properties = new LinkedHashMap<>(live.getStart().getData().getProperties());
        Path file = directory.resolve("saved-" + round + ".sgf");
        String saved = save(mode, file);
        assertSame(live, Lizzie.board.getHistory());
        assertSame(selected, live.getCurrentHistoryNode());
        assertEquals(properties, live.getStart().getData().getProperties());
        assertTrue(saved.contains("AP[KG-next: " + Lizzie.nextVersion + "]"), saved);
        assertTrue(saved.contains("KM[7.25]"), saved);
        assertAllReaders(saved, 7.25);
        AtomicReference<Boolean> loaded = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
          try {
            loaded.set(SGFParser.load(file.toString(), false, false));
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
        // The file loader posts its navigation finalizer after applying the parser result.
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(true, loaded.get());
        assertEquals(7.25, Lizzie.board.getHistory().getGameInfo().getKomi());
      }
    }
  }

  private String save(SaveMode mode, Path file) throws Exception {
    if (mode == SaveMode.FILE || mode == SaveMode.AUTO_FILE) {
      SGFParser.save(Lizzie.board, file.toString(), mode == SaveMode.AUTO_FILE);
    } else if (mode == SaveMode.STRING || mode == SaveMode.UPLOAD) {
      Files.writeString(file, SGFParser.saveToString(mode == SaveMode.UPLOAD));
    } else {
      AtomicReference<SGFParser.SaveSnapshot> snapshot = new AtomicReference<>();
      SwingUtilities.invokeAndWait(() -> {
        try {
          snapshot.set(SGFParser.captureSnapshot(Lizzie.board,
              mode != SaveMode.BRANCH, mode == SaveMode.RAW_COMMENTS,
              mode == SaveMode.BRANCH || mode == SaveMode.RAW_BRANCH));
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      });
      Files.writeString(file, snapshot.get().serialize());
    }
    return Files.readString(file);
  }

  private static void assertAllReaders(String sgf, double expected) throws Exception {
    var detached = SGFParser.parseSgf(sgf, true);
    assertNotNull(detached);
    assertEquals(expected, detached.getGameInfo().getKomi(), "detached: " + sgf);
    assertTrue(SGFParser.loadFromString(sgf, false));
    assertEquals(expected, Lizzie.board.getHistory().getGameInfo().getKomi(), "live: " + sgf);
    assertTrue(SGFParser.loadFromStringforedit(sgf));
    assertEquals(expected, Lizzie.board.getHistory().getGameInfo().getKomi(), "edit: " + sgf);
  }
}
