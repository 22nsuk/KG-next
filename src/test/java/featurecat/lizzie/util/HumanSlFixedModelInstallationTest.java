package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class HumanSlFixedModelInstallationTest {
  private static final String MODEL_URL_PROPERTY = "lizzie.humansl.model.url";
  private static final String MODEL_SHA_PROPERTY = "lizzie.humansl.model.sha256";
  private static final String MODEL_SIZE_PROPERTY = "lizzie.humansl.model.size";
  private static final String SELECTED_HUMAN_SL_KEY = "katago-human-sl-model-path";
  private static final byte[] OFFICIAL_BYTES = repeatedBytes(32 * 1024, (byte) 7);
  private static final byte[] CORRUPT_BYTES = repeatedBytes(OFFICIAL_BYTES.length, (byte) 8);
  private static final byte[] CUSTOM_BYTES = repeatedBytes(2 * 1024 * 1024, (byte) 9);
  private static final byte[] ORDINARY_BYTES =
      "ordinary model fixture".getBytes(StandardCharsets.UTF_8);
  private static final String GTP_CONFIG = "numSearchThreads=3\n";

  @TempDir Path root;

  private final Map<String, String> previousProperties = new HashMap<>();
  private Config previousConfig;
  private Path fixedModel;
  private Path ordinaryModel;
  private Path gtpConfig;

  @BeforeEach
  void setUp() throws Exception {
    previousConfig = Lizzie.config;
    for (String property :
        new String[] {"user.dir", MODEL_URL_PROPERTY, MODEL_SHA_PROPERTY, MODEL_SIZE_PROPERTY}) {
      previousProperties.put(property, System.getProperty(property));
    }
    System.setProperty("user.dir", root.toString());
    System.setProperty(
        MODEL_SHA_PROPERTY,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(OFFICIAL_BYTES)));
    System.setProperty(MODEL_SIZE_PROPERTY, Integer.toString(OFFICIAL_BYTES.length));

    Lizzie.config = ConfigTestHelper.createForTests(root);
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.config.leelazConfig = new JSONObject();
    Lizzie.config.config =
        new JSONObject()
            .put("ui", Lizzie.config.uiConfig)
            .put("leelaz", Lizzie.config.leelazConfig);

    ordinaryModel = write(root.resolve("weights/ordinary.bin.gz"), ORDINARY_BYTES);
    Path engine = write(root.resolve("ordinary/katago"), new byte[] {1});
    gtpConfig =
        write(root.resolve("ordinary/gtp.cfg"), GTP_CONFIG.getBytes(StandardCharsets.UTF_8));
    Path analysisConfig =
        write(
            root.resolve("ordinary/analysis.cfg"),
            "numAnalysisThreads=2\n".getBytes(StandardCharsets.UTF_8));
    Lizzie.config
        .uiConfig
        .put("katago-preferred-weight-path", ordinaryModel.toString())
        .put("katago-auto-setup-weight-path", ordinaryModel.toString())
        .put("katago-auto-setup-engine-path", engine.toString())
        .put("katago-auto-setup-gtp-config-path", gtpConfig.toString())
        .put("katago-auto-setup-analysis-config-path", analysisConfig.toString());
    fixedModel =
        Files.createDirectories(root.resolve("human-sl-models"))
            .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME);

    // Ordinary engine settings may migrate during discovery; finish that before the snapshot.
    assertEquals(ordinaryModel, KataGoAutoSetupHelper.inspectLocalSetup().activeWeightPath);
  }

  @AfterEach
  void tearDown() {
    Lizzie.config = previousConfig;
    previousProperties.forEach(
        (property, previous) -> {
          if (previous == null) {
            System.clearProperty(property);
          } else {
            System.setProperty(property, previous);
          }
        });
  }

  @ParameterizedTest(name = "custom selected={0}, fixed model={1}")
  @MethodSource("discoveryCases")
  void fixedModelDiscoveryPreservesSelectedModels(boolean customSelected, FixedModelState state)
      throws Exception {
    Path selectedCustom = selectCustomModel(customSelected);
    writeFixedModel(state);
    SavedSelection before = saveSelection();

    try (ModelServer server = ModelServer.start(200, OFFICIAL_BYTES)) {
      System.setProperty(MODEL_URL_PROPERTY, server.url());

      Path discovered = KataGoAutoSetupHelper.findOfficialHumanSlModel(null);

      assertEquals(state == FixedModelState.VALID ? fixedModel : null, discovered);
      assertEquals(0, server.requests.get(), "discovery must use local contents only");
      if (state != FixedModelState.ABSENT) {
        assertArrayEquals(
            state == FixedModelState.VALID ? OFFICIAL_BYTES : CORRUPT_BYTES,
            Files.readAllBytes(fixedModel));
      } else {
        assertFalse(Files.exists(fixedModel));
      }
      assertFalse(Files.exists(partialModel()));
      assertSelectionPreserved(before, selectedCustom);
    }
  }

  @ParameterizedTest(name = "custom selected={0}, acquisition={1}")
  @MethodSource("installationCases")
  void fixedModelInstallationPreservesSelectedModels(
      boolean customSelected, InstallationCase operation) throws Exception {
    Path selectedCustom = selectCustomModel(customSelected);
    writeFixedModel(operation.initialState);
    SavedSelection before = saveSelection();
    byte[] response =
        operation == InstallationCase.CHECKSUM_FAILURE ? CORRUPT_BYTES : OFFICIAL_BYTES;
    int responseStatus = operation.httpFailure() ? 500 : 200;

    try (ModelServer server = ModelServer.start(responseStatus, response)) {
      System.setProperty(MODEL_URL_PROPERTY, server.url());
      KataGoAutoSetupHelper.DownloadSession session = new KataGoAutoSetupHelper.DownloadSession();
      if (operation == InstallationCase.PRE_CANCELLED) {
        session.cancel();
      }
      AtomicLong receivedBytes = new AtomicLong();
      AtomicLong cancelledAt = new AtomicLong();
      KataGoAutoSetupHelper.ProgressListener progress =
          (message, count, total) -> {
            receivedBytes.set(count);
            if (operation == InstallationCase.MID_DOWNLOAD_CANCELLED && count > 0) {
              assertTrue(
                  Files.isRegularFile(partialModel()), "cancel an active temporary download");
              cancelledAt.compareAndSet(0, count);
              session.cancel();
            }
          };

      if (operation.successful) {
        assertEquals(
            fixedModel, KataGoAutoSetupHelper.installOfficialHumanSlModel(progress, session));
        assertArrayEquals(OFFICIAL_BYTES, Files.readAllBytes(fixedModel));
        assertEquals(fixedModel, KataGoAutoSetupHelper.findOfficialHumanSlModel(null));
      } else {
        IOException failure =
            assertThrows(
                IOException.class,
                () -> KataGoAutoSetupHelper.installOfficialHumanSlModel(progress, session));
        if (operation.cancelled()) {
          assertTrue(failure instanceof KataGoAutoSetupHelper.DownloadCancelledException);
          assertTrue(session.isCancelled());
        }
        if (operation.httpFailure()) {
          assertTrue(failure.getMessage().contains("HTTP 500"), failure.getMessage());
        }
        assertFalse(Files.exists(fixedModel), "a failed install must not leave a usable target");
        assertNull(KataGoAutoSetupHelper.findOfficialHumanSlModel(null));
      }

      if (operation == InstallationCase.MID_DOWNLOAD_CANCELLED) {
        assertTrue(cancelledAt.get() > 0, "cancellation must happen after receiving model bytes");
        assertTrue(
            cancelledAt.get() < OFFICIAL_BYTES.length,
            "cancellation must happen before the complete response is received");
      }
      if (operation == InstallationCase.CHECKSUM_FAILURE) {
        assertEquals(
            OFFICIAL_BYTES.length,
            receivedBytes.get(),
            "checksum validation must reject a complete response of the expected size");
      }
      assertFalse(Files.exists(partialModel()), "temporary downloads must be removed");
      assertEquals(operation.expectedRequests(), server.requests.get());
      assertSelectionPreserved(before, selectedCustom);
    }
  }

  @ParameterizedTest(name = "cancel waiting retry={0}")
  @ValueSource(booleans = {false, true})
  void cancelledDownloadFinishesCleanupBeforeAnotherInstall(boolean cancelWaitingRetry)
      throws Exception {
    Path selectedCustom = selectCustomModel(true);
    SavedSelection before = saveSelection();
    CountDownLatch firstDownloadStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstDownload = new CountDownLatch(1);
    KataGoAutoSetupHelper.DownloadSession firstSession =
        new KataGoAutoSetupHelper.DownloadSession();
    KataGoAutoSetupHelper.DownloadSession retrySession =
        new KataGoAutoSetupHelper.DownloadSession();

    try (ModelServer server = ModelServer.start(200, OFFICIAL_BYTES)) {
      System.setProperty(MODEL_URL_PROPERTY, server.url());
      FutureTask<Path> firstAttempt =
          new FutureTask<>(
              () ->
                  KataGoAutoSetupHelper.installOfficialHumanSlModel(
                      (message, count, total) -> {
                        if (count <= 0) {
                          return;
                        }
                        assertTrue(Files.isRegularFile(partialModel()));
                        firstDownloadStarted.countDown();
                        try {
                          assertTrue(
                              releaseFirstDownload.await(10, TimeUnit.SECONDS),
                              "the test must release the first download");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(e);
                        }
                      },
                      firstSession));
      FutureTask<Path> retryAttempt =
          new FutureTask<>(
              () -> KataGoAutoSetupHelper.installOfficialHumanSlModel(null, retrySession));
      Thread firstWorker = new Thread(firstAttempt, "humansl-first-install");
      Thread retryWorker = new Thread(retryAttempt, "humansl-retry-install");
      try {
        firstWorker.start();
        assertTrue(firstDownloadStarted.await(10, TimeUnit.SECONDS));
        byte[] firstPartialBytes = Files.readAllBytes(partialModel());
        assertTrue(
            firstPartialBytes.length > 0 && firstPartialBytes.length < OFFICIAL_BYTES.length);

        firstSession.cancel();
        retryWorker.start();
        awaitBlockedByFirstDownload(retryWorker, firstWorker, retryAttempt);
        assertEquals(1, server.requests.get(), "the waiting install must not start a download");
        assertArrayEquals(
            firstPartialBytes,
            Files.readAllBytes(partialModel()),
            "the waiting install must not truncate or delete the first temporary file");
        if (cancelWaitingRetry) {
          retrySession.cancel();
        }

        releaseFirstDownload.countDown();
        assertCancelled(firstAttempt);
        if (cancelWaitingRetry) {
          assertCancelled(retryAttempt);
          assertEquals(
              1, server.requests.get(), "a cancelled waiting install must not request data");
          assertFalse(Files.exists(fixedModel));
          assertNull(KataGoAutoSetupHelper.findOfficialHumanSlModel(null));
        } else {
          assertEquals(fixedModel, retryAttempt.get(10, TimeUnit.SECONDS));
          assertEquals(2, server.requests.get());
          assertArrayEquals(OFFICIAL_BYTES, Files.readAllBytes(fixedModel));
          assertEquals(fixedModel, KataGoAutoSetupHelper.findOfficialHumanSlModel(null));
        }
        assertFalse(Files.exists(partialModel()));
        assertSelectionPreserved(before, selectedCustom);
      } finally {
        firstSession.cancel();
        retrySession.cancel();
        releaseFirstDownload.countDown();
        firstWorker.join(TimeUnit.SECONDS.toMillis(10));
        retryWorker.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(firstWorker.isAlive(), "the first install must finish");
        assertFalse(retryWorker.isAlive(), "the retry install must finish");
      }
    }
  }

  private static void awaitBlockedByFirstDownload(
      Thread retryWorker, Thread firstWorker, FutureTask<Path> retryAttempt)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline && !retryAttempt.isDone()) {
      ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(retryWorker.getId());
      if (info != null
          && info.getThreadState() == Thread.State.BLOCKED
          && info.getLockOwnerId() == firstWorker.getId()) {
        return;
      }
      Thread.sleep(5);
    }
    assertTrue(false, "the retry must wait for the first download to finish cleanup");
  }

  private static void assertCancelled(FutureTask<Path> attempt) {
    ExecutionException failure =
        assertThrows(ExecutionException.class, () -> attempt.get(10, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof KataGoAutoSetupHelper.DownloadCancelledException);
  }

  private Path selectCustomModel(boolean customSelected) throws IOException {
    if (!customSelected) {
      return null;
    }
    Path custom = write(root.resolve("external/selected-human.bin.gz"), CUSTOM_BYTES);
    KataGoAutoSetupHelper.rememberHumanSlModel(custom);
    assertTrue(KataGoAutoSetupHelper.inspectHumanSlModel().isInstalled());
    return custom;
  }

  private void writeFixedModel(FixedModelState state) throws IOException {
    if (state != FixedModelState.ABSENT) {
      Files.write(fixedModel, state == FixedModelState.VALID ? OFFICIAL_BYTES : CORRUPT_BYTES);
    }
  }

  private SavedSelection saveSelection() throws IOException {
    Lizzie.config.save();
    return new SavedSelection(
        Lizzie.config.config.toString(), Files.readAllBytes(root.resolve("config.txt")));
  }

  private void assertSelectionPreserved(SavedSelection before, Path selectedCustom)
      throws IOException {
    assertEquals(before.configJson, Lizzie.config.config.toString());
    assertEquals(
        selectedCustom == null ? "" : selectedCustom.toString(),
        Lizzie.config.uiConfig.optString(SELECTED_HUMAN_SL_KEY, ""));
    if (selectedCustom != null) {
      assertEquals(selectedCustom, KataGoAutoSetupHelper.inspectHumanSlModel().modelPath);
      assertTrue(KataGoAutoSetupHelper.inspectHumanSlModel().isInstalled());
      assertArrayEquals(CUSTOM_BYTES, Files.readAllBytes(selectedCustom));
    }
    KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    assertEquals(ordinaryModel, snapshot.activeWeightPath);
    assertEquals(gtpConfig, snapshot.gtpConfigPath);
    assertArrayEquals(ORDINARY_BYTES, Files.readAllBytes(ordinaryModel));
    assertEquals(GTP_CONFIG, Files.readString(gtpConfig));
    assertArrayEquals(
        before.configBytes,
        Files.readAllBytes(root.resolve("config.txt")),
        "discovery and installation must not persist a different selection");

    Lizzie.config.save();
    JSONObject persisted = new JSONObject(Files.readString(root.resolve("config.txt")));
    assertTrue(
        new JSONObject(before.configJson).similar(persisted),
        "saving after the operation must preserve the selected models on disk");
  }

  private Path partialModel() {
    return fixedModel.resolveSibling(fixedModel.getFileName() + ".part");
  }

  private static Stream<Arguments> discoveryCases() {
    return Stream.of(false, true)
        .flatMap(
            custom ->
                Arrays.stream(FixedModelState.values()).map(state -> Arguments.of(custom, state)));
  }

  private static Stream<Arguments> installationCases() {
    return Stream.of(false, true)
        .flatMap(
            custom ->
                Arrays.stream(InstallationCase.values())
                    .map(operation -> Arguments.of(custom, operation)));
  }

  private static Path write(Path path, byte[] bytes) throws IOException {
    Files.createDirectories(path.getParent());
    return Files.write(path, bytes);
  }

  private static byte[] repeatedBytes(int size, byte value) {
    byte[] bytes = new byte[size];
    Arrays.fill(bytes, value);
    return bytes;
  }

  private enum FixedModelState {
    ABSENT,
    VALID,
    CORRUPT
  }

  private enum InstallationCase {
    SUCCESS(FixedModelState.ABSENT, true),
    REUSE(FixedModelState.VALID, true),
    REPAIR(FixedModelState.CORRUPT, true),
    HTTP_FAILURE(FixedModelState.ABSENT, false),
    CHECKSUM_FAILURE(FixedModelState.ABSENT, false),
    PRE_CANCELLED(FixedModelState.ABSENT, false),
    MID_DOWNLOAD_CANCELLED(FixedModelState.ABSENT, false),
    REPAIR_HTTP_FAILURE(FixedModelState.CORRUPT, false);

    private final FixedModelState initialState;
    private final boolean successful;

    InstallationCase(FixedModelState initialState, boolean successful) {
      this.initialState = initialState;
      this.successful = successful;
    }

    private boolean cancelled() {
      return this == PRE_CANCELLED || this == MID_DOWNLOAD_CANCELLED;
    }

    private boolean httpFailure() {
      return this == HTTP_FAILURE || this == REPAIR_HTTP_FAILURE;
    }

    private int expectedRequests() {
      return this == REUSE || this == PRE_CANCELLED ? 0 : 1;
    }
  }

  private record SavedSelection(String configJson, byte[] configBytes) {}

  private static final class ModelServer implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    private ModelServer(HttpServer server, int status, byte[] response) {
      this.server = server;
      server.createContext(
          "/model.bin.gz",
          exchange -> {
            requests.incrementAndGet();
            try (exchange) {
              if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
                return;
              }
              exchange.sendResponseHeaders(200, response.length);
              try (var output = exchange.getResponseBody()) {
                output.write(response);
              }
            }
          });
    }

    private static ModelServer start(int status, byte[] response) throws IOException {
      ModelServer fixture =
          new ModelServer(
              HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0), status, response);
      fixture.server.start();
      return fixture;
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/model.bin.gz";
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
