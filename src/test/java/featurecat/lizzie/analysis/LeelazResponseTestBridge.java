package featurecat.lizzie.analysis;

/** Cross-package access to package-private response callbacks for process-level tests. */
public final class LeelazResponseTestBridge {
  private LeelazResponseTestBridge() {}

  public static void sendCommandWithResponse(Leelaz engine, String command, Runnable onResponse) {
    engine.sendCommandWithResponseForTest(command, onResponse);
  }

  public static void installOutput(Leelaz engine, java.io.OutputStream output) {
    engine.installFreshCommandOutputForTest(output);
  }

  public static void dispatch(Leelaz engine, String line) throws java.io.IOException {
    engine.dispatchReaderLineForTest(line);
  }

  public static void reset(Leelaz engine) {
    engine.resetGtpCommandStateForTest("console genmove test reset");
  }

  public static void requestShutdown(Leelaz engine) {
    engine.requestCurrentReaderShutdownForTest();
  }
}
