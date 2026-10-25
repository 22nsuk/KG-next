package featurecat.lizzie.analysis.ssh;

import java.util.Locale;
import java.util.Objects;

/** Host, port and account of one SSH endpoint. */
public record SshTarget(String host, int port, String username) {
  public static final int DEFAULT_PORT = 22;

  public SshTarget {
    host = Objects.requireNonNull(host, "host").trim();
    username = Objects.requireNonNull(username, "username").trim();
    if (host.isEmpty()) {
      throw new IllegalArgumentException("SSH host is empty.");
    }
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("SSH port is out of range: " + port);
    }
  }

  /** Parses a user-entered port, falling back to 22 like the legacy engine settings did. */
  public static int parsePort(String port) {
    if (port == null || port.isBlank()) {
      return DEFAULT_PORT;
    }
    try {
      int parsed = Integer.parseInt(port.trim());
      return parsed >= 1 && parsed <= 65_535 ? parsed : DEFAULT_PORT;
    } catch (NumberFormatException e) {
      return DEFAULT_PORT;
    }
  }

  /** The host name as OpenSSH writes it in known_hosts: {@code [host]:port} for non-22 ports. */
  public String knownHostsName() {
    String normalized = host.toLowerCase(Locale.ROOT);
    return port == DEFAULT_PORT ? normalized : "[" + normalized + "]:" + port;
  }
}
