package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GetTencentRequest;
import featurecat.lizzie.analysis.GetTencentRequest.RequestKind;
import featurecat.lizzie.rules.SGFParser;
import java.awt.AWTEvent;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Production response dispatch, page state and asynchronous SGF application, without Tencent I/O. */
class TencentKifuRequestDesktopTest {
  @Test
  void responsesStayWithTheirRequestAndFailedPagesCanRetry() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.runWithStartupTimeout(
        getClass(), "tencent-requests", List.of(), List.of(), 120, 90);
    assertEquals("PASS", Files.readString(result));
    assertNotNull(ImageIO.read(result.resolveSibling("tencent-paging.png").toFile()));
  }

  public static void main(String[] args) {
    try {
      runProbe(Path.of(args[0]), Path.of(args[1]));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void runProbe(Path work, Path result) throws Exception {
    Files.writeString(work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":3,"
            + "\"auto-save-exit\":false,\"resume-previous-game\":false,"
            + "\"auto-check-engine-alive\":false,\"auto-quick-analyze-on-load\":false,"
            + "\"analysis-engine-preload\":false,\"play-sound\":false,\"tencent-after-get\":2}}");
    System.setProperty("lizzie.work.dir", work.toString());
    Lizzie.main(new String[0]);
    awaitEdt(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
    Field startup = field(LizzieFrame.class, "autoSaveStartupPrepared");
    awaitEdt(() -> startup.getBoolean(Lizzie.frame));
    TencentKifuDownload window = edt(() -> {
      for (Window dialog : Window.getWindows()) if (dialog instanceof JDialog) dialog.dispose();
      Lizzie.setPrimaryEngine(null);
      Lizzie.frame.setBounds(20, 20, 1180, 820);
      TencentKifuDownload download = new TencentKifuDownload();
      set(download, "currentQuery", "player");
      download.setVisible(true);
      return download;
    });
    DesktopProbeProcess.applicationReady(result);
    List<ScriptedRequest> requests = new ArrayList<>();
    try (MessageCloser messages = new MessageCloser()) {
      ScriptedRequest first = replaceRequest(window, requests);
      receive(window, first, RequestKind.LIST, page(1, 25));
      assertEquals(25, edt(() -> rows(window)));
      assertFalse(edt(() -> bool(window, "isComplete")), "a short page can have a successor");
      assertTrue(edt(() -> ((JLabel) get(window, "lblTab")).getText().contains("...")));

      DesktopProbeProcess.phase(result, "page-retry");
      nextPage(window);
      nextPage(window);
      assertEquals(List.of("game-25"), first.pages);
      fail(window, first, RequestKind.LIST, "{\"result\":1,\"resultstr\":\"offline\"}", messages);
      assertEquals(25, edt(() -> rows(window)));
      assertFalse(edt(() -> progressVisible(window)));
      nextPage(window);
      assertEquals(List.of("game-25", "game-25"), first.pages);

      // A valid first row must not survive a failure in the second row.
      String malformed = new JSONObject().put("result", 0).put("chesslist",
          new JSONArray().put(game(26)).put("malformed row")).toString();
      fail(window, first, RequestKind.LIST, malformed, messages);
      assertEquals(25, edt(() -> rows(window)));
      assertEquals(1, edt(() -> get(window, "curTabNumber")));
      nextPage(window);
      assertEquals(List.of("game-25", "game-25", "game-25"), first.pages);
      receive(window, first, RequestKind.LIST, page(26, 25));
      assertEquals(50, edt(() -> rows(window)));
      assertEquals(2, edt(() -> get(window, "curTabNumber")));

      DesktopProbeProcess.phase(result, "independent-completion");
      nextPage(window);
      assertEquals("game-50", first.pages.get(3));
      edt(() -> { window.loadTencentKifu("game-1"); return null; });
      fail(window, first, RequestKind.KIFU, "{\"result\":1,\"resultstr\":\"no record\"}", messages);
      assertFalse(edt(() -> bool(window, "isKifuLoading")));
      assertTrue(edt(() -> ((JTable) get(window, "table")).isEnabled()));
      assertTrue(edt(() -> progressVisible(window)), "the pending page still owns progress");
      nextPage(window);
      assertEquals(4, first.pages.size(), "a kifu failure must not release the pending page");
      receive(window, first, RequestKind.LIST, page(51, 0));
      assertTrue(edt(() -> bool(window, "isComplete")));
      assertFalse(edt(() -> progressVisible(window)));
      assertFalse(edt(() -> ((JLabel) get(window, "lblTab")).getText().contains("...")));
      int beforeNotice = messages.count.get();
      nextPage(window);
      awaitEdt(() -> messages.count.get() == beforeNotice + 1);
      assertEquals(4, first.pages.size(), "a committed empty page must not be fetched again");

      for (String payload : List.of("{\"result\":0}", "{\"chess\":\"\"}", "{\"chess\":null}")) {
        edt(() -> { window.loadTencentKifu("game-1"); return null; });
        fail(window, first, RequestKind.KIFU, payload, messages);
        assertFalse(edt(() -> bool(window, "isKifuLoading")));
        assertTrue(edt(() -> ((JTable) get(window, "table")).isEnabled()));
        assertFalse(edt(() -> progressVisible(window)));
      }
      edt(() -> { window.presentWindow(); return null; });
      ImageIO.write(new Robot().createScreenCapture(edt(window::getBounds)), "png",
          result.resolveSibling("tencent-paging.png").toFile());

      DesktopProbeProcess.phase(result, "queued-response-ownership");
      ScriptedRequest second = new ScriptedRequest(window);
      requests.add(second);
      int previousMessages = messages.count.get();
      edt(() -> {
        window.receiveResult(first, RequestKind.LIST, page(51, 1));
        window.receiveResult(first, RequestKind.LIST, "{\"result\":1,\"resultstr\":\"stale\"}");
        window.receiveResult(first, RequestKind.KIFU, sgf("old Tencent"));
        first.shutdown();
        set(window, "tencentReq", second);
        set(window, "isSearching", true);
        invoke(window, "showProgressNotice", new Class<?>[] {String.class}, "current search");
        return null;
      });
      edt(() -> null);
      assertEquals(50, edt(() -> rows(window)));
      assertTrue(edt(() -> bool(window, "isSearching")));
      assertTrue(edt(() -> progressVisible(window)));
      assertEquals(previousMessages, messages.count.get());
      // A current response must still be accepted after rejecting the old queue entries.
      receive(window, second, RequestKind.LIST, page(51, 1));
      assertEquals(51, edt(() -> rows(window)));
      assertFalse(edt(() -> bool(window, "isSearching")));

      DesktopProbeProcess.phase(result, "prepared-sgf-ownership");
      var original = edt(() -> Lizzie.board.getHistory());
      Path occupied = work.resolve("occupied.sgf");
      Files.writeString(occupied, "(;SZ[19]PB[file request];B[aa])");
      ScriptedRequest third;
      try (var read = SgfReadTestSupport.block()) {
        var file = edt(() -> Lizzie.frame.openFileAsync(occupied.toFile(), true, false));
        read.awaitStarted();
        edt(() -> { window.loadTencentKifu("game-1"); return null; });
        receive(window, second, RequestKind.KIFU, sgf("old Tencent"));
        assertTrue(edt(() -> bool(window, "isKifuLoading")));
        third = replaceRequest(window, requests);
        edt(() -> {
          set(window, "isKifuLoading", false);
          window.loadTencentKifu("game-2");
          return null;
        });
        read.release();
        assertFalse(file.get(10, TimeUnit.SECONDS));
        SgfReadTestSupport.awaitPending();
        assertSame(original, edt(() -> Lizzie.board.getHistory()));
        assertTrue(edt(() -> bool(window, "isKifuLoading")), "old completion cannot end the new load");
      }
      receive(window, third, RequestKind.KIFU, sgf("current Tencent"));
      SgfReadTestSupport.awaitPending();
      awaitEdt(() -> !bool(window, "isKifuLoading"));
      assertTrue(edt(() -> SGFParser.saveToString(false)).contains("PB[current Tencent]"));
      assertTrue(edt(() -> ((JTable) get(window, "table")).isEnabled()));
      assertFalse(edt(() -> progressVisible(window)));
      Files.writeString(result, "PASS");
    } finally {
      for (ScriptedRequest request : requests) request.shutdown();
      edt(() -> { window.dispose(); return null; });
    }
  }

  private static ScriptedRequest replaceRequest(TencentKifuDownload window,
      List<ScriptedRequest> requests) throws Exception {
    ScriptedRequest request = new ScriptedRequest(window);
    requests.add(request);
    edt(() -> {
      GetTencentRequest previous = (GetTencentRequest) get(window, "tencentReq");
      if (previous != null) previous.shutdown();
      set(window, "tencentReq", request);
      return null;
    });
    return request;
  }

  private static void receive(TencentKifuDownload window, GetTencentRequest source,
      RequestKind kind, String payload) throws Exception {
    window.receiveResult(source, kind, payload);
    edt(() -> null);
  }

  private static void fail(TencentKifuDownload window, GetTencentRequest source,
      RequestKind kind, String payload, MessageCloser messages) throws Exception {
    int before = messages.count.get();
    receive(window, source, kind, payload);
    awaitEdt(() -> messages.count.get() == before + 1);
  }

  private static void nextPage(TencentKifuDownload window) throws Exception {
    edt(() -> { invoke(window, "maybeGetNextPage", new Class<?>[0]); return null; });
  }

  private static String page(int first, int count) {
    JSONArray rows = new JSONArray();
    for (int i = first; i < first + count; i++) rows.put(game(i));
    return new JSONObject().put("result", 0).put("chesslist", rows).toString();
  }

  private static JSONObject game(int index) {
    return new JSONObject().put("chessid", "game-" + index).put("blacknick", "player")
        .put("whitenick", "opponent").put("blackdan", 20).put("whitedan", 22)
        .put("starttime", "2026-11-15 12:00:00").put("movenum", 120).put("winner", 1)
        .put("point", -1);
  }

  private static String sgf(String player) {
    return new JSONObject().put("chess", "(;SZ[19]PB[" + player + "];B[pd];W[dd])").toString();
  }

  private static int rows(TencentKifuDownload window) throws Exception {
    return ((List<?>) get(window, "tencentKifuInfos")).size();
  }

  private static boolean progressVisible(TencentKifuDownload window) throws Exception {
    JPanel progress = (JPanel) get(window, "progressGlassPane");
    return progress != null && progress.isVisible();
  }

  private static boolean bool(TencentKifuDownload window, String name) throws Exception {
    return (boolean) get(window, name);
  }

  private static Object get(TencentKifuDownload window, String name) throws Exception {
    return field(TencentKifuDownload.class, name).get(window);
  }

  private static void set(TencentKifuDownload window, String name, Object value) throws Exception {
    field(TencentKifuDownload.class, name).set(window, value);
  }

  private static Field field(Class<?> owner, String name) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void invoke(TencentKifuDownload window, String name, Class<?>[] types,
      Object... values) throws Exception {
    Method method = TencentKifuDownload.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    method.invoke(window, values);
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(10, TimeUnit.SECONDS);
  }

  private static void awaitEdt(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!edt(condition)) {
      assertTrue(System.nanoTime() < deadline, "EDT condition did not become true");
      Thread.sleep(10);
    }
  }

  private static final class ScriptedRequest extends GetTencentRequest {
    final List<String> pages = new ArrayList<>();

    ScriptedRequest(TencentKifuDownload window) { super(window); }

    @Override public void fetchNextPage(String username, String lastCode) { pages.add(lastCode); }
    @Override public void fetchKifu(String chessId) {}
  }

  private static final class MessageCloser implements AWTEventListener, AutoCloseable {
    final AtomicInteger count = new AtomicInteger();

    MessageCloser() {
      Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.WINDOW_EVENT_MASK);
    }

    @Override public void eventDispatched(AWTEvent event) {
      if (event instanceof WindowEvent window && window.getID() == WindowEvent.WINDOW_OPENED
          && window.getWindow() instanceof Message) {
        SwingUtilities.invokeLater(window.getWindow()::dispose);
        count.incrementAndGet();
      }
    }

    @Override public void close() {
      Toolkit.getDefaultToolkit().removeAWTEventListener(this);
    }
  }
}
