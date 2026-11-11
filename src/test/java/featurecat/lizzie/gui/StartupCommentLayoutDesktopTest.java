package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTextPaneUI;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual startup and HTML layout, including startup entered by an EDT engine-selection action. */
public class StartupCommentLayoutDesktopTest {
  private static final ConcurrentHashMap<String, Throwable> violations = new ConcurrentHashMap<>();
  private static final AtomicReference<Throwable> uncaught = new AtomicReference<>();
  private static final AtomicInteger installedPanes = new AtomicInteger();
  private static final AtomicInteger layouts = new AtomicInteger();
  private static volatile Throwable constructionFailure;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void createsAndUpdatesCommentsOnEdt(boolean selectEngine) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            getClass(), "startup-comment-layout", List.of(), List.of("" + selectEngine));
    assertEquals("PASS", Files.readString(result));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void propagatesWindowConstructionFailureToCaller(boolean error) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            getClass(),
            "startup-comment-failure",
            List.of(),
            List.of(error ? "error-failure" : "runtime-failure"));
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) throws Exception {
    boolean selectEngine = Boolean.parseBoolean(args[0]);
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      writeProfile(work, selectEngine);
      System.setProperty("lizzie.work.dir", work.toString());
      // Production CrashHandlers chains this handler, so an asynchronous EDT failure fails the
      // probe.
      Thread.setDefaultUncaughtExceptionHandler(
          (thread, error) -> {
            uncaught.compareAndSet(null, error);
            error.printStackTrace();
          });
      AtomicBoolean selectedOnEdt = new AtomicBoolean();
      SwingUtilities.invokeAndWait(
          () -> {
            UIManager.put("TextPaneUI", ObservedTextPaneUI.class.getName());
            if (selectEngine) {
              Toolkit.getDefaultToolkit()
                  .addAWTEventListener(
                      event -> {
                        if (event.getID() != WindowEvent.WINDOW_OPENED
                            || ((WindowEvent) event).getWindow() != LoadEngine.engjf) return;
                        LoadEngine selector = findSelector(LoadEngine.engjf);
                        assertNotNull(selector);
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        selector.noEngine.doClick();
                        assertNotNull(
                            Lizzie.frame, "start must publish the frame before returning");
                        assertNotNull(Lizzie.gtpConsole, "start must finish constructing its UI");
                        selectedOnEdt.set(true);
                      },
                      AWTEvent.WINDOW_EVENT_MASK);
            }
          });
      DesktopProbeProcess.phase(result, "production-startup");
      if (args[0].endsWith("-failure")) {
        constructionFailure =
            args[0].equals("error-failure")
                ? new AssertionError("controlled window construction failure")
                : new IllegalStateException("controlled window construction failure");
        assertSame(
            constructionFailure,
            assertThrows(constructionFailure.getClass(), () -> Lizzie.main(new String[0])));
        assertNull(uncaught.get(), "construction failures must reach the synchronous caller");
        Files.writeString(result, "PASS");
        System.exit(0);
      }
      Lizzie.main(new String[0]);
      if (!selectEngine) {
        assertNotNull(Lizzie.frame, "non-EDT startup must remain synchronous");
        assertNotNull(Lizzie.gtpConsole);
      }
      await(
          () ->
              Lizzie.engineManager != null
                  && Lizzie.frame != null
                  && Lizzie.frame.isShowing()
                  && (!selectEngine || selectedOnEdt.get()));
      DesktopProbeProcess.applicationReady(result);
      verifyLoadingAndReadyComments();
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(installedPanes.get() >= 2, "both production comment panes were observed");
            assertTrue(layouts.get() > 0, "real Swing text layout must have run");
            assertNoThreadFailures();
          });
      AtomicReference<java.awt.Rectangle> bounds = new AtomicReference<>();
      SwingUtilities.invokeAndWait(() -> bounds.set(Lizzie.frame.getBounds()));
      ImageIO.write(
          new Robot().createScreenCapture(bounds.get()),
          "png",
          result.getParent().resolve("comments.png").toFile());
      Files.writeString(result, "PASS");
      System.exit(0);
    } catch (Throwable failure) {
      violations.values().forEach(Throwable::printStackTrace);
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void writeProfile(Path work, boolean selectEngine) throws Exception {
    JSONObject ui =
        new JSONObject()
            .put("autoload-empty", !selectEngine)
            .put("autoload-default", false)
            .put("autoload-last", false)
            .put("first-time-load", false)
            .put("use-language", 3)
            .put("play-sound", false)
            .put("auto-quick-analyze-on-load", false)
            .put("analysis-engine-preload", false)
            .put("auto-replay-branch", false)
            .put("show-comment", true)
            .put("is-showing-blunder-table", false);
    JSONArray engines = new JSONArray();
    if (selectEngine) {
      // The real selector's "no engine" button is used; this entry is never launched.
      engines.put(
          new JSONObject()
              .put("name", "Unselected fixture")
              .put("command", "unselected-startup-fixture")
              .put("preload", false));
    }
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject()
            .put("leelaz", new JSONObject().put("engine-settings-list", engines))
            .put("ui", ui)
            .toString());
  }

  private static void verifyLoadingAndReadyComments() throws Exception {
    String loading = "STARTUP_LOADING_MARKER";
    SwingUtilities.invokeAndWait(
        () -> {
          Lizzie.leelaz.isLoaded = false;
          Lizzie.gtpConsole.console.setText("initializing engine ".repeat(50) + "\n" + loading);
        });
    // Initial READY can precede engine loading. This exercises the original scroll/layout path.
    Lizzie.engineStartupStatus.ready();
    await(() -> displayedComment().contains(loading));

    String comment = "한국어 주석 READY_COMMENT_MARKER\n" + "긴 주석의 줄 바꿈을 확인합니다. ".repeat(80);
    SwingUtilities.invokeAndWait(
        () -> {
          Lizzie.leelaz.isLoaded = true;
          Lizzie.board.getHistory().getData().setAuthoredComment(comment);
        });
    Lizzie.engineStartupStatus.ready();
    await(() -> displayedComment().contains("READY_COMMENT_MARKER"));
    for (boolean area : new boolean[] {false, true}) {
      SwingUtilities.invokeAndWait(
          () -> {
            Lizzie.frame.setCommentPaneOrArea(area);
            Lizzie.frame.setSize(area ? 1100 : 1300, 850);
          });
      await(
          () -> {
            Lizzie.frame.validate();
            JTextPane pane = (JTextPane) Lizzie.frame.commentScrollPane.getViewport().getView();
            assertInstanceOf(ObservedTextPaneUI.class, pane.getUI());
            return pane.isShowing()
                && pane.getPreferredSize().height > 0
                && displayedComment().contains("READY_COMMENT_MARKER");
          });
    }
  }

  private static String displayedComment() {
    JTextPane pane = (JTextPane) Lizzie.frame.commentScrollPane.getViewport().getView();
    try {
      return pane.getDocument().getText(0, pane.getDocument().getLength());
    } catch (javax.swing.text.BadLocationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static LoadEngine findSelector(Container parent) {
    for (Component child : parent.getComponents()) {
      if (child instanceof LoadEngine) return (LoadEngine) child;
      if (child instanceof Container) {
        LoadEngine found = findSelector((Container) child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      AtomicBoolean done = new AtomicBoolean();
      SwingUtilities.invokeAndWait(
          () -> {
            if (uncaught.get() != null)
              throw new AssertionError("Uncaught startup failure", uncaught.get());
            done.set(condition.getAsBoolean());
          });
      if (done.get()) return;
      Thread.sleep(20);
    }
    fail("Startup/comment condition did not complete");
  }

  private static void assertNoThreadFailures() {
    assertNull(uncaught.get(), "startup must not leave an uncaught EDT exception");
    assertTrue(
        violations.isEmpty(), () -> "Comment UI accessed outside EDT: " + violations.keySet());
  }

  /**
   * Observes ownership without replacing the production HTML document, views, or layout algorithm.
   */
  public static final class ObservedTextPaneUI extends BasicTextPaneUI {
    public static ComponentUI createUI(JComponent component) {
      return new ObservedTextPaneUI();
    }

    @Override
    public void installUI(JComponent component) {
      if (component instanceof JPaintTextPane) installedPanes.incrementAndGet();
      observe(component, "install");
      if (component instanceof JPaintTextPane) {
        if (constructionFailure instanceof RuntimeException) {
          throw (RuntimeException) constructionFailure;
        }
        if (constructionFailure instanceof Error) throw (Error) constructionFailure;
      }
      super.installUI(component);
    }

    @Override
    protected void propertyChange(PropertyChangeEvent event) {
      if (List.of("document", "editorKit", "font", "ancestor").contains(event.getPropertyName())) {
        observe(getComponent(), "property:" + event.getPropertyName());
      }
      super.propertyChange(event);
    }

    @Override
    public Dimension getPreferredSize(JComponent component) {
      if (component instanceof JPaintTextPane) layouts.incrementAndGet();
      observe(component, "layout");
      return super.getPreferredSize(component);
    }

    private static void observe(JComponent component, String operation) {
      if (component instanceof JPaintTextPane && !SwingUtilities.isEventDispatchThread()) {
        violations.computeIfAbsent(
            operation, key -> new AssertionError(key + " on " + Thread.currentThread().getName()));
      }
    }
  }
}
