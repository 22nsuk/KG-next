package featurecat.lizzie.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A real child whose OS termination is deliberately held after a destroy request. */
public final class DelayedTerminationProcess extends Process implements AutoCloseable {
  private final Process child;
  private final CompletableFuture<Process> exited;
  private final CountDownLatch terminationRequested = new CountDownLatch(1);

  public DelayedTerminationProcess(Path directory) throws Exception {
    Files.createDirectories(directory);
    Path ready = directory.resolve("child.ready");
    child =
        new ProcessBuilder(
                Utils.resolveJavaCommand(),
                "-Djava.awt.headless=true",
                "-cp",
                System.getProperty("java.class.path"),
                DelayedTerminationProcess.class.getName(),
                ready.toString())
            .start();
    exited = child.onExit().thenApply(ignored -> this);
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      if (!Files.exists(ready) || !child.isAlive()) {
        throw new AssertionError("waiting child did not become ready");
      }
    } catch (Exception | Error failure) {
      close();
      throw failure;
    }
  }

  public boolean awaitTerminationRequest(long timeout, TimeUnit unit) throws InterruptedException {
    return terminationRequested.await(timeout, unit);
  }

  /** Release the artificial OS delay while retaining the original child identity. */
  public void finishTermination() {
    child.destroyForcibly();
  }

  @Override
  public Process destroyForcibly() {
    terminationRequested.countDown();
    return this;
  }

  @Override
  public void destroy() {
    terminationRequested.countDown();
  }

  @Override
  public CompletableFuture<Process> onExit() {
    return exited;
  }

  @Override
  public boolean isAlive() {
    return child.isAlive();
  }

  @Override
  public int exitValue() {
    return child.exitValue();
  }

  @Override
  public int waitFor() throws InterruptedException {
    return child.waitFor();
  }

  @Override
  public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    return child.waitFor(timeout, unit);
  }

  @Override
  public long pid() {
    return child.pid();
  }

  @Override
  public ProcessHandle toHandle() {
    return child.toHandle();
  }

  @Override
  public InputStream getInputStream() {
    return child.getInputStream();
  }

  @Override
  public InputStream getErrorStream() {
    return child.getErrorStream();
  }

  @Override
  public OutputStream getOutputStream() {
    return child.getOutputStream();
  }

  @Override
  public void close() throws Exception {
    finishTermination();
    if (!child.waitFor(5, TimeUnit.SECONDS)) {
      throw new AssertionError("waiting child survived fixture cleanup");
    }
    child.getOutputStream().close();
    child.getInputStream().close();
    child.getErrorStream().close();
  }

  public static void main(String[] args) throws Exception {
    Files.writeString(Path.of(args[0]), "ready");
    while (System.in.read() != -1) {}
  }
}
