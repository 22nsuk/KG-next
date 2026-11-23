package featurecat.lizzie.util;

import java.util.Locale;

/** Operating-system checks, evaluated once from {@code os.name}. */
public final class OsPlatform {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");

  private static final boolean LINUX = "Linux".equalsIgnoreCase(System.getProperty("os.name"));

  private OsPlatform() {}

  public static boolean isLinux() {
    return LINUX;
  }

  public static boolean isWindows() {
    return WINDOWS;
  }
}
