package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class KataGoAssetCatalogTest {
  private static final String CUSTOM_COMMIT = "a".repeat(40);

  private JSONObject customCudaCatalogJson() throws IOException {
    JSONObject root = sourceCatalogJson();
    for (String id : new String[] {"windows-nvidia", "windows-nvidia-cuda13"}) {
      JSONObject asset = root.getJSONObject("assets").getJSONObject(id);
      asset.remove("downloadUrl");
      asset.remove("inventorySha256");
      asset.put("origin", "project-source-build");
      asset.put("katagoSourceCommit", CUSTOM_COMMIT);
      asset.put("katagoSourceRepository", "https://github.com/22nsuk/KataGo");
      asset.put("engineReleaseRepository", "22nsuk/KataGo");
      asset.put("engineReleaseTag", "kg-next-" + CUSTOM_COMMIT.substring(0, 12));
      asset.put("assetName", "katago-source-" + CUSTOM_COMMIT.substring(0, 12) + "-" + id + ".zip");
      asset.put("sourceMetadataSha256", "b".repeat(64));
      asset.put("zlibLinkage", "static");
    }
    return root;
  }

  @Test
  void customCudaAssetsUseTheirOwnSourceAndReleaseWithoutRepointingOtherAssets()
      throws IOException {
    KataGoAssetCatalog catalog = new KataGoAssetCatalog(customCudaCatalogJson());
    for (String id : new String[] {"windows-nvidia", "windows-nvidia-cuda13"}) {
      KataGoAssetCatalog.Asset asset = catalog.asset(id);
      assertEquals(CUSTOM_COMMIT, asset.sourceCommit());
      assertEquals("https://github.com/22nsuk/KataGo", asset.sourceRepository());
      assertEquals("static", asset.zlibLinkage());
      assertEquals(
          "https://github.com/22nsuk/KataGo/releases/download/kg-next-"
              + CUSTOM_COMMIT.substring(0, 12)
              + "/"
              + asset.assetName(),
          catalog.assetDownloadUrl(asset));
      String manifest = catalog.engineManifestText(asset);
      assertTrue(manifest.contains("Source commit: " + CUSTOM_COMMIT + "\n"));
      assertTrue(manifest.contains("Source repository: https://github.com/22nsuk/KataGo\n"));
      assertTrue(
          manifest.contains(
              "Engine release tag: kg-next-" + CUSTOM_COMMIT.substring(0, 12) + "\n"));
    }
    assertTrue(
        catalog
            .assetDownloadUrl(catalog.asset("windows-tensorrt"))
            .startsWith(
                "https://github.com/wimi321/lizzieyzy-next/releases/download/next-2026-09-17.1/"));
    assertEquals(
        KataGoAssetCatalog.get().katagoSourceCommit(), catalog.asset("windows-cpu").sourceCommit());
  }

  @Test
  void customSourceIdentityRejectsWrongOriginMissingHashMutableTagAndUnrelatedTarget()
      throws IOException {
    for (String[] change :
        new String[][] {
          {"origin", "official-release"},
          {"katagoSourceCommit", "master"},
          {"katagoSourceRepository", "https://github.com/lightvector/KataGo"},
          {"engineReleaseRepository", "evil/KataGo"},
          {"engineReleaseTag", "latest"},
          {"engineReleaseTag", "kg-next-000000000000"},
          {"sourceMetadataSha256", ""},
          {"downloadUrl", "https://example.com/engine.zip"}
        }) {
      JSONObject root = customCudaCatalogJson();
      root.getJSONObject("assets").getJSONObject("windows-nvidia").put(change[0], change[1]);
      assertThrows(IllegalStateException.class, () -> new KataGoAssetCatalog(root));
    }
    JSONObject root = customCudaCatalogJson();
    JSONObject cuda = root.getJSONObject("assets").getJSONObject("windows-nvidia");
    JSONObject cpu = root.getJSONObject("assets").getJSONObject("windows-cpu");
    for (String field :
        new String[] {
          "katagoSourceCommit",
          "katagoSourceRepository",
          "engineReleaseRepository",
          "engineReleaseTag"
        }) {
      cpu.put(field, cuda.get(field));
    }
    assertThrows(IllegalStateException.class, () -> new KataGoAssetCatalog(root));
  }

  private JSONObject sourceCatalogJson() throws IOException {
    JSONObject root;
    try (var input = getClass().getResourceAsStream("/katago-assets.json")) {
      root = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    root.put("origin", "project-source-build");
    root.put("engineReleaseRepository", "wimi321/lizzieyzy-next");
    root.put("engineReleaseTag", "next-2026-09-17.1");
    JSONObject assets = root.getJSONObject("assets");
    for (String id : new String[] {"windows-nvidia", "windows-nvidia-cuda13"}) {
      JSONObject asset = assets.getJSONObject(id);
      for (String field :
          new String[] {
            "katagoSourceCommit",
            "katagoSourceRepository",
            "engineReleaseRepository",
            "engineReleaseTag",
            "sourceMetadataSha256"
          }) {
        asset.remove(field);
      }
    }
    assets.getJSONObject("windows-nvidia").remove("origin");
    JSONObject official = assets.getJSONObject("windows-nvidia-cuda13");
    official.put("origin", "official-release");
    official.put("zlibLinkage", "dynamic");
    official.put("inventorySha256", "a".repeat(64));
    official.put("assetName", "katago-v1.18.2-cuda13.2-cudnn9.24.0-windows-x64.zip");
    official.put(
        "downloadUrl",
        "https://github.com/lightvector/KataGo/releases/download/v1.18.2/"
            + official.getString("assetName"));
    for (String id : assets.keySet()) {
      if (assets.getJSONObject(id).optString("origin").equals("official-release")) {
        continue;
      }
      assets
          .getJSONObject(id)
          .put(
              "assetName",
              "katago-source-"
                  + root.getString("katagoSourceCommit").substring(0, 12)
                  + "-"
                  + id
                  + ".zip");
    }
    return root;
  }

  @Test
  void sourceEngineRepairUsesProjectAssetsWithoutChangingModelOrigins() throws IOException {
    KataGoAssetCatalog catalog = new KataGoAssetCatalog(sourceCatalogJson());
    assertTrue(
        catalog
            .assetDownloadUrl(catalog.asset("windows-tensorrt"))
            .startsWith(
                "https://github.com/wimi321/lizzieyzy-next/releases/download/next-2026-09-17.1/"));
    assertTrue(
        catalog.modelDownloadUrl(catalog.model("b10-balanced")).contains("lightvector/KataGo"));
  }

  @Test
  void sourceDownloadsRejectForeignRepositoryAndMutableTags() throws IOException {
    for (String[] change :
        new String[][] {
          {"engineReleaseRepository", "evil/KataGo"},
          {"engineReleaseTag", "latest"},
          {"engineReleaseTag", "next-2026-09-17.1/../other"},
          {"katagoSourceCommit", "master"},
          {"origin", "unknown"}
        }) {
      JSONObject root = sourceCatalogJson();
      root.put(change[0], change[1]);
      assertThrows(IllegalStateException.class, () -> new KataGoAssetCatalog(root));
    }
  }

  @Test
  void sourceAssetCannotSubstituteAnOldOrUnsafeFile() throws IOException {
    for (String name : new String[] {"../old.zip", "old-engine.zip", "folder\\engine.zip"}) {
      JSONObject root = sourceCatalogJson();
      root.getJSONObject("assets").getJSONObject("windows-cpu").put("assetName", name);
      assertThrows(IllegalStateException.class, () -> new KataGoAssetCatalog(root));
    }
  }

  @Test
  void pinsSourceBuiltKataGo1182AndPreservesB11AsTheOnlyBundledDefault() {
    KataGoAssetCatalog catalog = KataGoAssetCatalog.get();
    KataGoAssetCatalog.Model model = catalog.defaultModel();

    assertEquals("1.18.2", catalog.katagoVersion());
    assertEquals("v1.18.2", catalog.katagoReleaseTag());
    assertEquals(
        "katago-source-47aadc08518b-windows-cpu.zip", catalog.asset("windows-cpu").assetName());
    assertEquals("kata1-tf3-b11c768-s11003M-d5973M-7gres.bin.gz", model.fileName());
    assertEquals(262_039_869L, model.sizeBytes());
    assertEquals(
        "93bdb63a3bfae4a70db0cb5265287495ecfc10b1ba1cc6814feeba1cdf055871", model.sha256());
    assertTrue(model.bundled());
    assertFalse(catalog.model("b10-balanced").bundled());
    assertEquals("2026-09-25", model.publishedAt());
    assertEquals(
        "https://media.katagotraining.org/uploaded/networks/models/kata1/" + model.fileName(),
        catalog.modelDownloadUrl(model));
    assertEquals(
        "https://github.com/lightvector/KataGo/releases/download/v1.17.1/"
            + catalog.model("b10-balanced").fileName(),
        catalog.modelDownloadUrl(catalog.model("b10-balanced")));
  }

  @Test
  void keepsWindowsCuda128UnifiedAndLinuxCuda121() {
    KataGoAssetCatalog catalog = KataGoAssetCatalog.get();

    assertEquals("cuda12.8-cudnn9", catalog.asset("windows-nvidia").runtimeProfile());
    assertEquals(64, catalog.asset("windows-nvidia").executableSha256().length());
    assertEquals("project-source-build", catalog.origin());
    assertEquals("static", catalog.asset("windows-nvidia").zlibLinkage());
    assertEquals("static", catalog.asset("windows-tensorrt").zlibLinkage());
    assertEquals("cuda12.1-cudnn9", catalog.asset("linux-nvidia").runtimeProfile());
    assertEquals(
        "katago-source-"
            + catalog.asset("windows-nvidia").sourceCommit().substring(0, 12)
            + "-windows-nvidia.zip",
        catalog.asset("windows-nvidia").assetName());
    assertEquals(
        "katago-source-47aadc08518b-linux-nvidia.zip", catalog.asset("linux-nvidia").assetName());
  }

  @Test
  void optionalCuda13UsesOfficialArchiveAndCannotClaimStaticZlib() throws IOException {
    KataGoAssetCatalog catalog = new KataGoAssetCatalog(sourceCatalogJson());
    KataGoAssetCatalog.Asset asset = catalog.asset("windows-nvidia-cuda13");
    assertEquals("cuda13.2-cudnn9.24", asset.runtimeProfile());
    assertEquals("dynamic", asset.zlibLinkage());
    assertEquals(
        "https://github.com/lightvector/KataGo/releases/download/v1.18.2/" + asset.assetName(),
        catalog.assetDownloadUrl(asset));
    for (String[] change :
        new String[][] {
          {"downloadUrl", "https://example.com/engine.zip"},
          {"inventorySha256", ""},
          {"origin", "unknown"},
          {"zlibLinkage", "static"},
          {"assetName", "katago-v1.18.1-cuda13.zip"}
        }) {
      JSONObject root = sourceCatalogJson();
      root.getJSONObject("assets").getJSONObject("windows-nvidia-cuda13").put(change[0], change[1]);
      assertThrows(IllegalStateException.class, () -> new KataGoAssetCatalog(root));
    }
  }

  @Test
  void staticZlibLinkageIsLimitedToPinnedProjectWindowsGpuAssets() throws IOException {
    JSONObject missing = sourceCatalogJson();
    missing.getJSONObject("assets").getJSONObject("windows-nvidia").remove("zlibLinkage");
    assertThrows(IllegalStateException.class, () -> new KataGoAssetCatalog(missing));

    JSONObject widened = sourceCatalogJson();
    widened.getJSONObject("assets").getJSONObject("windows-cpu").put("zlibLinkage", "static");
    assertThrows(IllegalStateException.class, () -> new KataGoAssetCatalog(widened));
  }

  @Test
  void exposesExperimentalWindowsBackendsWithTrustedHashes() {
    KataGoAssetCatalog catalog = KataGoAssetCatalog.get();

    for (String id :
        new String[] {
          "windows-directml",
          "windows-openvino",
          "windows-rocm-gfx103x",
          "windows-rocm-gfx110x",
          "windows-rocm-gfx1151",
          "windows-rocm-gfx120x"
        }) {
      KataGoAssetCatalog.Asset asset = catalog.asset(id);
      assertEquals("experimental", asset.releaseTier());
      assertEquals(64, asset.sha256().length());
      assertTrue(catalog.assetDownloadUrl(asset).endsWith(asset.assetName()));
    }
  }
}
