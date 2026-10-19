package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Real permission failures with restoration for configuration and work-directory tests. */
public final class FileAccessTestSupport {
  private FileAccessTestSupport() {}

  public static AutoCloseable denyRead(Path file) throws IOException {
    return denyPermissions(
        file,
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ),
        AclEntryPermission.READ_DATA);
  }

  public static AutoCloseable denyParentWrite(Path directory) throws IOException {
    return denyPermissions(
        directory,
        EnumSet.of(
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE),
        AclEntryPermission.WRITE_DATA);
  }

  private static AutoCloseable denyPermissions(
      Path path, Set<PosixFilePermission> deniedPosix, AclEntryPermission deniedWindows)
      throws IOException {
    PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
    if (posix != null) {
      Set<PosixFilePermission> original = posix.readAttributes().permissions();
      Set<PosixFilePermission> restricted = EnumSet.noneOf(PosixFilePermission.class);
      restricted.addAll(original);
      restricted.removeAll(deniedPosix);
      posix.setPermissions(restricted);
      return () -> Files.setPosixFilePermissions(path, original);
    }
    AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
    assertNotNull(acl, "The regression requires enforceable POSIX permissions or Windows ACLs");
    List<AclEntry> original = acl.getAcl();
    List<AclEntry> restricted = new ArrayList<>(original);
    // Deny only the relevant access. Keep ACL updates and deletion available for cleanup and
    // preserve the writable-parent replacement mechanism that caused the original data loss.
    restricted.add(
        0,
        AclEntry.newBuilder()
            .setType(AclEntryType.DENY)
            .setPrincipal(Files.getOwner(path))
            .setPermissions(deniedWindows)
            .build());
    acl.setAcl(restricted);
    return () -> acl.setAcl(original);
  }
}
