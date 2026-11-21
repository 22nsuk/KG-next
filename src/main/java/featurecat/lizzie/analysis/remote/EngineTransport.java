package featurecat.lizzie.analysis.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface EngineTransport extends AutoCloseable {
  void start() throws IOException;

  /**
   * Starts with an owner for work that can outlive this call's result deadline. An asynchronous
   * implementation synchronously registers completion before launching that work, and completes it
   * only after transferring or disposing of its resources. Synchronous transports need no extra
   * registration.
   */
  default void start(Consumer<CompletableFuture<Void>> pendingStartup) throws IOException {
    start();
  }

  InputStream stdout();

  OutputStream stdin();

  InputStream stderr();

  boolean isOpen();

  default void setUnresponsiveListener(Runnable listener) {}

  default void markAnalysisProgressAccepted(long totalPlayouts) {}

  /** True when the transport ended deliberately so its owner can rebuild a fresh session. */
  default boolean isRecoveryRequested() {
    return false;
  }

  String description();

  /**
   * Immediately tears down the physical transport without writing an application-level shutdown
   * command. Implementations must make this operation idempotent.
   */
  void abort();

  @Override
  void close();
}
