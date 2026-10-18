package featurecat.lizzie.teacher;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Transport boundary shared by the API client and the evidence validation pass. */
@FunctionalInterface
interface CommentaryClient {
  String stream(
      List<TeacherLlmClient.Message> messages,
      TeacherLlmClient.Cancellation cancellation,
      Consumer<String> onText)
      throws IOException, InterruptedException;
}
