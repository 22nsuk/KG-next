package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;

/**
 * The settings window may release its pause before or after its catalog replacement is ready.
 * All transitions run on the EDT. Only an edit of the same selected entry can inherit the pause.
 */
final class EngineSettingsPause {
  private Leelaz owner;
  private long generation;
  private Object readyIncarnation;
  private boolean closed;

  EngineSettingsPause(Leelaz engine, long generation) {
    owner = engine;
    this.generation = generation;
    readyIncarnation = engine.engineIncarnationSnapshot();
  }

  boolean canFollow(Leelaz previous, EngineData entry) {
    return !closed && previous == owner && entry != null && !entry.id.isBlank()
        && entry.id.equals(previous.savedEntryId)
        && generation == Lizzie.capturePrimaryEngineGeneration(previous)
        && (readyIncarnation == null || previous.isCurrentLiveEngineIncarnation(readyIncarnation));
  }

  void follow(Leelaz replacement, long newGeneration) {
    owner = replacement;
    generation = newGeneration;
    readyIncarnation = null;
  }

  void replacementReady(Leelaz replacement, long readyGeneration, Object incarnation) {
    if (owner == replacement && generation == readyGeneration) {
      readyIncarnation = incarnation;
      resumeIfReady();
    }
  }

  void close() {
    closed = true;
    resumeIfReady();
  }

  private void resumeIfReady() {
    if (closed && readyIncarnation != null) {
      Object incarnation = readyIncarnation;
      readyIncarnation = null;
      owner.resumeSettingsPonder(generation, incarnation);
    }
  }
}
