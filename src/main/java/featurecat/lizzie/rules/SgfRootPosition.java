package featurecat.lizzie.rules;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** ReadBoard can observe a position and its counters without having the preceding moves. */
record SgfRootPosition(String value, int eventCount) {
  static final String PROPERTY = "KGRP";
  private static final String VERSION = "KG-next/1 ";
  private static final String PLAIN = VERSION + "plain";

  static SgfDocument.Event lastPayload(SgfDocument document, SgfCancellation cancellation) {
    return document.rootPropertyEvent(PROPERTY, SgfRootPosition::hasVersion, cancellation);
  }

  private static boolean hasVersion(String value) {
    return value.startsWith(VERSION);
  }

  static void appendTo(Map<String, List<String>> properties, BoardData data, int width, int height) {
    String payload = encode(data, width, height);
    // Export changes AP to KG-next. A trailing neutral payload keeps earlier values opaque.
    if (payload == null
        && data.getPropertyValues(PROPERTY).stream().anyMatch(SgfRootPosition::hasVersion)) {
      payload = PLAIN;
    }
    if (payload != null) SGFParser.addProperty(properties, PROPERTY, payload);
  }

  private static String encode(BoardData data, int width, int height) {
    if (!data.isSnapshotNode()) return null;
    String marker = data.lastMoveOnBoard(width, height)
        .map(point -> (data.lastMoveColor == Stone.BLACK ? "B" : "W")
            + SgfDocument.asCoord(point[0], point[1], width, height))
        .orElse("-");
    if (data.moveNumber == 0 && data.blackCaptures == 0 && data.whiteCaptures == 0
        && marker.equals("-")) return null;
    StringBuilder out = new StringBuilder(VERSION).append(data.moveNumber).append(' ')
        .append(data.blackCaptures).append(' ').append(data.whiteCaptures).append(' ').append(marker);
    if (data.moveNumberList != null) {
      for (int i = 0; i < data.stones.length; i++) {
        int number = data.moveNumberList[i];
        if (number > 0 && data.stones[i] != Stone.EMPTY) {
          out.append(' ').append(data.stones[i] == Stone.BLACK ? 'B' : 'W')
              .append(SgfDocument.asCoord(i / height, i % height, width, height))
              .append(':').append(number);
        }
      }
    }
    return out.toString();
  }

  /** Validate against the completed setup before publishing any part of the private payload. */
  void restore(BoardData data, SgfCancellation cancellation) {
    if (PLAIN.equals(value) || value.length() > 64L + 36L * data.stones.length) return;
    String[] fields = value.split(" ", -1);
    if (fields.length < 5 || fields.length - 5 > data.stones.length) return;
    try {
      int moveNumber = Integer.parseInt(fields[1]);
      int blackCaptures = Integer.parseInt(fields[2]);
      int whiteCaptures = Integer.parseInt(fields[3]);
      // Each subsequent action can add a move or a stone that is captured later.
      long remainingCaptures = (long) data.stones.length + eventCount;
      if (moveNumber < 0 || (long) moveNumber + eventCount > Integer.MAX_VALUE
          || blackCaptures < 0 || blackCaptures + remainingCaptures > Integer.MAX_VALUE
          || whiteCaptures < 0 || whiteCaptures + remainingCaptures > Integer.MAX_VALUE) return;
      int[] marker = null;
      if (!fields[4].equals("-")) {
        marker = stonePoint(data, fields[4]);
        if (marker == null) return;
      }
      int[] numbers = new int[data.stones.length];
      for (int i = 5; i < fields.length; i++) {
        cancellation.checkEvery(i);
        int colon = fields[i].indexOf(':');
        if (colon < 0) return;
        int[] point = stonePoint(data, fields[i].substring(0, colon));
        if (point == null) return;
        int index = data.zobrist.index(point[0], point[1]);
        int number = Integer.parseInt(fields[i].substring(colon + 1));
        if (numbers[index] != 0 || number <= 0 || number > moveNumber) return;
        numbers[index] = number;
      }
      data.moveNumber = moveNumber;
      data.blackCaptures = blackCaptures;
      data.whiteCaptures = whiteCaptures;
      data.lastMove = Optional.ofNullable(marker);
      data.lastMoveColor = marker == null ? Stone.EMPTY : data.stones[data.zobrist.index(marker[0], marker[1])];
      data.moveNumberList = numbers;
    } catch (NumberFormatException invalidCounter) {
      // Standard setup and PL remain usable when another editor has damaged the extension.
    }
  }

  private static int[] stonePoint(BoardData data, String token) {
    if (token.isEmpty() || (token.charAt(0) != 'B' && token.charAt(0) != 'W')) return null;
    int[] point = SgfDocument.parseBoardPoint(
        token.substring(1), data.zobrist.width(), data.zobrist.height());
    Stone color = token.charAt(0) == 'B' ? Stone.BLACK : Stone.WHITE;
    return point != null && data.stones[data.zobrist.index(point[0], point[1])] == color ? point : null;
  }
}
