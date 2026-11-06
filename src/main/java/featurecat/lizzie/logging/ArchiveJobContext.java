package featurecat.lizzie.logging;

import ch.qos.logback.core.Context;
import ch.qos.logback.core.spi.ConfigurationEvent;
import ch.qos.logback.core.spi.ConfigurationEventListener;
import ch.qos.logback.core.spi.LifeCycle;
import ch.qos.logback.core.spi.SequenceNumberGenerator;
import ch.qos.logback.core.status.StatusManager;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Tracks a single rolling policy's asynchronous archive writes and removals. */
final class ArchiveJobContext implements Context {
  private final Context delegate;
  private final Set<Job<?>> pending = new HashSet<>();
  private final ExecutorService executor = new TrackingExecutor(false);
  private final ExecutorService alternateExecutor = new TrackingExecutor(true);

  private final class TrackingExecutor extends AbstractExecutorService {
    private final boolean alternate;

    TrackingExecutor(boolean alternate) {
      this.alternate = alternate;
    }

    private ExecutorService target() {
      return alternate ? delegate.getAlternateExecutorService() : delegate.getExecutorService();
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> task) {
      return new Job<>(task);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable task, T result) {
      return new Job<>(java.util.concurrent.Executors.callable(task, result));
    }

    @Override
    public void execute(Runnable task) {
      Job<?> job =
          task instanceof Job<?> existing
              ? existing
              : new Job<>(java.util.concurrent.Executors.callable(task, null));
      synchronized (pending) {
        if (job.isDone()) return;
        pending.add(job);
      }
      try {
        target().execute(job);
      } catch (RuntimeException | Error failure) {
        job.cancel(false);
        throw failure;
      }
    }

    // The LoggerContext owns this executor and its lifetime, not the rolling policy.
    @Override
    public void shutdown() {
      throw new UnsupportedOperationException("shared executor");
    }

    @Override
    public List<Runnable> shutdownNow() {
      throw new UnsupportedOperationException("shared executor");
    }

    @Override
    public boolean isShutdown() {
      return target().isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return target().isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return target().awaitTermination(timeout, unit);
    }
  }

  ArchiveJobContext(Context delegate) {
    this.delegate = delegate;
  }

  boolean hasPendingJobs() {
    synchronized (pending) {
      return !pending.isEmpty();
    }
  }

  void awaitIdle(long deadline, BooleanSupplier cancelled) throws IOException {
    synchronized (pending) {
      while (!pending.isEmpty()) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
          throw new InterruptedIOException("diagnostic export cancelled");
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("archive completion timed out");
        try {
          TimeUnit.NANOSECONDS.timedWait(
              pending, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("diagnostic export cancelled");
        }
      }
    }
  }

  private final class Job<T> extends FutureTask<T> {
    private boolean running;

    Job(Callable<T> task) {
      super(task);
    }

    @Override
    public void run() {
      synchronized (pending) {
        if (isDone()) return;
        running = true;
      }
      try {
        super.run();
      } finally {
        synchronized (pending) {
          running = false;
          pending.remove(this);
          pending.notifyAll();
        }
      }
    }

    @Override
    protected void done() {
      synchronized (pending) {
        // Cancellation of a running job is not proof that its file writes have stopped.
        if (!running) {
          pending.remove(this);
          pending.notifyAll();
        }
      }
    }
  }

  @Override
  public ExecutorService getExecutorService() {
    return executor;
  }

  @Override
  public ExecutorService getAlternateExecutorService() {
    return alternateExecutor;
  }

  @Override
  public StatusManager getStatusManager() {
    return delegate.getStatusManager();
  }

  @Override
  public Object getObject(String key) {
    return delegate.getObject(key);
  }

  @Override
  public void putObject(String key, Object value) {
    delegate.putObject(key, value);
  }

  @Override
  public void addSubstitutionProperty(String key, String value) {
    delegate.addSubstitutionProperty(key, value);
  }

  @Override
  public void addSubstitutionProperties(java.util.Properties properties) {
    delegate.addSubstitutionProperties(properties);
  }

  @Override
  public String getProperty(String key) {
    return delegate.getProperty(key);
  }

  @Override
  public void putProperty(String key, String value) {
    delegate.putProperty(key, value);
  }

  @Override
  public Map<String, String> getCopyOfPropertyMap() {
    return delegate.getCopyOfPropertyMap();
  }

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public void setName(String name) {
    delegate.setName(name);
  }

  @Override
  public long getBirthTime() {
    return delegate.getBirthTime();
  }

  @Override
  public ReentrantLock getConfigurationLock() {
    return delegate.getConfigurationLock();
  }

  @Override
  public ScheduledExecutorService getScheduledExecutorService() {
    return delegate.getScheduledExecutorService();
  }

  @Override
  public void register(LifeCycle component) {
    delegate.register(component);
  }

  @Override
  public void addScheduledFuture(ScheduledFuture<?> future) {
    delegate.addScheduledFuture(future);
  }

  @Override
  public SequenceNumberGenerator getSequenceNumberGenerator() {
    return delegate.getSequenceNumberGenerator();
  }

  @Override
  public void setSequenceNumberGenerator(SequenceNumberGenerator generator) {
    delegate.setSequenceNumberGenerator(generator);
  }

  @Override
  public void addConfigurationEventListener(ConfigurationEventListener listener) {
    delegate.addConfigurationEventListener(listener);
  }

  @Override
  public void removeConfigurationEventListener(ConfigurationEventListener listener) {
    delegate.removeConfigurationEventListener(listener);
  }

  @Override
  public void fireConfigurationEvent(ConfigurationEvent event) {
    delegate.fireConfigurationEvent(event);
  }
}
