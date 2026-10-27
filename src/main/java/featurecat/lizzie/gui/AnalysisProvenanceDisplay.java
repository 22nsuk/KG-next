package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.rules.BoardData;
import java.text.MessageFormat;
import java.util.Objects;
import java.util.ResourceBundle;

/** Plain-text description of the displayed position, including separate ownership evidence. */
final class AnalysisProvenanceDisplay {
  private AnalysisProvenanceDisplay() {}

  static String describe(BoardData data, ResourceBundle resources) {
    if (data == null) {
      return resources.getString("WholeGameAnalysis.source.none");
    }
    synchronized (data) {
      StringBuilder text = new StringBuilder();
      text.append(
          data.moveNumber == 0
              ? resources.getString("WholeGameAnalysis.source.initial")
              : format(resources, "WholeGameAnalysis.source.position", data.moveNumber));
      text.append('\n').append(resources.getString("WholeGameAnalysis.source.analysis"));
      if (!data.hasPrimaryAnalysisPayload()) {
        text.append('\n').append(resources.getString("WholeGameAnalysis.source.none"));
        return text.toString();
      }
      AnalysisProvenance analysis = data.primaryAnalysisProvenance();
      appendSource(text, analysis, data.engineName, resources);
      if (data.isChanged) {
        text.append('\n').append(resources.getString("WholeGameAnalysis.source.invalidated"));
      }
      text.append("\n\n").append(resources.getString("WholeGameAnalysis.source.ownership"));
      if (data.estimateArray == null || data.estimateArray.isEmpty()) {
        text.append('\n').append(resources.getString("WholeGameAnalysis.source.noOwnership"));
      } else {
        AnalysisProvenance ownership = data.primaryOwnershipProvenance();
        if (analysis != null && Objects.equals(analysis, ownership)) {
          text.append('\n').append(resources.getString("WholeGameAnalysis.source.same"));
        } else {
          appendSource(text, ownership, "", resources);
        }
      }
      return text.toString();
    }
  }

  private static void appendSource(
      StringBuilder text, AnalysisProvenance source, String legacyName, ResourceBundle resources) {
    if (source == null) {
      text.append('\n').append(resources.getString("WholeGameAnalysis.source.unknown"));
      if (legacyName != null && !legacyName.isBlank()) {
        append(text, resources, "WholeGameAnalysis.source.legacyEngine", legacyName);
      }
      return;
    }
    append(text, resources, "WholeGameAnalysis.source.engine", source.engineName(), source.engineVersion());
    append(text, resources, "WholeGameAnalysis.source.model", source.modelName());
    append(text, resources, "WholeGameAnalysis.source.modelSha", source.modelSha256());
    append(text, resources, "WholeGameAnalysis.source.configSha", source.configSha256());
    append(text, resources, "WholeGameAnalysis.source.rules", source.rules(), source.komi());
    append(text, resources, "WholeGameAnalysis.source.parameters", source.pda(), source.wrn());
    append(text, resources, "WholeGameAnalysis.source.humanSL", source.humanSLProfile());
  }

  private static void append(
      StringBuilder text, ResourceBundle resources, String key, Object... values) {
    Object[] displayValues = new Object[values.length];
    for (int index = 0; index < values.length; index++) {
      Object value = values[index];
      displayValues[index] =
          value == null || value.toString().isBlank()
              ? resources.getString("WholeGameAnalysis.source.unknownValue")
              : value.toString().replace('\n', ' ').replace('\r', ' ');
    }
    text.append('\n').append(format(resources, key, displayValues));
  }

  private static String format(ResourceBundle resources, String key, Object... values) {
    return MessageFormat.format(resources.getString(key), values);
  }
}
