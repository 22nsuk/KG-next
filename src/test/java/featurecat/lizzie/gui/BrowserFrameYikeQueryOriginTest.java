package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BrowserFrameYikeQueryOriginTest {
  private static final String ROOM = "https://home.yikeweiqi.com/#/unite/66304678";
  private OnlineDialog previousDialog;
  private RecordingDialog dialog;

  @BeforeEach
  void installDialog() throws Exception {
    drainWorker();
    previousDialog = LizzieFrame.onlineDialog;
    dialog = allocate(RecordingDialog.class);
    dialog.messages = new CopyOnWriteArrayList<>();
    dialog.threads = new CopyOnWriteArrayList<>();
    LizzieFrame.onlineDialog = dialog;
  }

  @AfterEach
  void restoreDialog() throws Exception {
    try {
      drainWorker();
    } finally {
      LizzieFrame.onlineDialog = previousDialog;
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://yikeweiqi.com/#/game", "https://home.yikeweiqi.com/#/live/new-room/123",
      "HTTPS://WWW.YikeWeiqi.com/#/unite/9"
  })
  void allowsMainFramesOnYikeHttpDomains(String url) {
    assertTrue(BrowserFrame.isTrustedYikeQuerySource(true, true, url));
    assertFalse(BrowserFrame.isTrustedYikeQuerySource(false, true, url));
    assertFalse(BrowserFrame.isTrustedYikeQuerySource(true, false, url));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
      " ", "https://yikeweiqi.com.evil.example/", "https://evilyikeweiqi.com/",
      "https://yikeweiqi.com@evil.example/", "https://evil.example/?next=https://home.yikeweiqi.com/",
      "https://evil.example/#https://home.yikeweiqi.com/", "file:///tmp/yikeweiqi.com",
      "data:text/html,yikeweiqi.com", "javascript:location='https://yikeweiqi.com'",
      "chrome-error://chromewebdata/", "about:blank", "//home.yikeweiqi.com/",
      "https://", "https://home.yikeweiqi.com\\@evil.example/", " https://home.yikeweiqi.com/"
  })
  void rejectsMissingMalformedAndLookalikeOrigins(String url) {
    assertFalse(BrowserFrame.isTrustedYikeQuerySource(true, true, url));
  }

  @ParameterizedTest
  @ValueSource(strings = {"yikeUnite:", "yikeGeometry:"})
  void actualRouterRejectsUntrustedFramesBeforeAnySuccessOrPayloadSideEffect(String prefix)
      throws Exception {
    BrowserFrame window = window(true);
    setField(window, "lastObservedYikePageUrl", "preserved-session");
    setField(window, "lastObservedYikePageUrlMillis", 42L);
    String payload = "{\"href\":\"" + ROOM + "\",\"sgf\":\"(;SZ[19];B[aa])\"}";
    for (CefFrame source : List.of(frame(true, "https://evil.example/"), frame(false, ROOM))) {
      assertRejected(window, source, prefix + payload);
    }
    assertRejected(window, null, prefix + payload);
    assertRejected(window(false), frame(true, ROOM), prefix + payload);
    assertTrue(window.createMessageRouterHandler().onQuery(null, frame(true, "data:text/html,x"),
        1L, prefix + payload, false, null), "Rejection must also work without a callback");
    drainWorker();
    assertTrue(dialog.messages.isEmpty(), "An untrusted frame must not enqueue either payload");
    assertEquals("preserved-session", field(window, "lastObservedYikePageUrl"));
    assertEquals(42L, field(window, "lastObservedYikePageUrlMillis"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"yikeUnite:", "yikeGeometry:"})
  void actualRouterAcknowledgesTrustedQueriesAndDispatchesOnTheWorker(String prefix)
      throws Exception {
    BrowserFrame window = window(true);
    RecordingCallback callback = new RecordingCallback();
    String payload = "{\"message\":\"trusted payload\"}";
    Thread caller = Thread.currentThread();
    assertTrue(window.createMessageRouterHandler().onQuery(null, frame(true, ROOM), 1L,
        prefix + payload, false, callback));
    assertEquals(1, callback.successes);
    assertEquals(0, callback.failures);
    drainWorker();
    assertEquals(List.of(prefix + payload), dialog.messages);
    assertEquals(1, dialog.threads.size());
    assertNotEquals(caller, dialog.threads.get(0));
    assertEquals("yike-query-worker", dialog.threads.get(0).getName());
  }

  @Test
  void unrelatedQueriesAreNotConsumedOrSubjectedToYikeOriginChecks() throws Exception {
    BrowserFrame window = window(false);
    CefFrame mustNotInspect = (CefFrame) Proxy.newProxyInstance(CefFrame.class.getClassLoader(),
        new Class<?>[] {CefFrame.class}, (proxy, method, args) -> {
          throw new AssertionError("Unrelated query must not inspect its frame");
        });
    RecordingCallback callback = new RecordingCallback();
    assertFalse(window.createMessageRouterHandler().onQuery(null, mustNotInspect, 1L,
        "other:{}", false, callback));
    assertFalse(window.createMessageRouterHandler().onQuery(null, mustNotInspect, 2L,
        null, false, callback));
    assertEquals(0, callback.successes);
    assertEquals(0, callback.failures);
    assertTrue(dialog.messages.isEmpty());
  }

  private static void assertRejected(BrowserFrame window, CefFrame frame, String request) {
    RecordingCallback callback = new RecordingCallback();
    assertTrue(window.createMessageRouterHandler().onQuery(null, frame, 1L, request, false, callback));
    assertEquals(0, callback.successes);
    assertEquals(1, callback.failures);
    assertEquals(403, callback.errorCode);
    assertEquals("untrusted frame", callback.errorMessage);
  }

  private static BrowserFrame window(boolean yike) throws Exception {
    BrowserFrame window = allocate(BrowserFrame.class);
    setField(window, "isYike", yike);
    return window;
  }

  private static CefFrame frame(boolean main, String url) {
    return (CefFrame) Proxy.newProxyInstance(CefFrame.class.getClassLoader(),
        new Class<?>[] {CefFrame.class}, (proxy, method, args) -> switch (method.getName()) {
          case "isMain" -> main;
          case "getURL" -> url;
          default -> throw new AssertionError("Unexpected frame call: " + method.getName());
        });
  }

  private static void drainWorker() throws InterruptedException {
    CountDownLatch drained = new CountDownLatch(1);
    YikeQueryWorker.submit(drained::countDown);
    assertTrue(drained.await(5, TimeUnit.SECONDS), "Yike worker must finish before fixture restoration");
  }

  private static Object field(BrowserFrame window, String name) throws Exception {
    Field field = BrowserFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(window);
  }

  private static void setField(BrowserFrame window, String name, Object value) throws Exception {
    Field field = BrowserFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(window, value);
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private static final class RecordingCallback implements CefQueryCallback {
    int successes;
    int failures;
    int errorCode;
    String errorMessage;

    @Override
    public void success(String response) {
      successes++;
    }

    @Override
    public void failure(int code, String message) {
      failures++;
      errorCode = code;
      errorMessage = message;
    }
  }

  private static final class RecordingDialog extends OnlineDialog {
    List<String> messages;
    List<Thread> threads;

    private RecordingDialog() {
      super(null);
    }

    @Override
    public void handleYikeUniteSgf(String payload) {
      threads.add(Thread.currentThread());
      messages.add("yikeUnite:" + payload);
    }

    @Override
    public void handleYikeGeometryProbe(String payload) {
      threads.add(Thread.currentThread());
      messages.add("yikeGeometry:" + payload);
    }
  }
}
