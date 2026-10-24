package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.analysis.remote.SshEnginePasswords;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class EngineDataJsonTest {
  @Test
  void keepsVaultReferencesSeparateFromTheLegacyPasswordField() {
    EngineData source = new EngineData();
    source.useJavaSSH = true;
    source.password = "vault:existing-shared-reference";

    JSONObject stored = Utils.engineDataToJson(source);
    assertEquals("existing-shared-reference", stored.getString("passwordRef"));
    assertEquals("", stored.getString("password"));
    assertEquals(source.password, Utils.engineDataFromJson(stored, 0).password);

    SshEnginePasswords.writeTo(stored, "");
    assertFalse(stored.has("passwordRef"));
    assertEquals("", Utils.engineDataFromJson(stored, 0).password);
  }

  @Test
  void retainsLegacyPasswordUntilASettingsCommitMigratesIt() {
    String legacy = Utils.doEncrypt("existing password");
    EngineData source = Utils.engineDataFromJson(new JSONObject().put("password", legacy), 0);
    JSONObject stored = Utils.engineDataToJson(source);
    assertEquals(legacy, stored.getString("password"));
    assertFalse(stored.has("passwordRef"));
  }

  @Test
  void roundTripsClientOwnedGtpProfileDefensively() {
    EngineData source = new EngineData();
    source.commands = "ZenGTPX.exe";
    source.name = "Zen 6d";
    source.width = 19;
    source.height = 19;
    source.komi = 7.5F;
    source.gtpConfigurationProtocol = "zengtp-config";
    source.gtpConfigurationProfile = new JSONObject().put("mode", "rank").put("rankPreset", "6d");

    JSONObject stored = Utils.engineDataToJson(source);
    EngineData restored = Utils.engineDataFromJson(stored, 3);

    assertEquals(3, restored.index);
    assertEquals("zengtp-config", restored.gtpConfigurationProtocol);
    assertEquals("6d", restored.gtpConfigurationProfile.getString("rankPreset"));
    assertNotSame(source.gtpConfigurationProfile, restored.gtpConfigurationProfile);
    restored.gtpConfigurationProfile.put("rankPreset", "1k");
    assertEquals("6d", source.gtpConfigurationProfile.getString("rankPreset"));
  }

  @Test
  void legacyEngineJsonRemainsCompatibleAndEmptyProfilesAreNotWritten() {
    EngineData legacy = Utils.engineDataFromJson(new JSONObject().put("command", "engine"), 0);
    assertEquals("", legacy.gtpConfigurationProtocol);
    assertNull(legacy.gtpConfigurationProfile);

    JSONObject stored = Utils.engineDataToJson(legacy);
    assertFalse(stored.has("gtpConfigurationProtocol"));
    assertFalse(stored.has("gtpConfigurationProfile"));
  }

  @Test
  void copiesGtpConfigurationWithoutSharingMutableProfile() {
    EngineData source = new EngineData();
    source.gtpConfigurationProtocol = "zengtp-config";
    source.gtpConfigurationProfile = new JSONObject().put("mode", "rank");
    EngineData copy = new EngineData();

    copy.copyGtpConfigurationFrom(source);
    copy.gtpConfigurationProfile.put("mode", "fixed-time");

    assertEquals("zengtp-config", copy.gtpConfigurationProtocol);
    assertEquals("rank", source.gtpConfigurationProfile.getString("mode"));
    assertNotSame(source.gtpConfigurationProfile, copy.gtpConfigurationProfile);
  }
}
