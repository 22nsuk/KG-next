package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;

/** Runs the real detector with an isolated PATH and a GPU query child controlled by a release file. */
public final class NvidiaGpuDiscoveryFixture implements AutoCloseable {
  private final Path root;
  private final List<ProcessHandle> children = new ArrayList<>();

  public NvidiaGpuDiscoveryFixture(Path root) throws IOException {
    this.root = root;
    Files.createDirectories(root.resolve("queries"));
  }

  public static void runFork(Class<?> scenarioClass, String scenario, Path root) throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")),
        "The synthetic nvidia-smi uses a POSIX launcher; no GPU is required.");
    Path bin = Files.createDirectories(root.resolve("bin"));
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
        .map(value -> Path.of(value).toAbsolutePath().toString())
        .collect(Collectors.joining(File.pathSeparator));
    String launcher = "#!/bin/sh\nexec " + quote(java) + " -cp " + quote(classpath) + " "
        + quote(NvidiaGpuDiscoveryFixture.class.getName()) + " query " + quote(root.toString())
        + " \"$@\"\n";
    Path nvidiaSmi = Files.writeString(bin.resolve("nvidia-smi"), launcher);
    assertTrue(nvidiaSmi.toFile().setExecutable(true));
    Path output = root.resolve("scenario.log");
    ProcessBuilder builder = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", classpath,
        NvidiaGpuDiscoveryFixture.class.getName(), "scenario", root.toString(),
        scenarioClass.getName(), scenario);
    builder.directory(root.toFile());
    builder.environment().put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
    builder.redirectErrorStream(true).redirectOutput(output.toFile());
    Process process = builder.start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> "GPU scenario timed out: " + output);
      assertEquals(0, process.exitValue(), () -> {
        try { return Files.readString(output); }
        catch (IOException failure) { return failure.toString(); }
      });
    } finally {
      Files.writeString(root.resolve("release"), "cleanup");
      if (process.isAlive()) process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    }
  }

  public ProcessHandle awaitQuery() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      try (var files = Files.list(root.resolve("queries"))) {
        Path ready = files.filter(path -> path.toString().endsWith(".pid")).findFirst().orElse(null);
        if (ready != null) {
          ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(ready))).orElseThrow();
          assertTrue(child.isAlive(), "query exited before cancellation could be exercised");
          assertEquals(ProcessHandle.current().pid(), child.parent().orElseThrow().pid());
          children.add(child);
          return child;
        }
      }
      Thread.sleep(5);
    }
    throw new AssertionError("The real nvidia-smi query did not start");
  }

  public long queryCount() throws IOException {
    try (var files = Files.list(root.resolve("queries"))) {
      return files.filter(path -> path.toString().endsWith(".pid")).count();
    }
  }

  public void release() throws IOException {
    Files.writeString(root.resolve("release"), "complete query");
  }

  public static NvidiaGpuDetector.GpuInfo gpu() {
    return new NvidiaGpuDetector.GpuInfo("NVIDIA GeForce RTX 3070", 8, 6, "576.80", 8192, "fixture");
  }

  @Override
  public void close() throws Exception {
    release();
    // Retain handles observed while these children were alive. Never resolve a PID again in cleanup.
    for (ProcessHandle child : children) {
      if (child.isAlive()) child.destroyForcibly();
      child.onExit().get(5, TimeUnit.SECONDS);
    }
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  public static void main(String[] args) {
    int code = 0;
    try {
      Path root = Path.of(args[1]);
      if (args[0].equals("query")) {
        Path queries = Files.createDirectories(root.resolve("queries"));
        Path publication = Files.createTempFile(queries, "query-", ".tmp");
        Files.writeString(publication, Long.toString(ProcessHandle.current().pid()));
        Files.move(publication, queries.resolve(UUID.randomUUID() + ".pid"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!Files.exists(root.resolve("release")) && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(Files.exists(root.resolve("release")), "query was never released or cancelled");
        NvidiaGpuDetector.GpuInfo gpu = gpu();
        String compute = Arrays.stream(args).anyMatch(value -> value.contains("compute_cap"))
            ? gpu.computeCapabilityText() + ", " : "";
        System.out.println(gpu.name + ", " + compute + gpu.driverVersion + ", " + gpu.memoryMiB);
      } else {
        // Initialize native process support before a scenario selects Windows bundle policy.
        assertEquals(0, new ProcessBuilder("/bin/true").start().waitFor());
        var run = Class.forName(args[2]).getDeclaredMethod("runGpuScenario", String.class, Path.class);
        run.setAccessible(true);
        run.invoke(null, args[3], root);
      }
    } catch (Throwable failure) {
      Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
      cause.printStackTrace();
      code = 1;
    }
    System.exit(code);
  }
}
