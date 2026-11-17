package featurecat.lizzie.teacher;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Transport boundary shared by the API client and the evidence validation pass. */
@FunctionalInterface
interface CommentaryClient {
  /** Validate immediately before each provider request, including fallback and correction. */
  @FunctionalInterface
  interface RequestCheck {
    void check() throws IOException;
  }

  default String stream(
      List<TeacherLlmClient.Message> messages,
      TeacherLlmClient.Cancellation cancellation,
      Consumer<String> onText)
      throws IOException, InterruptedException {
    return stream(messages, cancellation, onText, () -> {});
  }

  String stream(
      List<TeacherLlmClient.Message> messages,
      TeacherLlmClient.Cancellation cancellation,
      Consumer<String> onText,
      RequestCheck requestCheck)
      throws IOException, InterruptedException;
}
