package featurecat.lizzie.rules;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.util.EncodingDetector;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class GIBParser {
  private static int[][] handicapPlacement = {
    {3, 15}, {15, 3}, {15, 15}, {3, 3}, {3, 9}, {15, 9}, {9, 3}, {9, 15}, {9, 9}
  };

  public static boolean load(String filename) throws IOException {
    File file = new File(filename);
    if (!file.exists() || !file.canRead()) {
      return false;
    }

    String encoding = EncodingDetector.detect(filename);
    String value;
    try (FileInputStream fp = new FileInputStream(file);
        InputStreamReader reader =
            new InputStreamReader(fp, encoding.equals("WINDOWS-1252") ? "GB18030" : encoding)) {
      StringBuilder builder = new StringBuilder();
      while (reader.ready()) {
        builder.append((char) reader.read());
      }
      value = builder.toString();
    }
    if (value.isEmpty()) {
      return false;
    }

    GibGame game;
    try {
      game = parse(value);
    } catch (IllegalArgumentException invalidGib) {
      return false;
    }
    if (game == null) return false;

    Board board = Lizzie.board;
    Board.ClearStateSnapshot previous = board.captureClearState();
    boolean oriEmpty = EngineManager.isEmpty;
    boolean oriPlaySound = Lizzie.config.playSound;
    try {
      try {
        board.clear(false);
        EngineManager.isEmpty = true;
        Lizzie.config.playSound = false;
        for (Consumer<Board> move : game.moves()) move.accept(board);
        if (Lizzie.config.readKomi) {
          board.getHistory().getGameInfo().setKomi(game.komi());
        }
        Lizzie.frame.setPlayers(game.whitePlayer(), game.blackPlayer());
        GameInfo gameInfo = board.getHistory().getGameInfo();
        gameInfo.setPlayerBlack(game.blackPlayer());
        gameInfo.setPlayerWhite(game.whitePlayer());
        while (board.previousMove(false))
          ;
        if (Lizzie.config.loadSgfLast)
          while (board.nextMove(false))
            ;
        board.clearAfterMove();
        Lizzie.frame.refresh();
        return true;
      } finally {
        EngineManager.isEmpty = oriEmpty;
        Lizzie.config.playSound = oriPlaySound;
      }
    } catch (RuntimeException | Error failure) {
      try {
        board.restoreClearState(previous);
        if (!oriEmpty) board.resendCurrentPositionToPrimaryEngine();
      } catch (RuntimeException | Error restoreFailure) {
        failure.addSuppressed(restoreFailure);
      }
      throw failure;
    }
  }

  private static void placeHandicap(Board board, int handi) {
    if (handi > 9) {
      System.out.println("More than 9 in handicap not supported!");
      handi = 9;
    }
    if (handi == 5 || handi == 7) {
      board.place(9, 9, Stone.BLACK);
      handi--;
    }
    for (int i = 0; i < handi; i++) {
      board.place(handicapPlacement[i][0], handicapPlacement[i][1], Stone.BLACK);
    }
  }

  private record GibGame(
      String whitePlayer, String blackPlayer, double komi, List<Consumer<Board>> moves) {}

  private static GibGame parse(String value) {
    String whitePlayer = "Player 1";
    String blackPlayer = "Player 2";
    double komi = 7.5;
    List<Consumer<Board>> moves = new ArrayList<>();
    boolean recognized = false;

    // Decode every record before clearing the live board or forwarding anything to its engine.
    for (String rawLine : value.split("\n")) {
      String line = rawLine.trim();
      if (line.startsWith("\\[GAMEINFOMAIN=")) {
        String info = headerValue(line, "\\[GAMEINFOMAIN=");
        recognized = true;
        int i = info.indexOf("GONGJE:");
        if (i != -1) {
          int sk = i + "GONGJE:".length();
          int ek = info.indexOf(',', sk);
          komi = Integer.parseInt(info.substring(sk, ek < 0 ? info.length() : ek)) / 10.0;
        }
      } else if (line.startsWith("\\[GAMEBLACKNAME=")) {
        blackPlayer = headerValue(line, "\\[GAMEBLACKNAME=");
        recognized = true;
      } else if (line.startsWith("\\[GAMEWHITENAME=")) {
        whitePlayer = headerValue(line, "\\[GAMEWHITENAME=");
        recognized = true;
      } else {
        String[] fields = line.split("\\s+");
        switch (fields[0]) {
          case "INI":
            if (fields.length < 4) throw new IllegalArgumentException("Incomplete GIB INI record");
            int handicap = Integer.parseInt(fields[3]);
            if (handicap >= 2) moves.add(board -> placeHandicap(board, handicap));
            recognized = true;
            break;
          case "STO":
            if (fields.length < 6) throw new IllegalArgumentException("Incomplete GIB STO record");
            int x = Integer.parseInt(fields[4]);
            int y = Integer.parseInt(fields[5]);
            Stone color = fields[3].equals("1") ? Stone.BLACK : Stone.WHITE;
            moves.add(board -> board.place(x, y, color));
            recognized = true;
            break;
          case "SKI":
            moves.add(Board::pass);
            recognized = true;
            break;
          default:
            break;
        }
      }
    }
    return recognized ? new GibGame(whitePlayer, blackPlayer, komi, moves) : null;
  }

  private static String headerValue(String line, String prefix) {
    if (!line.endsWith("\\]")) throw new IllegalArgumentException("Incomplete GIB header");
    return line.substring(prefix.length(), line.length() - 2);
  }
}
