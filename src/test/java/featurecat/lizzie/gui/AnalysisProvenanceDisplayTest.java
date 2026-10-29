package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.rules.BoardData;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class AnalysisProvenanceDisplayTest {
  private final ResourceBundle resources =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.KOREA);

  @Test
  void legacyDisplayNameDoesNotIdentifyTheEvaluationOrOwnershipSource() {
    BoardData data = BoardData.empty(19, 19);
    data.setPlayouts(700);
    data.engineName = "KataGo custom name";
    data.estimateArray = List.of(0.5);
    data.isChanged = true;

    String text = AnalysisProvenanceDisplay.describe(data, resources);

    assertTrue(text.contains("기록된 엔진 표시 이름: KataGo custom name"));
    assertEquals(2, text.split("분석 출처 미확인", -1).length - 1);
    assertTrue(text.contains(resources.getString("WholeGameAnalysis.source.invalidated")));
    assertFalse(text.contains(resources.getString("WholeGameAnalysis.source.same")));
    assertEquals(700, data.getPlayouts());
    assertTrue(data.isChanged);
  }

  @Test
  void evaluationAndOwnershipRetainTheirOwnRecordedSources() {
    BoardData data = BoardData.empty(19, 19);
    data.setPlayouts(700);
    data.setPrimaryAnalysisProvenance(source("evaluation-model", "a", 1.0));
    data.estimateArray = List.of(0.5);
    data.setPrimaryOwnershipProvenance(source("ownership-model", "b", 2.0));

    String text = AnalysisProvenanceDisplay.describe(data, resources);
    int ownership = text.indexOf(resources.getString("WholeGameAnalysis.source.ownership"));

    assertTrue(text.substring(0, ownership).contains("모델: evaluation-model"));
    assertTrue(text.substring(0, ownership).contains("로컬 모델 파일 SHA-256: " + "a".repeat(64)));
    assertTrue(text.substring(0, ownership).contains("PDA: 1.0 · WRN: 0.25"));
    assertTrue(text.substring(ownership).contains("모델: ownership-model"));
    assertTrue(text.substring(ownership).contains("PDA: 2.0 · WRN: 0.25"));
    assertFalse(text.contains(resources.getString("WholeGameAnalysis.source.same")));
  }

  @Test
  void identicalRecordedOwnershipDetailsAreDescribedWithoutRepeatingTheFields() {
    BoardData data = BoardData.empty(19, 19);
    data.setPlayouts(700);
    data.setPrimaryAnalysisProvenance(source("same-model", "a", 1.0));
    data.estimateArray = List.of(0.5);
    data.setPrimaryOwnershipProvenance(source("same-model", "a", 1.0));

    String text = AnalysisProvenanceDisplay.describe(data, resources);

    assertTrue(text.contains(resources.getString("WholeGameAnalysis.source.same")));
    assertEquals(1, text.split("모델: same-model", -1).length - 1);
  }

  @Test
  void missingRecordedFieldsAreNotInferredFromLiveOrLegacyValues() {
    BoardData data = BoardData.empty(19, 19);
    data.setPlayouts(700);
    data.pda = 12.0;
    data.komi = 6.5;
    data.setPrimaryAnalysisProvenance(
        new AnalysisProvenance("recorded engine", "", "", "", "", "", null, null, null, ""));

    String text = AnalysisProvenanceDisplay.describe(data, resources);

    assertTrue(text.contains("엔진: recorded engine · 버전: 미확인"));
    assertTrue(text.contains("모델: 미확인"));
    assertTrue(text.contains("규칙: 미확인 · 덤: 미확인"));
    assertTrue(text.contains("PDA: 미확인 · WRN: 미확인"));
    assertTrue(text.contains("HumanSL 프로필: 미확인"));
    assertTrue(text.contains(resources.getString("WholeGameAnalysis.source.noOwnership")));
  }

  @Test
  void noPayloadIsDistinctFromAnUnknownSource() {
    String text = AnalysisProvenanceDisplay.describe(BoardData.empty(19, 19), resources);

    assertTrue(text.contains(resources.getString("WholeGameAnalysis.source.none")));
    assertFalse(text.contains(resources.getString("WholeGameAnalysis.source.unknown")));
  }

  private static AnalysisProvenance source(String model, String hashCharacter, double pda) {
    return new AnalysisProvenance(
        "KataGo", "1.16", model, hashCharacter.repeat(64), "c".repeat(64),
        "chinese", 7.5, pda, 0.25, "rank_5d");
  }
}
