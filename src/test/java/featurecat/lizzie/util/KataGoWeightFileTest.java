package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataGoWeightFileTest {
  private static final String MEDIA = "https://media.katagotraining.org/uploaded/networks/models/";

  @TempDir Path folder;

  @Test
  void onlyHttpsLinksToTheTrainingSiteWithPlainNamesAreTrusted() {
    assertTrue(KataGoWeightFile.isTrustedListing(MEDIA + "kata1/kata1-b28c512nbt-s1-d1.bin.gz"));
    assertTrue(KataGoWeightFile.isTrustedListing("https://katagotraining.org/m/g170e.txt.gz"));
    assertFalse(KataGoWeightFile.isTrustedListing("http://media.katagotraining.org/a.bin.gz"));
    assertFalse(KataGoWeightFile.isTrustedListing("https://example.com/a.bin.gz"));
    assertFalse(
        KataGoWeightFile.isTrustedListing("https://katagotraining.org.example.com/a.bin.gz"));
    assertFalse(KataGoWeightFile.isTrustedListing(MEDIA + "..%5C..%5Cevil.bin.gz"), "a backslash");
    assertFalse(KataGoWeightFile.isTrustedListing(MEDIA + "a\\..\\evil.bin.gz"), "a raw backslash");
    assertFalse(KataGoWeightFile.isTrustedListing(MEDIA + "..%2Fevil.bin.gz"), "encoded folder");
    assertFalse(KataGoWeightFile.isTrustedListing("https://user@katagotraining.org/a.bin.gz"));
    assertFalse(KataGoWeightFile.isTrustedListing(null));
    assertFalse(KataGoWeightFile.isTrustedListing(MEDIA + "kata1/installer.exe"));
    assertFalse(KataGoWeightFile.isTrustedListing("not a url"));
  }

  @Test
  void aNameWithAFolderIsRefusedBeforeAnythingIsWritten() throws Exception {
    assertEquals(folder.resolve("model.bin.gz"), KataGoWeightFile.inFolder(folder, "model.bin.gz"));
    assertThrows(IOException.class, () -> KataGoWeightFile.inFolder(folder, "..\\evil.bin.gz"));
    assertThrows(IOException.class, () -> KataGoWeightFile.inFolder(folder, "../evil.bin.gz"));
    assertThrows(IOException.class, () -> KataGoWeightFile.inFolder(folder, "C:evil.bin.gz"));
    assertThrows(IOException.class, () -> KataGoWeightFile.inFolder(folder, "model.bin"));
  }

  @Test
  void aGzipWeightIsWholeOnlyWhenItReadsToTheEnd() throws Exception {
    byte[] raw = new byte[2 * 1024 * 1024];
    new java.util.Random(5).nextBytes(raw);
    ByteArrayOutputStream gzip = new ByteArrayOutputStream();
    try (GZIPOutputStream output = new GZIPOutputStream(gzip)) {
      output.write(raw);
    }
    Path whole = Files.write(folder.resolve("whole.bin.gz"), gzip.toByteArray());
    Path cut =
        Files.write(
            folder.resolve("cut.bin.gz.part"),
            Arrays.copyOf(gzip.toByteArray(), gzip.size() - 100));

    KataGoAutoSetupHelper.DownloadSession session = new KataGoAutoSetupHelper.DownloadSession();
    assertTrue(KataGoWeightFile.isWhole(whole, session));
    assertFalse(KataGoWeightFile.isWhole(cut, session));
    byte[] corrupt = gzip.toByteArray();
    corrupt[corrupt.length - 8] ^= 1; // Trailer CRC, without changing the file size.
    Path badCrc = Files.write(folder.resolve("bad-crc.bin.gz.part"), corrupt);
    assertFalse(KataGoWeightFile.isWhole(badCrc, session));
    Path notGzip = Files.write(folder.resolve("not-gzip.bin.gz.part"), raw);
    assertFalse(KataGoWeightFile.isWhole(notGzip, session));
    Files.delete(notGzip); // Invalid gzip headers must close the underlying file on Windows.
  }

  @Test
  void cancelledValidationStopsBeforeOpeningTheModel() {
    KataGoAutoSetupHelper.DownloadSession session = new KataGoAutoSetupHelper.DownloadSession();
    session.cancel();
    assertThrows(
        KataGoAutoSetupHelper.DownloadCancelledException.class,
        () -> KataGoWeightFile.isWhole(folder.resolve("absent.bin.gz"), session));
  }
}
