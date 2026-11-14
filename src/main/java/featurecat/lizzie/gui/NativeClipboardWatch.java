package featurecat.lizzie.gui;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Observes OS clipboard changes without reading or replacing the user's clipboard data. */
interface NativeClipboardWatch extends AutoCloseable {
  boolean unchanged();
  @Override default void close() {}

  static NativeClipboardWatch capture() {
    try {
      if (Platform.isWindows()) return counter(() -> {
        long value = Integer.toUnsignedLong(Windows.API.GetClipboardSequenceNumber());
        if (value == 0) {
          if (Windows.API.OpenClipboard(null) == 0) {
            throw new IllegalStateException("Clipboard sequence is unavailable");
          }
          Windows.API.CloseClipboard(); // Zero can also be the initial, accessible sequence.
        }
        return value;
      });
      if (Platform.isMac()) return counter(Mac::changeCount);
      if (Platform.isLinux()) return new XSelectionWatch();
      throw new IllegalStateException("Clipboard observation is unsupported on this platform");
    } catch (LinkageError unavailable) {
      throw new IllegalStateException("Could not load native clipboard observation", unavailable);
    }
  }

  private static NativeClipboardWatch counter(LongSupplier read) {
    long initial = read.getAsLong();
    return () -> read.getAsLong() == initial;
  }

  interface Windows extends StdCallLibrary {
    Windows API = Native.load("user32", Windows.class);
    int GetClipboardSequenceNumber();
    int OpenClipboard(Pointer window);
    int CloseClipboard();
  }

  final class Mac {
    private static final NativeLibrary APP_KIT = NativeLibrary.getInstance("AppKit");
    private static final NativeLibrary OBJC = NativeLibrary.getInstance("objc");
    private static final NativeLibrary SYSTEM = NativeLibrary.getInstance("System");
    private static final Function SEND = OBJC.getFunction("objc_msgSend");
    private static final Function CLASS = OBJC.getFunction("objc_getClass");
    private static final Function SELECTOR = OBJC.getFunction("sel_registerName");

    interface Work extends Callback { void invoke(Pointer context); }

    static long changeCount() {
      AtomicLong result = new AtomicLong();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Work work = ignored -> {
        try { result.set(readOnAppKitThread()); }
        catch (RuntimeException | LinkageError error) { failure.set(error); }
      };
      if (SYSTEM.getFunction("pthread_main_np").invokeInt(new Object[0]) != 0) {
        work.invoke(null);
      } else {
        SYSTEM.getFunction("dispatch_sync_f").invokeVoid(new Object[] {
            SYSTEM.getGlobalVariableAddress("_dispatch_main_q"), null, work});
      }
      if (failure.get() != null) {
        throw new IllegalStateException("Could not observe macOS pasteboard", failure.get());
      }
      return result.get();
    }

    private static Pointer send(Pointer receiver, String selector) {
      return SEND.invokePointer(new Object[] {receiver, SELECTOR.invokePointer(new Object[] {selector})});
    }

    private static long readOnAppKitThread() {
      Pointer pool = send(send(CLASS.invokePointer(new Object[] {"NSAutoreleasePool"}), "alloc"), "init");
      try {
        Pointer board = send(CLASS.invokePointer(new Object[] {"NSPasteboard"}), "generalPasteboard");
        if (board == null) throw new IllegalStateException("General pasteboard is unavailable");
        return SEND.invokeLong(new Object[] {board, SELECTOR.invokePointer(new Object[] {"changeCount"})});
      } finally {
        SEND.invokeVoid(new Object[] {pool, SELECTOR.invokePointer(new Object[] {"drain"})});
      }
    }
  }

  interface Xlib extends Library {
    Xlib API = Native.load("X11", Xlib.class);
    Pointer XOpenDisplay(String name);
    NativeLong XDefaultRootWindow(Pointer display);
    NativeLong XInternAtom(Pointer display, String name, int onlyIfExists);
    int XSync(Pointer display, int discard);
    int XPending(Pointer display);
    int XNextEvent(Pointer display, Pointer event);
    int XCloseDisplay(Pointer display);
  }

  interface Xfixes extends Library {
    Xfixes API = Native.load("Xfixes", Xfixes.class);
    int XFixesQueryExtension(Pointer display, IntByReference event, IntByReference error);
    void XFixesSelectSelectionInput(Pointer display, NativeLong window, NativeLong selection, NativeLong mask);
  }

  final class XSelectionWatch implements NativeClipboardWatch {
    private Pointer display;
    private final int eventType;
    private boolean changed;

    XSelectionWatch() {
      display = Xlib.API.XOpenDisplay(null);
      if (display == null) throw new IllegalStateException("X11 clipboard display is unavailable");
      try {
        IntByReference event = new IntByReference();
        if (Xfixes.API.XFixesQueryExtension(display, event, new IntByReference()) == 0) {
          throw new IllegalStateException("XFixes clipboard observation is unavailable");
        }
        eventType = event.getValue(); // XFixesSelectionNotify is offset zero.
        Xfixes.API.XFixesSelectSelectionInput(display, Xlib.API.XDefaultRootWindow(display),
            Xlib.API.XInternAtom(display, "CLIPBOARD", 0), new NativeLong(7));
        drain(); // Establish the baseline before the SGF snapshot is captured.
      } catch (RuntimeException | LinkageError failure) {
        close();
        throw failure;
      }
    }

    private boolean drain() {
      Xlib.API.XSync(display, 0); // Never discard queued ownership changes.
      boolean observed = false;
      try (Memory event = new Memory(24L * Native.LONG_SIZE)) {
        while (Xlib.API.XPending(display) > 0) {
          Xlib.API.XNextEvent(display, event);
          observed |= event.getInt(0) == eventType;
        }
      }
      return observed;
    }

    @Override public boolean unchanged() {
      if (display == null) return false;
      changed |= drain();
      return !changed;
    }

    @Override public void close() {
      if (display != null) {
        Xlib.API.XCloseDisplay(display);
        display = null;
      }
    }
  }
}
