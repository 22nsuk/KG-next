package featurecat.lizzie.util;

import java.util.Locale;

/** Operating-system checks, evaluated once from {@code os.name}. */
public final class OsPlatform {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");

  private OsPlatform() {}

  public static boolean isWindows() {
    return WINDOWS;
  }
}
