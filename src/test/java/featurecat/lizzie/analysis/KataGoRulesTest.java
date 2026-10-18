package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KataGoRulesTest {

  @Test
  void chineseSimpleAndPositionalShareChineseSummaryButAreNotEquivalent() {
    KataGoRules simple = KataGoRules.parse("chinese").orElseThrow();
    KataGoRules positional = KataGoRules.parse("chinese-ogs").orElseThrow();

    assertEquals(KataGoRules.Summary.CHINESE, simple.summary());
    assertEquals(KataGoRules.Summary.CHINESE, positional.summary());
    assertFalse(simple.semanticallyEquals(positional));
  }

  @Test
  void summaryNamesOnlyRulesThatEqualAPreset() {
    Map<String, KataGoRules.Summary> expected =
        Map.ofEntries(
            Map.entry("chinese", KataGoRules.Summary.CHINESE),
            Map.entry("chinese-kgs", KataGoRules.Summary.CHINESE),
            Map.entry("japanese", KataGoRules.Summary.JAPANESE),
            Map.entry("korean", KataGoRules.Summary.JAPANESE),
            Map.entry("stone-scoring", KataGoRules.Summary.CHINESE_ANCIENT),
            Map.entry("tromp-taylor", KataGoRules.Summary.TROMP_TAYLOR),
            Map.entry("goe", KataGoRules.Summary.GOE),
            Map.entry("aga", KataGoRules.Summary.OTHER),
            Map.entry("new-zealand", KataGoRules.Summary.OTHER),
            Map.entry("aga-button", KataGoRules.Summary.OTHER),
            Map.entry("ancient-territory", KataGoRules.Summary.OTHER));
    expected.forEach(
        (name, summary) ->
            assertEquals(summary, KataGoRules.parse(name).orElseThrow().summary(), name));

    KataGoRules oldTrompTaylorButton =
        KataGoRules.parse("tromp-taylor")
            .orElseThrow()
            .overlayEditor("AREA", "POSITIONAL", true, "NONE", "N", false, false);
    KataGoRules chineseWithSuicide =
        KataGoRules.parse("chinese")
            .orElseThrow()
            .overlayEditor("AREA", "SIMPLE", true, "NONE", "N", false, true);
    assertEquals(KataGoRules.Summary.OTHER, oldTrompTaylorButton.summary());
    assertEquals(KataGoRules.Summary.OTHER, chineseWithSuicide.summary());
  }

  @Test
  void goeMatchesKataGoAndDiffersFromTrompTaylorOnlyByFriendlyPass() {
    KataGoRules goe = KataGoRules.parse("GOE").orElseThrow();
    KataGoRules kataGoReadback =
        KataGoRules.parse(
                "= {\"friendlyPassOk\":true,\"hasButton\":false,\"ko\":\"POSITIONAL\","
                    + "\"scoring\":\"AREA\",\"suicide\":true,\"tax\":\"NONE\","
                    + "\"whiteHandicapBonus\":\"0\"}")
            .orElseThrow();
    KataGoRules trompTaylor = KataGoRules.parse("tromp-taylor").orElseThrow();

    assertTrue(goe.semanticallyEquals(kataGoReadback));
    assertTrue(
        trompTaylor
            .overlayEditor("AREA", "POSITIONAL", true, "NONE", "0", false, true)
            .semanticallyEquals(goe));
    assertFalse(trompTaylor.semanticallyEquals(goe));
  }

  @Test
  void declarationAliasesResolveToTheSamePreset() {
    Map<String, String> aliases =
        Map.ofEntries(
            Map.entry("cn", "chinese"),
            Map.entry(" CN ", "chinese"),
            Map.entry("jp", "japanese"),
            Map.entry("kr", "korean"),
            Map.entry("ing", "goe"),
            Map.entry("nz", "new-zealand"),
            Map.entry("newzealand", "new-zealand"),
            Map.entry("New Zealand", "new-zealand"),
            Map.entry("new_zealand", "new-zealand"),
            Map.entry("french", "aga"),
            Map.entry("chineseogs", "chinese-ogs"),
            Map.entry("chinese_kgs", "chinese-kgs"),
            Map.entry("stonescoring", "stone-scoring"),
            Map.entry("ancientarea", "stone-scoring"),
            Map.entry("ancient area", "stone-scoring"),
            Map.entry("ancientterritory", "ancient-territory"),
            Map.entry("agabutton", "aga-button"),
            Map.entry("tromptaylor", "tromp-taylor"),
            Map.entry("Tromp Taylor", "tromp-taylor"));
    aliases.forEach(
        (alias, preset) ->
            assertTrue(
                KataGoRules.parse(alias)
                    .orElseThrow()
                    .semanticallyEquals(KataGoRules.parse(preset).orElseThrow()),
                alias));
    assertTrue(KataGoRules.parse("zh").isEmpty());
    assertTrue(KataGoRules.parse("chinese rules").isEmpty());
  }

  @Test
  void ancientTerritoryMatchesKataGo() {
    KataGoRules ancient = KataGoRules.parse("ancient-territory").orElseThrow();
    assertEquals("SIMPLE", ancient.string("ko"));
    assertEquals("TERRITORY", ancient.string("scoring"));
    assertEquals("ALL", ancient.string("tax"));
    assertFalse(ancient.bool("suicide"));
    assertFalse(ancient.bool("hasButton"));
    assertEquals("0", ancient.string("whiteHandicapBonus"));
    assertFalse(ancient.bool("friendlyPassOk"));
  }

  @Test
  void jsonKeyOrderDoesNotChangeEquality() {
    KataGoRules first =
        KataGoRules.parse(
                "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
                    + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}")
            .orElseThrow();
    KataGoRules second =
        KataGoRules.parse(
                "{\"friendlyPassOk\":true,\"whiteHandicapBonus\":\"N\",\"hasButton\":false,"
                    + "\"suicide\":false,\"tax\":\"NONE\",\"scoring\":\"AREA\",\"ko\":\"SIMPLE\"}")
            .orElseThrow();

    assertTrue(first.semanticallyEquals(second));
    assertTrue(first.semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()));
  }

  @Test
  void extraFieldsAreKeptAndParticipateInEquality() {
    KataGoRules withExtra =
        KataGoRules.parse(
                "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
                    + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true,"
                    + "\"experimentalKo\":\"X\"}")
            .orElseThrow();
    KataGoRules chinese = KataGoRules.parse("chinese").orElseThrow();

    assertTrue(withExtra.hasField("experimentalKo"));
    assertEquals("X", withExtra.string("experimentalKo"));
    assertFalse(withExtra.semanticallyEquals(chinese));
    assertTrue(
        withExtra
            .overlayEditor("AREA", "SIMPLE", false, "NONE", "N", false, true)
            .semanticallyEquals(withExtra));
  }

  @Test
  void missingRequiredFieldCannotProveEquivalenceToACompletePreset() {
    KataGoRules incomplete =
        KataGoRules.parse(
                "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
                    + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\"}")
            .orElseThrow();

    assertFalse(incomplete.hasRequiredFields());
    assertFalse(incomplete.semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()));
  }

  @Test
  void officialPresetsExpandKnownFields() {
    KataGoRules trompTaylor = KataGoRules.parse("tromp-taylor").orElseThrow();
    assertEquals("POSITIONAL", trompTaylor.string("ko"));
    assertEquals("AREA", trompTaylor.string("scoring"));
    assertTrue(trompTaylor.bool("suicide"));
    assertEquals("0", trompTaylor.string("whiteHandicapBonus"));
    assertFalse(trompTaylor.bool("friendlyPassOk"));
    assertTrue(trompTaylor.hasRequiredFields());
  }

  @Test
  void defaultKomiMatchesKataGoPresetDefaults() {
    // Values from KataGo cpp/game/rules.cpp parseRulesHelper.
    Map<String, Double> expected =
        Map.ofEntries(
            Map.entry("tromp-taylor", 7.5),
            Map.entry("chinese", 7.5),
            Map.entry("chinese-ogs", 7.5),
            Map.entry("chinese-kgs", 7.5),
            Map.entry("japanese", 6.5),
            Map.entry("korean", 6.5),
            Map.entry("stone-scoring", 7.5),
            Map.entry("aga", 7.5),
            Map.entry("bga", 7.5),
            Map.entry("new-zealand", 7.0),
            Map.entry("aga-button", 7.0),
            Map.entry("goe", 7.5),
            Map.entry("ancient-territory", 6.5));
    assertEquals(expected.keySet(), Set.copyOf(KataGoRules.officialPresetNames()));
    for (Map.Entry<String, Double> entry : expected.entrySet()) {
      assertEquals(
          entry.getValue(),
          KataGoRules.parse(entry.getKey()).orElseThrow().defaultKomi().getAsDouble(),
          entry.getKey());
    }
  }

  @Test
  void defaultKomiForCustomRulesFollowsScoringAndButton() {
    KataGoRules areaWithButton =
        KataGoRules.parse(
                "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
                    + "\"hasButton\":true,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}")
            .orElseThrow();
    KataGoRules territoryWithButton =
        KataGoRules.parse(
                "{\"ko\":\"SIMPLE\",\"scoring\":\"TERRITORY\",\"tax\":\"SEKI\",\"suicide\":false,"
                    + "\"hasButton\":true,\"whiteHandicapBonus\":\"0\",\"friendlyPassOk\":true}")
            .orElseThrow();
    KataGoRules nearNewZealand =
        KataGoRules.parse(
                "{\"ko\":\"SITUATIONAL\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":true,"
                    + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}")
            .orElseThrow();
    KataGoRules unknownScoring =
        KataGoRules.parse("{\"ko\":\"SIMPLE\",\"suicide\":false}").orElseThrow();

    assertEquals(7.0, areaWithButton.defaultKomi().getAsDouble());
    assertEquals(6.5, territoryWithButton.defaultKomi().getAsDouble());
    assertEquals(7.5, nearNewZealand.defaultKomi().getAsDouble());
    assertTrue(unknownScoring.defaultKomi().isEmpty());
  }
}
