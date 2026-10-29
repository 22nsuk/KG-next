package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.util.AutomaticSaveIntent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutomaticSaveIntentTest {
  @TempDir Path directory;
  private static final String SGF = "(;SZ[5]C[복구];B[aa])";

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void matchingReplacementMergesOnlyItsOwnMetadataAndClearsAfterCommit(int index) throws Exception {
    JSONObject candidate = metadata(index);
    AutomaticSaveIntent.write(directory, index, SGF, candidate);
    Files.writeString(sgf(index), SGF);
    AutomaticSaveIntent loaded = AutomaticSaveIntent.read(directory);
    assertNotNull(loaded);
    assertEquals(index, loaded.index());
    assertEquals(SGF, loaded.readMatchingSgf());
    JSONObject current = new JSONObject().put("unrelated", "later rename")
        .put("save-auto-game-index2", -5).put("save-auto-game-time" + (3 - index), "other time");
    String before = current.toString();
    JSONObject merged = loaded.mergeInto(current);
    assertEquals(before, current.toString());
    assertEquals("later rename", merged.getString("unrelated"));
    assertEquals("other time", merged.getString("save-auto-game-time" + (3 - index)));
    assertEquals("captured time", merged.getString("save-auto-game-time" + index));
    assertEquals("captured cursor", merged.getString("save-auto-game-move-list" + index));
    assertEquals(1, merged.getInt("save-auto-game-move-number" + index));
    assertEquals(index == 1 ? -1 : -5, merged.getInt("save-auto-game-index2"));
    assertThrows(IOException.class, () -> AutomaticSaveIntent.write(directory, index, SGF, candidate));
    loaded.clear();
    assertNull(AutomaticSaveIntent.read(directory));
    assertEquals(SGF, Files.readString(sgf(index)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void interruptionBeforeSgfReplacementDoesNotAdoptOldOrAbsentTarget(boolean existing) throws Exception {
    AutomaticSaveIntent intent = AutomaticSaveIntent.write(directory, 2, SGF, metadata(2));
    if (existing) Files.writeString(sgf(2), "(;SZ[5]C[older])");
    assertNull(intent.readMatchingSgf());
    intent.clear();
    assertNull(AutomaticSaveIntent.read(directory));
    if (existing) assertEquals("(;SZ[5]C[older])", Files.readString(sgf(2)));
    else assertFalse(Files.exists(sgf(2)));
  }

  @Test
  void unreadableSourceAndMalformedIntentRemainAvailableForRecovery() throws Exception {
    AutomaticSaveIntent intent = AutomaticSaveIntent.write(directory, 1, SGF, metadata(1));
    Files.createDirectory(sgf(1));
    Files.writeString(sgf(1).resolve("keep"), "keep");
    assertThrows(IOException.class, intent::readMatchingSgf);
    assertNotNull(AutomaticSaveIntent.read(directory));
    Path marker = directory.resolve("save/automatic-save-intent.json");
    Files.writeString(marker, "{broken");
    assertThrows(IOException.class, () -> AutomaticSaveIntent.read(directory));
    assertThrows(IOException.class, () -> AutomaticSaveIntent.write(directory, 1, SGF, metadata(1)));
    assertThrows(IOException.class, intent::clear);
    assertEquals("{broken", Files.readString(marker));
    assertEquals("keep", Files.readString(sgf(1).resolve("keep")));
  }

  @Test
  void legacyDirectoryNeedsNoMigration() throws Exception {
    assertNull(AutomaticSaveIntent.read(directory));
    assertFalse(Files.exists(directory.resolve("save")));
  }

  private Path sgf(int index) { return directory.resolve("save/autoGame" + index + ".sgf"); }

  private static JSONObject metadata(int index) {
    return new JSONObject().put("save-auto-game-time" + index, "captured time")
        .put("save-auto-game-move-number" + index, 1)
        .put("save-auto-game-move-list" + index, "captured cursor");
  }
}
