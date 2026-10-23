package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NvidiaRuntimePinsTest {
  private static final Set<String> RUNTIME_KEYS =
      Set.of("cuda_cudart", "libcublas", "libnvjitlink", "cuda_nvrtc", "cudnn");

  @Test
  void acceptsAllFiveBuildPinsAndCaseInsensitiveDigests() throws Exception {
    Map<String, JSONObject> dependencies = buildRuntimeDependencies();
    assertEquals(RUNTIME_KEYS, dependencies.keySet());
    for (Map.Entry<String, JSONObject> dependency : dependencies.entrySet()) {
      String version = dependency.getValue().getString("version");
      String sha256 = dependency.getValue().getString("sha256");
      assertDoesNotThrow(() -> NvidiaRuntimePins.require(dependency.getKey(), version, sha256));
      assertDoesNotThrow(
          () ->
              NvidiaRuntimePins.require(
                  dependency.getKey(), version, sha256.toUpperCase(Locale.ROOT)));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"cuda_cudart", "libcublas", "libnvjitlink", "cuda_nvrtc", "cudnn"})
  void rejectsEachUnpinnedVersionOrDigest(String key) throws Exception {
    JSONObject dependency = buildRuntimeDependencies().get(key);
    String version = dependency.getString("version");
    String sha256 = dependency.getString("sha256");
    assertThrows(IOException.class, () -> NvidiaRuntimePins.require(key, version + ".1", sha256));
    assertThrows(
        IOException.class,
        () -> NvidiaRuntimePins.require(key, version, "0".repeat(64)),
        "A digest supplied by NVIDIA's install-time JSON must not replace the build pin.");
    assertThrows(IOException.class, () -> NvidiaRuntimePins.require(key, null, sha256));
    assertThrows(IOException.class, () -> NvidiaRuntimePins.require(key, version, null));
  }

  @Test
  void rejectsPackagesOutsideTheCuda12TensorRtRuntimeSet() {
    assertThrows(IOException.class, () -> NvidiaRuntimePins.require("libcufft", "1", "0"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"developer.download.nvidia.com", "developer.download.nvidia.cn"})
  void tensorRtPackageAssemblyPreservesPinsAndSelectedNvidiaMirror(String host) throws Exception {
    Map<String, JSONObject> dependencies = buildRuntimeDependencies();
    JSONObject cudaManifest = runtimeManifest(dependencies, false);
    JSONObject cudnnManifest = runtimeManifest(dependencies, true);
    List<?> packages = resolveTensorRtRuntimePackages(host, cudaManifest, cudnnManifest);

    assertEquals(6, packages.size());
    Map<String, Object> resolved = new LinkedHashMap<>();
    for (Object spec : packages) {
      resolved.put(packageField(spec, "key").toString(), spec);
    }
    assertEquals(
        Set.of("cuda_cudart", "libcublas", "libnvjitlink", "cuda_nvrtc", "cudnn", "tensorrt"),
        resolved.keySet());
    for (Map.Entry<String, JSONObject> entry : dependencies.entrySet()) {
      JSONObject dependency = entry.getValue();
      Object spec = resolved.get(entry.getKey());
      assertEquals(dependency.getString("version"), packageField(spec, "version"));
      assertEquals(dependency.getString("sha256"), packageField(spec, "sha256"));
      assertEquals(dependency.getLong("sizeBytes"), packageField(spec, "sizeBytes"));
      assertEquals(
          dependency.getString("url").replace("developer.download.nvidia.com", host),
          packageField(spec, "url"));
    }
    Object tensorRt = resolved.get("tensorrt");
    assertEquals("10.9.0.34", packageField(tensorRt, "version"));
    assertEquals(KataGoRuntimeHelper.TENSORRT_RUNTIME_SHA256, packageField(tensorRt, "sha256"));
    assertEquals(
        "https://"
            + host
            + "/compute/machine-learning/tensorrt/10.9.0/zip/"
            + "TensorRT-10.9.0.34.Windows.win10.cuda-12.8.zip",
        packageField(tensorRt, "url"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"cuda_cudart", "libcublas", "libnvjitlink", "cuda_nvrtc", "cudnn"})
  void tensorRtPackageAssemblyRejectsEachChangedVersionAndDigest(String key) throws Exception {
    Map<String, JSONObject> dependencies = buildRuntimeDependencies();
    for (boolean changeVersion : List.of(true, false)) {
      JSONObject cudaManifest = runtimeManifest(dependencies, false);
      JSONObject cudnnManifest = runtimeManifest(dependencies, true);
      JSONObject packageJson =
          (key.equals("cudnn") ? cudnnManifest : cudaManifest).getJSONObject(key);
      if (changeVersion) {
        packageJson.put("version", packageJson.getString("version") + ".1");
      } else {
        JSONObject platform = packageJson.getJSONObject("windows-x86_64");
        (key.equals("cudnn") ? platform.getJSONObject("cuda12") : platform)
            .put("sha256", "0".repeat(64));
      }
      IOException error =
          assertThrows(
              IOException.class,
              () ->
                  resolveTensorRtRuntimePackages(
                      "developer.download.nvidia.cn", cudaManifest, cudnnManifest));
      assertTrue(error.getMessage().contains("Unexpected NVIDIA " + key + " metadata"));
    }
  }

  private static JSONObject runtimeManifest(Map<String, JSONObject> dependencies, boolean cudnn) {
    JSONObject manifest = new JSONObject();
    for (Map.Entry<String, JSONObject> entry : dependencies.entrySet()) {
      if (entry.getKey().equals("cudnn") != cudnn) {
        continue;
      }
      JSONObject dependency = entry.getValue();
      String baseUrl =
          "https://developer.download.nvidia.com/compute/"
              + (cudnn ? "cudnn" : "cuda")
              + "/redist/";
      JSONObject platform =
          new JSONObject()
              .put("relative_path", dependency.getString("url").substring(baseUrl.length()))
              .put("sha256", dependency.getString("sha256"))
              .put("size", Long.toString(dependency.getLong("sizeBytes")));
      manifest.put(
          entry.getKey(),
          new JSONObject()
              .put("version", dependency.getString("version"))
              .put("windows-x86_64", cudnn ? new JSONObject().put("cuda12", platform) : platform));
    }
    return manifest;
  }

  private static List<?> resolveTensorRtRuntimePackages(
      String host, JSONObject cudaManifest, JSONObject cudnnManifest) throws Exception {
    Method method =
        KataGoRuntimeHelper.class.getDeclaredMethod(
            "resolveTensorRtRuntimePackages", String.class, JSONObject.class, JSONObject.class);
    method.setAccessible(true);
    try {
      return (List<?>) method.invoke(null, host, cudaManifest, cudnnManifest);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }

  private static Object packageField(Object spec, String name) throws Exception {
    Field field = spec.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(spec);
  }

  private static Map<String, JSONObject> buildRuntimeDependencies() throws IOException {
    JSONArray dependencies =
        new JSONObject(Files.readString(Path.of("scripts/katago_cuda_dependencies.json")))
            .getJSONArray("dependencies");
    Map<String, JSONObject> runtimeDependencies = new LinkedHashMap<>();
    for (int index = 0; index < dependencies.length(); index++) {
      JSONObject dependency = dependencies.getJSONObject(index);
      String key = dependency.getString("name");
      if (RUNTIME_KEYS.contains(key)) {
        if (runtimeDependencies.put(key, dependency) != null) {
          throw new IOException("Duplicate build runtime dependency: " + key);
        }
      }
    }
    return runtimeDependencies;
  }
}
