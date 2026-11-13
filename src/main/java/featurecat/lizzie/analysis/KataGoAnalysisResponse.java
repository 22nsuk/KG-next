package featurecat.lizzie.analysis;

import org.json.JSONException;
import org.json.JSONObject;

/** Classifies JSON protocol envelopes before consumers inspect their analysis payloads. */
final class KataGoAnalysisResponse {
  enum Kind {
    ERROR,
    WARNING,
    ACTION,
    NO_RESULTS,
    PARTIAL,
    FINAL
  }

  private KataGoAnalysisResponse() {}

  static Kind kind(JSONObject response) {
    if (response.has("error")) return Kind.ERROR;
    if (response.has("warning")) return Kind.WARNING;
    if (response.has("action")) return Kind.ACTION;
    if (flag(response, "noResults")) return Kind.NO_RESULTS;
    return flag(response, "isDuringSearch") ? Kind.PARTIAL : Kind.FINAL;
  }

  private static boolean flag(JSONObject response, String name) {
    if (!response.has(name)) return false;
    Object value = response.get(name);
    if (!(value instanceof Boolean)) {
      throw new JSONException("KataGo response field " + name + " must be a boolean.");
    }
    return (Boolean) value;
  }

  static boolean warningChangesRequest(JSONObject response) {
    // KataGo replaces unsupported rules and ignores unknown override keys. Accepting the
    // result would claim it used the requested rules/profile/settings when it did not.
    String field = response.optString("field", "");
    return field.equals("rules") || field.equals("overrideSettings");
  }

  static String diagnostic(JSONObject response) {
    String type =
        response.has("error") ? "error" : response.has("warning") ? "warning" : "noResults";
    String field = response.optString("field", "");
    String id = response.optString("id", "");
    String message =
        type.equals("noResults")
            ? "The request ended without analysis results."
            : response.optString(type, "Unknown engine diagnostic.");
    return "KataGo "
        + type
        + (id.isEmpty() ? "" : " (" + id + ")")
        + (field.isEmpty() ? "" : " [" + field + "]")
        + ": "
        + message;
  }
}
