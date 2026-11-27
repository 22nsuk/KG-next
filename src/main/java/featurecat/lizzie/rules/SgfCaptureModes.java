package featurecat.lizzie.rules;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps the KG-next capture suffix separate from opaque SGF values on the same property. */
record SgfCaptureModes(boolean recorded, Set<SgfDocument.Event> ownedProperties,
    Set<SgfDocument.Event> noCaptureMoves) {
  static final String ROOT_FLAG = "KGCM";
  static final String MOVE_FLAG = "KGNC";
  static final SgfCaptureModes UNMARKED = new SgfCaptureModes(false, Set.of(), Set.of());
  private static final String ON = "1";
  private static final String VERSION = "KG-next/1";
  // A literal trailing flag needs a neutral final value when AP is rewritten on save.
  private static final String LITERAL = "KG-next/plain";

  private static boolean isFlag(String value) {
    return ON.equals(value) || LITERAL.equals(value);
  }

  static Map<String, List<String>> withFlag(Map<String, List<String>> properties, String key,
      boolean enabled) {
    if (!enabled && !isFlag(SGFParser.getOrDefault(properties, key, null))) return properties;
    var result = new LinkedHashMap<>(properties);
    // Keep the final 1 readable by trial builds that predate the version suffix.
    if (enabled && ROOT_FLAG.equals(key)) SGFParser.addProperty(result, key, VERSION);
    SGFParser.addProperty(result, key, enabled ? ON : LITERAL);
    return result;
  }

  static SgfCaptureModes read(SgfDocument document, SgfCancellation cancellation) {
    var rootFlag = document.rootPropertyEvent(ROOT_FLAG, cancellation);
    if (rootFlag == null || !isFlag(rootFlag.rawValue())) return UNMARKED;
    // The parser may reorder fields within a node. Equal values in sibling nodes remain distinct.
    Set<SgfDocument.Event> owned = Collections.newSetFromMap(new IdentityHashMap<>());
    owned.add(rootFlag);
    if (LITERAL.equals(rootFlag.rawValue())) return new SgfCaptureModes(false, owned, Set.of());

    Set<SgfDocument.Event> noCapture = Collections.newSetFromMap(new IdentityHashMap<>());
    SgfDocument.Event move = null;
    SgfDocument.Event flag = null;
    var events = document.events();
    for (int i = 0; i <= events.size(); i++) {
      cancellation.checkEvery(i);
      var event = i == events.size() ? null : events.get(i);
      if (event == null || event.kind() != 'P') {
        if (move != null && flag != null && isFlag(flag.rawValue())
            && !SgfDocument.isPassPoint(move.rawValue(), document.width(), document.height())) {
          owned.add(flag);
          if (ON.equals(flag.rawValue())) noCapture.add(move);
        }
        move = null;
        flag = null;
      } else if (event == rootFlag && i > 0) {
        var version = events.get(i - 1);
        if (ROOT_FLAG.equals(version.name()) && VERSION.equals(version.rawValue())) owned.add(version);
      } else if ("B".equals(event.name()) || "W".equals(event.name())) {
        move = event;
      } else if (MOVE_FLAG.equals(event.name())) {
        flag = event;
      }
    }
    return new SgfCaptureModes(true, owned, noCapture);
  }
}
