package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SgfDocumentTest {
  private static final String ESCAPED_VALUE =
      "plain\\]bracket\\\\n literal\\n physical\\\r\n tail\\";

  @ParameterizedTest
  @ValueSource(strings = {"C", "LZ", "LZOP", "LZ2", "LZOP2"})
  void commentAndAnalysisEscapesDecodeWithoutChangingTheRawValue(String name) {
    var event = new SgfDocument.Event('P', name, ESCAPED_VALUE);
    assertEquals("plain]bracket\\n literal\n physical\r\n tail\\", event.legacyValue());
    assertEquals(ESCAPED_VALUE, event.rawValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PB", "PW", "RE", "DT"})
  void gameInfoRetainsBothLiteralAndPhysicalEscapes(String name) {
    var event = new SgfDocument.Event('P', name, ESCAPED_VALUE);
    assertEquals(ESCAPED_VALUE, event.legacyValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"KGP", "XX", ""})
  void opaqueValuesRetainEscapesAndOnlyTranslateAnEscapedLetterN(String name) {
    var event = new SgfDocument.Event('P', name, ESCAPED_VALUE);
    assertEquals("plain\\]bracket\\\\n literal\\\n physical\\\r\n tail\\", event.legacyValue());
    assertEquals("unchanged\\]value\\\\n", new SgfDocument.Event('P', name,
        "unchanged\\]value\\\\n").legacyValue());
  }

  @Test
  void escapedBracketParityKeepsRawValuesRepeatedPropertiesAndCollectionOrder() {
    String raw = "literal [ ; ) \\] bracket " + "\\".repeat(32)
        + " pair " + "\\".repeat(31) + "] tail";
    var document = SgfDocument.parse("before (;C[" + raw
        + "][repeated]LZ2[analysis](;B[aa])(;W[bb])) \n (;PW[name]) after");
    assertNotNull(document);
    assertEquals(
        List.of(
            new SgfDocument.Event('P', "C", raw),
            new SgfDocument.Event('P', "C", "repeated"),
            new SgfDocument.Event('P', "LZ2", "analysis"),
            new SgfDocument.Event('P', "B", "aa"),
            new SgfDocument.Event('P', "W", "bb"),
            new SgfDocument.Event('P', "PW", "name")),
        properties(document));
    assertEquals("(;PPP(;P)(;P))(;P)", document.events().stream()
        .map(event -> String.valueOf(event.kind())).reduce("", String::concat));
    assertFalse(document.multiGo());
  }

  @Test
  void smartGoNamesRemoveOnlyAsciiLowercaseIncludingAnEmptyResult() {
    var document = SgfDocument.parse(
        "(;PlayerBlack[name]note[opaque][repeat]LZ2[one]LZOP2[two]Élower[accent])");
    assertNotNull(document);
    assertEquals(List.of("PB", "", "", "LZ2", "LZOP2", "É"),
        properties(document).stream().map(SgfDocument.Event::name).toList());
  }

  @Test
  void embeddedSizeAndSetupTextCannotResizeOrExpandTheGame() {
    var document = SgfDocument.parse(
        "SZ[52] outside (;PW[SZ[2:3\\] text]C[AB[zz:aa\\] text]"
            + "SZ[5:7]AddBlack[aa:ab][ea:eb]note[aa:bb][cc:dd]) after");
    assertNotNull(document);
    assertEquals(5, document.width());
    assertEquals(7, document.height());
    assertEquals(
        List.of(
            new SgfDocument.Event('P', "PW", "SZ[2:3\\] text"),
            new SgfDocument.Event('P', "C", "AB[zz:aa\\] text"),
            new SgfDocument.Event('P', "SZ", "5:7"),
            new SgfDocument.Event('P', "AB", "aa"),
            new SgfDocument.Event('P', "AB", "ab"),
            new SgfDocument.Event('P', "AB", "ea"),
            new SgfDocument.Event('P', "AB", "eb"),
            new SgfDocument.Event('P', "", "aa:bb"),
            new SgfDocument.Event('P', "", "cc:dd")),
        properties(document));
  }

  @Test
  void unfinishedValuesKeepTheExistingPrefixUnlessNormalizationRequiresACompleteTree() {
    String unfinished = "(;C[done]XX[unfinished\\)";
    var document = SgfDocument.parse(unfinished);
    assertNotNull(document);
    assertEquals(List.of(new SgfDocument.Event('P', "C", "done")), properties(document));
    assertNull(SgfDocument.parse(unfinished.replace("XX[", "\\nXX[")));
    assertNull(SgfDocument.parse("(;SZ[5]AB[aa:ab)"));
  }

  @Test
  void structuralEscapesDoNotDecodeValueEscapesOrJoinPropertyNames() {
    var document = SgfDocument.parse("(;SZ[5]\\r\\nC[literal\\n physical\n]\\n;B[aa])");
    assertNotNull(document);
    assertEquals("literal\\n physical\n", properties(document).get(1).rawValue());
    assertEquals("literal\n physical\n", properties(document).get(1).legacyValue());
    assertNull(SgfDocument.parse("(;Player\\nBlack[name])"));
    assertNull(SgfDocument.parse("(;SZ[5])\\n(;C[unfinished)"));
  }

  private static List<SgfDocument.Event> properties(SgfDocument document) {
    return document.events().stream().filter(event -> event.kind() == 'P').toList();
  }
}
