package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.StoneSoundTestSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SGFParserSoundStateTest {
  private RulesLayerTestHarness env;
  private final AtomicInteger soundRequests = new AtomicInteger();

  @BeforeEach
  void prepare() throws Exception {
    env = RulesLayerTestHarness.open();
    StoneSoundTestSupport.setStoneSound(ignored -> soundRequests.incrementAndGet());
  }

  @AfterEach
  void restore() {
    StoneSoundTestSupport.setStoneSound(null);
    if (env != null) env.close();
  }

  @ParameterizedTest(name = "sound={0}, edit={1}")
  @CsvSource({"true, false", "false, false", "true, true", "false, true"})
  void successfulLoadSuppressesPlaybackThenRestoresThePreference(boolean playSound, boolean edit) {
    Lizzie.config.playSound = playSound;
    String sgf = "(;SZ[5];B[aa];W[bb])";

    assertTrue(edit ? SGFParser.loadFromStringforedit(sgf) : SGFParser.loadFromString(sgf, false));

    assertEquals(playSound, Lizzie.config.playSound);
    assertEquals(0, soundRequests.get(), "reconstructing the game must remain silent");
    assertFalse(env.board().isLoadingFile);
    env.board().place(3, 3);
    assertEquals(playSound ? 1 : 0, soundRequests.get(), "the next move must use the saved preference");
  }

  @ParameterizedTest(name = "sound={0}, file content={1}, input={2}")
  @MethodSource("rejectedLoads")
  void rejectedLoadRestoresSoundAndTheCurrentGame(
      boolean playSound, boolean fileContent, String input) throws Exception {
    assertTrue(SGFParser.loadFromString(
        "(;SZ[5]C[original];B[aa](;W[bb]C[main])(;W[cc]C[variation]))", false));
    BoardHistoryList history = env.board().getHistory();
    history.nextVariationWithoutEngineSync(0);
    history.nextVariationWithoutEngineSync(1);
    BoardHistoryNode selected = history.getCurrentHistoryNode();
    String originalSgf = SGFParser.saveToString(false);
    Lizzie.config.playSound = playSound;

    assertFalse(fileContent
        ? SGFParser.loadFileContent(input, false, false)
        : SGFParser.loadFromString(input, false));

    assertAll(
        () -> assertEquals(playSound, Lizzie.config.playSound),
        () -> assertSame(history, env.board().getHistory()),
        () -> assertSame(selected, history.getCurrentHistoryNode()),
        () -> assertEquals(originalSgf, SGFParser.saveToString(false)),
        () -> assertFalse(env.board().isLoadingFile));
    assertEquals(0, soundRequests.get());
    env.board().place(3, 3);
    assertEquals(playSound ? 1 : 0, soundRequests.get(), "a failed import must not mute later moves");
  }

  private static Stream<Arguments> rejectedLoads() {
    return Stream.of(true, false).flatMap(playSound ->
        Stream.of("not an SGF", "", "(;SZ[50000])").flatMap(input -> Stream.of(
            Arguments.of(playSound, false, input), Arguments.of(playSound, true, input))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void editLoadRestoresSoundWhenTheParseExceptionEscapes(boolean playSound) {
    Lizzie.config.playSound = playSound;

    assertThrows(IllegalArgumentException.class,
        () -> SGFParser.loadFromStringforedit("(;SZ[50000])"));

    assertEquals(playSound, Lizzie.config.playSound);
    assertFalse(env.board().isLoadingFile);
    assertEquals(0, soundRequests.get());
  }
}
