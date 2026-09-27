package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SGFParserDateTest {
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "2026-09-12", "2026", "2026-09", "2026-09-12,13,10-01"})
  void importedDateSurvivesRepeatedSave(String date) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      String sgf = "(;SZ[5]" + (date == null ? "" : "DT[" + date + "]") + ";B[aa];W[bb])";
      for (int round = 0; round < 3; round++) {
        Lizzie.board.setHistory(SGFParser.parseSgf(sgf, false));
        assertEquals(date, Lizzie.board.getHistory().getGameInfo().getDate());
        sgf = SGFParser.saveToString(false);
        assertDate(date, sgf);
      }
    }
  }

  @Test
  void liveLoadAndEditReloadKeepTheImportedDateInSaveSnapshots() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromString("(;SZ[5]DT[2026-09-12];B[aa])", false));
      assertEquals("2026-09-12", Lizzie.board.getHistory().getGameInfo().getDate());
      assertDate("2026-09-12", saveSnapshot());
      assertTrue(SGFParser.loadFromStringforedit(SGFParser.saveToString(false)));
      assertDate("2026-09-12", saveSnapshot());

      assertTrue(SGFParser.loadFromString("(;SZ[5];W[bb])", false));
      assertNull(Lizzie.board.getHistory().getGameInfo().getDate());
      assertDate(null, saveSnapshot());
    }
  }

  @Test
  void explicitDateChangesOverrideImportedRootAndCanClearTheDate() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5]DT[2026-09-12];B[aa])", false));
      Lizzie.board.getHistory().getGameInfo().setDate("2001-02-03");
      assertDate("2001-02-03", SGFParser.saveToString(false));
      assertDate("2001-02-03", saveSnapshot());
      Lizzie.board.getHistory().getGameInfo().setDate(null);
      assertDate(null, SGFParser.saveToString(false));
      assertDate(null, saveSnapshot());
    }
  }

  private static void assertDate(String expected, String sgf) {
    var properties = SGFParser.parseSgf(sgf, false).getStart().getData().getProperties();
    assertEquals(expected, properties.get("DT"));
    if (expected == null) {
      assertFalse(properties.containsKey("DT"));
    }
  }

  private static String saveSnapshot() throws Exception {
    AtomicReference<String> saved = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            saved.set(SGFParser.saveSnapshot(Lizzie.board, false, false, false));
          } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          }
        });
    return saved.get();
  }
}
