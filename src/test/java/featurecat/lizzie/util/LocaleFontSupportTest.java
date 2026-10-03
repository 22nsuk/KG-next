package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class LocaleFontSupportTest {
  @Test
  void leavesConfiguredFontAloneOutsideThaiLocale() {
    assertEquals(
        "Dialog",
        LocaleFontSupport.resolveConfiguredFontName("Dialog", Locale.US, "Fallback"));
  }

  @Test
  void replacesUnsupportedThaiLogicalFontWithFirstSupportedPhysicalFont() {
    String resolved =
        LocaleFontSupport.resolveThaiFontName(
            "Dialog", "Dialog", name -> "Leelawadee UI".equals(name));

    assertEquals("Leelawadee UI", resolved);
  }

  @Test
  void preservesCustomThaiFontWhenItSupportsThai() {
    String resolved =
        LocaleFontSupport.resolveThaiFontName(
            "Custom Thai", "Dialog", name -> "Custom Thai".equals(name));

    assertEquals("Custom Thai", resolved);
  }

  @Test
  void treatsLizzieDefaultAsTheConfiguredFallback() {
    assertEquals(
        "Fallback Thai",
        LocaleFontSupport.resolveConfiguredFontName(
            "Lizzie Default", Locale.US, "Fallback Thai"));
  }

  @Test
  void koreanAutomaticDefaultPrefersKoreanGlyphsOverAUsableLogicalFont() {
    assertEquals(
        "Malgun Gothic",
        LocaleFontSupport.resolveKoreanDefaultFontName(
            "Dialog", name -> "Dialog".equals(name) || "Malgun Gothic".equals(name)));
  }

  @Test
  void koreanDefaultUsesAvailableMacOrLinuxFontsWithoutRequiringWindowsFonts() {
    for (String available : new String[] {"Apple SD Gothic Neo", "Noto Sans CJK KR", "Noto Sans KR"}) {
      assertEquals(
          available,
          LocaleFontSupport.resolveKoreanDefaultFontName("Dialog", available::equals));
    }
  }

  @Test
  void koreanExplicitRenderableFontIsPreserved() {
    assertEquals(
        "Custom Korean",
        LocaleFontSupport.resolveKoreanFontName(
            "Custom Korean", "Dialog", name -> "Custom Korean".equals(name)));
  }

  @Test
  void missingKoreanFontFallsBackAndNoInstalledMatchKeepsLogicalFallback() {
    assertEquals(
        "Noto Sans CJK KR",
        LocaleFontSupport.resolveKoreanFontName(
            "Missing Font", "Dialog", "Noto Sans CJK KR"::equals));
    assertEquals(
        "Dialog", LocaleFontSupport.resolveKoreanDefaultFontName("Dialog", name -> false));
  }

  @Test
  void legacyAndRenamedDefaultSelectionsAreCompatible() {
    for (String name : new String[] {"Lizzie Default", "Lizzie默认", "KG-next Default", "  "}) {
      assertEquals(
          "Fallback", LocaleFontSupport.resolveConfiguredFontName(name, Locale.US, "Fallback"));
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void englishCandidateNamesResolveEvenWhenWindowsExposesLocalizedFontNames() {
    Assumptions.assumeTrue(java.util.Arrays.asList(
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getAvailableFontFamilyNames(Locale.US)).contains("Malgun Gothic"));
    assertEquals("Malgun Gothic",
        LocaleFontSupport.resolveDefaultFontName("Dialog", Locale.KOREAN));
  }
}
