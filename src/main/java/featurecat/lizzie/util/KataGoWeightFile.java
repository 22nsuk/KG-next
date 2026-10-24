package featurecat.lizzie.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/** Source, file-name and gzip completeness checks for downloaded KataGo weights. */
final class KataGoWeightFile {
  /** A plain weight file name: no folders, so it stays in the folder it is resolved against. */
  private static final Pattern PLAIN_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.(?:bin|txt)\\.gz");

  private KataGoWeightFile() {}

  /**
   * Whether a download link read from the networks page may be followed: HTTPS to the KataGo
   * training site, ending in a plain weight file name. The page is not ours, so a link elsewhere,
   * or a name with a folder in it (on Windows a backslash is one), is dropped.
   */
  static boolean isTrustedListing(String url) {
    if (url == null) return false;
    try {
      URI uri = new URI(url);
      String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
      String path = uri.getRawPath() == null ? "" : uri.getRawPath();
      return "https".equalsIgnoreCase(uri.getScheme())
          && (host.equals("katagotraining.org") || host.endsWith(".katagotraining.org"))
          && uri.getUserInfo() == null
          && isPlainName(path.substring(path.lastIndexOf('/') + 1));
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /** {@code folder.resolve(name)}, refusing a name that is not a plain weight file name. */
  static Path inFolder(Path folder, String name) throws IOException {
    if (!isPlainName(name)) {
      throw new IOException("Unsafe weight file name: " + name);
    }
    return folder.resolve(name);
  }

  static boolean isPlainName(String name) {
    return name != null && PLAIN_NAME.matcher(name).matches();
  }

  /**
   * Read the gzip trailer and CRC before accepting an unpinned model, including a cached .part
   * file. This checks format completeness; it does not replace a known SHA-256 digest.
   */
  static boolean isWhole(Path path, KataGoAutoSetupHelper.DownloadSession session)
      throws IOException {
    session.throwIfCancelled();
    // Retain ownership of raw even if GZIPInputStream rejects its header during construction.
    try (InputStream raw = Files.newInputStream(path);
        InputStream input = new GZIPInputStream(raw, 64 * 1024)) {
      byte[] buffer = new byte[64 * 1024];
      while (true) {
        session.throwIfCancelled();
        if (input.read(buffer) < 0) break;
      }
      session.throwIfCancelled();
      return true;
    } catch (ZipException | EOFException e) {
      session.throwIfCancelled();
      return false;
    }
  }
}
