package featurecat.lizzie.rules;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Opt-in JNA binding. The application does not load this class or the Rust library. */
final class SgfNativeDocumentParser implements AutoCloseable {
  static final int MAX_FRAME = 64 * 1024 * 1024;
  record Result(SgfDocument document, String route, String nativeError, long parseNanos) {}

  private final NativeLibrary library;
  private final Function parse, data, length, free, live;
  private boolean closed;

  SgfNativeDocumentParser(Path path) {
    library = NativeLibrary.getInstance(path.toAbsolutePath().toString());
    try {
      parse = library.getFunction("kg_sgf_parse");
      data = library.getFunction("kg_sgf_result_data");
      length = library.getFunction("kg_sgf_result_len");
      free = library.getFunction("kg_sgf_free");
      live = library.getFunction("kg_sgf_live_results");
    } catch (RuntimeException | Error failure) {
      library.close();
      throw failure;
    }
  }

  // One owner per binding: close cannot unload a library with an active call/result.
  synchronized Result parse(String input) throws IOException {
    ensureOpen();
    checkInterrupted();
    String normalized = SgfDocument.normalizeInput(input);
    if (normalized == null) return new Result(null, "Java rejected", "normalization rejected", 0);
    byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_FRAME) return fallback(input, "native input exceeds 64 MiB");
    byte[] response = invoke(bytes);
    checkInterrupted(); // A cancelled caller never adopts a result; the native result is already freed.
    var frame = new DataInputStream(new ByteArrayInputStream(response));
    long nanos = frame.readLong();
    int status = frame.readUnsignedByte();
    if (status == 1) {
      String error = text(frame);
      if (frame.available() != 0) throw new IOException("Trailing native error data");
      if (error.contains("panicked")) throw new IOException(error);
      return fallback(input, error);
    }
    if (status != 0) throw new IOException("Unknown native status: " + status);
    text(frame); // The input policy is the existing Java decoder, followed by UTF-8 handoff.
    int warnings = frame.readInt();
    if (warnings < 0 || warnings > frame.available() / 4) throw new IOException("Invalid warnings");
    for (int i = 0; i < warnings; i++) text(frame);
    List<SgfDocument.Event> events = new ArrayList<>();
    while (frame.available() > 0) {
      char kind = (char) frame.readUnsignedByte();
      if (kind == 'P') events.add(new SgfDocument.Event(kind, text(frame), text(frame)));
      else if (kind == '(' || kind == ')' || kind == ';') events.add(new SgfDocument.Event(kind, "", ""));
      else throw new IOException("Unknown native event: " + kind);
    }
    int[] size = SgfDocument.parseBoardSize(normalized);
    return new Result(new SgfDocument(events, size[0], size[1],
        SgfDocument.hasMultiGoBranchSuffix(normalized)), "native", null, nanos);
  }

  private static Result fallback(String input, String error) {
    SgfDocument document = SgfDocument.parse(input);
    return new Result(document, document == null ? "Java rejected" : "Java fallback", error, 0);
  }

  // Copy the response once, then release its Rust allocation even if copying/decoding fails.
  private byte[] invoke(byte[] bytes) throws IOException {
    Pointer result = parse.invokePointer(new Object[] {bytes, (long) bytes.length});
    if (result == null) throw new IOException("Native result allocation failed");
    try {
      long size = length.invokeLong(new Object[] {result});
      if (size < 9 || size > MAX_FRAME) throw new IOException("Invalid native response size: " + size);
      Pointer buffer = data.invokePointer(new Object[] {result});
      if (buffer == null) throw new IOException("Missing native response data");
      return buffer.getByteArray(0, (int) size);
    } finally {
      free.invokeVoid(new Object[] {result});
    }
  }

  synchronized long liveResults() { ensureOpen(); return live.invokeLong(new Object[0]); }

  private static String text(DataInputStream frame) throws IOException {
    int size = frame.readInt();
    if (size < 0 || size > frame.available()) throw new IOException("Invalid native string size");
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frame.readNBytes(size))).toString();
  }

  private static void checkInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("SGF import cancelled");
  }

  private void ensureOpen() { if (closed) throw new IllegalStateException("Native parser is closed"); }

  @Override public synchronized void close() {
    if (!closed) { closed = true; library.close(); }
  }
}
