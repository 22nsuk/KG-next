package featurecat.lizzie.analysis.ssh;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.logging.WorkDirectoryResolver;
import featurecat.lizzie.util.AtomicTextFileWriter;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Trusts a first SSH host key only after recording it; later connections require that key. */
public final class SshKnownHosts {
  static final String DIRECTORY_NAME = "ssh";
  static final String FILE_NAME = "known_hosts";
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.REMOTE);
  private static final Object FILE_UPDATE_LOCK = new Object();

  @FunctionalInterface
  interface Recorder {
    void write(Path file, String contents) throws IOException;
  }

  private final Path file;
  private final Recorder recorder;

  public SshKnownHosts(Path file) {
    this(file, AtomicTextFileWriter::write);
  }

  SshKnownHosts(Path file, Recorder recorder) {
    this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    this.recorder = Objects.requireNonNull(recorder, "recorder");
  }

  public static SshKnownHosts forWorkDirectory() {
    return new SshKnownHosts(defaultFile());
  }

  static Path defaultFile() {
    File configured = Lizzie.config == null ? null : Lizzie.config.getWorkDirectory();
    Path workDirectory =
        configured != null ? configured.toPath() : WorkDirectoryResolver.resolve().directory();
    return workDirectory.resolve(DIRECTORY_NAME).resolve(FILE_NAME);
  }

  public Path file() {
    return file;
  }

  List<String> recordedKeyTypes(SshTarget target) throws IOException {
    Set<String> keyTypes = new LinkedHashSet<>();
    for (KnownHostEntry entry : entriesFor(target, read())) {
      keyTypes.add(entry.getKeyEntry().getKeyType());
    }
    return new ArrayList<>(keyTypes);
  }

  Check newCheck(SshTarget target) throws IOException {
    Files.createDirectories(file.getParent());
    return new Check(target);
  }

  private String read() throws IOException {
    try {
      return Files.readString(file);
    } catch (NoSuchFileException absent) {
      if (!Files.notExists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw absent;
      return "";
    }
  }

  private List<KnownHostEntry> entriesFor(SshTarget target, String contents) throws IOException {
    List<KnownHostEntry> matches = new ArrayList<>();
    int lineNumber = 0;
    for (String line : contents.split("\\R", -1)) {
      lineNumber++;
      int comment = line.indexOf('#');
      String record = (comment < 0 ? line : line.substring(0, comment)).trim();
      if (record.isEmpty()) continue;
      KnownHostEntry entry;
      try {
        entry = KnownHostEntry.parseKnownHostEntry(record);
      } catch (RuntimeException invalid) {
        throw new IOException(
            "Invalid SSH known_hosts entry at " + file + ":" + lineNumber, invalid);
      }
      if (entry == null || entry.getKeyEntry() == null) {
        throw new IOException("Invalid SSH known_hosts entry at " + file + ":" + lineNumber);
      }
      if (entry.isHostMatch(target.host(), target.port())) matches.add(entry);
    }
    return matches;
  }

  static String fingerprint(PublicKey key) {
    return key == null ? "?" : KeyUtils.getFingerPrint(key);
  }

  final class Check {
    private final SshTarget target;
    private volatile IOException failure;

    private Check(SshTarget target) {
      this.target = target;
    }

    ServerKeyVerifier verifier() {
      return (session, address, presented) -> {
        try {
          synchronized (FILE_UPDATE_LOCK) {
            // Lock the whole lookup/registration, including other app processes sharing this work
            // directory. A second first connection must compare with the key just recorded.
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel channel =
                    FileChannel.open(
                        lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
              if (lock == null) throw new IOException("SSH known_hosts is being updated: " + file);
              String contents = read();
              List<KnownHostEntry> recorded = entriesFor(target, contents);
              if (!recorded.isEmpty()) return matchesRecorded(session, recorded, presented);
              String separator = contents.isEmpty() || contents.endsWith("\n") ? "" : "\n";
              recorder.write(
                  file,
                  contents
                      + separator
                      + target.knownHostsName()
                      + " "
                      + PublicKeyEntry.toString(presented)
                      + "\n");
              LOG.info(
                  "Recorded SSH host key for {} on first connection: {} {}",
                  target.knownHostsName(),
                  KeyUtils.getKeyType(presented),
                  fingerprint(presented));
              return true;
            }
          }
        } catch (IOException | GeneralSecurityException | RuntimeException error) {
          failure =
              error instanceof IOException io
                  ? io
                  : new IOException("Unable to verify SSH known_hosts " + file, error);
          LOG.warn(
              "Refused SSH connection because host-key verification failed for {}",
              target.knownHostsName(),
              failure);
          return false;
        }
      };
    }

    private boolean matchesRecorded(
        ClientSession session, List<KnownHostEntry> recorded, PublicKey presented)
        throws IOException, GeneralSecurityException {
      List<String> fingerprints = new ArrayList<>();
      boolean matches = false;
      for (KnownHostEntry entry : recorded) {
        PublicKey expected =
            entry.getKeyEntry().resolvePublicKey(session, PublicKeyEntryResolver.FAILING);
        fingerprints.add(fingerprint(expected));
        if ("revoked".equals(entry.getMarker())) {
          if (KeyUtils.compareKeys(expected, presented)) {
            throw new IOException("SSH host key is revoked in " + file);
          }
          continue;
        }
        if (entry.getMarker() != null) {
          throw new IOException(
              "Unsupported SSH known_hosts marker: " + entry.getMarker() + " (" + file + ")");
        }
        matches |= KeyUtils.compareKeys(expected, presented);
      }
      if (matches) return true;
      failure =
          new SshHostKeyMismatchException(
              target.knownHostsName(),
              String.join(", ", fingerprints),
              fingerprint(presented),
              file,
              null);
      return false;
    }

    boolean rejected() {
      return failure != null;
    }

    IOException rejection(Throwable cause) {
      IOException rejected = failure;
      if (rejected == null) return new IOException("SSH host-key verification failed.", cause);
      if (cause != null && cause != rejected) rejected.addSuppressed(cause);
      return rejected;
    }
  }
}
