package featurecat.lizzie.teacher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Checks completed replies against frozen evidence, allowing at most one correction request. */
final class GroundedCommentaryClient implements CommentaryClient {
  private final CommentaryClient delegate;
  private final List<TeacherEvidence.Position> positions;

  GroundedCommentaryClient(CommentaryClient delegate, List<TeacherEvidence.Position> positions) {
    this.delegate = Objects.requireNonNull(delegate);
    this.positions = List.copyOf(positions);
    if (this.positions.isEmpty()) {
      throw new IllegalArgumentException("Commentary requires position evidence.");
    }
  }

  @Override
  public String stream(
      List<TeacherLlmClient.Message> messages,
      TeacherLlmClient.Cancellation cancellation,
      Consumer<String> onText)
      throws IOException, InterruptedException {
    List<TeacherLlmClient.Message> input = new ArrayList<>(messages);
    TeacherLlmClient.Cancellation requestCancellation =
        cancellation == null ? new TeacherLlmClient.Cancellation() : cancellation;
    Consumer<String> receiver = onText == null ? ignored -> {} : onText;
    for (int attempt = 0; attempt < 2; attempt++) {
      checkCancellation(requestCancellation);
      // Partial or rejected text must never reach the UI or become follow-up context.
      String result = delegate.stream(List.copyOf(input), requestCancellation, ignored -> {});
      checkCancellation(requestCancellation);
      if (result == null || result.isBlank()) {
        throw unverifiedReply();
      }
      TeacherVerifier.Result check = TeacherVerifier.verify(result, positions);
      if (check.ok()) {
        receiver.accept(result);
        return result;
      }
      if (attempt == 0) {
        input.add(new TeacherLlmClient.Message("assistant", result));
        input.add(
            new TeacherLlmClient.Message(
                "user",
                "Correct the unsupported claims below using ONLY the original frozen evidence. "
                    + "Do not add moves or substitute arbitrary coordinates. Remove unsupported claims, "
                    + "retain useful board reasoning, and return the complete corrected lesson in the "
                    + "requested language.\n"
                    + String.join("\n", check.violations.stream().limit(8).toList())));
      }
    }
    throw unverifiedReply();
  }

  private static void checkCancellation(TeacherLlmClient.Cancellation cancellation) {
    if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new CancellationException("AI commentary request was cancelled.");
    }
  }

  private static IOException unverifiedReply() {
    return new IOException(
        TeacherStrings.get(
            "Teacher.learning.unverified",
            "The explanation could not be verified against this position. Nothing was saved."));
  }
}
