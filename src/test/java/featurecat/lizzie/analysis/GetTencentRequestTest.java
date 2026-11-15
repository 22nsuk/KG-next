package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.GetTencentRequest.RequestKind;
import featurecat.lizzie.gui.TencentKifuDownload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

public class GetTencentRequestTest {

  @Test
  void buildChessListUrlUsesTencentCgiContract() {
    String url =
        GetTencentRequest.buildChessListUrl(
            "测试 用户", "", "1778996987030102957", 25, "session token");

    assertTrue(
        url.startsWith("https://cgi.huanle.qq.com/cgi-bin/CommonMobileCGI/TXWQFetchChessList?"));
    assertTrue(url.contains("type=7"));
    assertTrue(url.contains("lastCode=1778996987030102957"));
    assertTrue(url.contains("username=%E6%B5%8B%E8%AF%95+%E7%94%A8%E6%88%B7"));
    assertTrue(url.contains("srcuid="));
    assertTrue(url.contains("txwqsession=session+token"));
    assertTrue(url.contains("fetchnum=25"));
  }

  @Test
  void numericTencentQueryIsAlsoUsedAsSrcUid() {
    assertEquals("1358592", GetTencentRequest.resolveSrcUid("1358592"));
    assertEquals("", GetTencentRequest.resolveSrcUid("腾讯昵称"));
  }

  @Test
  void buildChessDetailUrlUsesChessIdOnly() {
    String url = GetTencentRequest.buildChessDetailUrl("1778996477030103932");

    assertEquals(
        "https://happyapp.huanle.qq.com/cgi-bin/CommonMobileCGI/TXWQFetchChess?chessid=1778996477030103932",
        url);
  }

  @ParameterizedTest
  @EnumSource(RequestKind.class)
  void requestsDeliverTheirOriginalSenderAndKind(RequestKind kind) throws Exception {
    String payload = kind == RequestKind.LIST
        ? "{\"result\":0,\"chesslist\":[]}"
        : "{\"result\":0,\"chess\":\"(;SZ[19]PB[棋手];B[pd])\"}";
    try (ScriptedRequest request = new ScriptedRequest(payload, false)) {
      request.start(kind);
      Result result = request.result();
      assertSame(request, result.source());
      assertEquals(kind, result.kind());
      assertEquals(0, result.payload().getInt("result"));
      if (kind == RequestKind.LIST) {
        assertEquals("player", result.payload().getString("tencent_query"));
        request.fetchNextPage("player", "123");
        assertEquals(RequestKind.LIST, request.result().kind());
      } else {
        assertEquals("(;SZ[19]PB[棋手];B[pd])", result.payload().getString("chess"));
      }
    }
  }

  @ParameterizedTest
  @EnumSource(RequestKind.class)
  void requestFailuresKeepTheirKind(RequestKind kind) throws Exception {
    try (ScriptedRequest request = new ScriptedRequest(null, true)) {
      request.start(kind);
      Result result = request.result();
      assertSame(request, result.source());
      assertEquals(kind, result.kind());
      assertEquals(1, result.payload().getInt("result"));
      assertEquals("connection reset", result.payload().getString("resultstr"));
    }
  }

  @ParameterizedTest
  @EnumSource(RequestKind.class)
  void emptyResponsesCompleteWithFailure(RequestKind kind) throws Exception {
    try (ScriptedRequest request = new ScriptedRequest("  ", false)) {
      request.start(kind);
      Result result = request.result();
      assertEquals(kind, result.kind());
      assertEquals(1, result.payload().getInt("result"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void shutdownDropsLateSuccessAndFailure(boolean fails) throws Exception {
    try (ScriptedRequest request = new ScriptedRequest("{\"chesslist\":[]}", fails)) {
      request.block = true;
      request.fetchNextPage("player", "123");
      assertTrue(request.started.await(5, TimeUnit.SECONDS));
      request.shutdown();
      request.release.countDown();
      request.awaitTermination();
      assertTrue(request.download.results.isEmpty());
    }
  }

  @Test
  void bodyReaderPreservesUtf8AndPropagatesMidBodyFailure() throws Exception {
    String payload = "{\"chess\":\"(;PB[棋手])\"}";
    assertEquals(payload, GetTencentRequest.readResponseBody(
        new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8))));
    assertEquals("", GetTencentRequest.readResponseBody(new ByteArrayInputStream(new byte[0])));
    InputStream broken = new InputStream() {
      private int reads;

      @Override
      public int read() throws IOException {
        if (reads++ < 5) return 'x';
        throw new IOException("connection reset mid-body");
      }
    };
    assertThrows(IOException.class, () -> GetTencentRequest.readResponseBody(broken));
  }

  private record Result(GetTencentRequest source, RequestKind kind, JSONObject payload) {}

  private static final class RecordingDownload extends TencentKifuDownload {
    private BlockingQueue<Result> results;

    @Override
    public void receiveResult(GetTencentRequest source, RequestKind kind, String payload) {
      results.add(new Result(source, kind, new JSONObject(payload)));
    }

    static RecordingDownload create() throws Exception {
      Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      RecordingDownload download = (RecordingDownload)
          ((sun.misc.Unsafe) field.get(null)).allocateInstance(RecordingDownload.class);
      download.results = new LinkedBlockingQueue<>();
      return download;
    }
  }

  private static final class ScriptedRequest extends GetTencentRequest implements AutoCloseable {
    private final RecordingDownload download;
    private final String response;
    private final boolean fails;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private boolean block;

    ScriptedRequest(String response, boolean fails) throws Exception {
      this(RecordingDownload.create(), response, fails);
    }

    private ScriptedRequest(RecordingDownload download, String response, boolean fails) {
      super(download);
      this.download = download;
      this.response = response;
      this.fails = fails;
    }

    void start(RequestKind kind) {
      if (kind == RequestKind.LIST) search("player");
      else fetchKifu("123");
    }

    Result result() throws Exception {
      Result result = download.results.poll(5, TimeUnit.SECONDS);
      assertNotNull(result, "request must deliver a terminal result");
      return result;
    }

    @Override
    String httpGet(String url) {
      started.countDown();
      if (block) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (release.getCount() != 0) {
          try {
            assertTrue(release.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
          } catch (InterruptedException ignored) {
            interrupted = true; // Like an in-flight socket read, completion outlives shutdownNow.
          }
        }
        if (interrupted) Thread.currentThread().interrupt();
      }
      if (fails) throw new IllegalStateException("connection reset");
      return response;
    }

    void awaitTermination() throws Exception {
      Field field = GetTencentRequest.class.getDeclaredField("executor");
      field.setAccessible(true);
      assertTrue(((ExecutorService) field.get(this)).awaitTermination(5, TimeUnit.SECONDS));
    }

    @Override
    public void close() throws Exception {
      release.countDown();
      shutdown();
      awaitTermination();
    }
  }
}
