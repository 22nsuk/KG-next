package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/** Initializes production refresh wiring on the existing headless, Unsafe-allocated frame fixtures. */
public final class AnalysisUiRefreshTestSupport {
  public static void install(LizzieFrame frame) throws Exception {
    Method refresh = LizzieFrame.class.getDeclaredMethod("refreshAnalysisSurfaces", boolean.class);
    refresh.setAccessible(true);
    Method candidates = LizzieFrame.class.getDeclaredMethod("refreshTrackingCandidateVisibility");
    candidates.setAccessible(true);
    AnalysisUiRefresh updates = new AnalysisUiRefresh(
        frame::updateTitle, () -> invoke(refresh, frame, true), () -> invoke(refresh, frame, false),
        () -> invoke(candidates, frame));
    Field field = LizzieFrame.class.getDeclaredField("analysisUiRefresh");
    field.setAccessible(true);
    field.set(frame, updates);
  }

  public static void awaitIdle(LizzieFrame frame) throws Exception {
    Object updates = field(frame, LizzieFrame.class, "analysisUiRefresh");
    Object coalescer = field(updates, AnalysisUiRefresh.class, "coalescer");
    AtomicBoolean pending =
        (AtomicBoolean) field(coalescer, SwingRefreshCoalescer.class, "pending");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do {
      SwingUtilities.invokeAndWait(() -> {});
      if (!pending.get()) return;
      Thread.sleep(1);
    } while (System.nanoTime() < deadline);
    assertFalse(pending.get(), "analysis refresh must finish before restoring fixture globals");
  }

  private static Object field(Object target, Class<?> owner, String name) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void invoke(Method method, Object target, Object... arguments) {
    try {
      method.invoke(target, arguments);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Error error) throw error;
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new AssertionError(failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }
}
