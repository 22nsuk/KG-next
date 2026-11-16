package featurecat.lizzie.rules;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Explicit, request-local cancellation for private SGF preparation; never reads thread interrupts. */
final class SgfCancellation {
  private static final int POLL_INTERVAL = 1024;
  static final SgfCancellation NONE = new SgfCancellation(() -> false);
  private final BooleanSupplier cancelled;

  SgfCancellation(BooleanSupplier cancelled) {
    this.cancelled = cancelled;
  }

  void check() {
    if (cancelled.getAsBoolean()) throw new CancellationException("SGF preparation cancelled");
  }

  // Bound character/point scanning work between polls without a volatile read for every character.
  void checkEvery(int position) {
    if ((position & (POLL_INTERVAL - 1)) == 0) check();
  }

  int indexOf(String text, char value, int start) {
    if (this == NONE) return text.indexOf(value, start);
    check();
    int i = Math.max(0, start);
    while (i < text.length()) {
      int end = i + Math.min(POLL_INTERVAL, text.length() - i);
      for (; i < end; i++) {
        if (text.charAt(i) == value) return i;
      }
      check();
    }
    return -1;
  }

  int lastIndexOf(String text, char value) {
    if (this == NONE) return text.lastIndexOf(value);
    check();
    int i = text.length() - 1;
    while (i >= 0) {
      int end = Math.max(-1, i - POLL_INTERVAL);
      for (; i > end; i--) {
        if (text.charAt(i) == value) return i;
      }
      check();
    }
    return -1;
  }

  int indexOf(String text, String value, int start) {
    if (this == NONE) return text.indexOf(value, start);
    check();
    int last = text.length() - value.length();
    int i = Math.max(0, start);
    while (i <= last) {
      int end = i + Math.min(POLL_INTERVAL, last - i + 1);
      for (; i < end; i++) {
        if (text.charAt(i) == value.charAt(0) && text.startsWith(value, i)) return i;
      }
      check();
    }
    return -1;
  }
}
