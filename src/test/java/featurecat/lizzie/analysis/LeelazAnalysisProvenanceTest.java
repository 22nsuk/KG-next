package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeelazAnalysisProvenanceTest {
  private static final String RULES =
      "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}";
  @TempDir Path root;

  @Test
  void absentAndUnobservedReadersDoNotInferNamesFilesOrDefaultConditions() throws Exception {
    Leelaz engine = new Leelaz("katago gtp -model user-file-name.bin.gz");
    assertNull(engine.captureAnalysisProvenance());
    assertNull(engine.analysisSettingsToken());
    installReader(engine);
    engine.currentEnginename = "user nickname";
    engine.oriEnginename = "another nickname";
    engine.komi = 6.5f;
    engine.pda = 2;
    engine.wrn = 0.4;
    engine.recentRulesLine = "= stale rules";

    AnalysisProvenance source = engine.captureAnalysisProvenance();
    assertNotNull(source);
    assertEquals("", source.engineName());
    assertEquals("", source.engineVersion());
    assertEquals("", source.modelName());
    assertEquals("", source.modelSha256());
    assertEquals("", source.configSha256());
    assertEquals("", source.rules());
    assertNull(source.komi());
    assertNull(source.pda());
    assertNull(source.wrn());
    assertEquals("", source.humanSLProfile());
  }

  @Test
  void actualReportedNameAndCompleteVersionBelongOnlyToTheirReader() throws Exception {
    Leelaz engine = new Leelaz("katago gtp");
    installReader(engine);
    engine.started = true;
    engine.suppressGlobalEnginePresentationForTest();
    Object first = engine.currentEngineIncarnation();
    engine.isCheckingName = true;
    classify(engine, first, "=", "KataGo", "custom", "build");
    engine.isCheckingVersion = true;
    classify(engine, first, "=", "1.18.2", "CUDA");

    AnalysisProvenance source = engine.captureAnalysisProvenance();
    assertEquals("KataGo custom build", source.engineName());
    assertEquals("1.18.2 CUDA", source.engineVersion());
    assertNotEquals(Integer.toString(engine.version), source.engineVersion());

    installReader(engine);
    engine.isCheckingName = true;
    classify(engine, first, "=", "KataGo", "retired");
    assertEquals("", engine.captureAnalysisProvenance().engineName());
    assertEquals("", engine.captureAnalysisProvenance().engineVersion());
  }

  @Test
  void lookupCancellationAndLateCompletionCannotLeakAcrossReplacementOrShutdown() throws Exception {
    Leelaz engine = new Leelaz("katago gtp");
    installReader(engine);
    Object first = engine.currentEngineIncarnation();
    CompletableFuture<AnalysisModelIdentity.FilesIdentity> pending = new CompletableFuture<>();
    set(first, "analysisModelLookup", lookup(pending));
    installReader(engine);
    assertTrue(pending.isCancelled(), "reader replacement cancels its own file lookup");
    set(first, "analysisModelLookup", AnalysisModelIdentity.knownModel("late retired model"));
    assertEquals("", engine.captureAnalysisProvenance().modelName());

    Object current = engine.currentEngineIncarnation();
    set(current, "analysisModelLookup", AnalysisModelIdentity.knownModel("remote model identifier"));
    assertEquals("remote model identifier", engine.captureAnalysisProvenance().modelName());
    assertEquals("", engine.captureAnalysisProvenance().modelSha256());
    CompletableFuture<AnalysisModelIdentity.FilesIdentity> shutdownLookup = new CompletableFuture<>();
    set(current, "analysisModelLookup", lookup(shutdownLookup));
    engine.shutdownReaderBindingForTest(current);
    assertTrue(shutdownLookup.isCancelled());
    assertNull(engine.captureAnalysisProvenance());
    assertNull(engine.analysisSettingsToken());
  }

  @Test
  void captureNeverWaitsForLookupOrEngineAndReaderMonitors() throws Exception {
    Leelaz engine = new Leelaz("katago gtp");
    installReader(engine);
    Object binding = engine.currentEngineIncarnation();
    CompletableFuture<AnalysisModelIdentity.FilesIdentity> pending = new CompletableFuture<>();
    set(binding, "analysisModelLookup", lookup(pending));
    Method arbitration = Leelaz.class.getDeclaredMethod("engineArbitrationLock");
    arbitration.setAccessible(true);
    Object endpointLock = arbitration.invoke(engine);
    try {
      synchronized (endpointLock) {
        synchronized (binding) {
          AnalysisProvenance source =
              assertTimeoutPreemptively(Duration.ofSeconds(1), engine::captureAnalysisProvenance);
          assertEquals("", source.modelName());
          assertFalse(pending.isDone());
          assertNotNull(
              assertTimeoutPreemptively(Duration.ofSeconds(1), engine::analysisSettingsToken));
        }
      }
    } finally {
      pending.cancel(true);
    }
  }

  @Test
  void runtimeConditionsRequireSuccessfulResponsesAndDynamicPdaRemainsUnknown() throws Exception {
    try (Environment env = new Environment()) {
      int komi = env.send("komi 6.5");
      assertNull(env.source().komi());
      env.reply(komi, "");
      assertEquals(6.5, env.source().komi());

      int pda = env.send("kata-get-param playoutDoublingAdvantage");
      int wrn = env.send("kata-set-param analysisWideRootNoise 0.25");
      env.reply(pda, "1.75");
      env.reply(wrn, "");
      assertEquals(1.75, env.source().pda());
      assertEquals(0.25, env.source().wrn());
      env.engine.isDymPda = true;
      assertNull(env.source().pda());
      env.engine.isDymPda = false;

      int rejected = env.send("kata-set-param playoutDoublingAdvantage 9");
      assertNull(env.source().pda(), "a changed condition is unconfirmed until its reply");
      env.engine.processCommandResponseLineForTest("?" + rejected + " rejected");
      assertNull(env.source().pda());
      int malformed = env.send("kata-get-param analysisWideRootNoise");
      env.reply(malformed, "NaN");
      assertNull(env.source().wrn());
    }
  }

  @Test
  void delayedConditionReplyCannotOverwriteANewerPhysicalCommand() throws Exception {
    try (Environment env = new Environment()) {
      int old = env.send("kata-get-param playoutDoublingAdvantage");
      int newer = env.send("kata-set-param playoutDoublingAdvantage 2.5");
      env.reply(newer, "");
      assertEquals(2.5, env.source().pda());
      env.reply(old, "1.0");
      assertEquals(2.5, env.source().pda());

      env.engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      assertNull(env.source().komi());
      assertNull(env.source().pda());
      assertNull(env.source().wrn());
    }
  }

  @Test
  void rulesComeFromTheCurrentReaderReadbackAndBecomeUnknownDuringChanges() throws Exception {
    try (Environment env = new Environment()) {
      env.engine.queryEngineRules();
      env.reply(env.commandId("kata-get-rules"), RULES);
      assertEquals("= " + RULES, env.source().rules());
      env.send("kata-set-rules japanese");
      assertEquals("", env.source().rules());
      env.engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      assertEquals("", env.source().rules());
    }
  }

  @Test
  void rawParameterWritesInvalidateSettingsImmediatelyEvenWhenRejectedOrUnsupported()
      throws Exception {
    try (Environment env = new Environment()) {
      Object initial = env.engine.analysisSettingsToken();
      int rejected = env.raw("kata-set-param playoutDoublingAdvantage 3");
      Object pending = env.engine.analysisSettingsToken();
      assertNotSame(initial, pending);
      env.reject(rejected);
      assertSame(pending, env.engine.analysisSettingsToken());
      assertNull(env.source().pda());

      int accepted = env.raw("kata-set-param analysisWideRootNoise 0.25");
      Object next = env.engine.analysisSettingsToken();
      assertNotSame(pending, next);
      env.reply(accepted, "");
      assertSame(next, env.engine.analysisSettingsToken());
      assertEquals(0.25, env.source().wrn());

      int humanSl = env.raw("kata-set-param\thumanSLProfile rank_5d");
      Object profile = env.engine.analysisSettingsToken();
      assertNotSame(next, profile, "unrepresented parameters still affect the analysis run");
      env.reject(humanSl);
      int batch = env.raw("kata-set-params {\"analysisWideRootNoise\":0.5}");
      assertNotSame(profile, env.engine.analysisSettingsToken());
      assertNull(env.source().wrn(), "a batch cannot keep an old parameter observation");
      env.reply(batch, "");
      assertNull(env.source().wrn(), "batch values require an explicit parameter readback");
    }
  }

  @Test
  void sameConfirmedKomiAndSemanticRulesRestorePreserveTheSettingsToken() throws Exception {
    try (Environment env = new Environment()) {
      env.reply(env.send("komi 6.5"), "");
      env.engine.queryEngineRules();
      env.reply(env.commandId("kata-get-rules"), RULES);
      Object original = env.engine.analysisSettingsToken();

      int sameKomi = env.raw("komi 6.50");
      assertSame(original, env.engine.analysisSettingsToken());
      env.reply(sameKomi, "");
      assertEquals(6.5, env.source().komi());
      int sameRules = env.raw("kata-set-rules chinese");
      assertSame(original, env.engine.analysisSettingsToken());
      env.reply(sameRules, "");
      env.engine.queryEngineRules();
      env.reply(env.commandId("kata-get-rules"), RULES);
      assertEquals("= " + RULES, env.source().rules());
      assertSame(original, env.engine.analysisSettingsToken());

      int changedKomi = env.raw("komi 7.5");
      Object changed = env.engine.analysisSettingsToken();
      assertNotSame(original, changed);
      env.reply(changedKomi, "");
      env.raw("kata-set-rules japanese");
      assertNotSame(changed, env.engine.analysisSettingsToken());
    }
  }

  @Test
  void parameterQueriesAndCompletedFileMetadataDoNotInvalidateSettings() throws Exception {
    try (Environment env = new Environment()) {
      Object original = env.engine.analysisSettingsToken();
      int read = env.send("kata-get-param playoutDoublingAdvantage");
      assertSame(original, env.engine.analysisSettingsToken());
      env.reply(read, "1.5");
      assertSame(original, env.engine.analysisSettingsToken());
      CompletableFuture<AnalysisModelIdentity.FilesIdentity> pending = new CompletableFuture<>();
      set(env.engine.currentEngineIncarnation(), "analysisModelLookup", lookup(pending));
      assertEquals("", env.source().modelName());
      pending.complete(new AnalysisModelIdentity.FilesIdentity("observed model", "a".repeat(64), ""));
      assertEquals("observed model", env.source().modelName());
      assertSame(original, env.engine.analysisSettingsToken());

      env.engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      assertNotSame(original, env.engine.analysisSettingsToken());
    }
  }

  @Test
  void rawSingleRuleAndKgsRuleWritesRetireTheRunAndPriorRuleObservation() throws Exception {
    try (Environment env = new Environment()) {
      for (String command : List.of("kata-set-rule ko POSITIONAL", "kgs-rules japanese")) {
        env.engine.queryEngineRules();
        env.reply(env.commandId("kata-get-rules"), RULES);
        assertEquals("= " + RULES, env.source().rules());
        CurrentEngineAnalysisContext context = new CurrentEngineAnalysisContext(env.engine);
        assertTrue(context.matchesCurrentEngine());

        int written = env.raw(command);

        assertEquals("", env.source().rules());
        assertFalse(context.matchesCurrentEngine());
        env.reply(written, "");
        assertEquals("", env.source().rules(), "rule aliases need a fresh complete readback");
        assertFalse(context.matchesCurrentEngine());
      }
    }
  }

  @Test
  void rawSgfLoadRetiresTheRunBeforeItsUnobservedRulesAndKomiCanBeReused() throws Exception {
    try (Environment env = new Environment()) {
      env.reply(env.send("komi 6.5"), "");
      env.engine.queryEngineRules();
      env.reply(env.commandId("kata-get-rules"), RULES);
      CurrentEngineAnalysisContext context = new CurrentEngineAnalysisContext(env.engine);

      int load = env.raw("loadsgf selected-game.sgf");

      assertFalse(context.matchesCurrentEngine());
      assertEquals("", env.source().rules());
      assertNull(env.source().komi());
      env.reply(load, "");
      assertFalse(context.matchesCurrentEngine());
    }
  }

  private static void installReader(Leelaz engine) {
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
  }

  private static void classify(Leelaz engine, Object binding, String... params) throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod(
            "checkNameAndVersion",
            String[].class,
            long.class,
            Object.class,
            EngineManager.EngineGameOwnerTransaction.class);
    method.setAccessible(true);
    method.invoke(engine, params, -1L, binding, null);
  }

  private static AnalysisModelIdentity.Lookup lookup(
      Future<AnalysisModelIdentity.FilesIdentity> result) throws Exception {
    Constructor<AnalysisModelIdentity.Lookup> constructor =
        AnalysisModelIdentity.Lookup.class.getDeclaredConstructor(Future.class);
    constructor.setAccessible(true);
    return constructor.newInstance(result);
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private final class Environment implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final Leelaz previousSecond = Lizzie.leelaz2;
    private final Board previousBoard = Lizzie.board;
    private final GtpConsolePane previousConsole = Lizzie.gtpConsole;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final Leelaz engine;

    Environment() throws Exception {
      Lizzie.config = ConfigTestHelper.createForTests(root);
      Lizzie.frame = null;
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      Lizzie.gtpConsole =
          (QuietConsole) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(QuietConsole.class);
      engine = new Leelaz("katago gtp");
      engine.installFreshCommandOutputForTest(output);
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      engine.isCheckingName = false;
      engine.isCheckingVersion = false;
      engine.commandLists.addAll(List.of("kata-set-rules", "kata-get-rules"));
      set(engine, "endGetCommandList", true);
      Lizzie.setPrimaryEngine(engine);
      Lizzie.leelaz2 = null;
      Lizzie.board = new Board();
    }

    AnalysisProvenance source() {
      return engine.captureAnalysisProvenance();
    }

    int send(String command) throws Exception {
      Method method = Leelaz.class.getDeclaredMethod("sendCommand", String.class, Runnable.class);
      method.setAccessible(true);
      method.invoke(engine, command, (Runnable) () -> {});
      return commandId(command);
    }

    int raw(String command) {
      assertTrue(engine.sendRawConsoleCommand(command));
      return commandId(command);
    }

    int commandId(String command) {
      String[] lines = output.toString(StandardCharsets.UTF_8).split("\\R");
      for (int index = lines.length - 1; index >= 0; index--) {
        String line = lines[index].trim();
        if (line.equals(command)) return -1;
        if (line.endsWith(" " + command)) return Integer.parseInt(line.substring(0, line.indexOf(' ')));
      }
      throw new AssertionError("Command was not written: " + command + " in " + output);
    }

    void reply(int id, String payload) {
      engine.processCommandResponseLineForTest("=" + (id < 0 ? "" : id) + " " + payload);
    }

    void reject(int id) {
      engine.processCommandResponseLineForTest("?" + (id < 0 ? "" : id) + " rejected");
    }

    @Override
    public void close() {
      engine.shutdown();
      Lizzie.config = previousConfig;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.leelaz2 = previousSecond;
      Lizzie.board = previousBoard;
      Lizzie.gtpConsole = previousConsole;
      Lizzie.frame = previousFrame;
    }
  }

  private static final class QuietConsole extends GtpConsolePane {
    private QuietConsole() {
      super(null);
    }

    @Override
    public void addLine(String line) {}
  }
}
