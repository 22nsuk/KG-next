package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GroundedCommentaryClientTest {
  @Test
  void emitsOnlyTheCompletedVerifiedReplyWithoutAnExtraRequest() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    List<String> displayed = new ArrayList<>();
    CommentaryClient transport =
        (messages, cancellation, receiver) -> {
          calls.incrementAndGet();
          receiver.accept("Incomplete ");
          receiver.accept("fragments");
          return "Compare D4.";
        };

    String result = grounded(transport).stream(messages(), null, displayed::add);

    assertEquals("Compare D4.", result);
    assertEquals(List.of(result), displayed);
    assertEquals(1, calls.get());
  }

  @Test
  void correctsOnceUsingTheOriginalEvidenceAndNeverEmitsTheRejectedReply() throws Exception {
    List<List<TeacherLlmClient.Message>> requests = new ArrayList<>();
    List<TeacherEvidence.Position> evidence = new ArrayList<>(List.of(position()));
    List<String> displayed = new ArrayList<>();
    CommentaryClient transport =
        (messages, cancellation, receiver) -> {
          requests.add(messages);
          // A later caller change cannot replace the evidence used for this request.
          evidence.clear();
          String reply = requests.size() == 1 ? "Play Q16." : "Compare D4.";
          receiver.accept(reply);
          return reply;
        };
    List<TeacherLlmClient.Message> original = messages();

    String result =
        new GroundedCommentaryClient(transport, evidence)
            .stream(original, new TeacherLlmClient.Cancellation(), displayed::add);

    assertEquals("Compare D4.", result);
    assertEquals(List.of(result), displayed);
    assertEquals(2, requests.size());
    assertEquals(1, original.size());
    assertEquals(1, requests.get(0).size(), "The first request must remain an immutable snapshot");
    assertSame(original.get(0), requests.get(1).get(0));
    assertEquals("assistant", requests.get(1).get(1).role);
    assertEquals("Play Q16.", requests.get(1).get(1).content);
    assertEquals("user", requests.get(1).get(2).role);
    assertTrue(requests.get(1).get(2).content.contains("Unsupported coordinate Q16"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Q16", "D40", "I9", "D0", "D-1"})
  void stopsAfterOneUnsuccessfulCorrectionWithoutDisplayingEitherReply(String coordinate) {
    AtomicInteger calls = new AtomicInteger();
    List<String> displayed = new ArrayList<>();
    CommentaryClient transport =
        (messages, cancellation, receiver) -> {
          calls.incrementAndGet();
          receiver.accept("Play " + coordinate + ".");
          return "Play " + coordinate + ".";
        };

    assertThrows(
        IOException.class,
        () -> grounded(transport).stream(messages(), null, displayed::add));
    assertEquals(2, calls.get());
    assertTrue(displayed.isEmpty());
  }

  @Test
  void keepsNonblockingWarningsWithoutPayingForACorrection() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    String reply = "D4 is the only move.";
    CommentaryClient transport =
        (messages, cancellation, receiver) -> {
          calls.incrementAndGet();
          return reply;
        };

    assertEquals(reply, grounded(transport).stream(messages(), null, ignored -> {}));
    assertTrue(TeacherVerifier.verify(reply, position()).hasNotes());
    assertEquals(1, calls.get());
  }

  @Test
  void doesNotRetryTransportFailuresOrExposeTheirPartialText() {
    AtomicInteger calls = new AtomicInteger();
    List<String> displayed = new ArrayList<>();
    IOException interruption = new IOException("stream ended prematurely");
    CommentaryClient transport =
        (messages, cancellation, receiver) -> {
          calls.incrementAndGet();
          receiver.accept("Compare D4.");
          throw interruption;
        };

    IOException failure =
        assertThrows(
            IOException.class,
            () -> grounded(transport).stream(messages(), null, displayed::add));
    assertSame(interruption, failure);
    assertEquals(1, calls.get());
    assertTrue(displayed.isEmpty());
  }

  @Test
  void checksCancellationAgainBeforePublishingTheCompletedReply() {
    AtomicInteger calls = new AtomicInteger();
    List<String> displayed = new ArrayList<>();
    CommentaryClient transport =
        (messages, cancellation, receiver) -> {
          calls.incrementAndGet();
          cancellation.cancel();
          return "Compare D4.";
        };

    assertThrows(
        CancellationException.class,
        () -> grounded(transport).stream(messages(), null, displayed::add));
    assertEquals(1, calls.get());
    assertTrue(displayed.isEmpty());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void rejectsEmptyRepliesWithoutDisplayingOrRetryingThem(String reply) {
    AtomicInteger calls = new AtomicInteger();
    List<String> displayed = new ArrayList<>();
    CommentaryClient transport =
        (messages, cancellation, receiver) -> {
          calls.incrementAndGet();
          return reply;
        };

    assertThrows(
        IOException.class,
        () -> grounded(transport).stream(messages(), null, displayed::add));
    assertEquals(1, calls.get());
    assertTrue(displayed.isEmpty());
  }

  private static GroundedCommentaryClient grounded(CommentaryClient transport) {
    return new GroundedCommentaryClient(transport, List.of(position()));
  }

  private static List<TeacherLlmClient.Message> messages() {
    return List.of(new TeacherLlmClient.Message("user", "Explain the supplied D4 evidence."));
  }

  private static TeacherEvidence.Position position() {
    return new TeacherEvidence.Position(
        10,
        "B",
        1000,
        "D4",
        OptionalDouble.empty(),
        List.of(new TeacherEvidence.Candidate(1, "D4", 55, 1.2, 800, List.of("D4"))));
  }
}
