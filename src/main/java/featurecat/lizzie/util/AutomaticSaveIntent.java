package featurecat.lizzie.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.json.JSONObject;

/** Identifies an automatic SGF replacement whose manifest may not have reached disk yet. */
public final class AutomaticSaveIntent {
  private static final String FILE_NAME = "automatic-save-intent.json";
  private final Path marker;
  private final int index;
  private final String digest;
  private final String time;
  private final int moveNumber;
  private final String moves;
  private final String encoded;

  private AutomaticSaveIntent(Path marker, String encoded) throws IOException {
    this.marker = marker;
    this.encoded = encoded;
    try {
      JSONObject record = new JSONObject(encoded);
      if (record.getInt("version") != 1) throw new IllegalArgumentException("Unknown intent version");
      index = record.getInt("slot");
      if (index != 1 && index != 2) throw new IllegalArgumentException("Invalid automatic slot");
      digest = record.getString("sha256");
      if (!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SGF digest");
      time = record.getString("time");
      moveNumber = record.getInt("move-number");
      if (moveNumber < 0) throw new IllegalArgumentException("Invalid move number");
      moves = record.getString("move-list");
    } catch (RuntimeException failure) {
      throw new IOException("Invalid automatic-save intent: " + marker, failure);
    }
  }

  /** Called on the shared save worker, before replacing either automatic SGF. */
  public static AutomaticSaveIntent write(
      Path workDirectory, int index, String sgf, JSONObject candidate) throws IOException {
    Path marker = marker(workDirectory);
    // Never replace unresolved recovery evidence, including malformed or unreadable records.
    if (read(workDirectory) != null) throw new IOException("Automatic-save intent is still pending: " + marker);
    String prefix = "save-auto-game-";
    JSONObject record = new JSONObject()
        .put("version", 1).put("slot", index).put("sha256", digest(sgf))
        .put("time", candidate.getString(prefix + "time" + index))
        .put("move-number", candidate.getInt(prefix + "move-number" + index))
        .put("move-list", candidate.getString(prefix + "move-list" + index));
    AutomaticSaveIntent intent = new AutomaticSaveIntent(marker, record.toString(2));
    Files.createDirectories(marker.getParent());
    AtomicTextFileWriter.write(marker, intent.encoded);
    return intent;
  }

  /** Absence is legacy/settled state; malformed or unreadable evidence must not be discarded. */
  public static boolean exists(Path workDirectory) throws IOException {
    try {
      Files.readAttributes(marker(workDirectory), BasicFileAttributes.class);
      return true;
    } catch (NoSuchFileException absent) {
      return false;
    }
  }

  /** Absence is legacy/settled state; malformed or unreadable evidence must not be discarded. */
  public static AutomaticSaveIntent read(Path workDirectory) throws IOException {
    Path marker = marker(workDirectory);
    try {
      return new AutomaticSaveIntent(marker, Files.readString(marker));
    } catch (NoSuchFileException absent) {
      return null;
    }
  }

  public int index() { return index; }

  /** Null means the intended replacement never arrived; the existing target stays untouched. */
  public String readMatchingSgf() throws IOException {
    try {
      String sgf = Files.readString(marker.getParent().resolve("autoGame" + index + ".sgf"));
      return digest.equals(digest(sgf)) ? sgf : null;
    } catch (NoSuchFileException absent) {
      return null;
    }
  }

  /** Merge only this save's metadata, retaining later unrelated saved-list edits. */
  public JSONObject mergeInto(JSONObject current) {
    JSONObject merged = new JSONObject(current.toString());
    merged.put("save-auto-game-index" + index, index == 2 ? -5 : 1);
    merged.put("save-auto-game-time" + index, time);
    merged.put("save-auto-game-move-number" + index, moveNumber);
    merged.put("save-auto-game-move-list" + index, moves);
    if (index == 1) merged.put("save-auto-game-index2", -1);
    return merged;
  }

  /** Clear after committing the manifest, or after proving the SGF replacement did not happen. */
  public void clear() throws IOException {
    String current;
    try {
      current = Files.readString(marker);
    } catch (NoSuchFileException absent) {
      return;
    }
    if (!encoded.equals(current)) throw new IOException("Automatic-save intent changed: " + marker);
    Files.delete(marker);
  }

  private static Path marker(Path directory) {
    return directory.resolve("save").resolve(FILE_NAME).toAbsolutePath().normalize();
  }

  private static String digest(String sgf) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(sgf.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
