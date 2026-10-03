package featurecat.lizzie.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class KoreanWordingTest {
  private final ResourceBundle ko = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.KOREAN);

  @Test void resumeAndDrawingAreNotTranslatedAsResumeDocumentOrTiedGame() {
    assertEquals("재개", ko.getString("ContributeView.btnResume"));
    assertEquals("그리기", ko.getString("Controller.btnDrawPainting"));
    assertEquals("무승부", ko.getString("HumanSlGame.draw"));
  }
  @Test void rulesKeepMathematicalSymbolsAndScoringMeaning() {
    assertEquals("N", ko.getString("ContributeView.rules.whiteHandicapBonus.N"));
    assertEquals("N-1", ko.getString("SetKataRules.rdoHandicapKomiN1"));
    assertEquals("빅", ko.getString("SetKataRules.rdoSeKiTax"));
    assertEquals("영역 계가", ko.getString("SetKataRules.rdoArea"));
    assertEquals("집 계가", ko.getString("SetKataRules.rdoTerritory"));
  }
  @Test void translatedDownloadMessageKeepsItsFormattingArgument() {
    String message = String.format(ko.getString("AutoSetup.cleanTensorRtCacheConfirm"), "100 MiB");
    assertTrue(message.contains("100 MiB"));
    assertTrue(message.contains("다운로드의 재개 정보"));
  }
  @Test void restrictionTooltipDistinguishesNativeCapabilityFromTheGui() {
    assertTrue(ko.getString("RightClickMenu.reuseRootTreeUnavailable").contains("패치 엔진"));
    assertTrue(ko.getString("RightClickMenu.reuseRootTree").contains("이후 수순"));
  }
  @Test void aboutLinksIdentifyTheRenamedProjectAndKeepItsUpstreamCredit() {
    String about = ko.getString("LizzieConfig.about.lblLizzieInfo");
    assertTrue(about.contains("https://github.com/22nsuk/KG-next/releases"));
    assertTrue(about.contains("https://github.com/22nsuk/KG-next/issues"));
    assertTrue(ko.getString("LizzieConfig.about.lblOriginLizzieInfo2")
        .contains("https://github.com/wimi321/lizzieyzy-next"));
    assertTrue(ko.getString("LizzieConfig.lizzie.contributorsTitle")
        .contains("href=\"https://github.com/22nsuk/KG-next/graphs/contributors\""));
  }
}
