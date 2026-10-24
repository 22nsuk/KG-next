package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.awt.Container;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KataGoCudaSwitchTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void slowValidationKeepsEdtResponsiveAndGateHeldUntilCompletionDelivery(boolean lateCancel)
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      TestDialog dialog = fixture.dialog;
      SwingUtilities.invokeAndWait(() -> call(dialog, "switchBackToCuda"));
      assertTrue(dialog.started.await(5, TimeUnit.SECONDS));
      assertFalse(dialog.validationOnEdt, "compatibility validation must not run on the EDT");
      Thread worker = (Thread) get(dialog, "activeWorkerThread");
      // This EDT action must execute while inference is still blocked, and must not start it twice.
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue((boolean) call(dialog, "hasActiveBackgroundTask"));
            assertFalse(((JComponent) get(dialog, "btnSwitchBackCuda")).isEnabled());
            assertFalse(((JComponent) get(dialog, "btnUseWeight")).isEnabled());
            assertTrue(((JComponent) get(dialog, "btnClose")).isEnabled());
            call(dialog, "switchBackToCuda");
            dialog.release.countDown();
            try {
              worker.join(5000);
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            assertFalse(worker.isAlive());
            assertTrue(
                (boolean) call(dialog, "hasActiveBackgroundTask"),
                "worker termination must not release a result still queued on the EDT");
            if (lateCancel) call(dialog, "stopActiveDownload");
          });
      assertTrue(dialog.completed.await(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(
          () -> assertFalse((boolean) call(dialog, "hasActiveBackgroundTask")));
      assertEquals(1, dialog.calls.get());
      assertNull(dialog.failure);
      assertEquals(lateCancel, dialog.cancelled);
      assertSame(
          dialog.result,
          dialog.deliveredResult,
          "a committed result must survive late cancellation");
      assertTrue(dialog.completedOnEdt);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"stopActiveDownload", "closeOrCancelActiveTask"})
  void stopInterruptsValidationAndReleasesGateOnEdt(String action) throws Exception {
    try (Fixture fixture = new Fixture()) {
      TestDialog dialog = fixture.dialog;
      SwingUtilities.invokeAndWait(() -> call(dialog, "switchBackToCuda"));
      assertTrue(dialog.started.await(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> call(dialog, action));
      assertTrue(dialog.completed.await(5, TimeUnit.SECONDS));
      assertTrue(dialog.cancelled);
      assertInstanceOf(InterruptedIOException.class, dialog.failure);
      assertTrue(dialog.completedOnEdt);
      assertNull(dialog.deliveredResult);
      SwingUtilities.invokeAndWait(
          () -> {
            assertNull(get(dialog, "activeDownloadSession"));
            assertNull(get(dialog, "activeWorkerThread"));
          });
    }
  }

  @Test
  void validationFailureReachesEdtAndAllowsRetry() throws Exception {
    try (Fixture fixture = new Fixture()) {
      TestDialog dialog = fixture.dialog;
      dialog.rejection = new IOException("inference rejected");
      dialog.release.countDown();
      SwingUtilities.invokeAndWait(() -> call(dialog, "switchBackToCuda"));
      assertTrue(dialog.completed.await(5, TimeUnit.SECONDS));
      assertSame(dialog.rejection, dialog.failure);
      assertFalse(dialog.cancelled);
      assertTrue(dialog.completedOnEdt);
      SwingUtilities.invokeAndWait(
          () -> assertFalse((boolean) call(dialog, "hasActiveBackgroundTask")));
    }
  }

  private static final class Fixture implements AutoCloseable {
    final ResourceBundle previous = Lizzie.resourceBundle;
    final TestDialog dialog;

    Fixture() throws Exception {
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafe.setAccessible(true);
      dialog = (TestDialog) ((sun.misc.Unsafe) unsafe.get(null)).allocateInstance(TestDialog.class);
      dialog.content = new JPanel();
      dialog.started = new CountDownLatch(1);
      dialog.release = new CountDownLatch(1);
      dialog.completed = new CountDownLatch(1);
      dialog.calls = new AtomicInteger();
      var resultConstructor =
          SetupResult.class.getDeclaredConstructor(
              SetupSnapshot.class, int.class, String.class, boolean.class);
      resultConstructor.setAccessible(true);
      dialog.result = resultConstructor.newInstance(null, -1, "CUDA", false);
      set(dialog, "selectedBenchmarkEntryId", "");
      set(dialog, "measuredTuningDialog", new MeasuredTuningDialog());
      set(
          dialog,
          "tensorRtRepairSession",
          new featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairSession());
      // Bypass native-window construction and discovery only; busy/stop controls are real Swing.
      for (Field field : KataGoAutoSetupDialog.class.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())
            || !JComponent.class.isAssignableFrom(field.getType())) continue;
        var constructors = field.getType().getDeclaredConstructors();
        Object component = null;
        for (var constructor : constructors) {
          constructor.setAccessible(true);
          if (constructor.getParameterCount() == 0) component = constructor.newInstance();
        }
        if (field.getType().getSimpleName().equals("WeightRecommendationCard")) {
          var constructor = constructors[0];
          constructor.setAccessible(true);
          component =
              constructor.newInstance(
                  dialog, constructor.getParameterTypes()[1].getEnumConstants()[0]);
        }
        if (component != null) set(dialog, field.getName(), component);
      }
    }

    @Override
    public void close() throws Exception {
      dialog.release.countDown();
      Thread worker = (Thread) get(dialog, "activeWorkerThread");
      if (worker != null) {
        worker.interrupt();
        worker.join(5000);
      }
      SwingUtilities.invokeAndWait(() -> {});
      Lizzie.resourceBundle = previous;
    }
  }

  private static final class TestDialog extends KataGoAutoSetupDialog {
    JPanel content;
    CountDownLatch started, release, completed;
    AtomicInteger calls;
    IOException rejection;
    Exception failure;
    SetupResult result, deliveredResult;
    boolean cancelled, completedOnEdt, validationOnEdt;

    private TestDialog() {
      super(null);
    }

    @Override
    public Container getContentPane() {
      return content;
    }

    @Override
    public boolean isShowing() {
      return false;
    }

    @Override
    public void setVisible(boolean visible) {}

    // The fixture omits discovery; late completion may request it after releasing ownership.
    @Override
    public void refreshState() {}

    @Override
    SetupResult applyCudaProfile(SetupSnapshot selected, DownloadSession session)
        throws IOException {
      calls.incrementAndGet();
      validationOnEdt = SwingUtilities.isEventDispatchThread();
      started.countDown();
      if (SwingUtilities.isEventDispatchThread()) throw new IOException("validation ran on EDT");
      try {
        if (!release.await(10, TimeUnit.SECONDS))
          throw new IOException("test validation timed out");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("validation interrupted");
      }
      if (rejection != null) throw rejection;
      return result;
    }

    @Override
    void onCudaProfileSwitchCompleted(SetupResult result, Exception error, boolean stopped) {
      failure = error;
      deliveredResult = result;
      cancelled = stopped;
      completedOnEdt = SwingUtilities.isEventDispatchThread();
      completed.countDown();
    }
  }

  private static Field field(String name) throws ReflectiveOperationException {
    Field field = KataGoAutoSetupDialog.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void set(Object target, String name, Object value) throws Exception {
    field(name).set(target, value);
  }

  private static Object get(Object target, String name) {
    try {
      return field(name).get(target);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static Object call(Object target, String name) {
    try {
      var method = KataGoAutoSetupDialog.class.getDeclaredMethod(name);
      method.setAccessible(true);
      return method.invoke(target);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
