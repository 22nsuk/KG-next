package featurecat.lizzie.rules;

import java.util.Map;

/**
 * Recognizer for initial handicap setup nodes.
 *
 * <p>Validates that a node represents the initial handicap placement for a game according to the
 * following invariants:
 * <ul>
 *   <li>The node is a snapshot node (not a genuine move, pass, or dummy node).</li>
 *   <li>All ancestors back to root are pre-action snapshots with no stones or setup properties
 *       (metadata-only ancestry is supported, partial setup count is rejected).</li>
 *   <li>No genuine MOVE or PASS actions appear on the ancestor path.</li>
 *   <li>Explicit HA &gt;= 2 is declared either via caller GameInfo or node/ancestor properties, with no
 *       conflicting declarations, capped at GTP area - 1.</li>
 *   <li>The board at the node has exactly HA unique valid black stones and zero white stones.</li>
 *   <li>No AW or AE properties exist on the node.</li>
 *   <li>Explicit PL must be 'W' if present (PL[B] is rejected).</li>
 *   <li>Any explicit AB coordinates must be unique, within bounds, and match black stones.</li>
 * </ul>
 *
 * <p>This recognizer is pure and causes no history or global state mutations.
 */
public final class InitialHandicapSetup {

  private static final String SGF_ALPHABET =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

  private InitialHandicapSetup() {}

  /**
   * Recognizes whether {@code node} is an initial handicap setup node.
   *
   * @param node the history node to inspect
   * @param declaredHandicap the handicap declared in GameInfo (0 if unavailable)
   * @param width the board width
   * @param height the board height
   * @return {@code true} if {@code node} satisfies initial handicap invariants, {@code false} otherwise
   */
  public static boolean isInitialHandicap(
      BoardHistoryNode node, int declaredHandicap, int width, int height) {
    if (node == null || width <= 0 || height <= 0 || declaredHandicap < 0) {
      return false;
    }
    BoardData data = node.getData();
    if (data == null || data.isHistoryActionNode() || data.dummy) {
      return false;
    }

    int resolvedHandicap = declaredHandicap;

    // Check explicit HA on target node if present
    String nodeHaStr = data.getProperty("HA");
    if (nodeHaStr != null) {
      int nodeHa = parseExplicitHandicap(nodeHaStr);
      if (nodeHa < 2 || (resolvedHandicap > 0 && resolvedHandicap != nodeHa)) {
        return false;
      }
      resolvedHandicap = nodeHa;
    }

    // Single ancestor traversal back to root
    BoardHistoryNode curr = node.previous().orElse(null);
    while (curr != null) {
      BoardData currData = curr.getData();
      if (currData == null || currData.isHistoryActionNode() || currData.dummy) {
        return false;
      }
      Stone[] currStones = currData.stones;
      if (currStones != null) {
        for (Stone s : currStones) {
          if (s != null && !s.isEmpty()) {
            return false;
          }
        }
      }
      if (curr.extraStones != null && !curr.extraStones.isEmpty()) {
        return false;
      }
      Map<String, String> currProps = currData.getProperties();
      if (currProps != null) {
        if (currProps.containsKey("AB") || currProps.containsKey("AW") || currProps.containsKey("AE")) {
          return false;
        }
        String currPl = currProps.get("PL");
        if (currPl != null && !currPl.trim().isEmpty() && !"W".equalsIgnoreCase(currPl.trim())) {
          return false;
        }
        String currHaStr = currProps.get("HA");
        if (currHaStr != null) {
          int currHa = parseExplicitHandicap(currHaStr);
          if (currHa < 2 || (resolvedHandicap > 0 && resolvedHandicap != currHa)) {
            return false;
          }
          resolvedHandicap = currHa;
        }
      }
      curr = curr.previous().orElse(null);
    }

    // Validate handicap range: explicit HA >= 2 and GTP max area - 1 (cannot fill entire board)
    int area = width * height;
    if (resolvedHandicap < 2 || resolvedHandicap >= area) {
      return false;
    }

    // Check node properties: no AW/AE, and explicit PL must be 'W' if present
    Map<String, String> nodeProps = data.getProperties();
    if (nodeProps != null) {
      if (nodeProps.containsKey("AW") || nodeProps.containsKey("AE")) {
        return false;
      }
      String pl = nodeProps.get("PL");
      if (pl != null && !pl.trim().isEmpty() && !"W".equalsIgnoreCase(pl.trim())) {
        return false;
      }
    }

    // Check stones on node: exactly resolvedHandicap black stones, zero white stones
    Stone[] stones = data.stones;
    if (stones == null || stones.length != area) {
      return false;
    }
    int blackCount = 0;
    for (int i = 0; i < area; i++) {
      Stone s = stones[i];
      if (s == null || s.isEmpty()) {
        continue;
      }
      if (s.isBlack()) {
        blackCount++;
      } else {
        return false;
      }
    }
    if (blackCount != resolvedHandicap) {
      return false;
    }

    // Check extraStones if present
    if (node.extraStones != null) {
      for (ExtraStones es : node.extraStones) {
        if (!es.isBlack || es.x < 0 || es.x >= width || es.y < 0 || es.y >= height) {
          return false;
        }
      }
    }

    // If AB property is explicitly present, reject duplicate or invalid coordinates
    if (nodeProps != null && nodeProps.containsKey("AB")) {
      String abStr = nodeProps.get("AB");
      if (abStr == null || abStr.trim().isEmpty()) {
        return false;
      }
      String[] parts = abStr.split(",");
      if (parts.length != resolvedHandicap) {
        return false;
      }
      boolean[] seen = new boolean[area];
      for (String part : parts) {
        int[] coord = parseSgfCoord(part, width, height);
        if (coord == null) {
          return false;
        }
        int idx = coord[0] * height + coord[1];
        if (seen[idx] || stones[idx] != Stone.BLACK) {
          return false;
        }
        seen[idx] = true;
      }
    }

    return true;
  }

  private static int parseExplicitHandicap(String raw) {
    if (raw == null) {
      return -1;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return -1;
    }
    try {
      return Integer.parseInt(trimmed);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static int[] parseSgfCoord(String pos, int width, int height) {
    if (pos == null) {
      return null;
    }
    String trimmed = pos.trim();
    if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() >= 2) {
      trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
    }
    if (trimmed.isEmpty()) {
      return null;
    }
    if (width >= 52 || height >= 52) {
      int sep = trimmed.indexOf('_');
      if (sep <= 0 || sep == trimmed.length() - 1) {
        return null;
      }
      try {
        int x = Integer.parseInt(trimmed.substring(0, sep));
        int y = Integer.parseInt(trimmed.substring(sep + 1));
        if (x < 0 || x >= width || y < 0 || y >= height) {
          return null;
        }
        return new int[] {x, y};
      } catch (NumberFormatException e) {
        return null;
      }
    }
    if (trimmed.length() != 2) {
      return null;
    }
    int x = SGF_ALPHABET.indexOf(trimmed.charAt(0));
    int y = SGF_ALPHABET.indexOf(trimmed.charAt(1));
    if (x < 0 || x >= width || y < 0 || y >= height) {
      return null;
    }
    return new int[] {x, y};
  }
}
