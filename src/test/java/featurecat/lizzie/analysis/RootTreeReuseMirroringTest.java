package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RootTreeReuseMirroringTest {
  @Test
  void nonAnalysisCommandArgumentsRemainLiteral() throws Exception {
    Leelaz engine = new Leelaz("");
    for (String command : List.of(
        "loadsgf C:/studies/rootInfo true reuseRootTree true.sgf",
        "loadsgf C:/studies/genmove_analyze rootInfo true.sgf",
        "kata-analyze-file rootInfo true.txt",
        "124 kata-analyze-file rootInfo true.txt",
        "124 456 kata-analyze rootInfo true",
        "124 name # kata-analyze rootInfo true")) {
      assertEquals(command, engine.adaptRootTreeReuseForReceiver(command));
    }
    assertEquals("kata-genmove_analyze B", engine.adaptRootTreeReuseForReceiver(
        "kata-genmove_analyze B rootInfo true reuseRootTree true"));
    assertEquals("2147483648 kata-analyze 10", engine.adaptRootTreeReuseForReceiver(
        "2147483648 kata-analyze 10 rootInfo true"), "Adaptation must not erase an invalid ID");
    assertEquals("124 kata-analyze 10", engine.adaptRootTreeReuseForReceiver(
        "124kata-analyze 10 root\rInfo true reuseRoot\rTree true"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "00124 ", "124"})
  void mixedKataGoPairUsesTheReceivingEnginesCapabilityInBothDispatchPaths(String prefix) throws Exception {
    Config previousConfig = Lizzie.config;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    try {
      var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      Lizzie.config = (Config) ((sun.misc.Unsafe) field.get(null)).allocateInstance(Config.class);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      primary.isKatago = true;
      secondary.isKatago = true;
      primary.advertiseCommandsForTest(List.of("kata-analyze", "kg-reuse-root-tree"));
      secondary.advertiseCommandsForTest(List.of("kata-analyze"));
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;
      String command = prefix + "kata-analyze 10 allow b D4 1" + primary.addKataTag();
      assertTrue(command.contains("reuseRootTree true"));
      Method prepare = Leelaz.class.getDeclaredMethod("prepareDefaultMirroredCommand", String.class);
      prepare.setAccessible(true);
      String mirrored = (String) prepare.invoke(secondary, command);
      assertFalse(mirrored.contains("reuseRootTree"));
      assertFalse(mirrored.contains("rootInfo"));
      assertTrue(mirrored.contains("allow b D4 1"));
      if (!prefix.isEmpty()) assertTrue(mirrored.startsWith(prefix.trim() + " "));
      assertEquals(mirrored, secondary.adaptRootTreeReuseForReceiver(command));
      assertEquals(command, primary.adaptRootTreeReuseForReceiver(command));

      secondary.advertiseCommandsForTest(List.of("kata-analyze", "kg-reuse-root-tree"));
      assertEquals(command, prepare.invoke(secondary, command));
      assertEquals(command, secondary.adaptRootTreeReuseForReceiver(command));

      secondary.isKatago = false;
      String legacyMirrored = (String) prepare.invoke(secondary, command);
      assertFalse(legacyMirrored.contains("reuseRootTree"));
      assertFalse(legacyMirrored.contains("rootInfo"));
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
    }
  }
}
