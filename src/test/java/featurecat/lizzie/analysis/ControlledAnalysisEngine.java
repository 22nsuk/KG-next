package featurecat.lizzie.analysis;

import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.util.CommandLaunchHelper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Runs the dedicated startup and reader lifecycle with a test-owned process or transport. */
public final class ControlledAnalysisEngine extends AnalysisEngine {
  private Process child;
  private EngineTransport transport;

  public ControlledAnalysisEngine() throws IOException {
    super(true);
  }

  public void startWith(Process child) {
    this.child = Objects.requireNonNull(child);
    startEngine("test-analysis-engine");
  }

  public void startWith(EngineTransport transport) {
    this.transport = Objects.requireNonNull(transport);
    startEngine(RemoteComputeConfig.COMMAND_ZHIZI);
  }

  @Override
  AnalysisResourceCoordinator.EngineLaunch.LocalProcessFactory prepareLocalProcess(
      ProcessBuilder builder, CommandLaunchHelper.LaunchSpec spec, Path executable) {
    return () -> Objects.requireNonNull(child);
  }

  @Override
  EngineTransport createRemoteTransport(String command) {
    return Objects.requireNonNull(transport);
  }
}
